#include <cstring>
#include <cstdio>
#include <limits.h>
#include <unistd.h>
#include <stdlib.h>
#include <syscall.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <ctype.h>
#include "MapsRedirector.h"
#include "Log.h"
#include "SandboxFs.h"

static int create_temp_file() {
    char pattern[PATH_MAX] = {0};
    char *cache_dir = getenv("V_NATIVE_PATH");
    if (cache_dir == NULL || cache_dir[0] == '\0') {
        cache_dir = const_cast<char *>("/data/local/tmp");
    }
    int fd = static_cast<int>(syscall(__NR_openat, AT_FDCWD, cache_dir,
                                      O_RDWR | O_CLOEXEC | O_TMPFILE | O_EXCL, 0600));
    if (fd != -1) {
        return fd;
    }

    snprintf(pattern, sizeof(pattern), "%s/dev_maps_%d_%d", cache_dir, getpid(), gettid());
    fd = static_cast<int>(syscall(__NR_openat, AT_FDCWD, pattern,
                                  O_CREAT | O_RDWR | O_TRUNC | O_CLOEXEC, 0600));
    if (fd == -1) {
        ALOGE("fake_maps: cannot create tmp file, errno = %d", errno);
        return -1;
    }
    unlink(pattern);
    return fd;
}

static char *match_maps_item(char *line) {
    char *p = strstr(line, " /data/");
    return p;
}

static bool match_host_pkg(const char *path) {
    if (strstr(path, "com.ProjectMoon.LimbusCompany") != NULL) {
        return false;
    }
    return strstr(path, "io.busniess.va") != NULL
           || strstr(path, "com.example.limbuszhcn") != NULL
           || strstr(path, "libv++") != NULL;
}

static bool is_limbus_anonymous_exec_trampoline(const char *line) {
    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    if (real_lib_dir == NULL || real_lib_dir[0] == '\0' || line == NULL) {
        return false;
    }

    unsigned long start = 0;
    unsigned long end = 0;
    unsigned long offset = 0;
    unsigned long inode = 0;
    char permissions[5] = {0};
    char device[16] = {0};
    int consumed = 0;
    int parsed = sscanf(line, "%lx-%lx %4s %lx %15s %lu %n",
                        &start, &end, permissions, &offset, device, &inode, &consumed);
    if (parsed != 6 || start >= end || strcmp(permissions, "r-xp") != 0
            || offset != 0 || inode != 0 || end - start != 4096) {
        return false;
    }
    while (line[consumed] == ' ' || line[consumed] == '\t') {
        ++consumed;
    }
    return line[consumed] == '\0';
}

static const char *rewrite_limbus_map_path(const char *path, char *buffer, size_t size) {
    if (path == NULL || strstr(path, "com.ProjectMoon.LimbusCompany") == NULL) {
        return path;
    }
    const char *lib_name = strrchr(path, '/');
    if (lib_name == NULL || strstr(lib_name, ".so") == NULL) {
        return path;
    }

    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    if (real_lib_dir == NULL || real_lib_dir[0] == '\0') {
        return path;
    }
    int written = snprintf(buffer, size, "%s%s", real_lib_dir, lib_name);
    if (written <= 0 || static_cast<size_t>(written) >= size) {
        return path;
    }
    return buffer;
}

static bool is_proc_path(const char *pathname, const char **relative) {
    if (pathname == NULL || strncmp(pathname, "/proc/", sizeof("/proc/") - 1) != 0) {
        return false;
    }
    *relative = pathname + sizeof("/proc/") - 1;
    return true;
}

static bool is_self_or_pid_path(const char *relative) {
    if (strncmp(relative, "self/", sizeof("self/") - 1) == 0) {
        return true;
    }
    const char *p = relative;
    while (*p != '\0' && *p != '/') {
        if (!isdigit(*p)) {
            return false;
        }
        ++p;
    }
    return *p == '/';
}

static bool ends_with(const char *text, const char *suffix) {
    size_t text_len = strlen(text);
    size_t suffix_len = strlen(suffix);
    return text_len >= suffix_len && strcmp(text + text_len - suffix_len, suffix) == 0;
}

static bool is_proc_maps_file(const char *relative) {
    return is_self_or_pid_path(relative)
           && (ends_with(relative, "/maps") || ends_with(relative, "/smaps"));
}

static bool is_proc_cmdline_file(const char *relative) {
    return is_self_or_pid_path(relative) && ends_with(relative, "/cmdline");
}

static bool is_proc_comm_file(const char *relative) {
    return is_self_or_pid_path(relative)
           && ends_with(relative, "/comm");
}

static bool is_proc_status_file(const char *relative) {
    return is_self_or_pid_path(relative) && ends_with(relative, "/status");
}

static bool is_proc_modules_file(const char *relative) {
    return strcmp(relative, "modules") == 0;
}

static bool should_log_fake_proc(const char *pathname) {
    static int cmdline_logs = 0;
    static int comm_logs = 0;
    static int status_logs = 0;
    static int other_logs = 0;
    const char *relative = NULL;
    if (!is_proc_path(pathname, &relative)) {
        return other_logs++ < 8;
    }
    if (is_proc_cmdline_file(relative)) {
        return cmdline_logs++ < 4;
    }
    if (is_proc_comm_file(relative)) {
        return comm_logs++ < 4;
    }
    if (is_proc_status_file(relative)) {
        return status_logs++ < 4;
    }
    return other_logs++ < 8;
}

static void redirect_proc_maps_internal(const int fd, const int fake_fd) {
    char line[PATH_MAX];
    char *p = line, *e;
    size_t n = PATH_MAX - 1;
    ssize_t r;
    while ((r = TEMP_FAILURE_RETRY(read(fd, p, n))) > 0) {
        p[r] = '\0';
        p = line; // search begin at line start

        while ((e = strchr(p, '\n')) != NULL) {
            e[0] = '\0';

            if (is_limbus_anonymous_exec_trampoline(p)) {
                static int trampoline_logs = 0;
                if (trampoline_logs++ < 8) {
                    ALOGE("fake_maps: remove anonymous executable trampoline: %s", p);
                }
                p = e + 1;
                continue;
            }

            char *path = match_maps_item(p);
            if (path != NULL) {
                ++path; // skip blank

                char temp[PATH_MAX];
                const char *real_path = reverse_relocate_path(path, temp, sizeof(temp));
                char visible_temp[PATH_MAX];
                const char *visible_path = rewrite_limbus_map_path(real_path, visible_temp, sizeof(visible_temp));
                if (visible_path != NULL && match_host_pkg(visible_path)) {
                    ALOGE("remove map item: %s", p);
                    visible_path = NULL;
                }

                write(fake_fd, p, path - p);
                if (visible_path != NULL && !match_host_pkg(visible_path)) {
                    write(fake_fd, visible_path, strlen(visible_path));
                }
                write(fake_fd, "\n", 1);
            } else {
                e[0] = '\n';
                write(fake_fd, p, e - p + 1);
            }

            p = e + 1;
        }
        if (p == line) { // !any_entry
            ALOGE("fake_maps: cannot process line larger than %u bytes!", PATH_MAX);
            goto __break;
        } //if

        const size_t remain = strlen(p);
        if (remain <= (PATH_MAX / 2)) {
            memcpy(line, p, remain * sizeof(p[0]));
        } else {
            memmove(line, p, remain * sizeof(p[0]));
        } //if

        p = line + remain;
        n = PATH_MAX - 1 - remain;
    }

    __break:
    return;
}

int redirect_proc_maps(const char *const pathname, const int flags, const int mode) {
    const char *relative = NULL;
    if (!is_proc_path(pathname, &relative) || !is_proc_maps_file(relative)) {
        return 0;
    }
    ALOGE("start redirect: %s", pathname);

    int fd = syscall(__NR_openat, AT_FDCWD, pathname, flags, mode);
    if (fd == -1) {
        errno = EACCES;
        return -1;
    }

    int fake_fd = create_temp_file();
    if (fake_fd == -1) {
        ALOGE("fake_maps: create_temp_file failed, errno = %d", errno);
        errno = EACCES;
        return -1;
    }

    redirect_proc_maps_internal(fd, fake_fd);
    lseek(fake_fd, 0, SEEK_SET);
    syscall(__NR_close, fd);

    ALOGI("fake_maps: faked %s -> fd %d", pathname, fake_fd);
    return fake_fd;
}

static int fake_proc_small_file(const char *pathname, const char *content, size_t content_len) {
    int fake_fd = create_temp_file();
    if (fake_fd == -1) {
        ALOGE("fake_proc: create_temp_file failed for %s, errno = %d", pathname, errno);
        errno = EACCES;
        return -1;
    }
    write(fake_fd, content, content_len);
    lseek(fake_fd, 0, SEEK_SET);
    if (should_log_fake_proc(pathname)) {
        ALOGI("fake_proc: faked %s -> fd %d", pathname, fake_fd);
    }
    return fake_fd;
}

static int redirect_proc_status(const char *pathname, const int flags, const int mode) {
    int fd = syscall(__NR_openat, AT_FDCWD, pathname, flags, mode);
    if (fd == -1) {
        errno = EACCES;
        return -1;
    }
    int fake_fd = create_temp_file();
    if (fake_fd == -1) {
        syscall(__NR_close, fd);
        errno = EACCES;
        return -1;
    }

    char buffer[4096];
    ssize_t r;
    bool at_line_start = true;
    while ((r = TEMP_FAILURE_RETRY(read(fd, buffer, sizeof(buffer)))) > 0) {
        char *start = buffer;
        char *end = buffer + r;
        while (start < end) {
            char *line_end = static_cast<char *>(memchr(start, '\n', end - start));
            size_t line_len = line_end == NULL ? static_cast<size_t>(end - start)
                                               : static_cast<size_t>(line_end - start + 1);
            if (at_line_start && line_len >= 5 && strncmp(start, "Name:", 5) == 0) {
                write(fake_fd, "Name:\tLimbusCompany\n", sizeof("Name:\tLimbusCompany\n") - 1);
            } else {
                write(fake_fd, start, line_len);
            }
            at_line_start = line_end != NULL;
            start += line_len;
        }
    }
    syscall(__NR_close, fd);
    lseek(fake_fd, 0, SEEK_SET);
    if (should_log_fake_proc(pathname)) {
        ALOGI("fake_proc: faked %s -> fd %d", pathname, fake_fd);
    }
    return fake_fd;
}

int redirect_proc_file(const char *const pathname, const int flags, const int mode) {
    const char *relative = NULL;
    if (!is_proc_path(pathname, &relative)) {
        return 0;
    }
    if (is_proc_maps_file(relative)) {
        return redirect_proc_maps(pathname, flags, mode);
    }
    if (is_proc_cmdline_file(relative)) {
        static const char cmdline[] = "com.ProjectMoon.LimbusCompany";
        return fake_proc_small_file(pathname, cmdline, sizeof(cmdline));
    }
    if (is_proc_comm_file(relative)) {
        static const char comm[] = "LimbusCompany\n";
        return fake_proc_small_file(pathname, comm, sizeof(comm) - 1);
    }
    if (is_proc_status_file(relative)) {
        return redirect_proc_status(pathname, flags, mode);
    }
    if (is_proc_modules_file(relative)) {
        static const char empty_modules[] = "";
        return fake_proc_small_file(pathname, empty_modules, 0);
    }
    return 0;
}
