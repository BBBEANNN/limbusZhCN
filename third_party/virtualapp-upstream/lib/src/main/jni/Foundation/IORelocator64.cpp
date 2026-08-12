//
// VirtualApp Native Project
//
#include <unistd.h>
#include <stdlib.h>
#include <sys/ptrace.h>
#include <Substrate/CydiaSubstrate.h>
#include <Jni/VAJni.h>
#include <sys/stat.h>
#include <syscall.h>
#include <Foundation/syscall/BinarySyscallFinder.h>
#include <limits.h>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <sys/user.h>
#include <sys/time.h>
#include <sys/system_properties.h>
#include <ucontext.h>
#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <link.h>
#include <unwind.h>
#include <atomic>
#include <map>
#include <set>
#include <stdint.h>
#include <string>
#include <utility>

#include <asm/mman.h>
#include <sys/mman.h>
#include <arpa/inet.h>
#include <utils/zMd5.h>
#include <utils/controllerManagerNative.h>
#include <linux/in6.h>
#include <netdb.h>
#include <asm/unistd.h>

#include "IORelocator.h"
#include "LimbusTranslationRuntime.h"
#include "MapsRedirector.h"
#include "SandboxFs.h"
#include "canonicalize_md.h"
#include "Symbol.h"
#include "Log.h"

#include "transparentED/originalInterface.h"
#include "transparentED/ff_Recognizer.h"

void startIOHook(JNIEnv *env);

bool need_load_env = true;

int g_api_level;

bool execve_process = false;
bool g_limbus_container_process_known = false;
bool g_limbus_container_process = false;
static thread_local bool g_proc_redirect_active = false;
static pthread_mutex_t g_limbus_syscall_hook_lock = PTHREAD_MUTEX_INITIALIZER;
static std::set<void *> g_limbus_hooked_syscalls;
static std::set<int> g_limbus_seen_syscall_nums;
static std::set<std::string> g_limbus_seen_syscall_sites;
static pthread_mutex_t g_limbus_signal_diag_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_limbus_signal_diag_count = 0;
static int g_limbus_network_diag_count = 0;
static int g_limbus_network_io_diag_count = 0;
static struct sigaction g_limbus_sigsegv_action;
static volatile sig_atomic_t g_limbus_sigsegv_action_set = 0;
static struct sigaction g_limbus_sigill_actions[2];
static volatile sig_atomic_t g_limbus_sigill_action_index = 0;
static volatile sig_atomic_t g_limbus_sigill_action_set = 0;
static volatile uintptr_t g_limbus_appsealing_base = 0;
static const size_t kLimbusSignalModuleLimit = 128;
static const size_t kLimbusSignalModuleNameSize = 64;

struct LimbusSignalModule {
    uintptr_t start;
    uintptr_t end;
    char name[kLimbusSignalModuleNameSize];
};

static LimbusSignalModule g_limbus_signal_modules[2][kLimbusSignalModuleLimit];
static volatile sig_atomic_t g_limbus_signal_module_counts[2] = {0, 0};
static volatile sig_atomic_t g_limbus_signal_module_active = 0;
static pthread_mutex_t g_limbus_localize_io_lock = PTHREAD_MUTEX_INITIALIZER;
static std::atomic<int> g_limbus_localize_fd_count{0};
static std::set<std::string> g_limbus_localize_logged_paths;
static int g_limbus_localize_detail_log_count = 0;
static int g_limbus_localize_total_event_count = 0;
static int g_limbus_localize_last_summary_total = 0;
static long long g_limbus_localize_last_summary_ms = -1;
static const int kLimbusLocalizeDetailLogLimit = 120;
static const int kLimbusLocalizeSummaryEventInterval = 5000;
static const long long kLimbusLocalizeSummaryTimeIntervalMs = 5000;
static int g_limbus_localize_mmap_log_count = 0;
static const int kLimbusLocalizeMmapLogLimit = 1200;

struct LimbusLocalizeFdInfo {
    char original[PATH_MAX];
    char path[PATH_MAX];
    int flags;
    long long total_read;
    int read_ops;
};

static std::map<int, LimbusLocalizeFdInfo> g_limbus_localize_fds;
static timeval g_limbus_localize_io_start_time;
static bool g_limbus_localize_io_start_set = false;

#include "utils/zString.h"
#include "utils/utils.h"
#include "utils/Autolock.h"
#include "transparentED/virtualFileSystem.h"
#include "utils/mylog.h"

std::map<int64_t, MmapFileInfo *> MmapInfoMap;
using namespace xdja;

void startIOHook(int api_level);

char *get_process_name() {
    char *cmdline = (char *) calloc(0x400u, 1u);
    if (cmdline) {
        FILE *file = fopen("/proc/self/cmdline", "r");
        if (file) {
            int count = fread(cmdline, 1u, 0x400u, file);
            if (count) {
                if (cmdline[count - 1] == '\n') {
                    cmdline[count - 1] = '\0';
                }
            }
            fclose(file);
        } else {
            ALOGE("fail open cmdline.");
        }
    }
    return cmdline;
}

bool is_limbus_container_process() {
    if (g_limbus_container_process_known) {
        return g_limbus_container_process;
    }
    char *process_name = get_process_name();
    bool is_limbus_container = process_name
            && (strstr(process_name, "limbuszhcn") != nullptr
            || strstr(process_name, "com.ProjectMoon.LimbusCompany") != nullptr);
    free(process_name);
    return is_limbus_container;
}

static int block_limbus_signal_int() {
    errno = ESRCH;
    return -1;
}

static long block_limbus_signal_long() {
    errno = ESRCH;
    return -1;
}

static void log_limbus_network_bypass(const char *api, const char *target) {
    if (!is_limbus_container_process()) {
        return;
    }
    int count = __sync_fetch_and_add(&g_limbus_network_diag_count, 1);
    if (count >= 16) {
        return;
    }
    ALOGE("network guard >>> bypass Limbus VirtualApp network strategy api=%s target=%s state=%d white=%d",
          api,
          target != nullptr ? target : "(null)",
          getNetWorkState() ? 1 : 0,
          isWhiteList() ? 1 : 0);
}

static void log_limbus_network_io(const char *api, const char *target, long result, int error) {
    if (!is_limbus_container_process()) {
        return;
    }
    int count = __sync_fetch_and_add(&g_limbus_network_io_diag_count, 1);
    if (count >= 48) {
        return;
    }
    ALOGE("network io >>> Limbus api=%s target=%s result=%ld errno=%d strategy=%d",
          api,
          target != nullptr ? target : "(null)",
          result,
          error,
          getNetWorkState() ? 1 : 0);
}

static void format_limbus_socket_target(const struct sockaddr *address,
                                        char *target,
                                        size_t target_size) {
    if (target == nullptr || target_size == 0) {
        return;
    }
    target[0] = '\0';
    if (address == nullptr) {
        snprintf(target, target_size, "(null)");
        return;
    }
    char ip[INET6_ADDRSTRLEN] = {};
    if (address->sa_family == AF_INET) {
        const auto *ipv4 = reinterpret_cast<const struct sockaddr_in *>(address);
        if (inet_ntop(AF_INET, &ipv4->sin_addr, ip, sizeof(ip)) != nullptr) {
            snprintf(target, target_size, "%s:%u", ip, ntohs(ipv4->sin_port));
            return;
        }
    } else if (address->sa_family == AF_INET6) {
        const auto *ipv6 = reinterpret_cast<const struct sockaddr_in6 *>(address);
        if (inet_ntop(AF_INET6, &ipv6->sin6_addr, ip, sizeof(ip)) != nullptr) {
            snprintf(target, target_size, "[%s]:%u", ip, ntohs(ipv6->sin6_port));
            return;
        }
    }
    snprintf(target, target_size, "family=%d", address->sa_family);
}

static void patch_limbus_syscall_return_zero(void *func);

struct LimbusBacktraceState {
    uintptr_t *current;
    uintptr_t *end;
};

static _Unwind_Reason_Code limbus_unwind_callback(struct _Unwind_Context *context, void *arg) {
    auto *state = reinterpret_cast<LimbusBacktraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0 && state->current < state->end) {
        *state->current++ = pc;
    }
    return state->current == state->end ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static size_t capture_limbus_backtrace(uintptr_t *buffer, size_t max_frames) {
    LimbusBacktraceState state = {buffer, buffer + max_frames};
    _Unwind_Backtrace(limbus_unwind_callback, &state);
    return static_cast<size_t>(state.current - buffer);
}

static void log_limbus_native_backtrace(const char *api) {
    uintptr_t frames[32];
    size_t count = capture_limbus_backtrace(frames, sizeof(frames) / sizeof(frames[0]));
    ALOGE("%s >>> Limbus native backtrace frames=%zu", api, count);
    for (size_t i = 0; i < count; ++i) {
        Dl_info info;
        memset(&info, 0, sizeof(info));
        if (dladdr(reinterpret_cast<void *>(frames[i]), &info) != 0 && info.dli_fname != nullptr) {
            uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
            ALOGE("%s >>> #%zu pc %p %s +0x%lx %s",
                  api,
                  i,
                  reinterpret_cast<void *>(frames[i]),
                  info.dli_fname,
                  static_cast<unsigned long>(frames[i] - base),
                  info.dli_sname != nullptr ? info.dli_sname : "");
        } else {
            ALOGE("%s >>> #%zu pc %p <unknown>",
                  api,
                  i,
                  reinterpret_cast<void *>(frames[i]));
        }
    }
}

static void read_limbus_thread_name(pid_t tid, char *buffer, size_t buffer_size) {
    if (buffer == nullptr || buffer_size == 0) {
        return;
    }
    buffer[0] = '\0';
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
    FILE *file = fopen(path, "r");
    if (file == nullptr) {
        return;
    }
    if (fgets(buffer, static_cast<int>(buffer_size), file) != nullptr) {
        size_t len = strlen(buffer);
        if (len > 0 && buffer[len - 1] == '\n') {
            buffer[len - 1] = '\0';
        }
    }
    fclose(file);
}

static bool should_return_from_limbus_known_exit() {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.limbus.appsealing_exit_action", value) <= 0) {
        return false;
    }
    return strcmp(value, "return") == 0;
}

static void log_limbus_signal_diagnostic(const char *api, pid_t tid, int sig) {
    if (!is_limbus_container_process()) {
        return;
    }
#ifdef SIGXCPU
    bool interesting = sig == SIGXCPU;
#else
    bool interesting = false;
#endif
#ifdef SIGPWR
    interesting = interesting || sig == SIGPWR;
#endif
    if (!interesting) {
        return;
    }
    pthread_mutex_lock(&g_limbus_signal_diag_lock);
    if (g_limbus_signal_diag_count >= 8) {
        pthread_mutex_unlock(&g_limbus_signal_diag_lock);
        return;
    }
    g_limbus_signal_diag_count++;
    pthread_mutex_unlock(&g_limbus_signal_diag_lock);

    char target_name[64];
    read_limbus_thread_name(tid, target_name, sizeof(target_name));
    ALOGE("%s >>> Limbus signal diagnostic sig=%d caller_tid=%ld target_tid=%d target=%s",
          api,
          sig,
          static_cast<long>(syscall(__NR_gettid)),
          tid,
          target_name[0] == '\0' ? "<unknown>" : target_name);
}

static bool should_patch_limbus_known_exit_point(const char *api, int status, void *caller) {
    if (strcmp(api, "exit") != 0 || status != 0 || caller == nullptr) {
        return false;
    }
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (dladdr(caller, &info) == 0 || info.dli_fname == nullptr) {
        return false;
    }
    if (strstr(info.dli_fname, "libcovault-appsec.so") == nullptr) {
        return false;
    }
    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t caller_offset = reinterpret_cast<uintptr_t>(caller) - base;
    if (caller_offset == 0x20c58) {
        void *exit_group_svc = reinterpret_cast<void *>(base + 0x20d40);
        ALOGE("%s >>> patch known libcovault exit_group syscall func=%p",
              api,
              exit_group_svc);
        patch_limbus_syscall_return_zero(exit_group_svc);
    } else if (caller_offset == 0x23088) {
        void *exit_group_svc = reinterpret_cast<void *>(base + 0x2313c);
        ALOGE("%s >>> patch known libcovault secondary exit_group syscall func=%p",
              api,
              exit_group_svc);
        patch_limbus_syscall_return_zero(exit_group_svc);
    } else if (caller_offset == 0x70e2c) {
        void *exit_group_svc = reinterpret_cast<void *>(base + 0x70ee0);
        ALOGE("%s >>> patch known libcovault late exit_group syscall func=%p",
              api,
              exit_group_svc);
        patch_limbus_syscall_return_zero(exit_group_svc);
    } else {
        return false;
    }
    ALOGE("%s >>> patched known Limbus caller and return caller=%s +0x%lx",
          api,
          info.dli_fname,
          static_cast<unsigned long>(caller_offset));
    return true;
}

static bool handle_limbus_process_exit(const char *api, int status, void *caller) {
    pid_t tid = static_cast<pid_t>(syscall(__NR_gettid));
    char thread_name[64];
    read_limbus_thread_name(tid, thread_name, sizeof(thread_name));
    ALOGE("%s >>> stop Limbus caller thread status : %d tid : %d thread : %s pid : %d",
          api,
          status,
          tid,
          thread_name[0] == '\0' ? "<unknown>" : thread_name,
          getpid());
    log_limbus_native_backtrace(api);
    if (should_patch_limbus_known_exit_point(api, status, caller)) {
        if (should_return_from_limbus_known_exit()) {
            ALOGE("%s >>> return from known Limbus AppSealing exit hook thread=%s",
                  api,
                  thread_name[0] == '\0' ? "<unknown>" : thread_name);
            return true;
        }
        ALOGE("%s >>> terminate known Limbus AppSealing caller thread", api);
        pthread_exit(nullptr);
        return true;
    }
    pthread_exit(nullptr);
    return false;
}

static bool should_block_limbus_terminating_signal(int sig) {
    if (!is_limbus_container_process()) {
        return false;
    }
#ifdef SIGXCPU
    if (sig == SIGXCPU) {
        return false;
    }
#endif
#ifdef SIGPWR
    if (sig == SIGPWR) {
        return false;
    }
#endif
    switch (sig) {
        case SIGKILL:
        case SIGTERM:
        case SIGABRT:
        case SIGQUIT:
        case SIGTRAP:
        case SIGUSR1:
        case SIGUSR2:
            return true;
        default:
            return false;
    }
}

static bool is_limbus_allowed_signal_noise(int sig) {
    if (!is_limbus_container_process()) {
        return false;
    }
#ifdef SIGXCPU
    if (sig == SIGXCPU) {
        return true;
    }
#endif
#ifdef SIGPWR
    if (sig == SIGPWR) {
        return true;
    }
#endif
    return false;
}

static bool limbus_should_log_io_path(const char *pathname) {
    if (!g_limbus_container_process_known || !g_limbus_container_process || pathname == nullptr) {
        return false;
    }
    // 常规版本不记录全部 I/O 路径，避免 Unity 高频文件探测拖慢游戏启动。
    return false;
}

static bool limbus_is_interesting_game_path(const char *pathname) {
    if (!g_limbus_container_process_known || !g_limbus_container_process || pathname == nullptr) {
        return false;
    }
    return strstr(pathname, "Resources_moved") != nullptr
            || strstr(pathname, "Localize") != nullptr
            || strstr(pathname, "AssetBundles") != nullptr
            || strstr(pathname, "Addressable") != nullptr
            || strstr(pathname, "/aa/") != nullptr;
}

static bool limbus_localize_io_diag_enabled() {
    char prop[PROP_VALUE_MAX] = {};
    return __system_property_get("debug.limbus.localize_io", prop) > 0
            && strcmp(prop, "1") == 0;
}

static bool limbus_is_localize_io_path(const char *pathname) {
    if (!g_limbus_container_process_known
            || !g_limbus_container_process
            || pathname == nullptr) {
        return false;
    }
    bool looks_like_localize = strstr(pathname, "Resources_moved/Localize") != nullptr
            || strstr(pathname, "/Localize/") != nullptr
            || strstr(pathname, "/Lang/") != nullptr
            || strstr(pathname, "/translation-cache/") != nullptr;
    return looks_like_localize && limbus_localize_io_diag_enabled();
}

static long long limbus_localize_elapsed_ms_locked() {
    timeval now{};
    gettimeofday(&now, nullptr);
    if (!g_limbus_localize_io_start_set) {
        g_limbus_localize_io_start_time = now;
        g_limbus_localize_io_start_set = true;
    }
    long long sec = static_cast<long long>(now.tv_sec) -
            static_cast<long long>(g_limbus_localize_io_start_time.tv_sec);
    long long usec = static_cast<long long>(now.tv_usec) -
            static_cast<long long>(g_limbus_localize_io_start_time.tv_usec);
    return sec * 1000LL + usec / 1000LL;
}

static long limbus_gettid() {
    return static_cast<long>(syscall(__NR_gettid));
}

static const char *limbus_basename(const char *path) {
    if (path == nullptr) {
        return "(null)";
    }
    const char *slash = strrchr(path, '/');
    return slash != nullptr ? slash + 1 : path;
}

static void limbus_format_caller(char *dest, size_t size, void *caller) {
    if (dest == nullptr || size == 0) {
        return;
    }
    if (caller == nullptr) {
        snprintf(dest, size, "(null)");
        return;
    }
    Dl_info info{};
    if (dladdr(caller, &info) != 0 && info.dli_fname != nullptr && info.dli_fbase != nullptr) {
        uintptr_t offset = reinterpret_cast<uintptr_t>(caller)
                - reinterpret_cast<uintptr_t>(info.dli_fbase);
        snprintf(dest, size, "%s+0x%llx",
                 limbus_basename(info.dli_fname),
                 static_cast<unsigned long long>(offset));
        return;
    }
    snprintf(dest, size, "%p", caller);
}

static void limbus_copy_path(char *dest, size_t size, const char *source) {
    if (dest == nullptr || size == 0) {
        return;
    }
    if (source == nullptr) {
        snprintf(dest, size, "(null)");
        return;
    }
    snprintf(dest, size, "%s", source);
}

static bool limbus_should_log_localize_detail_locked(const char *op,
                                                     const char *original,
                                                     const char *relocated,
                                                     const char *caller) {
    g_limbus_localize_total_event_count++;
    if (g_limbus_localize_detail_log_count >= kLimbusLocalizeDetailLogLimit) {
        return false;
    }
    const char *path = relocated != nullptr ? relocated : original;
    if (path == nullptr) {
        path = "(null)";
    }
    std::string key = op != nullptr ? op : "(null)";
    key.push_back('\n');
    key.append(path);
    key.push_back('\n');
    key.append(caller != nullptr ? caller : "(null)");
    if (!g_limbus_localize_logged_paths.insert(key).second) {
        return false;
    }
    g_limbus_localize_detail_log_count++;
    return true;
}

static bool limbus_should_log_localize_summary_locked(long long elapsed) {
    if (g_limbus_localize_detail_log_count < kLimbusLocalizeDetailLogLimit) {
        return false;
    }
    if (g_limbus_localize_last_summary_ms < 0) {
        g_limbus_localize_last_summary_ms = elapsed;
        g_limbus_localize_last_summary_total = g_limbus_localize_total_event_count;
        return true;
    }
    if (g_limbus_localize_total_event_count - g_limbus_localize_last_summary_total
            >= kLimbusLocalizeSummaryEventInterval
            || elapsed - g_limbus_localize_last_summary_ms
            >= kLimbusLocalizeSummaryTimeIntervalMs) {
        g_limbus_localize_last_summary_ms = elapsed;
        g_limbus_localize_last_summary_total = g_limbus_localize_total_event_count;
        return true;
    }
    return false;
}

static void limbus_record_localize_open(const char *op,
                                        const char *original,
                                        const char *relocated,
                                        int fd,
                                        int flags,
                                        void *caller) {
    if (fd < 0 || (!limbus_is_localize_io_path(original) && !limbus_is_localize_io_path(relocated))) {
        return;
    }
    char caller_text[160];
    limbus_format_caller(caller_text, sizeof(caller_text), caller);
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto existing = g_limbus_localize_fds.find(fd);
    if (existing == g_limbus_localize_fds.end()) {
        g_limbus_localize_fd_count.fetch_add(1, std::memory_order_relaxed);
    }
    LimbusLocalizeFdInfo &info = g_limbus_localize_fds[fd];
    limbus_copy_path(info.original, sizeof(info.original), original);
    limbus_copy_path(info.path, sizeof(info.path), relocated);
    info.flags = flags;
    info.total_read = 0;
    info.read_ops = 0;
    long long elapsed = limbus_localize_elapsed_ms_locked();
    bool should_log = limbus_should_log_localize_detail_locked(op, original, relocated, caller_text);
    bool should_summary = !should_log && limbus_should_log_localize_summary_locked(elapsed);
    int detail_count = g_limbus_localize_detail_log_count;
    int total_count = g_limbus_localize_total_event_count;
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!should_log) {
        if (should_summary) {
            ALOGI("Limbus Localize IO summary t=%lld total=%d detail=%d last_op=%s caller=%s path=%s",
                  elapsed,
                  total_count,
                  detail_count,
                  op != nullptr ? op : "(null)",
                  caller_text,
                  relocated != nullptr ? relocated : (original != nullptr ? original : "(null)"));
        }
        return;
    }
    ALOGI("Limbus Localize IO open t=%lld tid=%ld op=%s fd=%d flags=0x%x caller=%s original=%s path=%s",
          elapsed,
          limbus_gettid(),
          op != nullptr ? op : "(null)",
          fd,
          flags,
          caller_text,
          original != nullptr ? original : "(null)",
          relocated != nullptr ? relocated : "(null)");
    if (detail_count == kLimbusLocalizeDetailLogLimit) {
        ALOGI("Limbus Localize IO detail limit reached total=%d detail=%d", total_count, detail_count);
    }
}

static void limbus_record_localize_meta(const char *op,
                                        const char *original,
                                        const char *relocated) {
    if (!limbus_is_localize_io_path(original) && !limbus_is_localize_io_path(relocated)) {
        return;
    }
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    long long elapsed = limbus_localize_elapsed_ms_locked();
    bool should_log = limbus_should_log_localize_detail_locked(op, original, relocated, "meta");
    bool should_summary = !should_log && limbus_should_log_localize_summary_locked(elapsed);
    int detail_count = g_limbus_localize_detail_log_count;
    int total_count = g_limbus_localize_total_event_count;
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!should_log) {
        if (should_summary) {
            ALOGI("Limbus Localize IO summary t=%lld total=%d detail=%d last_op=%s caller=meta path=%s",
                  elapsed,
                  total_count,
                  detail_count,
                  op != nullptr ? op : "(null)",
                  relocated != nullptr ? relocated : (original != nullptr ? original : "(null)"));
        }
        return;
    }
    ALOGI("Limbus Localize IO meta t=%lld tid=%ld op=%s original=%s path=%s",
          elapsed,
          limbus_gettid(),
          op != nullptr ? op : "(null)",
          original != nullptr ? original : "(null)",
          relocated != nullptr ? relocated : "(null)");
    if (detail_count == kLimbusLocalizeDetailLogLimit) {
        ALOGI("Limbus Localize IO detail limit reached total=%d detail=%d", total_count, detail_count);
    }
}

static void limbus_record_localize_read(const char *op,
                                        int fd,
                                        size_t requested,
                                        ssize_t ret,
                                        off64_t offset,
                                        bool has_offset) {
    if (g_limbus_localize_fd_count.load(std::memory_order_relaxed) <= 0) {
        return;
    }
    char original[PATH_MAX];
    char path[PATH_MAX];
    int flags = 0;
    long long total = 0;
    int ops = 0;
    long long elapsed = 0;
    bool found = false;
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto it = g_limbus_localize_fds.find(fd);
    if (it != g_limbus_localize_fds.end()) {
        found = true;
        if (ret > 0) {
            it->second.total_read += ret;
        }
        it->second.read_ops += 1;
        limbus_copy_path(original, sizeof(original), it->second.original);
        limbus_copy_path(path, sizeof(path), it->second.path);
        flags = it->second.flags;
        total = it->second.total_read;
        ops = it->second.read_ops;
        elapsed = limbus_localize_elapsed_ms_locked();
    }
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!found) {
        return;
    }
    if (has_offset) {
        ALOGI("Limbus Localize IO read t=%lld tid=%ld op=%s fd=%d requested=%zu ret=%zd offset=%lld total=%lld ops=%d flags=0x%x original=%s path=%s",
              elapsed,
              limbus_gettid(),
              op != nullptr ? op : "(null)",
              fd,
              requested,
              ret,
              static_cast<long long>(offset),
              total,
              ops,
              flags,
              original,
              path);
    } else {
        ALOGI("Limbus Localize IO read t=%lld tid=%ld op=%s fd=%d requested=%zu ret=%zd total=%lld ops=%d flags=0x%x original=%s path=%s",
              elapsed,
              limbus_gettid(),
              op != nullptr ? op : "(null)",
              fd,
              requested,
              ret,
              total,
              ops,
              flags,
              original,
              path);
    }
}

static void limbus_record_localize_stream_read(const char *op,
                                               FILE *stream,
                                               size_t requested,
                                               ssize_t ret) {
    if (stream == nullptr) {
        return;
    }
    int fd = fileno(stream);
    if (fd < 0) {
        return;
    }
    limbus_record_localize_read(op, fd, requested, ret, 0, false);
}

static void limbus_record_localize_close(int fd) {
    if (g_limbus_localize_fd_count.load(std::memory_order_relaxed) <= 0) {
        return;
    }
    char original[PATH_MAX];
    char path[PATH_MAX];
    int flags = 0;
    long long total = 0;
    int ops = 0;
    long long elapsed = 0;
    bool found = false;
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto it = g_limbus_localize_fds.find(fd);
    if (it != g_limbus_localize_fds.end()) {
        found = true;
        limbus_copy_path(original, sizeof(original), it->second.original);
        limbus_copy_path(path, sizeof(path), it->second.path);
        flags = it->second.flags;
        total = it->second.total_read;
        ops = it->second.read_ops;
        elapsed = limbus_localize_elapsed_ms_locked();
        g_limbus_localize_fds.erase(it);
        g_limbus_localize_fd_count.fetch_sub(1, std::memory_order_relaxed);
    }
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    if (!found) {
        return;
    }
    (void) elapsed;
    (void) total;
    (void) ops;
    (void) flags;
    (void) original;
    (void) path;
}

static bool limbus_find_localize_fd_info(int fd,
                                         LimbusLocalizeFdInfo *out,
                                         long long *elapsed_out) {
    if (g_limbus_localize_fd_count.load(std::memory_order_relaxed) <= 0) {
        return false;
    }
    bool found = false;
    pthread_mutex_lock(&g_limbus_localize_io_lock);
    auto it = g_limbus_localize_fds.find(fd);
    if (it != g_limbus_localize_fds.end()) {
        found = true;
        if (out != nullptr) {
            *out = it->second;
        }
        if (elapsed_out != nullptr) {
            *elapsed_out = limbus_localize_elapsed_ms_locked();
        }
    }
    pthread_mutex_unlock(&g_limbus_localize_io_lock);
    return found;
}

static bool limbus_should_log_localize_mmap_detail() {
    if (!limbus_localize_io_diag_enabled()) {
        return false;
    }
    int count = __sync_fetch_and_add(&g_limbus_localize_mmap_log_count, 1);
    if (count == kLimbusLocalizeMmapLogLimit) {
        ALOGI("Limbus Localize FD mmap diagnostic detail limit reached limit=%d",
              kLimbusLocalizeMmapLogLimit);
    }
    return count < kLimbusLocalizeMmapLogLimit;
}

static void limbus_record_localize_fd_meta(const char *op,
                                           int fd,
                                           long long arg1,
                                           long long arg2,
                                           long long ret) {
    LimbusLocalizeFdInfo info{};
    long long elapsed = 0;
    if (!limbus_find_localize_fd_info(fd, &info, &elapsed)
            || !limbus_should_log_localize_mmap_detail()) {
        return;
    }
    ALOGI("Limbus Localize FD %s t=%lld tid=%ld fd=%d arg1=%lld arg2=%lld ret=%lld total=%lld ops=%d flags=0x%x original=%s path=%s",
          op != nullptr ? op : "(null)",
          elapsed,
          limbus_gettid(),
          fd,
          arg1,
          arg2,
          ret,
          info.total_read,
          info.read_ops,
          info.flags,
          info.original,
          info.path);
}

static void limbus_record_localize_mmap(const char *op,
                                        int fd,
                                        void *ret,
                                        size_t length,
                                        int prot,
                                        int flags,
                                        size_t pgoffset,
                                        bool virtual_file) {
    LimbusLocalizeFdInfo info{};
    long long elapsed = 0;
    if (!limbus_find_localize_fd_info(fd, &info, &elapsed)
            || !limbus_should_log_localize_mmap_detail()) {
        return;
    }
    ALOGI("Limbus Localize FD %s t=%lld tid=%ld fd=%d ret=%p length=%zu prot=0x%x flags=0x%x pgoffset=%zu virtual=%d total=%lld ops=%d original=%s path=%s",
          op != nullptr ? op : "(null)",
          elapsed,
          limbus_gettid(),
          fd,
          ret,
          length,
          prot,
          flags,
          pgoffset,
          virtual_file ? 1 : 0,
          info.total_read,
          info.read_ops,
          info.original,
          info.path);
}

static void limbus_log_io_path(const char *op, const char *pathname, const char *relocated_path) {
    limbus_record_localize_meta(op, pathname, relocated_path);
    if (!limbus_should_log_io_path(pathname)
            && !limbus_should_log_io_path(relocated_path)
            && !limbus_is_interesting_game_path(pathname)
            && !limbus_is_interesting_game_path(relocated_path)) {
        return;
    }
    // 只保留少量与汉化资源有关的诊断，避免高频文件访问刷满日志。
    static int resource_path_logs = 0;
    if (__atomic_fetch_add(&resource_path_logs, 1, __ATOMIC_RELAXED) > 160) {
        return;
    }
    ALOGI("Limbus IO %s path=%s relocated=%s",
          op,
          pathname != nullptr ? pathname : "(null)",
          relocated_path != nullptr ? relocated_path : "(null)");
}

static void limbus_log_io_failure(const char *op, const char *pathname, const char *relocated_path, int ret, int saved_errno) {
    static int failure_logs = 0;
    if (failure_logs++ > 80) {
        return;
    }
    if (!limbus_is_interesting_game_path(pathname)
            && !limbus_is_interesting_game_path(relocated_path)) {
        return;
    }
    ALOGE("Limbus IO failed %s ret=%d errno=%d path=%s relocated=%s",
          op,
          ret,
          saved_errno,
          pathname != nullptr ? pathname : "(null)",
          relocated_path != nullptr ? relocated_path : "(null)");
}

static const char *relocate_path_with_dirfd(int dirfd, const char *pathname, char *const buffer, const size_t size) {
    if (pathname == nullptr || pathname[0] == '/' || dirfd == AT_FDCWD) {
        return relocate_path(pathname, buffer, size);
    }
    char fd_link[64];
    snprintf(fd_link, sizeof(fd_link), "/proc/self/fd/%d", dirfd);
    char dir_path[PATH_MAX];
    long dir_len = syscall(__NR_readlinkat, AT_FDCWD, fd_link, dir_path, sizeof(dir_path) - 1);
    if (dir_len <= 0) {
        return relocate_path(pathname, buffer, size);
    }
    dir_path[dir_len] = '\0';
    char full_path[PATH_MAX];
    int written = snprintf(full_path, sizeof(full_path), "%s/%s", dir_path, pathname);
    if (written <= 0 || static_cast<size_t>(written) >= sizeof(full_path)) {
        return relocate_path(pathname, buffer, size);
    }
    const char *relocated_full = relocate_path(full_path, buffer, size);
    if (relocated_full != nullptr && strcmp(relocated_full, full_path) != 0) {
        if (limbus_is_interesting_game_path(full_path) || limbus_is_interesting_game_path(relocated_full)) {
            ALOGI("Limbus IO dirfd path fd=%d path=%s full=%s relocated=%s",
                  dirfd,
                  pathname,
                  full_path,
                  relocated_full);
        }
        return relocated_full;
    }
    return relocate_path(pathname, buffer, size);
}

static bool limbus_is_translation_cache_path(const char *path) {
    return path != nullptr
           && strstr(path, "com.example.limbuszhcn") != nullptr
           && strstr(path, "/translation-cache/") != nullptr;
}

static const char *limbus_virtual_storage_path(const char *path, char *const buffer, const size_t size) {
    if (path == nullptr || buffer == nullptr || size == 0) {
        return path;
    }
    struct PrefixRule {
        const char *visible;
        const char *actual;
    };
    static const PrefixRule rules[] = {
            {"/storage/emulated/0/", "/data/user/0/com.example.limbuszhcn/virtual/storage/emulated/0/"},
            {"/sdcard/", "/data/user/0/com.example.limbuszhcn/virtual/storage/emulated/0/"},
            {"/mnt/sdcard/", "/data/user/0/com.example.limbuszhcn/virtual/storage/emulated/0/"},
    };
    for (const PrefixRule &rule : rules) {
        size_t prefix_len = strlen(rule.visible);
        if (strncmp(path, rule.visible, prefix_len) != 0) {
            continue;
        }
        int written = snprintf(buffer, size, "%s%s", rule.actual, path + prefix_len);
        if (written <= 0 || static_cast<size_t>(written) >= size) {
            return path;
        }
        return buffer;
    }
    return path;
}

static const char *relocate_metadata_path_with_dirfd(int dirfd, const char *pathname, char *const buffer, const size_t size) {
    const char *relocated_path = relocate_path_with_dirfd(dirfd, pathname, buffer, size);
    if (!limbus_is_translation_cache_path(relocated_path)) {
        return relocated_path;
    }
    char translated_path[PATH_MAX];
    translated_path[0] = '\0';
    if (relocated_path != nullptr) {
        snprintf(translated_path, sizeof(translated_path), "%s", relocated_path);
    }
    char full_path[PATH_MAX];
    const char *metadata_path = pathname;
    if (pathname != nullptr && pathname[0] != '/' && dirfd != AT_FDCWD) {
        char fd_link[64];
        snprintf(fd_link, sizeof(fd_link), "/proc/self/fd/%d", dirfd);
        char dir_path[PATH_MAX];
        long dir_len = syscall(__NR_readlinkat, AT_FDCWD, fd_link, dir_path, sizeof(dir_path) - 1);
        if (dir_len > 0) {
            dir_path[dir_len] = '\0';
            int written = snprintf(full_path, sizeof(full_path), "%s/%s", dir_path, pathname);
            if (written > 0 && static_cast<size_t>(written) < sizeof(full_path)) {
                metadata_path = full_path;
            }
        }
    }
    const char *actual_metadata_path = limbus_virtual_storage_path(metadata_path, buffer, size);
    if (limbus_is_interesting_game_path(metadata_path) || limbus_is_interesting_game_path(actual_metadata_path)) {
        ALOGI("Limbus IO metadata bypass translation path=%s translated=%s metadata=%s",
              metadata_path != nullptr ? metadata_path : "(null)",
              translated_path[0] != '\0' ? translated_path : "(null)",
              actual_metadata_path != nullptr ? actual_metadata_path : "(null)");
    }
    return actual_metadata_path;
}

static const char *limbus_reverse_host_vm_path(const char *path, char *const buffer, const size_t size) {
    if (!is_limbus_container_process() || path == nullptr || buffer == nullptr || size == 0) {
        return nullptr;
    }
    struct PrefixRule {
        const char *actual;
        const char *visible;
    };
    static const PrefixRule rules[] = {
            {
                    "/data/data/com.example.limbuszhcn/virtual/storage/emulated/0/",
                    "/storage/emulated/0/"
            },
            {
                    "/data/user/0/com.example.limbuszhcn/virtual/storage/emulated/0/",
                    "/storage/emulated/0/"
            },
            {
                    "/data/data/com.example.limbuszhcn/virtual/data/user/0/com.ProjectMoon.LimbusCompany/",
                    "/data/user/0/com.ProjectMoon.LimbusCompany/"
            },
            {
                    "/data/user/0/com.example.limbuszhcn/virtual/data/user/0/com.ProjectMoon.LimbusCompany/",
                    "/data/user/0/com.ProjectMoon.LimbusCompany/"
            },
    };
    for (const PrefixRule &rule : rules) {
        size_t prefix_len = strlen(rule.actual);
        if (strncmp(path, rule.actual, prefix_len) != 0) {
            continue;
        }
        int written = snprintf(buffer, size, "%s%s", rule.visible, path + prefix_len);
        if (written < 0 || static_cast<size_t>(written) >= size) {
            return nullptr;
        }
        return buffer;
    }
    return nullptr;
}

static int reverse_readlink_result(char *buf, size_t bufsiz, int ret) {
    if (ret <= 0 || buf == nullptr || bufsiz == 0) {
        return ret;
    }
    char link_temp[PATH_MAX];
    size_t copy_len = static_cast<size_t>(ret);
    if (copy_len >= sizeof(link_temp)) {
        copy_len = sizeof(link_temp) - 1;
    }
    memcpy(link_temp, buf, copy_len);
    link_temp[copy_len] = '\0';

    char reversed_temp[PATH_MAX];
    const char *reversed = reverse_relocate_path(link_temp, reversed_temp, sizeof(reversed_temp));
    if (reversed == nullptr || strcmp(reversed, link_temp) == 0) {
        reversed = limbus_reverse_host_vm_path(link_temp, reversed_temp, sizeof(reversed_temp));
    }
    if (reversed == nullptr || strcmp(reversed, link_temp) == 0) {
        return ret;
    }
    size_t reversed_len = strlen(reversed);
    if (reversed_len > bufsiz) {
        reversed_len = bufsiz;
    }
    memcpy(buf, reversed, reversed_len);
    if (strstr(link_temp, "com.example.limbuszhcn") != nullptr
            || strstr(link_temp, "/virtual/") != nullptr) {
        ALOGI("Limbus readlink reverse %s -> %.*s",
              link_temp,
              static_cast<int>(reversed_len),
              buf);
    }
    return static_cast<int>(reversed_len);
}

void init_limbus_container_process_flag() {
    char *process_name = get_process_name();
    g_limbus_container_process = process_name
            && (strstr(process_name, "limbuszhcn") != nullptr
            || strstr(process_name, "com.ProjectMoon.LimbusCompany") != nullptr);
    g_limbus_container_process_known = true;
    ALOGI("Limbus container process flag : %d name : %s",
          g_limbus_container_process ? 1 : 0,
          process_name != nullptr ? process_name : "(null)");
    free(process_name);
}

static int redirect_proc_file_guarded(const char *pathname, int flags, int mode) {
    if (g_proc_redirect_active) {
        return 0;
    }
    g_proc_redirect_active = true;
    int fake_fd = redirect_proc_file(pathname, flags, mode);
    g_proc_redirect_active = false;
    return fake_fd;
}

void ignore_limbus_sigalrm_if_needed() {
    if (!is_limbus_container_process()) {
        return;
    }
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = SIG_IGN;
    sigemptyset(&action.sa_mask);
    int ret = sigaction(SIGALRM, &action, nullptr);
    ALOGE("sigaction >>> install Limbus container SIGALRM ignore ret : %d errno : %d", ret, errno);
}

void IOUniformer::init_env_before_all() {
    if (!need_load_env) {
        return;
    }
    need_load_env = false;
    char *ld_preload = getenv("LD_PRELOAD");
    if (!ld_preload || !strstr(ld_preload, CORE_SO_NAME)) {
        return;
    }
    execve_process = true;
    char *process_name = get_process_name();
    ALOGI("Start init env : %s", process_name);
    free(process_name);
    char src_key[KEY_MAX];
    char dst_key[KEY_MAX];
    int i = 0;
    while (true) {
        memset(src_key, 0, sizeof(src_key));
        memset(dst_key, 0, sizeof(dst_key));
        sprintf(src_key, "V_REPLACE_ITEM_SRC_%d", i);
        sprintf(dst_key, "V_REPLACE_ITEM_DST_%d", i);
        char *src_value = getenv(src_key);
        if (!src_value) {
            break;
        }
        char *dst_value = getenv(dst_key);
        add_replace_item(src_value, dst_value);
        i++;
    }
    i = 0;
    while (true) {
        memset(src_key, 0, sizeof(src_key));
        sprintf(src_key, "V_KEEP_ITEM_%d", i);
        char *keep_value = getenv(src_key);
        if (!keep_value) {
            break;
        }
        add_keep_item(keep_value);
        i++;
    }
    i = 0;
    while (true) {
        memset(src_key, 0, sizeof(src_key));
        sprintf(src_key, "V_FORBID_ITEM_%d", i);
        char *forbid_value = getenv(src_key);
        if (!forbid_value) {
            break;
        }
        add_forbidden_item(forbid_value);
        i++;
    }
    char *api_level_char = getenv("V_API_LEVEL");
    if (api_level_char != NULL) {
        int api_level = atoi(api_level_char);
        startIOHook(api_level);
    }
}

static inline void
hook_function(void *handle, const char *symbol, void *new_func, void **old_func) {
    void *addr = dlsym(handle, symbol);
    if (addr == NULL) {
        ALOGE("Not found symbol : %s", symbol);
        return;
    }
    MSHookFunction(addr, new_func, old_func);
}

void onSoLoaded(const char *name, void *handle);

void IOUniformer::relocate(const char *orig_path, const char *new_path) {
    add_replace_item(orig_path, new_path);
}

const char *IOUniformer::query(const char *orig_path, char *const buffer, const size_t size) {
    return relocate_path(orig_path, buffer, size);
}

void IOUniformer::whitelist(const char *_path) {
    add_keep_item(_path);
}

void IOUniformer::forbid(const char *_path) {
    add_forbidden_item(_path);
}

void IOUniformer::readOnly(const char *_path) {
    add_readonly_item(_path);
}

const char *IOUniformer::reverse(const char *_path, char *const buffer, const size_t size) {
    return reverse_relocate_path(_path, buffer, size);
}


__BEGIN_DECLS

// int faccessat(int dirfd, const char *pathname, int mode, int flags);
HOOK_DEF(int, faccessat, int dirfd, const char *pathname, int mode, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(dirfd, pathname, temp, sizeof(temp));
    limbus_log_io_path("faccessat", pathname, relocated_path);
    if (relocated_path && !(mode & W_OK && isReadOnly(relocated_path))) {
        int ret = static_cast<int>(syscall(__NR_faccessat, dirfd, relocated_path, mode, flags));
        if (ret < 0) {
            limbus_log_io_failure("faccessat", pathname, relocated_path, ret, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("faccessat", pathname, relocated_path, -1, errno);
    return -1;
}

// int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags);
HOOK_DEF(int, fchmodat, int dirfd, const char *pathname, mode_t mode, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_fchmodat, dirfd, relocated_path, mode, flags));
    }
    errno = EACCES;
    return -1;
}

// int fstatat64(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat64, int dirfd, const char *pathname, struct stat *buf, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(dirfd, pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        int ret = static_cast<int>(syscall(__NR_newfstatat, dirfd, relocated_path, buf, flags));
        if (is_TED_Enable()) {
            int fd = originalInterface::original_openat(AT_FDCWD, relocated_path, O_RDONLY, 0);

            if (fd > 0) {
                if (EncryptFile::isEncryptFile(fd)) {
                    EncryptFile ef(relocated_path);
                    if (ef.create(fd, ENCRYPT_READ)) {
                        ef.fstat(fd, buf);
                    }
                }
                originalInterface::original_close(fd);
            }
        }
        return ret;
    }
    errno = EACCES;
    return -1;
}

// int kill(pid_t pid, int sig);
HOOK_DEF(int, kill, pid_t pid, int sig) {
    pid_t self = getpid();
    ALOGE("kill >>> pid : %d, sig : %d, self : %d", pid, sig, self);
    if (should_block_limbus_terminating_signal(sig)) {
        ALOGE("kill >>> blocked Limbus container signal : %d target : %d", sig, pid);
        return block_limbus_signal_int();
    }
    if (pid == self && sig == SIGALRM) {
        char *process_name = get_process_name();
        bool is_limbus_container = process_name && strstr(process_name, "limbuszhcn") != nullptr;
        ALOGE("kill >>> self SIGALRM process : %s, blocked : %d",
              process_name ? process_name : "<null>", is_limbus_container ? 1 : 0);
        free(process_name);
        if (is_limbus_container) {
            return 0;
        }
    }
    return static_cast<int>(syscall(__NR_kill, pid, sig));
}

// unsigned int alarm(unsigned int seconds);
HOOK_DEF(unsigned int, alarm, unsigned int seconds) {
    if (seconds > 0 && is_limbus_container_process()) {
        ALOGE("alarm >>> blocked Limbus container alarm seconds : %u", seconds);
        return 0;
    }
    return orig_alarm(seconds);
}

// int setitimer(int which, const struct itimerval *new_value, struct itimerval *old_value);
HOOK_DEF(int, setitimer, int which, const struct itimerval *new_value, struct itimerval *old_value) {
    if (which == ITIMER_REAL && new_value && is_limbus_container_process()) {
        ALOGE("setitimer >>> blocked Limbus container ITIMER_REAL sec : %ld usec : %ld interval_sec : %ld interval_usec : %ld",
              static_cast<long>(new_value->it_value.tv_sec),
              static_cast<long>(new_value->it_value.tv_usec),
              static_cast<long>(new_value->it_interval.tv_sec),
              static_cast<long>(new_value->it_interval.tv_usec));
        if (old_value) {
            memset(old_value, 0, sizeof(*old_value));
        }
        return 0;
    }
    return orig_setitimer(which, new_value, old_value);
}

// int raise(int sig);
HOOK_DEF(int, raise, int sig) {
    if (should_block_limbus_terminating_signal(sig)) {
        ALOGE("raise >>> blocked Limbus container signal : %d", sig);
        return block_limbus_signal_int();
    }
    if (sig == SIGALRM && is_limbus_container_process()) {
        ALOGE("raise >>> blocked Limbus container SIGALRM");
        return 0;
    }
    return orig_raise(sig);
}

// int tkill(pid_t tid, int sig);
HOOK_DEF(int, tkill, pid_t tid, int sig) {
    ALOGE("tkill >>> tid : %d, sig : %d", tid, sig);
    log_limbus_signal_diagnostic("tkill", tid, sig);
    if (should_block_limbus_terminating_signal(sig)) {
        ALOGE("tkill >>> blocked Limbus container signal : %d tid : %d", sig, tid);
        return block_limbus_signal_int();
    }
    if (sig == SIGALRM && is_limbus_container_process()) {
        ALOGE("tkill >>> blocked Limbus container SIGALRM");
        return 0;
    }
    return orig_tkill(tid, sig);
}

// int tgkill(pid_t tgid, pid_t tid, int sig);
HOOK_DEF(int, tgkill, pid_t tgid, pid_t tid, int sig) {
    pid_t self = getpid();
    if (!is_limbus_allowed_signal_noise(sig)) {
        ALOGE("tgkill >>> tgid : %d, tid : %d, sig : %d, self : %d", tgid, tid, sig, self);
    }
    log_limbus_signal_diagnostic("tgkill", tid, sig);
    if (should_block_limbus_terminating_signal(sig)) {
        ALOGE("tgkill >>> blocked Limbus container signal : %d tid : %d", sig, tid);
        return block_limbus_signal_int();
    }
    if (tgid == self && sig == SIGALRM && is_limbus_container_process()) {
        ALOGE("tgkill >>> blocked Limbus container self SIGALRM");
        return 0;
    }
    return orig_tgkill(tgid, tid, sig);
}

// int pthread_kill(pthread_t thread, int sig);
HOOK_DEF(int, pthread_kill, pthread_t thread, int sig) {
    if (!is_limbus_allowed_signal_noise(sig)) {
        ALOGE("pthread_kill >>> sig : %d", sig);
    }
    log_limbus_signal_diagnostic("pthread_kill", static_cast<pid_t>(syscall(__NR_gettid)), sig);
    if (should_block_limbus_terminating_signal(sig)) {
        ALOGE("pthread_kill >>> blocked Limbus container signal : %d", sig);
        return block_limbus_signal_int();
    }
    if (sig == SIGALRM && is_limbus_container_process()) {
        ALOGE("pthread_kill >>> blocked Limbus container SIGALRM");
        return 0;
    }
    return orig_pthread_kill(thread, sig);
}

// void exit(int status);
HOOK_DEF(void, exit, int status) {
    if (is_limbus_container_process()) {
        if (handle_limbus_process_exit("exit", status, __builtin_return_address(0))) {
            return;
        }
    }
    orig_exit(status);
}

// void _exit(int status);
HOOK_DEF(void, _exit, int status) {
    if (is_limbus_container_process()) {
        handle_limbus_process_exit("_exit", status, __builtin_return_address(0));
    }
    orig__exit(status);
}

// void abort(void);
HOOK_DEF(void, abort) {
    if (is_limbus_container_process()) {
        handle_limbus_process_exit("abort", 0, __builtin_return_address(0));
    }
    orig_abort();
}

static void limbus_sigsegv_guard(int sig, siginfo_t *info, void *context);
static void limbus_sigill_guard(int sig, siginfo_t *info, void *context);
static void maintain_limbus_sigsegv_guard();
static void maintain_limbus_sigill_guard();

static int collect_limbus_signal_module(struct dl_phdr_info *info, size_t, void *data) {
    if (info == nullptr || data == nullptr || info->dlpi_phdr == nullptr) {
        return 0;
    }
    auto *state = reinterpret_cast<std::pair<LimbusSignalModule *, size_t> *>(data);
    if (state->second >= kLimbusSignalModuleLimit) {
        return 1;
    }
    uintptr_t start = UINTPTR_MAX;
    uintptr_t end = 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &header = info->dlpi_phdr[i];
        if (header.p_type != PT_LOAD || header.p_memsz == 0) {
            continue;
        }
        uintptr_t segment_start = static_cast<uintptr_t>(info->dlpi_addr) + header.p_vaddr;
        uintptr_t segment_end = segment_start + header.p_memsz;
        if (segment_start < start) {
            start = segment_start;
        }
        if (segment_end > end) {
            end = segment_end;
        }
    }
    if (start == UINTPTR_MAX || end <= start) {
        return 0;
    }
    LimbusSignalModule &module = state->first[state->second++];
    module.start = start;
    module.end = end;
    const char *path = info->dlpi_name;
    const char *name = path == nullptr || path[0] == '\0' ? "<main>" : strrchr(path, '/');
    if (name != nullptr && name[0] == '/') {
        name++;
    }
    if (name == nullptr || name[0] == '\0') {
        name = "<unknown>";
    }
    size_t length = strnlen(name, kLimbusSignalModuleNameSize - 1);
    memcpy(module.name, name, length);
    module.name[length] = '\0';
    return 0;
}

static void collect_limbus_signal_exec_maps(
        std::pair<LimbusSignalModule *, size_t> *state) {
    if (state == nullptr || state->second >= kLimbusSignalModuleLimit) {
        return;
    }
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        return;
    }

    char line[PATH_MAX + 128];
    while (state->second < kLimbusSignalModuleLimit
            && fgets(line, sizeof(line), maps) != nullptr) {
        uintptr_t start = 0;
        uintptr_t end = 0;
        unsigned long offset = 0;
        unsigned long inode = 0;
        char permissions[5] = {};
        char device[16] = {};
        char path[PATH_MAX] = {};
        int fields = sscanf(line,
                            "%lx-%lx %4s %lx %15s %lu %4095s",
                            &start,
                            &end,
                            permissions,
                            &offset,
                            device,
                            &inode,
                            path);
        if (fields < 6 || end <= start || permissions[2] != 'x') {
            continue;
        }

        /*
         * dl_iterate_phdr 不会报告 ART 的 OAT/JIT/匿名执行区。Android 16 当前故障
         * 正发生在低 4GB 代码地址，因此只缓存这些缺口，避免普通共享库重复占满表。
         */
        bool low_exec_map = end <= 0x100000000ULL;
        bool art_exec_map = strstr(path, "oat") != nullptr
                || strstr(path, "jit") != nullptr
                || strstr(path, "dalvik") != nullptr
                || strstr(path, "base.apk") != nullptr;
        if (!low_exec_map && !art_exec_map) {
            continue;
        }

        LimbusSignalModule &module = state->first[state->second++];
        module.start = start;
        module.end = end;
        const char *name = path[0] == '\0' ? "<anonymous-exec>" : strrchr(path, '/');
        if (name != nullptr && name[0] == '/') {
            name++;
        }
        if (name == nullptr || name[0] == '\0') {
            name = path[0] == '\0' ? "<anonymous-exec>" : path;
        }
        size_t length = strnlen(name, kLimbusSignalModuleNameSize - 1);
        memcpy(module.name, name, length);
        module.name[length] = '\0';
    }
    fclose(maps);
}

static void refresh_limbus_signal_modules() {
    if (!is_limbus_container_process()) {
        return;
    }
    int next = g_limbus_signal_module_active == 0 ? 1 : 0;
    memset(g_limbus_signal_modules[next], 0, sizeof(g_limbus_signal_modules[next]));
    std::pair<LimbusSignalModule *, size_t> state = {
            g_limbus_signal_modules[next],
            0,
    };
    // 先记录装载器看不到的 ART 执行区，再补充常规 ELF 模块。
    collect_limbus_signal_exec_maps(&state);
    dl_iterate_phdr(collect_limbus_signal_module, &state);
    g_limbus_signal_module_counts[next] = static_cast<sig_atomic_t>(state.second);
    __sync_synchronize();
    g_limbus_signal_module_active = static_cast<sig_atomic_t>(next);
}

static char *limbus_signal_append_text(char *cursor, char *end, const char *text) {
    if (text == nullptr) {
        return cursor;
    }
    while (cursor < end && *text != '\0') {
        *cursor++ = *text++;
    }
    return cursor;
}

static char *limbus_signal_append_hex(char *cursor, char *end, uintptr_t value) {
    static const char hex[] = "0123456789abcdef";
    if (end - cursor < 18) {
        return cursor;
    }
    *cursor++ = '0';
    *cursor++ = 'x';
    for (int shift = static_cast<int>(sizeof(uintptr_t) * 8) - 4; shift >= 0; shift -= 4) {
        *cursor++ = hex[(value >> shift) & 0xf];
    }
    return cursor;
}

static char *limbus_signal_append_signed(char *cursor, char *end, long value) {
    char digits[32];
    size_t count = 0;
    unsigned long magnitude;
    if (value < 0) {
        if (cursor < end) {
            *cursor++ = '-';
        }
        magnitude = static_cast<unsigned long>(-(value + 1)) + 1;
    } else {
        magnitude = static_cast<unsigned long>(value);
    }
    do {
        digits[count++] = static_cast<char>('0' + magnitude % 10);
        magnitude /= 10;
    } while (magnitude != 0 && count < sizeof(digits));
    while (count > 0 && cursor < end) {
        *cursor++ = digits[--count];
    }
    return cursor;
}

static char *limbus_signal_append_module(char *cursor,
                                         char *end,
                                         const char *label,
                                         uintptr_t address) {
    cursor = limbus_signal_append_text(cursor, end, label);
    int active = g_limbus_signal_module_active == 0 ? 0 : 1;
    sig_atomic_t count = g_limbus_signal_module_counts[active];
    if (count < 0 || static_cast<size_t>(count) > kLimbusSignalModuleLimit) {
        count = 0;
    }
    const LimbusSignalModule *match = nullptr;
    for (sig_atomic_t i = 0; i < count; ++i) {
        const LimbusSignalModule &module = g_limbus_signal_modules[active][i];
        if (address >= module.start && address < module.end) {
            match = &module;
            break;
        }
    }
    if (match == nullptr) {
        return limbus_signal_append_text(cursor, end, "<unknown>");
    }
    cursor = limbus_signal_append_text(cursor, end, match->name);
    cursor = limbus_signal_append_text(cursor, end, "+");
    return limbus_signal_append_hex(cursor, end, address - match->start);
}

#if defined(__aarch64__)
static long limbus_signal_raw_syscall4(long number,
                                       long argument0,
                                       long argument1,
                                       long argument2,
                                       long argument3) {
    register long x0 asm("x0") = argument0;
    register long x1 asm("x1") = argument1;
    register long x2 asm("x2") = argument2;
    register long x3 asm("x3") = argument3;
    register long x8 asm("x8") = number;
    asm volatile(
            "svc #0"
            : "+r"(x0)
            : "r"(x1), "r"(x2), "r"(x3), "r"(x8)
            : "memory", "cc");
    return x0;
}

static char *limbus_signal_append_proc_map(char *cursor,
                                           char *end,
                                           const char *label,
                                           uintptr_t address) {
    cursor = limbus_signal_append_text(cursor, end, label);
    static const char maps_path[] = "/proc/self/maps";
    long fd = limbus_signal_raw_syscall4(
            __NR_openat,
            AT_FDCWD,
            reinterpret_cast<long>(maps_path),
            O_RDONLY | O_CLOEXEC,
            0);
    if (fd < 0) {
        return limbus_signal_append_text(cursor, end, "<open-failed>");
    }

    char read_buffer[512];
    char line[768];
    size_t line_length = 0;
    bool line_overflow = false;
    bool matched = false;
    while (!matched) {
        long count = limbus_signal_raw_syscall4(
                __NR_read,
                fd,
                reinterpret_cast<long>(read_buffer),
                sizeof(read_buffer),
                0);
        if (count <= 0) {
            break;
        }
        for (long i = 0; i < count && !matched; ++i) {
            char value = read_buffer[i];
            if (value != '\n') {
                if (line_length < sizeof(line)) {
                    line[line_length++] = value;
                } else {
                    line_overflow = true;
                }
                continue;
            }

            if (!line_overflow && line_length > 0) {
                size_t position = 0;
                uintptr_t start = 0;
                uintptr_t finish = 0;
                while (position < line_length && line[position] != '-') {
                    char digit = line[position++];
                    unsigned value_digit;
                    if (digit >= '0' && digit <= '9') {
                        value_digit = static_cast<unsigned>(digit - '0');
                    } else if (digit >= 'a' && digit <= 'f') {
                        value_digit = static_cast<unsigned>(digit - 'a' + 10);
                    } else {
                        start = 0;
                        break;
                    }
                    start = (start << 4U) | value_digit;
                }
                if (position < line_length && line[position] == '-') {
                    position++;
                    while (position < line_length && line[position] != ' ') {
                        char digit = line[position++];
                        unsigned value_digit;
                        if (digit >= '0' && digit <= '9') {
                            value_digit = static_cast<unsigned>(digit - '0');
                        } else if (digit >= 'a' && digit <= 'f') {
                            value_digit = static_cast<unsigned>(digit - 'a' + 10);
                        } else {
                            finish = 0;
                            break;
                        }
                        finish = (finish << 4U) | value_digit;
                    }
                }

                if (address >= start && address < finish) {
                    /*
                     * 跳过权限、偏移、设备号和 inode，保留映射路径原文。
                     * 该解析器仅用于崩溃诊断，完全依赖栈缓冲和原始系统调用，
                     * 不会在信号上下文中触发 malloc、锁或现有 IO hook。
                     */
                    int remaining_fields = 4;
                    while (position < line_length && remaining_fields > 0) {
                        while (position < line_length && line[position] == ' ') {
                            position++;
                        }
                        while (position < line_length && line[position] != ' ') {
                            position++;
                        }
                        remaining_fields--;
                    }
                    while (position < line_length && line[position] == ' ') {
                        position++;
                    }
                    if (position >= line_length) {
                        cursor = limbus_signal_append_text(cursor, end, "<anonymous>");
                    } else {
                        while (position < line_length && cursor < end) {
                            *cursor++ = line[position++];
                        }
                    }
                    cursor = limbus_signal_append_text(cursor, end, "+");
                    cursor = limbus_signal_append_hex(cursor, end, address - start);
                    matched = true;
                }
            }
            line_length = 0;
            line_overflow = false;
        }
    }
    limbus_signal_raw_syscall4(__NR_close, fd, 0, 0, 0);
    if (!matched) {
        cursor = limbus_signal_append_text(cursor, end, "<unknown>");
    }
    return cursor;
}

static bool limbus_signal_is_mapped(uintptr_t address) {
    if (address == 0) {
        return false;
    }
    unsigned char residency = 0;
    constexpr uintptr_t page_size = 4096;
    uintptr_t page = address & ~(page_size - 1);
    return limbus_signal_raw_syscall4(
            __NR_mincore,
            static_cast<long>(page),
            static_cast<long>(page_size),
            reinterpret_cast<long>(&residency),
            0) == 0;
}
#endif

// int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact);
HOOK_DEF(int, sigaction, int signum, const struct sigaction *act, struct sigaction *oldact) {
    if (signum == SIGALRM && is_limbus_container_process()) {
        ALOGE("sigaction >>> keep Limbus container SIGALRM ignored");
        struct sigaction ignored;
        memset(&ignored, 0, sizeof(ignored));
        ignored.sa_handler = SIG_IGN;
        sigemptyset(&ignored.sa_mask);
        return orig_sigaction(signum, &ignored, oldact);
    }
    if (signum == SIGSEGV && is_limbus_container_process()) {
        if (act == nullptr) {
            if (oldact != nullptr && g_limbus_sigsegv_action_set) {
                *oldact = g_limbus_sigsegv_action;
                return 0;
            }
            return orig_sigaction(signum, nullptr, oldact);
        }
        if (oldact != nullptr) {
            if (g_limbus_sigsegv_action_set) {
                *oldact = g_limbus_sigsegv_action;
            } else {
                orig_sigaction(signum, nullptr, oldact);
            }
        }
        g_limbus_sigsegv_action = *act;
        g_limbus_sigsegv_action_set = 1;
        struct sigaction guarded = *act;
        guarded.sa_sigaction = limbus_sigsegv_guard;
        guarded.sa_flags |= SA_SIGINFO;
        ALOGE("sigaction >>> guard Limbus user-delivered SIGSEGV flags=0x%x",
              act->sa_flags);
        return orig_sigaction(signum, &guarded, nullptr);
    }
    return orig_sigaction(signum, act, oldact);
}

static void limbus_sigsegv_guard(int sig, siginfo_t *info, void *context) {
    char diagnostic[2048];
    char *cursor = diagnostic;
    char *end = diagnostic + sizeof(diagnostic) - 2;
    uintptr_t pc = 0;
    uintptr_t lr = 0;
    uintptr_t sp = 0;
    uintptr_t registers[16] = {};
#if defined(__aarch64__)
    if (context != nullptr) {
        auto *ucontext = reinterpret_cast<ucontext_t *>(context);
        pc = static_cast<uintptr_t>(ucontext->uc_mcontext.pc);
        lr = static_cast<uintptr_t>(ucontext->uc_mcontext.regs[30]);
        sp = static_cast<uintptr_t>(ucontext->uc_mcontext.sp);
        for (size_t i = 0; i < 16; ++i) {
            registers[i] = static_cast<uintptr_t>(ucontext->uc_mcontext.regs[i]);
        }
    }
#endif
    uintptr_t address = info != nullptr ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
#if defined(__aarch64__)
    /*
     * Android 16 上，当前版本 AppSealing 的内置回溯器在找不到上一层描述时，
     * 会把一个已经失效的候选地址当成指令地址读取。固定现场如下：
     *   pc = libcovault-appsec.so + 0x145820
     *   lr = libcovault-appsec.so + 0x14580c
     *   x1 = 0xd2801168（它想匹配的 mov x8,#139 指令）
     *
     * 这里同时核对前后指令、返回位置、故障地址和寄存器，确保只接管这个
     * 已确认的回溯结束分支。跳回 +0x1457ec 后，原函数会返回“回溯结束”，
     * 不再读取失效地址；其他位置的真实内存错误仍交给原处理流程。
     */
    if (context != nullptr
            && info != nullptr
            && info->si_code > 0
            && pc > 0x34
            && lr + 0x14 == pc
            && address == registers[0]
            && registers[1] == 0x00000000d2801168ULL) {
        const auto *instructions = reinterpret_cast<const volatile uint32_t *>(pc);
        if (instructions[-5] == 0xaa0003f9
                && instructions[-1] == 0x72ba5001
                && instructions[0] == 0xb9400002
                && instructions[1] == 0x6b01005f
                && *reinterpret_cast<const volatile uint32_t *>(pc - 0x34) == 0x528000a0) {
            auto *ucontext = reinterpret_cast<ucontext_t *>(context);
            ucontext->uc_mcontext.pc = pc - 0x34;
            static const char message[] =
                    "Limbus SIGSEGV guard: recovered AppSealing invalid unwind candidate\n";
            syscall(__NR_write, STDERR_FILENO, message, sizeof(message) - 1);
            __android_log_write(ANDROID_LOG_WARN, "LimbusSIG", message);
            return;
        }
    }
#endif
    cursor = limbus_signal_append_text(cursor, end, "Limbus SIGSEGV guard: si_code=");
    cursor = limbus_signal_append_signed(cursor, end, info != nullptr ? info->si_code : 0);
    cursor = limbus_signal_append_text(cursor, end, " tid=");
    cursor = limbus_signal_append_signed(cursor, end, syscall(__NR_gettid));
#if defined(__aarch64__)
    char thread_name[16] = {};
    if (limbus_signal_raw_syscall4(
            __NR_prctl,
            PR_GET_NAME,
            reinterpret_cast<long>(thread_name),
            0,
            0) == 0) {
        cursor = limbus_signal_append_text(cursor, end, " thread=");
        cursor = limbus_signal_append_text(cursor, end, thread_name);
    }
#endif
    cursor = limbus_signal_append_text(cursor, end, " pc=");
    cursor = limbus_signal_append_hex(cursor, end, pc);
    cursor = limbus_signal_append_text(cursor, end, " lr=");
    cursor = limbus_signal_append_hex(cursor, end, lr);
    cursor = limbus_signal_append_text(cursor, end, " sp=");
    cursor = limbus_signal_append_hex(cursor, end, sp);
    cursor = limbus_signal_append_text(cursor, end, " addr=");
    cursor = limbus_signal_append_hex(cursor, end, address);
    for (size_t i = 0; i < 16; ++i) {
        cursor = limbus_signal_append_text(cursor, end, " x");
        cursor = limbus_signal_append_signed(cursor, end, static_cast<long>(i));
        cursor = limbus_signal_append_text(cursor, end, "=");
        cursor = limbus_signal_append_hex(cursor, end, registers[i]);
    }
#if defined(__aarch64__)
    bool pc_mapped = limbus_signal_is_mapped(pc);
    bool lr_mapped = limbus_signal_is_mapped(lr);
    cursor = limbus_signal_append_text(cursor, end, " pc_mapped=");
    cursor = limbus_signal_append_signed(cursor, end, pc_mapped ? 1 : 0);
    cursor = limbus_signal_append_text(cursor, end, " lr_mapped=");
    cursor = limbus_signal_append_signed(cursor, end, lr_mapped ? 1 : 0);
    if (pc_mapped && (pc & 0xfffU) <= 0xff0U) {
        /*
         * 仅在 mincore 确认当前页存在、且四条指令不会跨页时读取，避免诊断逻辑
         * 对已经损坏的 PC 再次触发嵌套 SIGSEGV。
         */
        const auto *instructions = reinterpret_cast<const volatile uint32_t *>(pc);
        for (size_t i = 0; i < 4; ++i) {
            cursor = limbus_signal_append_text(cursor, end, " insn");
            cursor = limbus_signal_append_signed(cursor, end, static_cast<long>(i));
            cursor = limbus_signal_append_text(cursor, end, "=");
            cursor = limbus_signal_append_hex(cursor, end, instructions[i]);
        }
    }
#endif
    cursor = limbus_signal_append_text(cursor, end, " ");
    cursor = limbus_signal_append_module(cursor, end, "pc_module=", pc);
    cursor = limbus_signal_append_text(cursor, end, " ");
    cursor = limbus_signal_append_module(cursor, end, "lr_module=", lr);
#if defined(__aarch64__)
    cursor = limbus_signal_append_text(cursor, end, " ");
    cursor = limbus_signal_append_proc_map(cursor, end, "pc_map=", pc);
    cursor = limbus_signal_append_text(cursor, end, " ");
    cursor = limbus_signal_append_proc_map(cursor, end, "lr_map=", lr);
#endif
    *cursor++ = '\n';
    *cursor = '\0';
    size_t diagnostic_length = static_cast<size_t>(cursor - diagnostic);
    syscall(__NR_write, STDERR_FILENO, diagnostic, diagnostic_length);
    __android_log_write(ANDROID_LOG_ERROR, "LimbusSIG", diagnostic);

    if (info != nullptr && info->si_code <= 0) {
        static const char message[] = "Limbus SIGSEGV guard: ignored user-delivered signal\n";
        syscall(__NR_write, STDERR_FILENO, message, sizeof(message) - 1);
        return;
    }

    struct sigaction action = g_limbus_sigsegv_action;
    if ((action.sa_flags & SA_SIGINFO) != 0
            && action.sa_sigaction != nullptr
            && action.sa_sigaction != limbus_sigsegv_guard) {
        /*
         * ART 会把 Java 隐式空指针检查实现为同步 SIGSEGV。它的处理器会修改
         * ucontext，把执行流切换到异常投递入口，然后正常返回。这里必须遵守
         * POSIX 信号链语义：下游自定义处理器返回后继续使用其更新后的上下文，
         * 不能再恢复默认动作并二次投递 SIGSEGV，否则 Android 16 上任意一次
         * Java 隐式检查都会被误杀成原生崩溃。
         */
        action.sa_sigaction(sig, info, context);
#if defined(__aarch64__)
        if (context != nullptr) {
            const auto *updated_context = reinterpret_cast<const ucontext_t *>(context);
            uintptr_t updated_pc = static_cast<uintptr_t>(updated_context->uc_mcontext.pc);
            uintptr_t updated_sp = static_cast<uintptr_t>(updated_context->uc_mcontext.sp);
            if (updated_pc != pc || updated_sp != sp) {
                return;
            }
#if defined(__NR_exit) && defined(__NR_gettid) && defined(__NR_getpid)
            /*
             * vivo V2314A / Android 13 的 v1.2 回归日志确认：AppSealing 后台 Thread-5
             * 会在 +0x248d4 通过空对象读取回调，随后其下游 handler 调用被容器拦截的
             * exit_group(139) 并原样返回。若继续恢复默认 SIGSEGV，会把一个保护库后台
             * 线程故障升级为整个游戏进程闪退；若直接返回，又会重试同一条指令形成死循环。
             *
             * 这里只在基址、偏移、故障地址、寄存器、四条指令以及“非主线程”全部匹配
             * 诊断现场时，用未经过 libc hook 的原始 exit 系统调用结束当前后台线程。
             * 其他同步故障仍保留下面的默认终止语义，避免吞掉真实崩溃。
             */
            uintptr_t appsealing_base = g_limbus_appsealing_base;
            long current_tid = limbus_signal_raw_syscall4(__NR_gettid, 0, 0, 0, 0);
            long process_id = limbus_signal_raw_syscall4(__NR_getpid, 0, 0, 0, 0);
            if (appsealing_base != 0
                    && pc == appsealing_base + 0x248d4
                    && address == 0x30
                    && registers[0] == 0
                    && current_tid > 0
                    && process_id > 0
                    && current_tid != process_id) {
                const auto *instructions =
                        reinterpret_cast<const volatile uint32_t *>(pc);
                if (instructions[0] == 0xf9401802
                        && instructions[1] == 0xaa1303e0
                        && instructions[2] == 0xd63f0040
                        && instructions[3] == 0xaa0003f6) {
                    static const char isolated_message[] =
                            "Limbus SIGSEGV guard: isolated confirmed AppSealing background fault\n";
                    limbus_signal_raw_syscall4(
                            __NR_write,
                            STDERR_FILENO,
                            reinterpret_cast<long>(isolated_message),
                            sizeof(isolated_message) - 1,
                            0);
                    for (;;) {
                        limbus_signal_raw_syscall4(__NR_exit, 0, 0, 0, 0);
                    }
                }
            }
#endif
            /*
             * 同步故障的下游处理器若原样返回，CPU 会再次执行同一条故障指令，
             * 形成 Issue #1 中每毫秒一次的 SIGSEGV/日志死循环。此时不能假装
             * 信号已被消费，必须继续走下面的默认终止语义。
             */
            static const char unchanged_message[] =
                    "Limbus SIGSEGV guard: downstream handler left fault context unchanged\n";
            syscall(__NR_write, STDERR_FILENO,
                    unchanged_message, sizeof(unchanged_message) - 1);
        } else {
            return;
        }
#else
        return;
#endif
    } else if (action.sa_handler != SIG_DFL && action.sa_handler != SIG_IGN
            && action.sa_handler != nullptr) {
        // 传统单参数处理器也必须实际改变同步故障上下文，否则会重试同一指令。
        action.sa_handler(sig);
#if defined(__aarch64__)
        if (context != nullptr) {
            const auto *updated_context = reinterpret_cast<const ucontext_t *>(context);
            if (static_cast<uintptr_t>(updated_context->uc_mcontext.pc) != pc
                    || static_cast<uintptr_t>(updated_context->uc_mcontext.sp) != sp) {
                return;
            }
        } else {
            return;
        }
#else
        return;
#endif
    }

    // 没有可调用的下游处理器时才保留真实 SIGSEGV 的默认终止语义。
    struct sigaction default_action;
    memset(&default_action, 0, sizeof(default_action));
    default_action.sa_handler = SIG_DFL;
    sigemptyset(&default_action.sa_mask);
    orig_sigaction(SIGSEGV, &default_action, nullptr);
    syscall(__NR_tgkill, getpid(), syscall(__NR_gettid), SIGSEGV);
    syscall(__NR_exit_group, 128 + SIGSEGV);
}

static void install_limbus_sigsegv_guard() {
    if (!is_limbus_container_process() || orig_sigaction == nullptr) {
        return;
    }
    refresh_limbus_signal_modules();
    struct sigaction current;
    memset(&current, 0, sizeof(current));
    if (orig_sigaction(SIGSEGV, nullptr, &current) != 0) {
        ALOGE("sigaction >>> unable to read current Limbus SIGSEGV handler errno=%d", errno);
        return;
    }
    g_limbus_sigsegv_action = current;
    g_limbus_sigsegv_action_set = 1;
    struct sigaction guarded = current;
    guarded.sa_sigaction = limbus_sigsegv_guard;
    guarded.sa_flags |= SA_SIGINFO;
    if (orig_sigaction(SIGSEGV, &guarded, nullptr) != 0) {
        ALOGE("sigaction >>> unable to install Limbus SIGSEGV guard errno=%d", errno);
        return;
    }
    ALOGE("sigaction >>> installed Limbus SIGSEGV guard flags=0x%x", current.sa_flags);
}

static void maintain_limbus_sigsegv_guard() {
    if (!is_limbus_container_process() || orig_sigaction == nullptr) {
        return;
    }
    struct sigaction current;
    memset(&current, 0, sizeof(current));
    if (orig_sigaction(SIGSEGV, nullptr, &current) != 0) {
        return;
    }
    if ((current.sa_flags & SA_SIGINFO) != 0
            && current.sa_sigaction == limbus_sigsegv_guard) {
        return;
    }

    // AppSealing 会使用直接 rt_sigaction 系统调用覆盖 libc 层安装的守卫。
    // 扫描线程需要保存它的新处理器后重新包裹，既能忽略人为投递的 SIGSEGV，
    // 又能在真实内存错误发生时记录 PC/LR 并继续交给原处理器处理。
    g_limbus_sigsegv_action = current;
    g_limbus_sigsegv_action_set = 1;
    struct sigaction guarded = current;
    guarded.sa_sigaction = limbus_sigsegv_guard;
    guarded.sa_flags |= SA_SIGINFO;
    if (orig_sigaction(SIGSEGV, &guarded, nullptr) == 0) {
        ALOGE("sigaction >>> restored Limbus SIGSEGV guard flags=0x%x",
              current.sa_flags);
    }
}

static void limbus_sigill_guard(int sig, siginfo_t *info, void *context) {
#if defined(__aarch64__)
    if (context != nullptr && info != nullptr && info->si_code == ILL_ILLOPC) {
        auto *ucontext = reinterpret_cast<ucontext_t *>(context);
        uintptr_t base = g_limbus_appsealing_base;
        uintptr_t pc = static_cast<uintptr_t>(ucontext->uc_mcontext.pc);
        uintptr_t lr = static_cast<uintptr_t>(ucontext->uc_mcontext.regs[30]);
        if (base != 0 && pc == base + 0x4e564 && lr == base + 0x4e550) {
            ucontext->uc_mcontext.pc = base + 0x4e998;
            static const char message[] =
                    "Limbus SIGILL guard: redirected confirmed AppSealing 50048 site\n";
            syscall(__NR_write, STDERR_FILENO, message, sizeof(message) - 1);
            return;
        }
    }
#endif

    if (g_limbus_sigill_action_set != 0) {
        sig_atomic_t index = g_limbus_sigill_action_index;
        struct sigaction action = g_limbus_sigill_actions[index == 0 ? 0 : 1];
        if ((action.sa_flags & SA_SIGINFO) != 0 && action.sa_sigaction != nullptr
                && action.sa_sigaction != limbus_sigill_guard) {
            action.sa_sigaction(sig, info, context);
            return;
        }
        if (action.sa_handler == SIG_IGN) {
            return;
        }
        if (action.sa_handler != SIG_DFL && action.sa_handler != nullptr) {
            action.sa_handler(sig);
            return;
        }
    }

    struct sigaction default_action;
    memset(&default_action, 0, sizeof(default_action));
    default_action.sa_handler = SIG_DFL;
    sigemptyset(&default_action.sa_mask);
    orig_sigaction(SIGILL, &default_action, nullptr);
    syscall(__NR_tgkill, getpid(), syscall(__NR_gettid), SIGILL);
}

static void maintain_limbus_sigill_guard() {
    if (orig_sigaction == nullptr || g_limbus_appsealing_base == 0) {
        return;
    }
    struct sigaction current;
    memset(&current, 0, sizeof(current));
    if (orig_sigaction(SIGILL, nullptr, &current) != 0) {
        return;
    }
    if ((current.sa_flags & SA_SIGINFO) != 0
            && current.sa_sigaction == limbus_sigill_guard) {
        return;
    }

    sig_atomic_t next_index = g_limbus_sigill_action_index == 0 ? 1 : 0;
    g_limbus_sigill_actions[next_index] = current;
    __sync_synchronize();
    g_limbus_sigill_action_index = next_index;
    g_limbus_sigill_action_set = 1;

    struct sigaction guarded = current;
    guarded.sa_sigaction = limbus_sigill_guard;
    guarded.sa_flags |= SA_SIGINFO;
    if (orig_sigaction(SIGILL, &guarded, nullptr) == 0) {
        ALOGE("sigaction >>> maintained confirmed Limbus AppSealing SIGILL guard flags=0x%x",
              current.sa_flags);
    }
}

// long syscall(long number, ...);
HOOK_DEF(long, syscall, long number, ...) {
    va_list args;
    va_start(args, number);
    long a1 = va_arg(args, long);
    long a2 = va_arg(args, long);
    long a3 = va_arg(args, long);
    long a4 = va_arg(args, long);
    long a5 = va_arg(args, long);
    long a6 = va_arg(args, long);
    va_end(args);

    if (is_limbus_container_process()) {
#ifdef __NR_openat
        if (number == __NR_openat && a2 != 0) {
            char temp[PATH_MAX];
            const char *relocated_path = relocate_path(reinterpret_cast<const char *>(a2), temp, sizeof(temp));
            if (relocated_path != NULL) {
                int fake_fd = redirect_proc_file_guarded(relocated_path, static_cast<int>(a3), static_cast<int>(a4));
                if (fake_fd != 0) {
                    return fake_fd;
                }
            }
        }
#endif
#ifdef __NR_readlinkat
        if (number == __NR_readlinkat && a2 != 0 && a3 != 0 && a4 > 0) {
            char temp[PATH_MAX];
            const char *relocated_path = relocate_path(reinterpret_cast<const char *>(a2), temp, sizeof(temp));
            if (relocated_path != NULL) {
                long ret = orig_syscall(number, a1, reinterpret_cast<long>(relocated_path), a3, a4, a5, a6);
                return reverse_readlink_result(reinterpret_cast<char *>(a3), static_cast<size_t>(a4), static_cast<int>(ret));
            }
        }
#endif
#ifdef __NR_read
        if (number == __NR_read) {
            long ret = orig_syscall(number, a1, a2, a3, a4, a5, a6);
            limbus_record_localize_read("syscall_read",
                                        static_cast<int>(a1),
                                        static_cast<size_t>(a3),
                                        static_cast<ssize_t>(ret),
                                        0,
                                        false);
            return ret;
        }
#endif
#ifdef __NR_pread64
        if (number == __NR_pread64) {
            long ret = orig_syscall(number, a1, a2, a3, a4, a5, a6);
            limbus_record_localize_read("syscall_pread64",
                                        static_cast<int>(a1),
                                        static_cast<size_t>(a3),
                                        static_cast<ssize_t>(ret),
                                        static_cast<off64_t>(a4),
                                        true);
            return ret;
        }
#endif
#ifdef __NR_lseek
        if (number == __NR_lseek) {
            long ret = orig_syscall(number, a1, a2, a3, a4, a5, a6);
            limbus_record_localize_fd_meta("syscall_lseek",
                                           static_cast<int>(a1),
                                           a2,
                                           a3,
                                           ret);
            return ret;
        }
#endif
#ifdef __NR_fstat
        if (number == __NR_fstat) {
            long ret = orig_syscall(number, a1, a2, a3, a4, a5, a6);
            struct stat *stat_buf = reinterpret_cast<struct stat *>(a2);
            limbus_record_localize_fd_meta("syscall_fstat",
                                           static_cast<int>(a1),
                                           stat_buf != nullptr ? static_cast<long long>(stat_buf->st_size) : -1,
                                           stat_buf != nullptr ? static_cast<long long>(stat_buf->st_mode) : -1,
                                           ret);
            return ret;
        }
#endif
        if (number == __NR_kill && a1 == getpid() && a2 == SIGALRM) {
            ALOGE("syscall >>> blocked Limbus container kill self SIGALRM");
            return 0;
        }
        if (number == __NR_kill && should_block_limbus_terminating_signal(static_cast<int>(a2))) {
            ALOGE("syscall >>> blocked Limbus container kill signal : %ld target : %ld", a2, a1);
            return block_limbus_signal_long();
        }
#ifdef __NR_tkill
        if (number == __NR_tkill && should_block_limbus_terminating_signal(static_cast<int>(a2))) {
            ALOGE("syscall >>> blocked Limbus container tkill signal : %ld tid : %ld", a2, a1);
            return block_limbus_signal_long();
        }
        if (number == __NR_tkill && a2 == SIGALRM) {
            ALOGE("syscall >>> blocked Limbus container tkill SIGALRM tid : %ld", a1);
            return 0;
        }
#endif
#ifdef __NR_tgkill
        if (number == __NR_tgkill && should_block_limbus_terminating_signal(static_cast<int>(a3))) {
            ALOGE("syscall >>> blocked Limbus container tgkill signal : %ld tid : %ld", a3, a2);
            return block_limbus_signal_long();
        }
        if (number == __NR_tgkill && a1 == getpid() && a3 == SIGALRM) {
            ALOGE("syscall >>> blocked Limbus container tgkill self SIGALRM tid : %ld", a2);
            return 0;
        }
#endif
#ifdef __NR_rt_sigqueueinfo
        if (number == __NR_rt_sigqueueinfo && should_block_limbus_terminating_signal(static_cast<int>(a2))) {
            ALOGE("syscall >>> blocked Limbus container rt_sigqueueinfo signal : %ld pid : %ld", a2, a1);
            return block_limbus_signal_long();
        }
#endif
#ifdef __NR_rt_tgsigqueueinfo
        if (number == __NR_rt_tgsigqueueinfo && should_block_limbus_terminating_signal(static_cast<int>(a3))) {
            ALOGE("syscall >>> blocked Limbus container rt_tgsigqueueinfo signal : %ld tid : %ld", a3, a2);
            return block_limbus_signal_long();
        }
#endif
#ifdef __NR_pidfd_send_signal
        if (number == __NR_pidfd_send_signal && should_block_limbus_terminating_signal(static_cast<int>(a2))) {
            ALOGE("syscall >>> blocked Limbus container pidfd_send_signal signal : %ld fd : %ld", a2, a1);
            return block_limbus_signal_long();
        }
#endif
#ifdef __NR_exit
        /*
         * AppSealing 在 Android 16 上可能通过 libc syscall() 只结束当前主线程。
         * 若放行，进程组中的 Binder/守护线程仍存活，Activity 会停在一个无法恢复的
         * zombie 主线程上；容器进程内应把该保护性退出视为已成功返回。
         */
        if (number == __NR_exit) {
            ALOGE("syscall >>> blocked Limbus container exit status : %ld", a1);
            return 0;
        }
#endif
#ifdef __NR_exit_group
        if (number == __NR_exit_group) {
            ALOGE("syscall >>> blocked Limbus container exit_group status : %ld", a1);
            return 0;
        }
#endif
#ifdef __NR_setitimer
        if (number == __NR_setitimer && a1 == ITIMER_REAL && a2 != 0) {
            ALOGE("syscall >>> blocked Limbus container setitimer ITIMER_REAL");
            return 0;
        }
#endif
#ifdef __NR_rt_sigaction
        if (number == __NR_rt_sigaction && a1 == SIGALRM) {
            ALOGE("syscall >>> blocked Limbus container rt_sigaction SIGALRM");
            return 0;
        }
#endif
    }
    return orig_syscall(number, a1, a2, a3, a4, a5, a6);
}

static int fake_limbus_proc_file(const char *pathname, int flags, int mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path == NULL) {
        return 0;
    }
    return redirect_proc_file_guarded(relocated_path, flags, mode);
}

static const char *limbus_module_directory(const char *pathname, char *buffer, size_t size) {
    if (!is_limbus_container_process() || pathname == nullptr
            || (strcmp(pathname, "/sys/module") != 0
            && strcmp(pathname, "/sys/module/") != 0)) {
        return nullptr;
    }
    const char *native_path = getenv("V_NATIVE_PATH");
    if (native_path == nullptr || native_path[0] == '\0') {
        return nullptr;
    }
    int written = snprintf(buffer, size, "%s/limbus-empty-modules", native_path);
    if (written <= 0 || static_cast<size_t>(written) >= size) {
        return nullptr;
    }
    if (syscall(__NR_mkdirat, AT_FDCWD, buffer, 0700) != 0 && errno != EEXIST) {
        ALOGE("Limbus module view >>> mkdir failed path=%s errno=%d", buffer, errno);
        return nullptr;
    }
    return buffer;
}

HOOK_DEF(DIR *, opendir, const char *pathname) {
    char module_temp[PATH_MAX];
    const char *module_path = limbus_module_directory(pathname, module_temp, sizeof(module_temp));
    if (module_path != nullptr) {
        ALOGI("Limbus module view >>> redirect directory %s -> %s", pathname, module_path);
        return orig_opendir(module_path);
    }
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != nullptr) {
        return orig_opendir(relocated_path);
    }
    errno = EACCES;
    return nullptr;
}

HOOK_DEF(int, open, const char *pathname, int flags, int mode) {
    int fake_fd = fake_limbus_proc_file(pathname, flags, mode);
    if (fake_fd != 0) {
        return fake_fd;
    }
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != NULL) {
        int ret = orig_open(relocated_path, flags, mode);
        limbus_record_localize_open("open", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        if (ret < 0) {
            limbus_log_io_failure("open", pathname, relocated_path, ret, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("open", pathname, relocated_path, -1, errno);
    return -1;
}

HOOK_DEF(int, open64, const char *pathname, int flags, int mode) {
    int fake_fd = fake_limbus_proc_file(pathname, flags, mode);
    if (fake_fd != 0) {
        return fake_fd;
    }
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != NULL) {
        int ret = orig_open64(relocated_path, flags, mode);
        limbus_record_localize_open("open64", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        if (ret < 0) {
            limbus_log_io_failure("open64", pathname, relocated_path, ret, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("open64", pathname, relocated_path, -1, errno);
    return -1;
}

HOOK_DEF(int, __open_2, const char *pathname, int flags) {
    int fake_fd = fake_limbus_proc_file(pathname, flags, 0);
    if (fake_fd != 0) {
        return fake_fd;
    }
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path != NULL) {
        int ret = orig___open_2(relocated_path, flags);
        limbus_record_localize_open("__open_2", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        return ret;
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, openat64, int fd, const char *pathname, int flags, int mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path_with_dirfd(fd, pathname, temp, sizeof(temp));
    limbus_log_io_path("openat64", pathname, relocated_path);
    if (relocated_path == NULL) {
        errno = EACCES;
        limbus_log_io_failure("openat64", pathname, relocated_path, -1, errno);
        return -1;
    }
    int fake_fd = redirect_proc_file_guarded(relocated_path, flags, mode);
    if (fake_fd != 0) {
        return fake_fd;
    }
    int ret = static_cast<int>(syscall(__NR_openat, fd, relocated_path, flags, mode));
    limbus_record_localize_open("openat64", pathname, relocated_path, ret, flags, __builtin_return_address(0));
    if (ret < 0) {
        limbus_log_io_failure("openat64", pathname, relocated_path, ret, errno);
    }
    return ret;
}

HOOK_DEF(FILE *, fopen, const char *pathname, const char *mode) {
    int fake_fd = fake_limbus_proc_file(pathname, O_RDONLY | O_CLOEXEC, 0);
    if (fake_fd > 0) {
        return fdopen(fake_fd, mode);
    }
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("fopen", pathname, relocated_path);
    if (relocated_path != NULL) {
        FILE *ret = orig_fopen(relocated_path, mode);
        if (ret != NULL) {
            limbus_record_localize_open("fopen", pathname, relocated_path, fileno(ret), O_RDONLY, __builtin_return_address(0));
        }
        if (ret == NULL) {
            limbus_log_io_failure("fopen", pathname, relocated_path, -1, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("fopen", pathname, relocated_path, -1, errno);
    return NULL;
}

HOOK_DEF(FILE *, fopen64, const char *pathname, const char *mode) {
    int fake_fd = fake_limbus_proc_file(pathname, O_RDONLY | O_CLOEXEC, 0);
    if (fake_fd > 0) {
        return fdopen(fake_fd, mode);
    }
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("fopen64", pathname, relocated_path);
    if (relocated_path != NULL) {
        FILE *ret = orig_fopen64(relocated_path, mode);
        if (ret != NULL) {
            limbus_record_localize_open("fopen64", pathname, relocated_path, fileno(ret), O_RDONLY, __builtin_return_address(0));
        }
        if (ret == NULL) {
            limbus_log_io_failure("fopen64", pathname, relocated_path, -1, errno);
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("fopen64", pathname, relocated_path, -1, errno);
    return NULL;
}

// int __statfs64(const char *path, size_t size, struct statfs *stat);
HOOK_DEF(int, __statfs64, const char *pathname, size_t size, struct statfs *stat) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("__statfs64", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, size, stat));
    }
    errno = EACCES;
    return -1;
}

// int lstat(const char *path, struct stat *buf);
HOOK_DEF(int, lstat, const char *pathname, struct stat *buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(AT_FDCWD, pathname, temp, sizeof(temp));
    limbus_log_io_path("lstat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        int ret = orig_lstat(relocated_path, buf);

        if (is_TED_Enable()) {
            int fd = originalInterface::original_openat(AT_FDCWD, relocated_path, O_RDONLY, 0);

            if (fd > 0) {
                if (EncryptFile::isEncryptFile(fd)) {
                    EncryptFile ef(relocated_path);
                    if (ef.create(fd, ENCRYPT_READ)) {
                        ef.fstat(fd, buf);
                    }
                }
                originalInterface::original_close(fd);
            }
        }

        return ret;
    }
    errno = EACCES;
    return -1;
}

// int stat(const char *path, struct stat *buf);
HOOK_DEF(int, stat, const char *pathname, struct stat *buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(AT_FDCWD, pathname, temp, sizeof(temp));
    limbus_log_io_path("stat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        int ret = orig_stat(relocated_path, buf);
        if (ret < 0) {
            limbus_log_io_failure("stat", pathname, relocated_path, ret, errno);
        }
        if (isReadOnly(relocated_path)) {
            buf->st_mode &= ~S_IWGRP;
        }

        if (is_TED_Enable()) {
            int fd = originalInterface::original_openat(AT_FDCWD, relocated_path, O_RDONLY, 0);

            if (fd > 0) {
                if (EncryptFile::isEncryptFile(fd)) {
                    EncryptFile ef(relocated_path);
                    if (ef.create(fd, ENCRYPT_READ)) {
                        ef.fstat(fd, buf);
                    }
                }
                originalInterface::original_close(fd);
            }
        }
        return ret;
    }
    errno = EACCES;
    limbus_log_io_failure("stat", pathname, relocated_path, -1, errno);
    return -1;
}

// int fchmod(const char *pathname, mode_t mode);
HOOK_DEF(int, fchmod, const char *pathname, mode_t mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_fchmod, relocated_path, mode));
    }
    errno = EACCES;
    return -1;
}


// int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
HOOK_DEF(int, fstatat, int dirfd, const char *pathname, struct stat *buf, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_metadata_path_with_dirfd(dirfd, pathname, temp, sizeof(temp));
    limbus_log_io_path("fstatat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_newfstatat, dirfd, relocated_path, buf, flags));
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, fstat, int fd, struct stat *buf)
{
    int ret = -1;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("fstat fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vfstat(vfd.get(), buf);
            flag = true;
        }
    }
    if(!flag) {
        ret = orig_fstat(fd, buf);
    }
    limbus_record_localize_fd_meta("fstat", fd,
                                   buf != nullptr ? static_cast<long long>(buf->st_size) : -1,
                                   buf != nullptr ? static_cast<long long>(buf->st_mode) : -1,
                                   ret);
    return ret;
}

// int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev);
HOOK_DEF(int, mknodat, int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_mknodat, dirfd, relocated_path, mode, dev));
    }
    errno = EACCES;
    return -1;
}

// int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags);
HOOK_DEF(int, utimensat, int dirfd, const char *pathname, const struct timespec times[2],
         int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_utimensat, dirfd, relocated_path, times, flags));
    }
    errno = EACCES;
    return -1;
}

// int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags);
HOOK_DEF(int, fchownat, int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_fchownat, dirfd, relocated_path, owner, group, flags));
    }
    errno = EACCES;
    return -1;
}

// int chroot(const char *pathname);
HOOK_DEF(int, chroot, const char *pathname) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_chroot, relocated_path));
    }
    errno = EACCES;
    return -1;
}

// int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, renameat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    char temp_old[PATH_MAX], temp_new[PATH_MAX];
    const char *relocated_path_old = relocate_path(oldpath, temp_old, sizeof(temp_old));
    const char *relocated_path_new = relocate_path(newpath, temp_new, sizeof(temp_new));
    if (relocated_path_old && relocated_path_new) {
        xdja::zs::sp <virtualFile> *vf2 = virtualFileManager::getVFM().queryVF(
                (char *) relocated_path_old);
        if (vf2 != nullptr) {
            slog(" *** need to force translate virtual File [%s] *** ", vf2->get()->getPath());

            xdja::zs::sp <virtualFile> pvf2(vf2->get());
            pvf2->lockWhole();
            pvf2->forceTranslate();
            pvf2->unlockWhole();
            pvf2->delRef();
        }

        {
            /**？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？**/
            virtualFileManager::getVFM().deleted((char *) relocated_path_old);
        }

        int ret = static_cast<int>(syscall(__NR_renameat, olddirfd, relocated_path_old, newdirfd,
                                           relocated_path_new));

        xdja::zs::sp <virtualFile> *vf3 = virtualFileManager::getVFM().queryVF(
                (char *) relocated_path_new);
        if (vf3 != nullptr) {
            xdja::zs::sp <virtualFile> pvf3(vf3->get());
            slog(" *** update virtual file [%s] *** ", pvf3->getPath());
            pvf3->lockWhole();
            virtualFileManager::getVFM().updateVF(*pvf3.get());
            pvf3->unlockWhole();
            pvf3->delRef();
        }

        /*zString op("renameat to %s ret %d err %s", redirect_path_new, ret, getErr);
        doFileTrace(redirect_path_old, op.toString());*/

        return ret;
    }
    errno = EACCES;
    return -1;
}

// int renameat2(int olddirfd, const char *oldpath, int newdirfd,
//               const char *newpath, unsigned int flags);
HOOK_DEF(int, renameat2, int olddirfd, const char *oldpath, int newdirfd,
         const char *newpath, unsigned int flags) {
    char temp_old[PATH_MAX], temp_new[PATH_MAX];
    // renameat2 允许相对路径，因此源、目标必须分别结合自己的 dirfd 解析后再重定向。
    const char *relocated_path_old = relocate_path_with_dirfd(
            olddirfd, oldpath, temp_old, sizeof(temp_old));
    const char *relocated_path_new = relocate_path_with_dirfd(
            newdirfd, newpath, temp_new, sizeof(temp_new));
    if (relocated_path_old == nullptr || relocated_path_new == nullptr) {
        errno = EACCES;
        return -1;
    }

    // 加密虚拟文件在重命名前必须先完成落盘，沿用旧 renameat hook 的缓存一致性处理。
    xdja::zs::sp<virtualFile> *source_virtual_file = virtualFileManager::getVFM().queryVF(
            const_cast<char *>(relocated_path_old));
    if (source_virtual_file != nullptr) {
        xdja::zs::sp<virtualFile> source(source_virtual_file->get());
        source->lockWhole();
        source->forceTranslate();
        source->unlockWhole();
        source->delRef();
    }
    virtualFileManager::getVFM().deleted(const_cast<char *>(relocated_path_old));

    int ret = static_cast<int>(syscall(__NR_renameat2,
                                       olddirfd,
                                       relocated_path_old,
                                       newdirfd,
                                       relocated_path_new,
                                       flags));
    int saved_errno = ret < 0 ? errno : 0;

    xdja::zs::sp<virtualFile> *target_virtual_file = virtualFileManager::getVFM().queryVF(
            const_cast<char *>(relocated_path_new));
    if (target_virtual_file != nullptr) {
        xdja::zs::sp<virtualFile> target(target_virtual_file->get());
        target->lockWhole();
        virtualFileManager::getVFM().updateVF(*target.get());
        target->unlockWhole();
        target->delRef();
    }

    // UnityCache 是本问题的关键提交点，保留低频结果日志便于真机确认临时文件已成功归档。
    if ((oldpath != nullptr && strstr(oldpath, "/UnityCache/") != nullptr)
            || (newpath != nullptr && strstr(newpath, "/UnityCache/") != nullptr)) {
        ALOGI("Limbus UnityCache renameat2 >>> ret=%d errno=%d flags=0x%x old=%s relocated_old=%s new=%s relocated_new=%s",
              ret,
              saved_errno,
              flags,
              oldpath != nullptr ? oldpath : "(null)",
              relocated_path_old,
              newpath != nullptr ? newpath : "(null)",
              relocated_path_new);
    }
    if (ret < 0) {
        errno = saved_errno;
    }
    return ret;
}

// int statfs64(const char *__path, struct statfs64 *__buf) __INTRODUCED_IN(21);
HOOK_DEF(int, statfs64, const char *filename, struct statfs64 *buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(filename, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, buf));
    }
    errno = EACCES;
    return -1;
}

// int unlinkat(int dirfd, const char *pathname, int flags);
HOOK_DEF(int, unlinkat, int dirfd, const char *pathname, int flags) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path && !isReadOnly(relocated_path)) {
        int ret = static_cast<int>(syscall(__NR_unlinkat, dirfd, relocated_path, flags));

        if (ret == 0) {
            /***？？？？？？？？？？？？？？？？？？？？？？？？？？？？？***/
            virtualFileManager::getVFM().deleted((char *) relocated_path);
        }

        return ret;
    }
    errno = EACCES;
    return -1;
}

// int symlinkat(const char *oldpath, int newdirfd, const char *newpath);
HOOK_DEF(int, symlinkat, const char *oldpath, int newdirfd, const char *newpath) {
    char temp[PATH_MAX];
    const char *relocated_path_old = relocate_path(oldpath, temp, sizeof(temp));
    if (relocated_path_old) {
        return static_cast<int>(syscall(__NR_symlinkat, relocated_path_old, newdirfd, newpath));
    }
    errno = EACCES;
    return -1;
}

// int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags);
HOOK_DEF(int, linkat, int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
         int flags) {
    char temp[PATH_MAX];
    const char *relocated_path_old = relocate_path(oldpath, temp, sizeof(temp));
    if (relocated_path_old) {
        return static_cast<int>(syscall(__NR_linkat, olddirfd, relocated_path_old, newdirfd,
                                        newpath,
                                        flags));
    }
    errno = EACCES;
    return -1;
}

// int mkdirat(int dirfd, const char *pathname, mode_t mode);
HOOK_DEF(int, mkdirat, int dirfd, const char *pathname, mode_t mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_mkdirat, dirfd, relocated_path, mode));
    }
    errno = EACCES;
    return -1;
}

// ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
HOOK_DEF(ssize_t, readlinkat, int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    limbus_log_io_path("readlinkat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        /*
         * readlinkat 的公开 ABI 返回 ssize_t。arm64 若错误返回 int，-1 会被零扩展为
         * 0x00000000ffffffff；Android 16 的 realpath 会把它当作长度并越界写入。
         */
        ssize_t ret = static_cast<ssize_t>(
                syscall(__NR_readlinkat, dirfd, relocated_path, buf, bufsiz));
        if (ret < 0) {
            return ret;
        }
        return static_cast<ssize_t>(
                reverse_readlink_result(buf, bufsiz, static_cast<int>(ret)));
    }
    errno = EACCES;
    return -1;
}


// int truncate(const char *path, off_t length);
HOOK_DEF(int, truncate, const char *pathname, off_t length) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_truncate, relocated_path, length));
    }
    errno = EACCES;
    return -1;
}

// int chdir(const char *path);
HOOK_DEF(int, chdir, const char *pathname) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_chdir, relocated_path));
    }
    errno = EACCES;
    return -1;
}

// int truncate64(const char *pathname, off_t length);
HOOK_DEF(int, truncate64, const char *pathname, off_t length) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        int ret = static_cast<int>(syscall(__NR_truncate, relocated_path, length));

        /*zString op("truncate64 length %ld ret %d err %s", length, ret, getErr);
        doFileTrace(relocated_path, op.toString());*/
        return ret;
    }

    errno = EACCES;
    return -1;
}


HOOK_DEF(char *, getcwd, char *buf, size_t size) {
    long ret = syscall(__NR_getcwd, buf, size);
    if (ret < 0) {
        return nullptr;
    }
    if (reverse_relocate_path_inplace(buf, size) < 0) {
        errno = EACCES;
        return nullptr;
    }
    return buf;
}

// int __openat(int fd, const char *pathname, int flags, int mode);
HOOK_DEF(int, openat, int fd, const char *pathname, int flags, int mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path_with_dirfd(fd, pathname, temp, sizeof(temp));
    limbus_log_io_path("openat", pathname, relocated_path);
    if (__predict_true(relocated_path)) {
        int fake_fd = redirect_proc_file_guarded(relocated_path, flags, mode);
        if (fake_fd != 0) {
            return fake_fd;
        }
        if ((flags & O_ACCMODE) == O_WRONLY) {
            flags &= ~O_ACCMODE;
            flags |= O_RDWR;
        }

        int ret = static_cast<int>(syscall(__NR_openat, fd, relocated_path, flags, mode));
        limbus_record_localize_open("openat", pathname, relocated_path, ret, flags, __builtin_return_address(0));
        if (ret < 0) {
            limbus_log_io_failure("openat", pathname, relocated_path, ret, errno);
        }
        /*zString op("openat fd = %d err = %s", ret, strerror(errno));
        doFileTrace(relocated_path, op.toString());*/

        if (getApiLevel() >= 29) {
            xdja::zs::sp<virtualFileDescribe> oldVfd(
                    virtualFileDescribeSet::getVFDSet().get(ret));
            if (oldVfd.get() != nullptr) {
                virtualFileDescribeSet::getVFDSet().reset(ret);
                xdja::zs::sp<virtualFile> vf(oldVfd->_vf->get());
                if (vf.get() != nullptr) {
                    virtualFileManager::getVFM().releaseVF(vf->getPath(), oldVfd.get());
                }
                oldVfd.get()->decStrong(0);
            }
        }

        if (ret > 0 && (is_TED_Enable() || changeDecryptState(false, 1)) &&
            isEncryptPath(relocated_path)) {

            /*******************only here**********************/
            virtualFileDescribe *pvfd = new virtualFileDescribe(ret);
            pvfd->incStrong(0);
            /***************************************************/
            xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

            int _Errno;
            xdja::zs::sp <virtualFile> vf(
                    virtualFileManager::getVFM().getVF(vfd.get(), (char *) relocated_path, &_Errno));

            virtualFileDescribeSet::getVFDSet().set(ret, pvfd);

            if (vf.get() != nullptr) {
                LOGE("judge : open vf [PATH %s] [VFS %d] [FD %d] [VFD %p]", vf->getPath(),
                     vf->getVFS(), ret, vfd.get());
                if ((flags & O_APPEND) == O_APPEND) {
                    vf->vlseek(vfd.get(), 0, SEEK_END);
                } else {
                    vf->vlseek(vfd.get(), 0, SEEK_SET);
                }
            } else {
                virtualFileDescribeSet::getVFDSet().reset(ret);
                /******through this way to release vfd *********/
                virtualFileDescribeSet::getVFDSet().release(pvfd);
                /***********************************************/

                if (_Errno < 0) {
                    //这种情况需要让openat 返回失败
                    originalInterface::original_close(ret);
                    ret = -1;
                    errno = EACCES;

                    if (flags & O_CREAT) {
                        originalInterface::original_unlinkat(AT_FDCWD, relocated_path, 0);
                    }

                    LOGE("judge : **** force openat fail !!! ****");
                }
            }
        }
        return ret;
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, close, int __fd) {
    int ret;
    LOGE("close fd[%d]", __fd);
    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(__fd));
    if (vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(__fd)) {
            log("close fd[%d] flag is closing", __fd);
            return -1;
        }
    } else {
        virtualFileDescribeSet::getVFDSet().setFlag(__fd, FD_CLOSING);

        virtualFileDescribeSet::getVFDSet().reset(__fd);
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            log("trace_close fd[%d]path[%s]vfd[%p]", __fd, vf->getPath(), vfd.get());
            virtualFileManager::getVFM().releaseVF(vf->getPath(), vfd.get());
        }

        /******through this way to release vfd *********/
        virtualFileDescribeSet::getVFDSet().release(vfd.get());
        /***********************************************/
    }

    limbus_record_localize_close(__fd);
    ret = static_cast<int>(syscall(__NR_close, __fd));
    virtualFileDescribeSet::getVFDSet().clearFlag(__fd);
    return ret;
}


// int __statfs (__const char *__file, struct statfs *__buf);
HOOK_DEF(int, __statfs, __const char *__file, struct statfs *__buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(__file, temp, sizeof(temp));
    limbus_log_io_path("__statfs", __file, relocated_path);
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, __buf));
    }
    errno = EACCES;
    return -1;
}

HOOK_DEF(int, statfs, __const char *__file, struct statfs *__buf) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(__file, temp, sizeof(temp));
    if (__predict_true(relocated_path)) {
        return static_cast<int>(syscall(__NR_statfs, relocated_path, __buf));
    }
    errno = EACCES;
    return -1;
}

static char **relocate_envp(const char *pathname, char *const envp[]) {
    if (strstr(pathname, "libweexjsb.so")) {
        return const_cast<char **>(envp);
    }
    char *soPath = getenv("V_SO_PATH");
    char *soPath64 = getenv("V_SO_PATH_64");

    char *env_so_path = NULL;
    FILE *fd = fopen(pathname, "r");
    if (!fd) {
        return const_cast<char **>(envp);
    }
    for (int i = 0; i < 4; ++i) {
        fgetc(fd);
    }
    int type = fgetc(fd);
    if (type == ELFCLASS32) {
        env_so_path = soPath;
    } else if (type == ELFCLASS64) {
        env_so_path = soPath64;
    }
    fclose(fd);
    if (env_so_path == NULL) {
        return const_cast<char **>(envp);
    }
    int len = 0;
    int ld_preload_index = -1;
    int self_so_index = -1;
    while (envp[len]) {
        /* find LD_PRELOAD element */
        if (ld_preload_index == -1 && !strncmp(envp[len], "LD_PRELOAD=", 11)) {
            ld_preload_index = len;
        }
        if (self_so_index == -1 && !strncmp(envp[len], "V_SO_PATH=", 10)) {
            self_so_index = len;
        }
        ++len;
    }
    /* append LD_PRELOAD element */
    if (ld_preload_index == -1) {
        ++len;
    }
    /* append V_env element */
    if (self_so_index == -1) {
        // V_SO_PATH
        // V_API_LEVEL
        // V_PREVIEW_API_LEVEL
        // V_NATIVE_PATH
        len += 4;
        if (soPath64) {
            // V_SO_PATH_64
            len++;
        }
        len += get_keep_item_count();
        len += get_forbidden_item_count();
        len += get_replace_item_count() * 2;
    }

    /* append NULL element */
    ++len;

    char **relocated_envp = (char **) malloc(len * sizeof(char *));
    memset(relocated_envp, 0, len * sizeof(char *));
    for (int i = 0; envp[i]; ++i) {
        if (i != ld_preload_index) {
            relocated_envp[i] = strdup(envp[i]);
        }
    }
    char LD_PRELOAD_VARIABLE[PATH_MAX];
    if (ld_preload_index == -1) {
        ld_preload_index = len - 2;
        sprintf(LD_PRELOAD_VARIABLE, "LD_PRELOAD=%s", env_so_path);
    } else {
        const char *orig_ld_preload = envp[ld_preload_index] + 11;
        sprintf(LD_PRELOAD_VARIABLE, "LD_PRELOAD=%s:%s", env_so_path, orig_ld_preload);
    }
    relocated_envp[ld_preload_index] = strdup(LD_PRELOAD_VARIABLE);
    int index = 0;
    while (relocated_envp[index]) index++;
    if (self_so_index == -1) {
        char element[PATH_MAX] = {0};
        sprintf(element, "V_SO_PATH=%s", soPath);
        relocated_envp[index++] = strdup(element);
        if (soPath64) {
            sprintf(element, "V_SO_PATH_64=%s", soPath64);
            relocated_envp[index++] = strdup(element);
        }
        sprintf(element, "V_API_LEVEL=%s", getenv("V_API_LEVEL"));
        relocated_envp[index++] = strdup(element);
        sprintf(element, "V_PREVIEW_API_LEVEL=%s", getenv("V_PREVIEW_API_LEVEL"));
        relocated_envp[index++] = strdup(element);
        sprintf(element, "V_NATIVE_PATH=%s", getenv("V_NATIVE_PATH"));
        relocated_envp[index++] = strdup(element);

        for (int i = 0; i < get_keep_item_count(); ++i) {
            PathItem &item = get_keep_items()[i];
            char env[PATH_MAX] = {0};
            sprintf(env, "V_KEEP_ITEM_%d=%s", i, item.path);
            relocated_envp[index++] = strdup(env);
        }

        for (int i = 0; i < get_forbidden_item_count(); ++i) {
            PathItem &item = get_forbidden_items()[i];
            char env[PATH_MAX] = {0};
            sprintf(env, "V_FORBID_ITEM_%d=%s", i, item.path);
            relocated_envp[index++] = strdup(env);
        }

        for (int i = 0; i < get_replace_item_count(); ++i) {
            ReplaceItem &item = get_replace_items()[i];
            char src[PATH_MAX] = {0};
            char dst[PATH_MAX] = {0};
            sprintf(src, "V_REPLACE_ITEM_SRC_%d=%s", i, item.orig_path);
            sprintf(dst, "V_REPLACE_ITEM_DST_%d=%s", i, item.new_path);
            relocated_envp[index++] = strdup(src);
            relocated_envp[index++] = strdup(dst);
        }
    }
    return relocated_envp;
}


// int (*origin_execve)(const char *pathname, char *const argv[], char *const envp[]);
HOOK_DEF(int, execve, const char *pathname, char *argv[], char *const envp[]) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (!relocated_path) {
        errno = EACCES;
        return -1;
    }

    char **relocated_envp = relocate_envp(relocated_path, envp);
    int ret = static_cast<int>(syscall(__NR_execve, relocated_path, argv, relocated_envp));
    if (relocated_envp != envp) {
        int i = 0;
        while (relocated_envp[i] != nullptr) {
            free(relocated_envp[i]);
            ++i;
        }
        free(relocated_envp);
    }
    return ret;
}

HOOK_DEF(void *, dlopen_CI, const char *filename, int flag) {
    char temp[PATH_MAX];
    const char *redirect_path = relocate_path(filename, temp, sizeof(temp));
    void *ret = orig_dlopen_CI(redirect_path, flag);
    onSoLoaded(filename, ret);
    return ret;
}

HOOK_DEF(void*, do_dlopen_CIV, const char *filename, int flag, const void *extinfo) {
    char temp[PATH_MAX];
    const char *redirect_path = relocate_path(filename, temp, sizeof(temp));
    void *ret = orig_do_dlopen_CIV(redirect_path, flag, extinfo);
    onSoLoaded(filename, ret);
    return ret;
}

HOOK_DEF(void*, do_dlopen_CIVV, const char *name, int flags, const void *extinfo,
         void *caller_addr) {
    char temp[PATH_MAX];
    const char *redirect_path = relocate_path(name, temp, sizeof(temp));
    void *ret = orig_do_dlopen_CIVV(redirect_path, flags, extinfo, caller_addr);
    onSoLoaded(name, ret);
    return ret;
}

//void *dlsym(void *handle, const char *symbol)
HOOK_DEF(void*, dlsym, void *handle, char *symbol) {
    return orig_dlsym(handle, symbol);
}

HOOK_DEF(pid_t, vfork) {
    return fork();
}

HOOK_DEF(ssize_t, pread64, int fd, void* buf, size_t count, off64_t offset) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("pread64 fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vpread64(vfd.get(), (char *) buf, count, offset);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_pread64(fd, buf, count, offset);

    limbus_record_localize_read("pread64", fd, count, ret, offset, true);
    return ret;
}

HOOK_DEF(ssize_t, pwrite64, int fd, const void *buf, size_t count, off64_t offset) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("pwrite64 fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vpwrite64(vfd.get(), (char *) buf, count, offset);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_pwrite64(fd, buf, count, offset);

    return ret;
}

HOOK_DEF(ssize_t, read, int fd, void *buf, size_t count) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("read fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vread(vfd.get(), (char *) buf, count);
            flag = true;
        }
    }

    if(!flag)
        ret = syscall(__NR_read, fd, buf, count);

    limbus_record_localize_read("read", fd, count, ret, 0, false);
    return ret;
}

HOOK_DEF(size_t, fread, void *ptr, size_t size, size_t nmemb, FILE *stream) {
    size_t ret = orig_fread(ptr, size, nmemb, stream);
    size_t requested = 0;
    if (size != 0 && nmemb <= SIZE_MAX / size) {
        requested = size * nmemb;
    } else {
        requested = SIZE_MAX;
    }
    size_t bytes = 0;
    if (size != 0 && ret <= SIZE_MAX / size) {
        bytes = ret * size;
    } else {
        bytes = SIZE_MAX;
    }
    limbus_record_localize_stream_read("fread", stream, requested,
                                       bytes > static_cast<size_t>(SSIZE_MAX)
                                               ? SSIZE_MAX
                                               : static_cast<ssize_t>(bytes));
    return ret;
}

HOOK_DEF(size_t, fread_unlocked, void *ptr, size_t size, size_t nmemb, FILE *stream) {
    size_t ret = orig_fread_unlocked(ptr, size, nmemb, stream);
    size_t requested = 0;
    if (size != 0 && nmemb <= SIZE_MAX / size) {
        requested = size * nmemb;
    } else {
        requested = SIZE_MAX;
    }
    size_t bytes = 0;
    if (size != 0 && ret <= SIZE_MAX / size) {
        bytes = ret * size;
    } else {
        bytes = SIZE_MAX;
    }
    limbus_record_localize_stream_read("fread_unlocked", stream, requested,
                                       bytes > static_cast<size_t>(SSIZE_MAX)
                                               ? SSIZE_MAX
                                               : static_cast<ssize_t>(bytes));
    return ret;
}

HOOK_DEF(char *, fgets, char *s, int size, FILE *stream) {
    char *ret = orig_fgets(s, size, stream);
    ssize_t bytes = ret != nullptr ? static_cast<ssize_t>(strlen(s)) : 0;
    limbus_record_localize_stream_read("fgets", stream,
                                       size > 0 ? static_cast<size_t>(size) : 0,
                                       bytes);
    return ret;
}

HOOK_DEF(ssize_t, write, int fd, const void* buf, size_t count) {
    ssize_t ret = 0;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if (virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("write fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vwrite(vfd.get(), (char *) buf, count);
            flag = true;
        }
    }

    if(!flag)
        ret = syscall(__NR_write, fd, buf, count);

    return ret;
}

HOOK_DEF(int, munmap, void *addr, size_t length) {
    int ret = -1;

    MmapFileInfo *fileInfo = 0;
    std::map<int64_t , MmapFileInfo *>::iterator iter = MmapInfoMap.find(std::int64_t(addr));
    if (iter != MmapInfoMap.end()) {
        MmapInfoMap.erase(iter);
        fileInfo = iter->second;
        if ((fileInfo->_flag & MAP_SHARED)) {
            int fd = syscall(__NR_openat, AT_FDCWD, fileInfo->_path, O_RDWR, 0);

            if (fd > 0 && isEncryptPath(fileInfo->_path)) {
                virtualFileDescribe *pvfd = new virtualFileDescribe(fd);
                xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

                int _Errno;
                xdja::zs::sp<virtualFile> vf(virtualFileManager::getVFM().getVF(vfd.get(), fileInfo->_path,
                                                                     &_Errno));
                virtualFileDescribeSet::getVFDSet().set(fd, pvfd);
                if (vf.get() != nullptr) {
                    vf->vpwrite64(vfd.get(), (char *) addr, length, fileInfo->_offsize * 4096);
                }

                virtualFileDescribeSet::getVFDSet().reset(fd);
                vf->delRef();
            }
            syscall(__NR_close, fd);
        }
    }

    ret = static_cast<int>(syscall(__NR_munmap, addr, length));

    return ret;
}

HOOK_DEF(int, msync, void *addr, size_t size, int flags) {
    int ret = -1;

    MmapFileInfo *fileInfo = 0;
    std::map<int64_t , MmapFileInfo *>::iterator iter = MmapInfoMap.find(std::int64_t(addr));
    if (iter != MmapInfoMap.end()) {
        MmapInfoMap.erase(iter);
        fileInfo = iter->second;
        if ((fileInfo->_flag & MAP_SHARED)) {
            int fd = syscall(__NR_openat, AT_FDCWD, fileInfo->_path, O_RDWR, 0);

            if (fd > 0 && isEncryptPath(fileInfo->_path)) {
                virtualFileDescribe *pvfd = new virtualFileDescribe(fd);
                xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

                int _Errno;
                xdja::zs::sp<virtualFile> vf(virtualFileManager::getVFM().getVF(vfd.get(), fileInfo->_path,
                                                                     &_Errno));
                virtualFileDescribeSet::getVFDSet().set(fd, pvfd);
                if (vf.get() != nullptr) {
                    vf->vpwrite64(vfd.get(), (char *) addr, size, fileInfo->_offsize * 4096);
                }

                virtualFileDescribeSet::getVFDSet().reset(fd);
                vf->delRef();
            }
            syscall(__NR_close, fd);
        }
    }

    ret = static_cast<int>(syscall(__NR_msync, addr, size, flags));

    return ret;
}

HOOK_DEF(void *, mmap, void *addr, size_t length, int prot,int flags, int fd, size_t pgoffset) {
    void * ret = 0;
    bool flag = false;

    do {
        if (fd == -1) break;

        xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));

        if (vfd.get() == nullptr) {
            if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
                log("__mmap2 fd[%d] flag is closing", fd);
                return MAP_FAILED;
            }
        } else {
            xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
            if (vf.get() != nullptr) {
                if (vf->getVFS() == VFS_ENCRYPT) {
                    flags |= MAP_ANONYMOUS;     //申请匿名内存
                    ret = (void *) syscall(__NR_mmap, addr, length, prot, flags, 0, 0);

                    bool nowrite = (prot & PROT_WRITE) == 0;
                    if (nowrite && -1 == mprotect(ret, length, prot | PROT_WRITE)) {
                        LOGE("__mmap2 mprotect failed.");
                    } else {
                        vf->vpread64(vfd.get(), (char *) ret, length, pgoffset * 4096);

                        if (nowrite) {
                            if (0 != mprotect(ret, length, prot)) {
                                LOGE("__mmap2 mprotect restore prot fails.");
                            }
                        }
                        MmapFileInfo *fileInfo = new MmapFileInfo(vf->getPath(), pgoffset,
                                                                  flags);
                        MmapInfoMap.insert(
                                std::pair<int64_t , MmapFileInfo *>(int64_t(ret), fileInfo));
                        flag = true;
                    }
                }
            }
        }
    }while(false);

    if(fd > 0)
    {
        /*zString path;
        getPathFromFd(fd, path);

        zString op("%c__mmap2 length %d flags %p pgoffset %p", flag?'v':' ', length, flags, pgoffset);
        doFileTrace(path.toString(), op.toString());*/
    }

    if(!flag)
        ret = (void *) syscall(__NR_mmap, addr, length, prot, flags, fd, pgoffset);

    limbus_record_localize_mmap("mmap", fd, ret, length, prot, flags, pgoffset, flag);
    return ret;
}

HOOK_DEF(off_t, lseek, int fd, off_t offset, int whence)
{
    off_t ret;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("lseek fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vlseek(vfd.get(), offset, whence);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_lseek(fd, offset, whence);

    limbus_record_localize_fd_meta("lseek", fd, offset, whence, ret);
    return ret;
}

HOOK_DEF(int, __llseek, unsigned int fd, unsigned long offset_high,
         unsigned long offset_low, off64_t *result,
         unsigned int whence)
{
    bool flag = false;

    int ret;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("__llseek fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vllseek(vfd.get(), offset_high, offset_low, result, whence);
            flag = true;
        }
    }

    if(!flag)
        ret = orig___llseek(fd, offset_high, offset_low, result, whence);

    limbus_record_localize_fd_meta("__llseek",
                                   static_cast<int>(fd),
                                   (static_cast<long long>(offset_high) << 32)
                                           | static_cast<long long>(offset_low),
                                   whence,
                                   ret);
    return ret;
}

//int ftruncate64(int, off_t)
HOOK_DEF(int, ftruncate64, int fd, off64_t length)
{
    int ret;
    bool flag = false;

    xdja::zs::sp<virtualFileDescribe> vfd(virtualFileDescribeSet::getVFDSet().get(fd));
    if(vfd.get() == nullptr) {
        if(virtualFileDescribeSet::getVFDSet().getFlag(fd)) {
            log("ftruncate64 fd[%d] flag is closing", fd);
            return -1;
        }
    } else {
        /*path.format("%s", vfd->_vf->getPath());*/
        xdja::zs::sp<virtualFile> vf(vfd->_vf->get());
        if (vf.get() != nullptr) {
            ret = vf->vftruncate64(vfd.get(), length);
            flag = true;
        }
    }

    if(!flag)
        ret = orig_ftruncate64(fd, length);

    return ret;
}

//ssize_t sendfile(int out_fd, int in_fd, off_t* offset, size_t count)
HOOK_DEF(ssize_t, sendfile, int out_fd, int in_fd, off_t* offset, size_t count)
{
    ssize_t ret;

    off_t off = 0;
    if(offset != 0)
        off = *offset;

    struct stat st;
    originalInterface::original_fstat(in_fd,&st);

    xdja::zs::sp<virtualFileDescribe> in_vfd(virtualFileDescribeSet::getVFDSet().get(in_fd));
    xdja::zs::sp<virtualFileDescribe> out_vfd(virtualFileDescribeSet::getVFDSet().get(out_fd));
    if(in_vfd.get() == nullptr && out_vfd.get() == nullptr) {
        if((virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) &&
           (virtualFileDescribeSet::getVFDSet().getFlag(in_fd))) {
            log("sendfile out_fd[%d] and in_fd[%d] flag is closing", out_fd, in_fd);
            return -1;
        }
        //完全不管
        ret = orig_sendfile(out_fd, in_fd, offset, count);
    } else {
        if(in_vfd.get() != nullptr && out_vfd.get() != nullptr) //完全管理
        {
            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());
            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            }
        }
        else if(in_vfd.get() == nullptr && out_vfd.get() != nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(in_fd)) {
                log("sendfile in_fd[%d] flag is closing", in_fd);
                return -1;
            }

            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            if(off + count > st.st_size) {
                real_count = (size_t)(st.st_size - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                ignoreFile::lseek(in_fd, off, SEEK_SET);
            } else {
                ignoreFile::lseek(in_fd, 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = ignoreFile::read(in_fd,buf,real_count % 1024);
                } else {
                    rl = ignoreFile::read(in_fd,buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                ignoreFile::lseek(in_fd, off, SEEK_SET);
            }
        }
        else if(in_vfd.get() != nullptr && out_vfd.get() == nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) {
                log("sendfile out_fd[%d] flag is closing", out_fd);
                return -1;
            }

            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024];
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                ignoreFile::write(out_fd,buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                in_vf->vlseek(in_vfd.get(), off, SEEK_SET);
            }
        }
    }

    return ret;
}

//ssize_t sendfile64(int out_fd, int in_fd, off64_t* offset, size_t count)
HOOK_DEF(ssize_t, sendfile64, int out_fd, int in_fd, off64_t* offset, size_t count)
{
    ssize_t ret;

    off64_t off = 0;
    if(offset != 0)
        off = *offset;

    struct stat st;
    originalInterface::original_fstat(in_fd,&st);

    unsigned long off_hi = static_cast<unsigned long>(off >> 32);
    unsigned long off_lo = static_cast<unsigned long>(off);

    xdja::zs::sp<virtualFileDescribe> in_vfd(virtualFileDescribeSet::getVFDSet().get(in_fd));
    xdja::zs::sp<virtualFileDescribe> out_vfd(virtualFileDescribeSet::getVFDSet().get(out_fd));
    if(in_vfd.get() == nullptr && out_vfd.get() == nullptr) {
        if((virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) &&
           (virtualFileDescribeSet::getVFDSet().getFlag(in_fd))) {
            log("sendfile64 out_fd[%d] and in_fd[%d] flag is closing", out_fd, in_fd);
            return -1;
        }
        //完全不管
        ret = orig_sendfile64(out_fd, in_fd, offset, count);
    } else {
        if(in_vfd.get() != nullptr && out_vfd.get() != nullptr) //完全管理
        {
            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());
            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            }
        }
        else if(in_vfd.get() == nullptr && out_vfd.get() != nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(in_fd)) {
                log("sendfile64 in_fd[%d] flag is closing", in_fd);
                return -1;
            }
            xdja::zs::sp<virtualFile> out_vf(out_vfd->_vf->get());

            size_t real_count = 0;
            if(off + count > st.st_size) {
                real_count = (size_t)(st.st_size - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                loff_t result;
                ignoreFile::llseek(in_fd, off_hi, off_lo, &result, SEEK_SET);
            } else {
                ignoreFile::lseek(in_fd, 0, SEEK_CUR);
            }

            char * buf = new char[1024]{0};
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = ignoreFile::read(in_fd,buf,real_count % 1024);
                } else {
                    rl = ignoreFile::read(in_fd,buf,1024);
                }
                out_vf->vwrite(out_vfd.get(),buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                loff_t result;
                ignoreFile::llseek(in_fd, off_hi, off_lo, &result, SEEK_SET);
            }
        }
        else if(in_vfd.get() != nullptr && out_vfd.get() == nullptr)
        {
            if (virtualFileDescribeSet::getVFDSet().getFlag(out_fd)) {
                log("sendfile64 out_fd[%d] flag is closing", out_fd);
                return -1;
            }

            xdja::zs::sp<virtualFile> in_vf(in_vfd->_vf->get());

            size_t real_count = 0;
            int encryptFileHeadLength = in_vf.get()->getHeaderOffSet();
            if(off + count > (st.st_size - encryptFileHeadLength)) {
                real_count = (size_t)(st.st_size - encryptFileHeadLength - off);
            } else {
                real_count = count;
            }

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            } else {
                in_vf->vlseek(in_vfd.get(), 0, SEEK_CUR);
            }

            char * buf = new char[1024];
            ret = 0;
            int rl = 0;
            int size = 0;
            while(size < real_count) {
                size += 1024;
                if(size > real_count) {
                    rl = in_vf->vread(in_vfd.get(),buf,real_count % 1024);
                } else {
                    rl = in_vf->vread(in_vfd.get(),buf,1024);
                }
                ignoreFile::write(out_fd,buf,rl);
                ret += rl;
            }

            delete []buf;

            if(offset != 0)
            {
                loff_t result;
                in_vf->vllseek(in_vfd.get(), off_hi, off_lo, &result, SEEK_SET);
            }
        }
    }

    return ret;
}

//int dup(int oldfd);
HOOK_DEF(int, dup, int oldfd)
{
    int ret = static_cast<int>(syscall(__NR_dup, oldfd));

    zString path, path2;
    getPathFromFd(oldfd, path);
    getPathFromFd(ret, path2);

    if (getApiLevel() >= 29) {
        xdja::zs::sp<virtualFileDescribe> oldVfd(
                virtualFileDescribeSet::getVFDSet().get(ret));
        if (oldVfd.get() != nullptr) {
            virtualFileDescribeSet::getVFDSet().reset(ret);
            xdja::zs::sp<virtualFile> vf(oldVfd->_vf->get());
            if (vf.get() != nullptr) {
                virtualFileManager::getVFM().releaseVF(vf->getPath(), oldVfd.get());
            }
            oldVfd.get()->decStrong(0);
        }
    }

    if(ret > 0 && (is_TED_Enable()||changeDecryptState(false,1)) && isEncryptPath(path2.toString())) {
        /*******************only here**********************/
        virtualFileDescribe *pvfd = new virtualFileDescribe(ret);
        pvfd->incStrong(0);
        /***************************************************/
        xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

        int _Errno;
        xdja::zs::sp<virtualFile> vf(virtualFileManager::getVFM().getVF(vfd.get(), path2.toString(), &_Errno));

        virtualFileDescribeSet::getVFDSet().set(ret, pvfd);

        if (vf.get() != nullptr) {
            LOGE("judge : dup vf [PATH %s] [VFS %d] [FD %d]", vf->getPath(), vf->getVFS(), ret);
            vf->vlseek(vfd.get(), 0, SEEK_SET);
        } else {
            virtualFileDescribeSet::getVFDSet().reset(ret);
            /******through this way to release vfd *********/
            virtualFileDescribeSet::getVFDSet().release(pvfd);
            /***********************************************/

            if(_Errno < 0)
            {
                //这种情况需要让openat 返回失败
                /*originalInterface::original_close(ret);
                ret = -1;
                errno = EACCES;

                if(flags & O_CREAT)
                {
                    originalInterface::original_unlinkat(AT_FDCWD, relocated_path, 0);
                }

                LOGE("judge : **** force openat fail !!! ****");*/
            }
        }
    }

    return ret;
}

//int dup3(int oldfd, int newfd, int flags);
HOOK_DEF(int, dup3, int oldfd, int newfd, int flags)
{
    return static_cast<int>(syscall(__NR_dup3, oldfd, newfd, flags));
}

HOOK_DEF(int, fcntl, int fd, int cmd, ...) {
    va_list arg;
    int ret = -1;
    va_start (arg, cmd);
    switch (cmd) {
        case F_DUPFD:
        case F_DUPFD_CLOEXEC: {
            int target = va_arg (arg, int);
            ret = static_cast<int>(syscall(__NR_fcntl, fd, cmd, target));

            if (getApiLevel() >= 29 && ret > 0) {
                xdja::zs::sp<virtualFileDescribe> oldVfd(
                        virtualFileDescribeSet::getVFDSet().get(ret));
                if (oldVfd.get() != nullptr) {
                    virtualFileDescribeSet::getVFDSet().reset(ret);
                    xdja::zs::sp<virtualFile> vf(oldVfd->_vf->get());
                    if (vf.get() != nullptr) {
                        virtualFileManager::getVFM().releaseVF(vf->getPath(), oldVfd.get());
                    }
                    oldVfd.get()->decStrong(0);
                }
            }

            zString path;
            getPathFromFd(ret, path);

            if (ret > 0 && (is_TED_Enable() || changeDecryptState(false, 1)) &&
                isEncryptPath(path.toString())) {
                /*******************only here**********************/
                virtualFileDescribe *pvfd = new virtualFileDescribe(ret);
                pvfd->incStrong(0);
                /***************************************************/
                xdja::zs::sp<virtualFileDescribe> vfd(pvfd);

                int _Errno;
                xdja::zs::sp<virtualFile> vf(
                        virtualFileManager::getVFM().getVF(vfd.get(), path.toString(), &_Errno));

                virtualFileDescribeSet::getVFDSet().set(ret, pvfd);

                if (vf.get() != nullptr) {
                    LOGE("judge : fcntl vf [PATH %s] [VFS %d] [FD %d]", vf->getPath(), vf->getVFS(),
                         ret);
                    vf->vlseek(vfd.get(), 0, SEEK_SET);
                } else {
                    virtualFileDescribeSet::getVFDSet().reset(ret);
                    /******through this way to release vfd *********/
                    virtualFileDescribeSet::getVFDSet().release(pvfd);
                    /***********************************************/
                }
            }
            va_end(arg);
        }
            break;
        default:
            void * target = va_arg(arg, void*);
            ret = orig_fcntl(fd, cmd, target);
            va_end(arg);
            break;
    }

    return ret;
}

HOOK_DEF(int, getaddrinfo,const char *__node, const char *__service, const struct addrinfo *__hints,
         struct addrinfo **__result) {
    int ret = -1;
    if (is_limbus_container_process()) {
        ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
        log_limbus_network_io("getaddrinfo", __node, ret, ret == 0 ? 0 : errno);
        return ret;
    }
    if (__node != nullptr) {
        if (getNetWorkState()) {
            if(isWhiteList()) {
                if(isIPAddress(__node) || isContainsStr(__node, ":")) {
                    if(isIPAddress(__node)) {
                        if(isIpV4Enable(__node)) {
                            ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    } else if(isContainsStr(__node,":")) {
                        if(isIpV6Enable(__node)) {
                            ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    }
                    errno = EAI_FAIL;
                    //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                    return ret;
                }

                if (!isDomainEnable(__node)) {
                    errno = EAI_FAIL;
                    return ret;
                }
                ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                if (ret == 0) {
                    struct addrinfo *add_result = (*__result);
                    do {
                        if (add_result->ai_addr->sa_family == AF_INET) {
                            sockaddr_in *pSin = (sockaddr_in *) (add_result->ai_addr);
                            char *ipv4 = inet_ntoa(pSin->sin_addr);
                            //log("wkw getaddrinfo ipv4 %s domain %s", ipv4, __node);
                            addWhiteIpStrategy(ipv4);
                        } else if (add_result->ai_addr->sa_family == AF_INET6) {
                            sockaddr_in6 sin6;
                            memcpy(&sin6, add_result->ai_addr, sizeof(sin6));
                            char ipv6[INET6_ADDRSTRLEN];
                            inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
                            //log("wkw getaddrinfo ipv6 %s domain %s", ipv6, __node);
                            addWhiteIpStrategy(ipv6);
                        }
                        add_result = add_result->ai_next;
                    } while (add_result != nullptr);
                }
                return ret;
            } else {
                if(isIPAddress(__node) || isContainsStr(__node,":")) {
                    if(isIPAddress(__node)) {
                        if(!isIpV4Enable(__node)) {
                            errno = EAI_FAIL;
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    } else if(isContainsStr(__node,":")) {
                        if(!isIpV6Enable(__node)) {
                            errno = EAI_FAIL;
                            //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                            return ret;
                        }
                    }
                    ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                    //log("wkw getaddrinfo: node %s ret %d",__node,ret);
                    return ret;
                }

                ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
                if(!isDomainEnable(__node)) {
                    struct addrinfo *add_result = (*__result);
                    do {
                        if (add_result->ai_addr->sa_family == AF_INET) {
                            sockaddr_in *pSin = (sockaddr_in *) (add_result->ai_addr);
                            char *ipv4 = inet_ntoa(pSin->sin_addr);
                            //log("wkw getaddrinfo ipv4 %s domain %s", ipv4, __node);
                            addWhiteIpStrategy(ipv4);
                        } else if (add_result->ai_addr->sa_family == AF_INET6) {
                            sockaddr_in6 sin6;
                            memcpy(&sin6, add_result->ai_addr, sizeof(sin6));
                            char ipv6[INET6_ADDRSTRLEN];
                            inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
                            //log("wkw getaddrinfo ipv6 %s domain %s", ipv6, __node);
                            addWhiteIpStrategy(ipv6);
                        }
                        add_result = add_result->ai_next;
                    } while (add_result != nullptr);
                    errno = EAI_FAIL;
                    return -1;
                }
                return ret;
            }
        }
    }
    ret = originalInterface::original_getaddrinfo(__node, __service, __hints, __result);
    return ret;
}

HOOK_DEF(ssize_t, sendto, int fd, const void *buf, size_t n, int flags, struct sockaddr *dst_addr,
         socklen_t dst_addr_length) {
    ssize_t ret = -1;
    if (is_limbus_container_process() && getNetWorkState()) {
        ret = syscall(__NR_sendto, fd, buf, n, flags, dst_addr, dst_addr_length);
        log_limbus_network_bypass("sendto", "socket");
        return ret;
    }
    if (getNetWorkState()) {
        if (nullptr != dst_addr) {
            if (dst_addr->sa_family == AF_INET) {
                sockaddr_in *pSin = (sockaddr_in *) dst_addr;
                char *ipv4 = inet_ntoa(pSin->sin_addr);
                //log("wkw sendto [ipv4 %s]", ipv4);
                if (!isIpV4Enable(ipv4)) {
                    //log("return [ret %d] ",ret);
                    errno = EACCES;
                    return ret;
                }
            } else if (dst_addr->sa_family == AF_INET6) {
                sockaddr_in6 sin6;
                memcpy(&sin6, dst_addr, sizeof(sin6));
                char ipv6[INET6_ADDRSTRLEN];
                inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
                //log("wkw sendto ipv6:%s", ipv6);
                if (!isIpV6Enable(ipv6)) {
                    errno = EACCES;
                    return ret;
                }
            }
        }
    }
    ret = syscall(__NR_sendto, fd, buf, n, flags, dst_addr, dst_addr_length);
    return ret;
}

HOOK_DEF(int, connect, int sd, struct sockaddr *addr, socklen_t socklen) {
    int ret = -1;
    if (is_limbus_container_process()) {
        char target[INET6_ADDRSTRLEN + 24] = {};
        format_limbus_socket_target(addr, target, sizeof(target));
        ret = static_cast<int>(syscall(__NR_connect, sd, addr, socklen));
        int error = ret < 0 ? errno : 0;
        log_limbus_network_io("connect", target, ret, error);
        return ret;
    }
    if (getNetWorkState()) {
        if (addr->sa_family == AF_INET) {
            sockaddr_in *pSin = (sockaddr_in *) addr;
            char *ipv4 = inet_ntoa(pSin->sin_addr);
            //int port = pSin->sin_port;
            //log("wkw connect [ipv4 %s]", ipv4);
            if (!isIpV4Enable(ipv4)) {
                //log("return [ret %d] ENETUNREACH",ret);
                errno = ENETUNREACH;//无法传送数据包至指定的主机.
                return ret;
            }
        } else if (addr->sa_family == AF_INET6) {
            sockaddr_in6 sin6;
            memcpy(&sin6, addr, sizeof(sin6));
            char ipv6[INET6_ADDRSTRLEN];
            inet_ntop(AF_INET6, &sin6.sin6_addr, ipv6, sizeof(ipv6));
            //log("wkw connect ipv6:%s", ipv6);
            if (!isIpV6Enable(ipv6)) {
                errno = ENETUNREACH;//无法传送数据包至指定的主机.
                return ret;
            }
        }
    }
    ret = static_cast<int>(syscall(__NR_connect, sd, addr, socklen));
    return ret;
}

HOOK_DEF(void, xlogger_Write, void* _info, const char* _log)
{
    slog_wx("%s", _log);

    orig_xlogger_Write(_info, _log);
}


__END_DECLS
// end IO DEF

bool on_found_syscall_aarch64(const char *path, int num, void *func) {
    static int pass = 0;
    switch (num) {
        case __NR_fchmodat:
            MSHookFunction(func, (void *) new_fchmodat, (void **) &orig_fchmodat);
            pass++;
            break;
        case __NR_faccessat:
            MSHookFunction(func, (void *) new_faccessat, (void **) &orig_faccessat);
            pass++;
            break;
        case __NR_statfs:
            MSHookFunction(func, (void *) new___statfs, (void **) &orig___statfs);
            pass++;
            break;
        case __NR_getcwd:
            MSHookFunction(func, (void *) new_getcwd, (void **) &orig_getcwd);
            pass++;
            break;
        case __NR_openat:
            MSHookFunction(func, (void *) new_openat, (void **) &orig_openat);
            pass++;
            break;
    }
    if (pass == 5) {
        return BREAK_FIND_SYSCALL;
    }
    return CONTINUE_FIND_SYSCALL;
}

bool on_found_linker_syscall_arch64(const char *path, int num, void *func) {
    switch (num) {
        case __NR_openat:
            MSHookFunction(func, (void *) new_openat, (void **) &orig_openat);
            return BREAK_FIND_SYSCALL;
    }
    return CONTINUE_FIND_SYSCALL;
}

static void patch_limbus_syscall_return_zero(void *func) {
    if (func == nullptr) {
        return;
    }
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        page_size = 4096;
    }
    uintptr_t page_start = reinterpret_cast<uintptr_t>(func) & ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page_start),
                 static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ALOGE("Limbus syscall patch >>> mprotect failed func=%p errno=%d", func, errno);
        return;
    }
    *reinterpret_cast<uint32_t *>(func) = 0xD2800000; // mov x0, #0
    __builtin___clear_cache(reinterpret_cast<char *>(func),
                            reinterpret_cast<char *>(func) + sizeof(uint32_t));
    mprotect(reinterpret_cast<void *>(page_start),
             static_cast<size_t>(page_size),
             PROT_READ | PROT_EXEC);
}

static long limbus_direct_openat(int dirfd, const char *pathname, int flags, mode_t mode) {
    char temp[PATH_MAX];
    const char *relocated_path = relocate_path(pathname, temp, sizeof(temp));
    if (relocated_path == nullptr) {
        errno = EACCES;
        return -1;
    }
    int fake_fd = redirect_proc_file_guarded(relocated_path, flags, mode);
    if (fake_fd != 0) {
        ALOGE("Limbus direct openat >>> redirected path=%s fd=%d", pathname, fake_fd);
        limbus_record_localize_open("direct_openat_fake", pathname, relocated_path, fake_fd, flags, __builtin_return_address(0));
        return fake_fd;
    }
    long ret = syscall(__NR_openat, dirfd, relocated_path, flags, mode);
    limbus_record_localize_open("direct_openat", pathname, relocated_path, static_cast<int>(ret), flags, __builtin_return_address(0));
    return ret;
}

static long limbus_direct_read(int fd, void *buf, size_t count) {
    long ret = syscall(__NR_read, fd, buf, count);
    limbus_record_localize_read("direct_read",
                                fd,
                                count,
                                static_cast<ssize_t>(ret),
                                0,
                                false);
    return ret;
}

#ifdef __NR_pread64
static long limbus_direct_pread64(int fd, void *buf, size_t count, off64_t offset) {
    long ret = syscall(__NR_pread64, fd, buf, count, offset);
    limbus_record_localize_read("direct_pread64",
                                fd,
                                count,
                                static_cast<ssize_t>(ret),
                                offset,
                                true);
    return ret;
}
#endif

static void *allocate_limbus_branch_trampoline(void *source, size_t size) {
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        page_size = 4096;
    }
    uintptr_t source_page = reinterpret_cast<uintptr_t>(source)
            & ~static_cast<uintptr_t>(page_size - 1);
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
    const uintptr_t step = 0x100000;
    for (uintptr_t distance = step; distance <= 0x07000000; distance += step) {
        uintptr_t candidates[] = {
                source_page + distance,
                source_page > distance ? source_page - distance : 0,
        };
        for (uintptr_t candidate : candidates) {
            if (candidate == 0) {
                continue;
            }
            void *result = mmap(reinterpret_cast<void *>(candidate),
                                size,
                                PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE,
                                -1,
                                0);
            if (result != MAP_FAILED) {
                return result;
            }
        }
    }
    return MAP_FAILED;
}

static bool patch_limbus_direct_syscall(void *svc, void *handler, const char *label) {
    if (svc == nullptr) {
        return false;
    }
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        page_size = 4096;
    }
    void *trampoline = allocate_limbus_branch_trampoline(svc, static_cast<size_t>(page_size));
    if (trampoline == MAP_FAILED) {
        ALOGE("Limbus direct %s >>> unable to allocate near trampoline errno=%d",
              label != nullptr ? label : "(null)",
              errno);
        return false;
    }

    auto *code = reinterpret_cast<uint32_t *>(trampoline);
    code[0] = 0x58000050; // ldr x16, #8
    code[1] = 0xD61F0200; // br x16
    *reinterpret_cast<uint64_t *>(code + 2) = reinterpret_cast<uint64_t>(handler);
    __builtin___clear_cache(reinterpret_cast<char *>(trampoline),
                            reinterpret_cast<char *>(trampoline) + 16);
    if (mprotect(trampoline, static_cast<size_t>(page_size), PROT_READ | PROT_EXEC) != 0) {
        ALOGE("Limbus direct %s >>> trampoline mprotect failed errno=%d",
              label != nullptr ? label : "(null)",
              errno);
        munmap(trampoline, static_cast<size_t>(page_size));
        return false;
    }

    intptr_t delta = reinterpret_cast<uintptr_t>(trampoline) - reinterpret_cast<uintptr_t>(svc);
    if ((delta & 3) != 0 || delta < -0x08000000LL || delta >= 0x08000000LL) {
        ALOGE("Limbus direct %s >>> trampoline out of branch range delta=%ld",
              label != nullptr ? label : "(null)",
              static_cast<long>(delta));
        munmap(trampoline, static_cast<size_t>(page_size));
        return false;
    }
    uint32_t branch = 0x94000000
            | (static_cast<uint32_t>(delta >> 2) & 0x03ffffff); // bl trampoline
    uintptr_t source_page = reinterpret_cast<uintptr_t>(svc)
            & ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(source_page),
                 static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ALOGE("Limbus direct %s >>> source mprotect failed errno=%d",
              label != nullptr ? label : "(null)",
              errno);
        munmap(trampoline, static_cast<size_t>(page_size));
        return false;
    }
    *reinterpret_cast<uint32_t *>(svc) = branch;
    __builtin___clear_cache(reinterpret_cast<char *>(svc),
                            reinterpret_cast<char *>(svc) + sizeof(uint32_t));
    mprotect(reinterpret_cast<void *>(source_page),
             static_cast<size_t>(page_size),
             PROT_READ | PROT_EXEC);
    ALOGE("Limbus direct %s >>> patched svc=%p trampoline=%p handler=%p",
          label != nullptr ? label : "(null)",
          svc,
          trampoline,
          handler);
    return true;
}

static bool patch_limbus_direct_openat(void *svc) {
    return patch_limbus_direct_syscall(
            svc,
            reinterpret_cast<void *>(&limbus_direct_openat),
            "openat");
}

static bool patch_limbus_direct_read(void *svc) {
    return patch_limbus_direct_syscall(
            svc,
            reinterpret_cast<void *>(&limbus_direct_read),
            "read");
}

#ifdef __NR_pread64
static bool patch_limbus_direct_pread64(void *svc) {
    return patch_limbus_direct_syscall(
            svc,
            reinterpret_cast<void *>(&limbus_direct_pread64),
            "pread64");
}
#endif

static bool patch_limbus_relative_branch(uintptr_t base,
                                         uintptr_t from_offset,
                                         uintptr_t to_offset,
                                         const char *reason,
                                         uint32_t expected_original) {
    void *from = reinterpret_cast<void *>(base + from_offset);
    intptr_t delta = static_cast<intptr_t>(to_offset) - static_cast<intptr_t>(from_offset);
    if ((delta & 3) != 0 || delta < -0x08000000LL || delta >= 0x08000000LL) {
        ALOGE("Limbus branch patch >>> invalid range reason=%s from=0x%lx to=0x%lx",
              reason,
              static_cast<unsigned long>(from_offset),
              static_cast<unsigned long>(to_offset));
        return false;
    }
    uint32_t branch = 0x14000000
            | (static_cast<uint32_t>(delta >> 2) & 0x03ffffff);
    uint32_t current = *reinterpret_cast<volatile uint32_t *>(from);
    if (current == branch) {
        return true;
    }
    if (current != expected_original) {
        ALOGE("Limbus branch patch >>> refuse unexpected instruction reason=%s offset=0x%lx expected=0x%08x actual=0x%08x",
              reason,
              static_cast<unsigned long>(from_offset),
              expected_original,
              current);
        return false;
    }
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        page_size = 4096;
    }
    uintptr_t page_start = reinterpret_cast<uintptr_t>(from)
            & ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page_start),
                 static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ALOGE("Limbus branch patch >>> mprotect failed reason=%s errno=%d", reason, errno);
        return false;
    }
    *reinterpret_cast<uint32_t *>(from) = branch;
    __builtin___clear_cache(reinterpret_cast<char *>(from),
                            reinterpret_cast<char *>(from) + sizeof(uint32_t));
    mprotect(reinterpret_cast<void *>(page_start),
             static_cast<size_t>(page_size),
             PROT_READ | PROT_EXEC);
    ALOGE("Limbus branch patch >>> patched reason=%s from=%p to=%p old=0x%08x new=0x%08x",
          reason,
          from,
          reinterpret_cast<void *>(base + to_offset),
          current,
          branch);
    return true;
}

static bool patch_limbus_nop(uintptr_t base,
                             uintptr_t offset,
                             const char *reason,
                             uint32_t expected_original) {
    void *target = reinterpret_cast<void *>(base + offset);
    constexpr uint32_t kNop = 0xd503201f;
    uint32_t current = *reinterpret_cast<volatile uint32_t *>(target);
    if (current == kNop) {
        return true;
    }
    if (current != expected_original) {
        ALOGE("Limbus nop patch >>> refuse unexpected instruction reason=%s offset=0x%lx expected=0x%08x actual=0x%08x",
              reason,
              static_cast<unsigned long>(offset),
              expected_original,
              current);
        return false;
    }
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        page_size = 4096;
    }
    uintptr_t page_start = reinterpret_cast<uintptr_t>(target)
            & ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page_start),
                 static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ALOGE("Limbus nop patch >>> mprotect failed reason=%s offset=0x%lx errno=%d",
              reason,
              static_cast<unsigned long>(offset),
              errno);
        return false;
    }
    *reinterpret_cast<uint32_t *>(target) = kNop;
    __builtin___clear_cache(reinterpret_cast<char *>(target),
                            reinterpret_cast<char *>(target) + sizeof(uint32_t));
    mprotect(reinterpret_cast<void *>(page_start),
             static_cast<size_t>(page_size),
             PROT_READ | PROT_EXEC);
    ALOGE("Limbus nop patch >>> patched reason=%s target=%p old=0x%08x",
          reason,
          target,
          current);
    return true;
}

static void patch_limbus_appsealing_70034(uintptr_t base) {
    static pthread_mutex_t patch_lock = PTHREAD_MUTEX_INITIALIZER;
    static uintptr_t expected_base = 0;
    static uint32_t expected_original[5] = {};
    pthread_mutex_lock(&patch_lock);
    if (expected_base != base) {
        expected_base = base;
        expected_original[0] = *reinterpret_cast<volatile uint32_t *>(base + 0x4e6b0);
        expected_original[1] = *reinterpret_cast<volatile uint32_t *>(base + 0x99820);
        expected_original[2] = *reinterpret_cast<volatile uint32_t *>(base + 0x54488);
        expected_original[3] = *reinterpret_cast<volatile uint32_t *>(base + 0x545e4);
        expected_original[4] = *reinterpret_cast<volatile uint32_t *>(base + 0x4e564);
        __sync_synchronize();
        g_limbus_appsealing_base = base;
        maintain_limbus_sigill_guard();
        ALOGE("Limbus AppSealing patch >>> captured verified offsets base=%p instructions=%08x/%08x/%08x/%08x/%08x",
              reinterpret_cast<void *>(base),
              expected_original[0],
              expected_original[1],
              expected_original[2],
              expected_original[3],
              expected_original[4]);
    }
    // Skip the two AppSealing actions that report code 70034 after host /data path checks.
    bool first = patch_limbus_relative_branch(base, 0x4e6b0, 0x4e998,
                                              "70034-data-path", expected_original[0]);
    bool second = patch_limbus_relative_branch(base, 0x99820, 0x99878,
                                               "70034-path-scan", expected_original[1]);
    // Keep the map audit running, but suppress the two confirmed 50040 report calls.
    bool third = patch_limbus_nop(base, 0x54488, "50040-map-report", expected_original[2]);
    bool fourth = patch_limbus_nop(base, 0x545e4, "50040-map-summary", expected_original[3]);
    // Runtime backtrace returns to +0x4e568 after the 50048 report call. Reuse the
    // verified cleanup target for this function instead of continuing with stale state.
    bool fifth = patch_limbus_relative_branch(base, 0x4e564, 0x4e998,
                                              "50048-report-cleanup", expected_original[4]);
    if (!first || !second || !third || !fourth || !fifth) {
        ALOGE("Limbus AppSealing patch >>> incomplete base=%p results=%d/%d/%d/%d/%d",
              reinterpret_cast<void *>(base),
              first,
              second,
              third,
              fourth,
              fifth);
    }
    pthread_mutex_unlock(&patch_lock);
}

int maintainLimbusAppSealingPatches() {
    uintptr_t base = g_limbus_appsealing_base;
    if (base == 0) {
        return 0;
    }
    maintain_limbus_sigill_guard();
    constexpr uint32_t k50048CleanupBranch = 0x1400010d;
    auto *target = reinterpret_cast<volatile uint32_t *>(base + 0x4e564);
    uint32_t before = *target;
    if (before == k50048CleanupBranch) {
        return 1;
    }
    // AppSealing rewrites this confirmed 50048 site with a transient instruction
    // shortly before execution. The exact PC and cleanup target are version-verified;
    // do not broaden this exception to the other guarded offsets.
    patch_limbus_relative_branch(base,
                                 0x4e564,
                                 0x4e998,
                                 "50048-maintain-transient-cleanup",
                                 before);
    uint32_t after = *target;
    if (after == k50048CleanupBranch) {
        ALOGE("Limbus AppSealing patch maintainer >>> restored confirmed offsets old=0x%08x",
              before);
        return 2;
    }
    return -1;
}

bool on_found_limbus_syscall_arch64(const char *path, int num, void *func) {
    bool key_library = path != nullptr
            && (strstr(path, "libil2cpp.so") != nullptr
            || strstr(path, "libunity.so") != nullptr
            || strstr(path, "libmain.so") != nullptr);
    uintptr_t offset = 0;
    if (key_library) {
        Dl_info info;
        memset(&info, 0, sizeof(info));
        if (dladdr(func, &info) != 0 && info.dli_fbase != nullptr) {
            offset = reinterpret_cast<uintptr_t>(func)
                    - reinterpret_cast<uintptr_t>(info.dli_fbase);
        }
    }
    pthread_mutex_lock(&g_limbus_syscall_hook_lock);
    if (g_limbus_seen_syscall_nums.insert(num).second) {
        ALOGE("Limbus syscall scan >>> num=%d path=%s func=%p", num, path, func);
    }
    if (key_library) {
        char site_key[256];
        snprintf(site_key, sizeof(site_key), "%s:%d:%lx",
                 path != nullptr ? path : "(null)",
                 num,
                 static_cast<unsigned long>(offset));
        if (g_limbus_seen_syscall_sites.insert(site_key).second) {
            ALOGE("Limbus syscall scan detail >>> num=%d path=%s func=%p offset=0x%lx",
                  num,
                  path,
                  func,
                  static_cast<unsigned long>(offset));
        }
    }
    pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
    switch (num) {
        case __NR_openat:
            if (path != nullptr && strstr(path, "libcovault-appsec.so") != nullptr) {
                Dl_info info;
                memset(&info, 0, sizeof(info));
                if (dladdr(func, &info) == 0 || info.dli_fbase == nullptr
                        || reinterpret_cast<uintptr_t>(func)
                        - reinterpret_cast<uintptr_t>(info.dli_fbase) != 0x18bf8) {
                    break;
                }
                patch_limbus_appsealing_70034(
                        reinterpret_cast<uintptr_t>(info.dli_fbase));
                pthread_mutex_lock(&g_limbus_syscall_hook_lock);
                if (!g_limbus_hooked_syscalls.insert(func).second) {
                    pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                    break;
                }
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                patch_limbus_direct_openat(func);
            }
            break;
#ifdef __NR_read
        case __NR_read:
            if (path != nullptr
                    && (strstr(path, "libil2cpp.so") != nullptr
                    || strstr(path, "libunity.so") != nullptr
                    || strstr(path, "libmain.so") != nullptr)) {
                pthread_mutex_lock(&g_limbus_syscall_hook_lock);
                if (!g_limbus_hooked_syscalls.insert(func).second) {
                    pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                    break;
                }
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                ALOGE("Limbus syscall patch >>> direct read path=%s func=%p", path, func);
                patch_limbus_direct_read(func);
            }
            break;
#endif
#ifdef __NR_pread64
        case __NR_pread64:
            if (path != nullptr
                    && (strstr(path, "libil2cpp.so") != nullptr
                    || strstr(path, "libunity.so") != nullptr
                    || strstr(path, "libmain.so") != nullptr)) {
                pthread_mutex_lock(&g_limbus_syscall_hook_lock);
                if (!g_limbus_hooked_syscalls.insert(func).second) {
                    pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                    break;
                }
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                ALOGE("Limbus syscall patch >>> direct pread64 path=%s func=%p", path, func);
                patch_limbus_direct_pread64(func);
            }
            break;
#endif
        case __NR_kill:
            pthread_mutex_lock(&g_limbus_syscall_hook_lock);
            if (!g_limbus_hooked_syscalls.insert(func).second) {
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                break;
            }
            pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
            ALOGE("Limbus syscall patch >>> kill path=%s func=%p", path, func);
            patch_limbus_syscall_return_zero(func);
            break;
#ifdef __NR_tkill
        case __NR_tkill:
            pthread_mutex_lock(&g_limbus_syscall_hook_lock);
            if (!g_limbus_hooked_syscalls.insert(func).second) {
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                break;
            }
            pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
            ALOGE("Limbus syscall patch >>> tkill path=%s func=%p", path, func);
            patch_limbus_syscall_return_zero(func);
            break;
#endif
#ifdef __NR_tgkill
        case __NR_tgkill:
            pthread_mutex_lock(&g_limbus_syscall_hook_lock);
            if (!g_limbus_hooked_syscalls.insert(func).second) {
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                break;
            }
            pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
            ALOGE("Limbus syscall patch >>> tgkill path=%s func=%p", path, func);
            patch_limbus_syscall_return_zero(func);
            break;
#endif
#ifdef __NR_exit
        case __NR_exit:
            pthread_mutex_lock(&g_limbus_syscall_hook_lock);
            if (!g_limbus_hooked_syscalls.insert(func).second) {
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                break;
            }
            pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
            /*
             * 仅修改已由指令扫描确认的 Limbus/AppSealing 直接 exit 站点，避免主线程
             * 被单独结束后留下僵尸进程；返回 0 保持保护库调用方的成功返回约定。
             */
            ALOGE("Limbus syscall patch >>> exit path=%s func=%p", path, func);
            patch_limbus_syscall_return_zero(func);
            break;
#endif
#ifdef __NR_exit_group
        case __NR_exit_group:
            pthread_mutex_lock(&g_limbus_syscall_hook_lock);
            if (!g_limbus_hooked_syscalls.insert(func).second) {
                pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
                break;
            }
            pthread_mutex_unlock(&g_limbus_syscall_hook_lock);
            ALOGE("Limbus syscall patch >>> exit_group path=%s func=%p", path, func);
            patch_limbus_syscall_return_zero(func);
            break;
#endif
    }
    return CONTINUE_FIND_SYSCALL;
}

static bool is_limbus_native_path(const char *name) {
    if (name == nullptr) {
        return false;
    }
    return strstr(name, "com.ProjectMoon.LimbusCompany") != nullptr
            || strstr(name, "libcovault-appsec.so") != nullptr
            || strstr(name, "libil2cpp.so") != nullptr
            || strstr(name, "libunity.so") != nullptr
            || strstr(name, "libmain.so") != nullptr
            || strstr(name, "libAppSealing") != nullptr
            || strstr(name, "appsealing") != nullptr
            || strstr(name, "AppSealing") != nullptr;
}

static void hook_limbus_native_syscalls(const char *name, bool allow_loaded_fallback) {
    if (!is_limbus_container_process() || !is_limbus_native_path(name)) {
        return;
    }
    int matched_maps = findSyscallsCount(name, on_found_limbus_syscall_arch64);
    if (matched_maps == 0 && allow_loaded_fallback) {
        /*
         * Android 16 上，原先读取到的文件列表会漏掉已经装入的游戏文件。
         * 后台扫描不处于文件装载回调中，可以改从装载器记录读取实际代码段。
         */
        matched_maps = findLoadedSyscallsCount(name, on_found_limbus_syscall_arch64);
        if (matched_maps > 0) {
            ALOGE("Limbus syscall hook >>> loaded-module fallback name=%s matchedSegments=%d",
                  name,
                  matched_maps);
        }
    }
    ALOGE("Limbus syscall hook >>> scan native map name=%s matchedMaps=%d", name, matched_maps);
    if (matched_maps > 0) {
        probe_limbus_translation_runtime(name);
    }

    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    if (real_lib_dir != nullptr && name != nullptr) {
        const char *lib_name = strrchr(name, '/');
        lib_name = lib_name == nullptr ? name : lib_name + 1;
        if (strstr(lib_name, ".so") != nullptr) {
            char real_path[PATH_MAX];
            snprintf(real_path, sizeof(real_path), "%s/%s", real_lib_dir, lib_name);
            if (strcmp(real_path, name) != 0) {
                int real_matched_maps = findSyscallsCount(real_path, on_found_limbus_syscall_arch64);
                if (real_matched_maps == 0 && allow_loaded_fallback) {
                    real_matched_maps = findLoadedSyscallsCount(
                            real_path, on_found_limbus_syscall_arch64);
                }
                ALOGE("Limbus syscall hook >>> scan real native map path=%s matchedMaps=%d",
                      real_path,
                      real_matched_maps);
                if (real_matched_maps > 0) {
                    probe_limbus_translation_runtime(real_path);
                }
            }
        }
    }
}

void scanLimbusNativeSyscalls(const char *nativeLibraryDir) {
    if (!is_limbus_container_process()) {
        return;
    }
    const char *libs[] = {
            "libcovault-appsec.so",
            "libil2cpp.so",
            "libunity.so",
            "libmain.so",
    };
    for (const char *lib : libs) {
        /*
         * 优先使用完整路径接管真实映射。只按文件名查找时会先经过伪装后的 maps，
         * 同一库可能被拆成很多段，首轮处理耗时过长；完整路径能让 AppSealing 的
         * 固定结束进程位置更早被接管。随后仍按文件名补查动态生成的匿名映射。
         */
        if (nativeLibraryDir != nullptr && nativeLibraryDir[0] != '\0') {
            char path[PATH_MAX];
            snprintf(path, sizeof(path), "%s/%s", nativeLibraryDir, lib);
            hook_limbus_native_syscalls(path, true);
        }
        hook_limbus_native_syscalls(lib, true);
    }
    // 原生保护库可能绕过 libc 直接替换信号处理器，因此每轮加载扫描结束后都校正一次。
    maintain_limbus_sigsegv_guard();
    // This entry point runs outside the linker callback after the game libraries are loaded.
    // Refreshing from onSoLoaded would re-enter dl_iterate_phdr while bionic is mutating its
    // module list and can itself cause a synchronous SIGSEGV on Android 12/13.
    refresh_limbus_signal_modules();
}

void onSoLoaded(const char *name, void *handle) {
    if (handle != nullptr) {
        // 文件装载回调中只能使用旧的轻量查找，避免在系统更新列表时再次遍历。
        hook_limbus_native_syscalls(name, false);
        on_limbus_translation_library_loaded(name, handle);
    }
}

bool relocate_linker(const char* LINKER_PATH) {
    intptr_t linker_addr, dlopen_off, symbol;
    if ((linker_addr = get_addr(LINKER_PATH)) == 0) {
        ALOGE("Cannot found linker addr.");
        return false;
    }
    if (resolve_symbol(LINKER_PATH, "__dl__Z9do_dlopenPKciPK17android_dlextinfoPKv",
                       &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__Z9do_dlopenPKciPK17android_dlextinfoPv",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__ZL10dlopen_extPKciPK17android_dlextinfoPv",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (
            resolve_symbol(LINKER_PATH, "__dl__Z20__android_dlopen_extPKciPK17android_dlextinfoPKv",
                           &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (
            resolve_symbol(LINKER_PATH, "__dl___loader_android_dlopen_ext",
                           &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIVV,
                       (void **) &orig_do_dlopen_CIVV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__Z9do_dlopenPKciPK17android_dlextinfo",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIV,
                       (void **) &orig_do_dlopen_CIV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl__Z8__dlopenPKciPKv",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIV,
                       (void **) &orig_do_dlopen_CIV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl___loader_dlopen",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_do_dlopen_CIV,
                       (void **) &orig_do_dlopen_CIV);
        return true;
    } else if (resolve_symbol(LINKER_PATH, "__dl_dlopen",
                              &dlopen_off) == 0) {
        symbol = linker_addr + dlopen_off;
        MSHookFunction((void *) symbol, (void *) new_dlopen_CI,
                       (void **) &orig_dlopen_CI);
        return true;
    }
    return false;
}

void startIOHook(int api_level) {
    void *handle = dlopen("libc.so", RTLD_NOW);

    if (handle) {
        init_limbus_container_process_flag();
        ignore_limbus_sigalrm_if_needed();
        HOOK_SYMBOL(handle, fchownat);
        if (api_level >= 36) {
            /*
             * Android 16 的 arm64 bionic renameat 只有 12 字节，末尾直接跳转
             * renameat2。旧 MSHookFunction 需要改写 16 字节，会越界覆盖紧邻的
             * rmdir 首指令并产生 SIGILL；API 36 保留 libc 原实现。
             */
            ALOGE("IO hook >>> skip Android 16 arm64 libc renameat hook to preserve adjacent rmdir");
            /*
             * rename/renameat 在 Android 16 都会跳转到独立的 renameat2 包装；该入口有
             * 足够的 28 字节可供旧 inline hook 安全建立跳板。UnityCache 的 native
             * Temp -> Shared 提交也经过这里，因此改 hook renameat2 才能保留下载资源。
             */
            HOOK_SYMBOL(handle, renameat2);
            ALOGE("IO hook >>> hooked Android 16 arm64 libc renameat2 for virtual cache commits");
        } else {
            HOOK_SYMBOL(handle, renameat);
        }
        HOOK_SYMBOL(handle, mkdirat);
        HOOK_SYMBOL(handle, mknodat);
        HOOK_SYMBOL(handle, truncate);
        HOOK_SYMBOL(handle, linkat);
        HOOK_SYMBOL(handle, readlinkat);
        HOOK_SYMBOL(handle, unlinkat);
        HOOK_SYMBOL(handle, symlinkat);
        HOOK_SYMBOL(handle, utimensat);
        HOOK_SYMBOL(handle, chdir);
        HOOK_SYMBOL(handle, execve);
        HOOK_SYMBOL(handle, statfs64);
        HOOK_SYMBOL(handle, kill);
        HOOK_SYMBOL(handle, alarm);
        HOOK_SYMBOL(handle, setitimer);
        HOOK_SYMBOL(handle, raise);
        HOOK_SYMBOL(handle, tkill);
        HOOK_SYMBOL(handle, tgkill);
        HOOK_SYMBOL(handle, pthread_kill);
        HOOK_SYMBOL(handle, exit);
        HOOK_SYMBOL(handle, _exit);
        HOOK_SYMBOL(handle, abort);
        HOOK_SYMBOL(handle, sigaction);
        install_limbus_sigsegv_guard();
        HOOK_SYMBOL(handle, syscall);
        HOOK_SYMBOL(handle, opendir);
        HOOK_SYMBOL(handle, open);
        HOOK_SYMBOL(handle, open64);
        HOOK_SYMBOL(handle, __open_2);
        HOOK_SYMBOL(handle, openat64);
        HOOK_SYMBOL(handle, fopen);
        HOOK_SYMBOL(handle, fopen64);
        HOOK_SYMBOL(handle, fread);
        HOOK_SYMBOL(handle, fread_unlocked);
        HOOK_SYMBOL(handle, fgets);
        HOOK_SYMBOL(handle, vfork);
        HOOK_SYMBOL(handle, faccessat);
        HOOK_SYMBOL(handle, openat);
        HOOK_SYMBOL(handle, fchmodat);
        HOOK_SYMBOL(handle, fstatat64);
        HOOK_SYMBOL(handle, statfs);
        HOOK_SYMBOL(handle, __statfs);
        HOOK_SYMBOL(handle, __statfs64);
        HOOK_SYMBOL(handle, getcwd);
        HOOK_SYMBOL(handle, stat);
        HOOK_SYMBOL(handle, lstat);
        HOOK_SYMBOL(handle, fstatat);
        HOOK_SYMBOL(handle, close);
        // Android 12's arm64 bionic read stub is not safe for this legacy inline
        // hook implementation.  A virtual package may replace its process title
        // (GMS does this with "com.google.android.gms"), so process-name based
        // exemptions are insufficient and leave read+8 containing a broken
        // trampoline instruction.  Keep libc read untouched for every arm64
        // virtual process; path routing is handled at open/openat/syscall.
        ALOGE("IO hook >>> skip arm64 libc read hook to avoid bionic read+8 SIGILL");
        HOOK_SYMBOL(handle, write);
        HOOK_SYMBOL(handle, mmap);
        HOOK_SYMBOL(handle, munmap);
        HOOK_SYMBOL(handle, pread64);
        HOOK_SYMBOL(handle, pwrite64);
        HOOK_SYMBOL(handle, fstat);
        HOOK_SYMBOL(handle, __llseek);
        HOOK_SYMBOL(handle, lseek);
        HOOK_SYMBOL(handle, ftruncate64);
        HOOK_SYMBOL(handle, sendfile);
        HOOK_SYMBOL(handle, sendfile64);
        HOOK_SYMBOL(handle, dup);
        HOOK_SYMBOL(handle, dup3);
        HOOK_SYMBOL(handle, fcntl);
        HOOK_SYMBOL(handle, getaddrinfo);
        HOOK_SYMBOL(handle, sendto);
#if defined(__x86_64__)
#else
        HOOK_SYMBOL(handle, connect);
#endif
        HOOK_SYMBOL(handle, msync);

        findSyscalls("/system/lib64/libc.so", on_found_syscall_aarch64);
        findSyscalls("/system/bin/linker64", on_found_linker_syscall_arch64);
        hook_limbus_native_syscalls("libcovault-appsec.so", true);
        hook_limbus_native_syscalls("libil2cpp.so", true);
        hook_limbus_native_syscalls("libunity.so", true);
        hook_limbus_native_syscalls("libmain.so", true);
        dlclose(handle);
    }
    originalInterface::original_lseek = orig_lseek;
    originalInterface::original_llseek = orig___llseek;
    originalInterface::original_fstat = orig_fstat;
    originalInterface::original_pwrite64 = orig_pwrite64;
    originalInterface::original_pread64 = orig_pread64;
    originalInterface::original_ftruncate64 = orig_ftruncate64;
    originalInterface::original_sendfile = orig_sendfile;
    originalInterface::original_getaddrinfo = orig_getaddrinfo;
}


void
IOUniformer::startUniformer(const char *so_path, const char *so_path_64, const char *native_path,
                            int api_level,
                            int preview_api_level) {
    bool ret = ff_Recognizer::getFFR().init(getMagicPath());
    LOGE("FFR path %s init %s", getMagicPath(), ret ? "success" : "fail");
    char api_level_chars[56];
    char pre_api_level_chars[56];
    setenv("V_SO_PATH", so_path, 1);
//    setenv("V_SO_PATH_64", so_path_64, 1);
    sprintf(api_level_chars, "%i", api_level);
    setenv("V_API_LEVEL", api_level_chars, 1);
    sprintf(pre_api_level_chars, "%i", preview_api_level);
    setenv("V_PREVIEW_API_LEVEL", pre_api_level_chars, 1);
    setenv("V_API_LEVEL", api_level_chars, 1);
    setenv("V_NATIVE_PATH", native_path, 1);
    startIOHook(api_level);
    // The legacy IO layer inline-hooks a number of bionic entry points.  Install
    // the translation dlsym lifecycle hook only after that pass has finished;
    // otherwise A64HookFunction tries to relocate an already patched prologue
    // and can crash in __fix_instructions during process startup.
    activate_limbus_translation_lifecycle_hook();
}
