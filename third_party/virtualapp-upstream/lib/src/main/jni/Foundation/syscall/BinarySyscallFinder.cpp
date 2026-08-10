/**
 * @author Lody
 *
 */


#include <stdio.h>
#include <limits.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <android/log.h>
#include <link.h>
#include "BinarySyscallFinder.h"

typedef unsigned long addr_t;

#if defined(__aarch64__)
#define AARCH64_SVC_0 0xD4000001
#define AARCH64_IS_MOV_X8(insn) ((int32_t)((insn) & 0xFFE0001F) == 0xD2800008)
#define AARCH64_IS_MOV_W8(insn) ((int32_t)((insn) & 0xFFE0001F) == 0x52800008)
#define AARCH64_IS_MOV_X_REG(insn) ((int32_t)((insn) & 0xFFE0FFE0) == 0xAA0003E0)
#define AARCH64_IS_MOV_W_REG(insn) ((int32_t)((insn) & 0xFFE0FFE0) == 0x2A0003E0)
static int g_unknown_svc_logs = 0;

static bool aarch64_is_mov_imm_to_reg(int32_t insn, unsigned reg, unsigned *value) {
    bool is_x = ((insn & 0xFFE0001F) == (0xD2800000 | reg));
    bool is_w = ((insn & 0xFFE0001F) == (0x52800000 | reg));
    if (!is_x && !is_w) {
        return false;
    }
    *value = (unsigned) ((insn >> 5) & 0xFFFF);
    return true;
}

static bool aarch64_find_recent_mov_imm_to_reg(int32_t *from,
                                               addr_t window_begin,
                                               unsigned reg,
                                               unsigned *value) {
    for (int i = 1; i <= 32; i++) {
        int32_t *candidate = from - i;
        if (reinterpret_cast<addr_t>(candidate) < window_begin) {
            break;
        }
        if (aarch64_is_mov_imm_to_reg(*candidate, reg, value)) {
            return true;
        }
    }
    return false;
}

static bool aarch64_find_recent_x8_syscall(int32_t *svc, addr_t window_begin, unsigned *syscall_num) {
    for (int i = 1; i <= 8; i++) {
        int32_t *candidate = svc - i;
        if (reinterpret_cast<addr_t>(candidate) < window_begin) {
            break;
        }
        int32_t insn = *candidate;
        if (AARCH64_IS_MOV_X8(insn) || AARCH64_IS_MOV_W8(insn)) {
            *syscall_num = (unsigned) ((insn >> 5) & 0xFFFF);
            return true;
        }
        if (AARCH64_IS_MOV_X_REG(insn) && (insn & 0x1F) == 8) {
            unsigned src_reg = (unsigned) ((insn >> 16) & 0x1F);
            if (aarch64_find_recent_mov_imm_to_reg(candidate, window_begin, src_reg, syscall_num)) {
                __android_log_print(ANDROID_LOG_ERROR,
                                    "V++",
                                    "Limbus syscall scan >>> resolved forwarded x8 syscall num=%u via x%u svc=%p",
                                    *syscall_num,
                                    src_reg,
                                    svc);
                return true;
            }
        }
        if (AARCH64_IS_MOV_W_REG(insn) && (insn & 0x1F) == 8) {
            unsigned src_reg = (unsigned) ((insn >> 16) & 0x1F);
            if (aarch64_find_recent_mov_imm_to_reg(candidate, window_begin, src_reg, syscall_num)) {
                __android_log_print(ANDROID_LOG_ERROR,
                                    "V++",
                                    "Limbus syscall scan >>> resolved forwarded w8 syscall num=%u via w%u svc=%p",
                                    *syscall_num,
                                    src_reg,
                                    svc);
                return true;
            }
        }
    }
    return false;
}

void
search_memory_syscall(const char *path, addr_t begin, addr_t end,
                      bool (*callback)(const char *, int, void *)) {
    addr_t start = begin;
    addr_t limit = end - sizeof(int32_t) * 2;
    do {
        int32_t *insn = reinterpret_cast<int32_t *>(start);
        if (insn[1] == AARCH64_SVC_0) {
            int32_t *svc = &insn[1];
            unsigned syscall_num = 0;
            if (aarch64_find_recent_x8_syscall(svc, begin, &syscall_num)) {
                if (!(*callback)(path, syscall_num, svc)) {
                    break;
                }
            } else if (g_unknown_svc_logs < 32) {
                g_unknown_svc_logs++;
                __android_log_print(ANDROID_LOG_ERROR,
                                    "V++",
                                    "Limbus syscall scan >>> unknown svc path=%s func=%p prev4=%08x prev3=%08x prev2=%08x prev1=%08x cur=%08x svc=%08x next=%08x",
                                    path,
                                    insn,
                                    start >= begin + sizeof(int32_t) * 4 ? insn[-4] : 0,
                                    start >= begin + sizeof(int32_t) * 3 ? insn[-3] : 0,
                                    start >= begin + sizeof(int32_t) * 2 ? insn[-2] : 0,
                                    start >= begin + sizeof(int32_t) ? insn[-1] : 0,
                                    insn[0],
                                    insn[1],
                                    start + sizeof(int32_t) * 2 < end ? insn[2] : 0);
            }
        }
        start += sizeof(int32_t);
    } while (start < limit);
}
#elif defined(__arm__)

#define ARM_IS_MOV_R7_IMM(insn) (((insn) & 0xFF00F000) == 0xE3007000)

void
search_memory_syscall(const char *path, addr_t begin, addr_t end,
                      bool (*callback)(const char *, int, void *)) {
    addr_t start = begin;
    addr_t limit = end - sizeof(int32_t) * 4;
    do {
        int32_t *insn = reinterpret_cast<int32_t *>(start);
        if (insn[0] == 0xE1A0C007 && ARM_IS_MOV_R7_IMM(insn[1]) && insn[2] == 0xEF000000) {
            int32_t value = insn[1];
            int syscall = ((value & 0xF0000) >> 4) | (value & 0x00FFF);
            (*callback)(path, syscall, NULL);
        }
        start += 1;
    } while (start < limit);
}

#elif defined(__i386__)
void
search_memory_syscall(const char *path, addr_t begin, addr_t end,
                      bool (*callback)(const char *, int, void *)) {

}
#endif


bool has_code(const char *perm) {
    bool r = false, x = false;
    for (int i = 0; i < 5; ++i) {
        if (perm[i] == 'r') {
            r = true;
        }
        if (perm[i] == 'x') {
            x = true;
        }
    }
    return r && x;
}

int findSyscallsCount(const char *path, bool (*callback)(const char *, int, void *)) {
    FILE *f;
    if ((f = fopen("/proc/self/maps", "r")) == NULL) {
        return 0;
    }
    char buf[PATH_MAX + 100], perm[5], dev[6], mapname[PATH_MAX];
    addr_t begin, end, inode, foo;
    int matched_maps = 0;

    while (!feof(f)) {
        if (fgets(buf, sizeof(buf), f) == 0)
            break;
        mapname[0] = '\0';
        sscanf(buf, "%lx-%lx %4s %lx %5s %ld %s", &begin, &end, perm,
               &foo, dev, &inode, mapname);
        if (strstr(buf, path) && has_code(perm)) {
            matched_maps++;
            search_memory_syscall(path, begin, end, callback);
        }
    }
    fclose(f);
    return matched_maps;
}

struct LoadedSyscallSearch {
    const char *path;
    bool (*callback)(const char *, int, void *);
    int matched_segments;
};

static int find_loaded_syscalls_callback(struct dl_phdr_info *info, size_t, void *data) {
    if (info == nullptr || data == nullptr || info->dlpi_phdr == nullptr) {
        return 0;
    }
    auto *search = reinterpret_cast<LoadedSyscallSearch *>(data);
    const char *module_path = info->dlpi_name;
    if (module_path == nullptr || module_path[0] == '\0'
            || strstr(module_path, search->path) == nullptr) {
        return 0;
    }

    /*
     * 新系统上，应用看到的文件列表可能不包含刚装入的游戏文件，但装载器记录仍然完整。
     * 这里只读取同时可读、可执行的代码段，避免把数据段误当成指令扫描。
     */
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &header = info->dlpi_phdr[i];
        if (header.p_type != PT_LOAD
                || (header.p_flags & PF_R) == 0
                || (header.p_flags & PF_X) == 0
                || header.p_memsz < sizeof(int32_t) * 2) {
            continue;
        }
        addr_t begin = static_cast<addr_t>(info->dlpi_addr) + header.p_vaddr;
        addr_t end = begin + header.p_memsz;
        search->matched_segments++;
        search_memory_syscall(module_path, begin, end, search->callback);
    }
    return 0;
}

int findLoadedSyscallsCount(const char *path,
                            bool (*callback)(const char *, int, void *)) {
    if (path == nullptr || path[0] == '\0' || callback == nullptr) {
        return 0;
    }
    LoadedSyscallSearch search = {
            path,
            callback,
            0,
    };
    dl_iterate_phdr(find_loaded_syscalls_callback, &search);
    return search.matched_segments;
}

void findSyscalls(const char *path, bool (*callback)(const char *, int, void *)) {
    findSyscallsCount(path, callback);
}
