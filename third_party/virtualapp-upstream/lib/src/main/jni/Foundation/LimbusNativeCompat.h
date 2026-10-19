#pragma once

#include <stdint.h>
#include <string.h>

/// <summary>保存信号交给下游前的 ARM64 执行状态，供包装器判断是否已经被消费。</summary>
struct LimbusSignalContextSnapshot {
    /// <summary>全部通用寄存器，包含 ART 使用的 x21 和返回地址 LR。</summary>
    uint64_t regs[31];
    /// <summary>当前指令地址。</summary>
    uint64_t pc;
    /// <summary>当前栈顶地址。</summary>
    uint64_t sp;
    /// <summary>ARM64 处理器执行状态。</summary>
    uint64_t pstate;
};

// 仅允许已知 ABI 与路径重定向语义相同的 libc 导出别名复用第一次 hook 的跳板。
// 同址但不同 replacement 的未知符号必须由调用方明确拒绝，不能静默替换其行为。
static inline bool limbus_equivalent_io_aliases(const char *first, const char *second) {
    static const char *const groups[][3] = {
            {"open", "open64", nullptr},
            {"openat", "openat64", nullptr},
            {"fopen", "fopen64", nullptr},
            {"statfs", "statfs64", "__statfs"},
            {"fstatat", "fstatat64", nullptr},
            {"sendfile", "sendfile64", nullptr},
    };
    for (const auto &group : groups) {
        bool first_found = false;
        bool second_found = false;
        for (const char *name : group) {
            if (name != nullptr) {
                first_found |= strcmp(first, name) == 0;
                second_found |= strcmp(second, name) == 0;
            }
        }
        if (first_found && second_found) return true;
    }
    return false;
}

// 将真实 orig 槽交给安装器，使它在改写入口前发布跳板，避免并发 wrapper 读到空指针。
static inline void *limbus_install_published_inline_hook(
        void (*installer)(void *, void *, void **),
        void *address, void *replacement, void **original_slot) {
    void *local_original = nullptr;
    void **published_slot = original_slot != nullptr ? original_slot : &local_original;
    installer(address, replacement, published_slot);
    return *published_slot;
}

// 下游 handler 可以只修改 x21、LR 或 PSTATE；仅比较 PC/SP 会误杀已消费的信号。
template <typename Before, typename After>
static inline bool limbus_machine_context_changed(const Before &before, const After &after) {
    if (before.pc != after.pc || before.sp != after.sp || before.pstate != after.pstate) return true;
    for (unsigned index = 0; index < 31; ++index) {
        if (before.regs[index] != after.regs[index]) return true;
    }
    return false;
}

// 内核保护页可能为 4/16/64 KiB；它与 ADRP 固定的 4 KiB 指令页无关。
static inline uintptr_t limbus_protection_start(uintptr_t address, uintptr_t page_size) {
    return address & ~(page_size - 1u);
}

static inline uintptr_t limbus_protection_length(
        uintptr_t address, uintptr_t length, uintptr_t page_size) {
    return ((address + length + page_size - 1u) & ~(page_size - 1u))
            - limbus_protection_start(address, page_size);
}
