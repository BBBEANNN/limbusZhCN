#ifndef BINARY_SYSCALL_FINDER_H
#define BINARY_SYSCALL_FINDER_H

#include <stdint.h>

#define BREAK_FIND_SYSCALL 0
#define CONTINUE_FIND_SYSCALL 1

void findSyscalls(const char *path, bool (*callback)(const char *, int, void *));
int findSyscallsCount(const char *path, bool (*callback)(const char *, int, void *));

/**
 * 从当前进程已经装入的代码段中查找系统调用。
 *
 * 仅应在装载回调之外调用，避免系统仍在更新文件列表时再次遍历。
 *
 * @param path 需要匹配的文件路径或文件名
 * @param callback 找到调用位置后的处理函数
 * @return 已扫描的可执行代码段数量
 */
int findLoadedSyscallsCount(const char *path, bool (*callback)(const char *, int, void *));

#endif //BINARY_SYSCALL_FINDER_H
