// 本测试直接编译生产重定位器，仅检查生成指令与信号策略，不在宿主执行 ARM64 机器码。
#define A64_RELOCATOR_TEST 1
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Substrate/And64InlineHook.cpp"
#include <stdio.h>
#include <initializer_list>

static int failures = 0;
static void check(bool condition, const char *message) {
    if (!condition) {
        fprintf(stderr, "FAIL: %s\n", message);
        ++failures;
    }
}

// 从生成的 LDR literal 指令中取出跳板保存的 64 位地址，独立核对重定位结果。
static uint64_t emitted_address(uint32_t *load) {
    int32_t offset = static_cast<int32_t>((load[0] >> 5) & 0x7ffffu);
    if ((offset & 0x40000) != 0) offset -= 0x80000;
    uint64_t address = 0;
    memcpy(&address, reinterpret_cast<char *>(load) + offset * 4, sizeof(address));
    return address;
}

static context make_context(uint32_t *input, size_t count) {
    context result = {};
    result.basep = reinterpret_cast<int64_t>(input);
    result.endp = reinterpret_cast<int64_t>(input + count);
    return result;
}

static void test_relocation() {
    check(__signed_immediate(0x40000u, 19) == -262144, "negative imm19 boundary");
    check(__signed_immediate(0x3ffffu, 19) == 262143, "positive imm19 boundary");
    check(__signed_immediate(0x2000u, 14) == -8192, "negative imm14 boundary");
    check(__address_immediate(0x90ffffe0u) == -4, "negative ADRP immediate");
    check(__trampoline_capacity_bytes(5) == 224, "capacity units include final branch");

    // 输入和输出相隔 2 MiB，保证真正走生产的远距离 literal / 条件分支展开路径。
    auto *storage = static_cast<unsigned char *>(calloc(1, 4 * 1024 * 1024));
    check(storage != nullptr, "allocate regression buffer");
    if (storage == nullptr) return;
    auto *input = reinterpret_cast<uint32_t *>(storage + 1024 * 1024);
    auto *output = reinterpret_cast<uint32_t *>(storage + 3 * 1024 * 1024);
    const uint64_t expected = 0x123456789abcdef0ULL;
    for (int32_t delta : {-0xdf288, 0xda1d8}) {
        memcpy(reinterpret_cast<char *>(input) + delta, &expected, sizeof(expected));
        input[0] = 0x58000000u | ((static_cast<uint32_t>(delta / 4) & 0x7ffffu) << 5);
        auto ctx = make_context(input, 1);
        uint32_t *in = input, *out = output;
        check(__fix_loadlit(&in, &out, &ctx), "relocate real signed literal offsets");
        check(emitted_address(output) == expected, "literal comes from signed original address");
    }
    for (uint32_t opcode : {0x34000000u, 0x36000000u}) {
        const unsigned bits = opcode == 0x34000000u ? 19u : 14u;
        input[0] = opcode | (((1u << bits) - 2u) << 5);
        auto ctx = make_context(input, 1);
        uint32_t *in = input, *out = output;
        check(__fix_cond_comp_test_branch(&in, &out, &ctx), "relocate backward CBZ/TBZ");
        auto *load = output[0] == A64_NOP ? output + 3 : output + 2;
        check(emitted_address(load) == reinterpret_cast<uint64_t>(input) - 8,
              "conditional branch preserves backward target");
    }

    // ADRP #0 位于原 4 KiB 页边界，仍必须指向原页，不能按跳板或 64 KiB 内核页重算。
    input = reinterpret_cast<uint32_t *>((reinterpret_cast<uintptr_t>(input) + 4095u) & ~uintptr_t(4095u));
    input[0] = 0x90000000u;
    auto ctx = make_context(input, 1);
    uint32_t *in = input, *out = output;
    check(__fix_pcreladdr(&in, &out, &ctx), "relocate ADRP at original page boundary");
    check(emitted_address(output) == reinterpret_cast<uint64_t>(input), "ADRP #0 preserves original page");

    // 五条 SIMD literal 是高展开场景；末尾回跳必须仍在已声明的 224 字节缓冲区内。
    for (unsigned index = 0; index < 5; ++index) input[index] = 0x9c000000u | (0x40000u << 5);
    for (unsigned index = 0; index <= 56; ++index) output[index] = 0xfeedbeefu;
    __fix_instructions(input, 5, output);
    check(output[56] == 0xfeedbeefu, "highest expansion leaves capacity canary intact");
    free(storage);
}

static void **expected_original_slot = nullptr;
static void fake_installer(void *, void *, void **slot) {
    check(slot == expected_original_slot, "installer receives live original slot");
    *slot = reinterpret_cast<void *>(uintptr_t(0x1000));
    // 模拟入口即将上线时另一线程读取 orig，必须已经可见。
    check(*expected_original_slot != nullptr, "original published before entry patch");
}

static void test_runtime_policy() {
    check(limbus_equivalent_io_aliases("open", "open64"), "open aliases reuse trampoline");
    check(limbus_equivalent_io_aliases("fopen64", "fopen"), "fopen aliases reuse trampoline");
    check(!limbus_equivalent_io_aliases("open", "dlsym"), "unknown replacements are not equivalent");
    check(!limbus_equivalent_io_aliases("statfs", "__statfs64"), "different statfs ABI is not equivalent");
    void *original = nullptr;
    expected_original_slot = &original;
    check(limbus_install_published_inline_hook(fake_installer, nullptr, nullptr, &original) == original,
          "installer returns published trampoline");
    LimbusSignalContextSnapshot before = {}, after = {};
    check(!limbus_machine_context_changed(before, after), "unchanged context stays unhandled");
    after.regs[21] = 1;
    check(limbus_machine_context_changed(before, after), "x21-only ART change is consumed");
    after = before;
    after.regs[30] = 1;
    check(limbus_machine_context_changed(before, after), "LR-only change is consumed");
    after = before;
    after.pstate = 1;
    check(limbus_machine_context_changed(before, after), "PSTATE-only change is consumed");
    for (uintptr_t page : {uintptr_t(4096), uintptr_t(16384), uintptr_t(65536)}) {
        check(limbus_protection_start(page + 4, page) == page, "kernel page start");
        check(limbus_protection_length(page + 4, 20, page) == page, "single-page exact protection");
        check(limbus_protection_length(page * 2 - 4, 20, page) == page * 2, "cross-page exact protection");
    }
}

/// <summary>运行不依赖 Android 设备的原生启动回归，失败时返回非零退出码。</summary>
/// <returns>全部回归通过时返回 0，否则返回 1。</returns>
int main() {
    test_relocation();
    test_runtime_policy();
    if (failures == 0) puts("PASS: ARM64 relocation, libc aliases, signal context and page boundaries");
    return failures == 0 ? 0 : 1;
}
