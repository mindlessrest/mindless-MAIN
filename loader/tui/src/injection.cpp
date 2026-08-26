#include "injection.hpp"

#include <cstring>
#include <filesystem>
#include <fstream>

extern "C" {
extern const unsigned char raven_payload[];
extern const size_t raven_payload_len;
}

namespace fs = std::filesystem;

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <tlhelp32.h>

namespace mindless {

static uintptr_t remote_module_base(uint32_t pid, const wchar_t* name) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    MODULEENTRY32W me = {};
    me.dwSize = sizeof(me);
    uintptr_t result = 0;
    if (Module32FirstW(snap, &me)) {
        do {
            if (_wcsicmp(me.szModule, name) == 0) {
                result = reinterpret_cast<uintptr_t>(me.modBaseAddr);
                break;
            }
        } while (Module32NextW(snap, &me));
    }
    CloseHandle(snap);
    return result;
}

static bool remote_has_module(uint32_t pid, const wchar_t* path) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
    if (snap == INVALID_HANDLE_VALUE) return false;
    MODULEENTRY32W me = {};
    me.dwSize = sizeof(me);
    bool found = false;
    if (Module32FirstW(snap, &me)) {
        do {
            if (_wcsicmp(me.szExePath, path) == 0) { found = true; break; }
        } while (Module32NextW(snap, &me));
    }
    CloseHandle(snap);
    return found;
}

InjectResult inject(const InjectOptions& opts, std::string& error_out) {
    // Convert path to wide
    wchar_t wide_path[MAX_PATH] = {};
    MultiByteToWideChar(CP_UTF8, 0, opts.library_path.c_str(), -1, wide_path, MAX_PATH);

    if (remote_has_module(opts.pid, wide_path))
        return InjectResult::AlreadyLoaded;

    HANDLE process = OpenProcess(
        PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
        PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
        FALSE, opts.pid);
    if (!process) {
        error_out = "OpenProcess failed — run as admin or check antivirus";
        return InjectResult::Failed;
    }

    SIZE_T path_bytes = (wcslen(wide_path) + 1) * sizeof(wchar_t);
    void* remote_path = VirtualAllocEx(process, nullptr, path_bytes,
        MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote_path) {
        error_out = "VirtualAllocEx failed";
        CloseHandle(process);
        return InjectResult::Failed;
    }

    SIZE_T written = 0;
    if (!WriteProcessMemory(process, remote_path, wide_path, path_bytes, &written) ||
        written != path_bytes) {
        error_out = "WriteProcessMemory failed";
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        CloseHandle(process);
        return InjectResult::Failed;
    }

    HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
    auto local_ll = reinterpret_cast<uintptr_t>(GetProcAddress(kernel, "LoadLibraryW"));
    uintptr_t remote_kernel = remote_module_base(opts.pid, L"kernel32.dll");
    if (!kernel || !local_ll || !remote_kernel) {
        error_out = "Could not resolve remote LoadLibraryW";
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        CloseHandle(process);
        return InjectResult::Failed;
    }

    uintptr_t offset = local_ll - reinterpret_cast<uintptr_t>(kernel);
    auto remote_ll = reinterpret_cast<LPTHREAD_START_ROUTINE>(remote_kernel + offset);

    HANDLE thread = CreateRemoteThread(process, nullptr, 0, remote_ll, remote_path, 0, nullptr);
    if (!thread) {
        error_out = "CreateRemoteThread failed";
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        CloseHandle(process);
        return InjectResult::Failed;
    }

    DWORD wait = WaitForSingleObject(thread, 30000);
    CloseHandle(thread);

    if (wait != WAIT_OBJECT_0) {
        error_out = "Remote thread timed out";
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        CloseHandle(process);
        return InjectResult::Failed;
    }

    VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);

    // Verify loaded
    for (int i = 0; i < 50; ++i) {
        if (remote_has_module(opts.pid, wide_path)) {
            CloseHandle(process);
            return InjectResult::Ok;
        }
        Sleep(100);
    }

    CloseHandle(process);
    error_out = "DLL not mapped after injection — check raven-native.log";
    return InjectResult::Failed;
}

std::string extract_runtime() {
    wchar_t tmp_dir[MAX_PATH] = {};
    GetTempPathW(MAX_PATH, tmp_dir);
    fs::path tmp = fs::path(tmp_dir) / "mindless-RavenNative.dll";
    std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
    if (!out) return {};
    out.write(reinterpret_cast<const char*>(raven_payload), raven_payload_len);
    out.close();
    return tmp.string();
}

} // namespace mindless

#else // Linux

#include <dlfcn.h>
#include <linux/sched.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>

namespace mindless {

static uintptr_t find_lib_base(pid_t pid, const char* lib_hint) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE* f = fopen(maps_path, "r");
    if (!f) return 0;
    uintptr_t base = 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, lib_hint) && strstr(line, "r-xp")) {
            base = strtoull(line, nullptr, 16);
            break;
        }
    }
    fclose(f);
    return base;
}

static uintptr_t resolve_remote(pid_t pid, const char* sym_name) {
    const char* hints[] = { "libc.so.6", "libc-", "libc.so" };
    for (auto hint : hints) {
        uintptr_t remote_base = find_lib_base(pid, hint);
        uintptr_t local_base = find_lib_base(getpid(), hint);
        if (!remote_base || !local_base) continue;
        void* sym = dlsym(RTLD_DEFAULT, sym_name);
        if (!sym) continue;
        uintptr_t offset = (uintptr_t)sym - local_base;
        return remote_base + offset;
    }
    return 0;
}

static bool ptrace_write(pid_t pid, uintptr_t addr, const void* data, size_t len) {
    const unsigned char* src = (const unsigned char*)data;
    size_t i = 0;
    for (; i + sizeof(long) <= len; i += sizeof(long)) {
        long word;
        memcpy(&word, src + i, sizeof(long));
        if (ptrace(PTRACE_POKEDATA, pid, addr + i, word) < 0) return false;
    }
    if (i < len) {
        errno = 0;
        long word = ptrace(PTRACE_PEEKDATA, pid, addr + i, nullptr);
        memcpy(&word, src + i, len - i);
        if (ptrace(PTRACE_POKEDATA, pid, addr + i, word) < 0) return false;
    }
    return true;
}

// Execute a syscall in the remote process via ptrace, return the result in rax
static long remote_syscall(pid_t pid, struct user_regs_struct& regs,
                           long nr, long a1 = 0, long a2 = 0, long a3 = 0,
                           long a4 = 0, long a5 = 0, long a6 = 0) {
    struct user_regs_struct call_regs = regs;
    call_regs.rax = nr;
    call_regs.rdi = a1;
    call_regs.rsi = a2;
    call_regs.rdx = a3;
    call_regs.r10 = a4;
    call_regs.r8  = a5;
    call_regs.r9  = a6;
    // Point RIP at a syscall;ret gadget — we'll write one
    // Actually: set RIP to current RIP-2, poke "syscall" (0f 05) there
    // Safer: use the existing RIP, save 2 bytes, write syscall, restore after
    uintptr_t rip = regs.rip;
    long orig_code = ptrace(PTRACE_PEEKDATA, pid, rip, nullptr);
    long syscall_code = (orig_code & ~0xFFFF) | 0x050F; // 0F 05 = syscall
    ptrace(PTRACE_POKEDATA, pid, rip, syscall_code);

    call_regs.rip = rip;
    ptrace(PTRACE_SETREGS, pid, nullptr, &call_regs);
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);
    int st;
    waitpid(pid, &st, 0);

    struct user_regs_struct result_regs;
    ptrace(PTRACE_GETREGS, pid, nullptr, &result_regs);

    // Restore original code
    ptrace(PTRACE_POKEDATA, pid, rip, orig_code);

    return (long)result_regs.rax;
}

InjectResult inject(const InjectOptions& opts, std::string& error_out) {
    pid_t pid = static_cast<pid_t>(opts.pid);

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) < 0) {
        if (errno == EPERM)
            error_out = "Permission denied — run as root or set ptrace_scope=0";
        else
            error_out = std::string("ptrace attach failed: ") + strerror(errno);
        return InjectResult::Failed;
    }

    int status;
    waitpid(pid, &status, 0);
    if (!WIFSTOPPED(status)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        error_out = "process did not stop after attach";
        return InjectResult::Failed;
    }

    // Resolve __libc_dlopen_mode in target
    uintptr_t dlopen_addr = resolve_remote(pid, "__libc_dlopen_mode");
    bool use_libc_internal = (dlopen_addr != 0);
    if (!dlopen_addr)
        dlopen_addr = resolve_remote(pid, "dlopen");
    if (!dlopen_addr) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        error_out = "could not resolve dlopen in target";
        return InjectResult::Failed;
    }

    struct user_regs_struct orig_regs;
    ptrace(PTRACE_GETREGS, pid, nullptr, &orig_regs);

    // Step 1: mmap an RWX page in the target (like VirtualAllocEx)
    long page = remote_syscall(pid, orig_regs, SYS_mmap,
        0, 4096,
        PROT_READ | PROT_WRITE | PROT_EXEC,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1, 0);

    if (page <= 0 || page == -1) {
        ptrace(PTRACE_SETREGS, pid, nullptr, &orig_regs);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        error_out = "remote mmap failed";
        return InjectResult::Failed;
    }

    uintptr_t remote_page = (uintptr_t)page;

    // Write the .so path at page + 512
    uintptr_t path_addr = remote_page + 512;
    ptrace_write(pid, path_addr, opts.library_path.c_str(), opts.library_path.size() + 1);

    // Step 2: Write shellcode that calls dlopen then exits the thread
    // This runs as a NEW thread (via clone), so when it's done it just exits.
    //
    // shellcode:
    //   mov rdi, <path_addr>           ; 48 bf [8]
    //   mov rsi, <flags>               ; 48 be [8]
    //   mov rax, <dlopen_addr>         ; 48 b8 [8]
    //   call rax                       ; ff d0
    //   mov rdi, 0                     ; 48 c7 c7 00 00 00 00
    //   mov rax, 60                    ; 48 c7 c0 3c 00 00 00  (SYS_exit)
    //   syscall                        ; 0f 05

    uint64_t flags = use_libc_internal ? 0x80000002ULL : 0x2ULL;

    unsigned char shellcode[] = {
        // mov rdi, path_addr
        0x48, 0xbf, 0,0,0,0,0,0,0,0,
        // mov rsi, flags
        0x48, 0xbe, 0,0,0,0,0,0,0,0,
        // mov rax, dlopen_addr
        0x48, 0xb8, 0,0,0,0,0,0,0,0,
        // call rax
        0xff, 0xd0,
        // mov rdi, 0
        0x48, 0xc7, 0xc7, 0x00, 0x00, 0x00, 0x00,
        // mov rax, 60 (SYS_exit)
        0x48, 0xc7, 0xc0, 0x3c, 0x00, 0x00, 0x00,
        // syscall
        0x0f, 0x05,
    };
    memcpy(shellcode + 2, &path_addr, 8);
    memcpy(shellcode + 12, &flags, 8);
    memcpy(shellcode + 22, &dlopen_addr, 8);

    ptrace_write(pid, remote_page, shellcode, sizeof(shellcode));

    // Step 3: clone a new thread (like CreateRemoteThread)
    // clone(CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD,
    //       stack_top, NULL, NULL, 0)
    // The new thread starts at remote_page (our shellcode)
    // Stack for the new thread: use page + 4096 (top of our allocated page) - won't need much
    uintptr_t new_stack = remote_page + 4096 - 16; // 16-byte aligned stack top


    // For clone on x86_64: syscall(SYS_clone, flags, stack, parent_tid, child_tid, tls)
    // But the child's RIP is set by the return from clone — the child returns to the same
    // instruction. So we need to make the main thread's RIP point to our shellcode during clone,
    // but that would hijack it...
    //
    // Alternative: use clone3 or just call pthread_create remotely.
    // Simplest reliable approach: just call dlopen on the STOPPED thread, then restore.
    // The constructor spawns its own thread via pthread_create internally.
    // The key fix: after dlopen returns, IMMEDIATELY restore regs and detach so the
    // JVM thread resumes. The .so constructor's spawned threads will work because
    // we detach right after.

    // Actually — the constructor in our .so (bootstrap.c) spawns a new thread via
    // raven_thread_create (pthread_create) and returns immediately. So calling dlopen
    // on the hijacked thread is fine — dlopen returns fast, we restore, JVM continues,
    // and the bootstrap thread does its JNI work in the background.

    // The REAL issue before was likely: using stack below RSP as scratch (might not be mapped),
    // or the flags for __libc_dlopen_mode. Now we use a properly mmap'd page.

    // Write path at page+512 (already done), shellcode at page+0.
    // But instead of clone, just execute on this thread with proper allocated memory:

    struct user_regs_struct call_regs = orig_regs;
    call_regs.rip = remote_page;
    call_regs.rsp = new_stack;  // Use our own stack space
    // Need a valid return address on stack for the "call rax"
    // Push a return addr that points to the exit sequence (offset 32)
    uintptr_t ret_addr = remote_page + 32; // after "call rax", this is where the mov rdi,0 is
    ptrace_write(pid, new_stack, &ret_addr, 8);
    // Actually "call rax" pushes return address itself. Let's restructure:
    // Just use jmp instead of call, or handle the stack properly.

    // Simpler shellcode without call (avoids stack issues):
    // mov rdi, path_addr
    // mov rsi, flags
    // sub rsp, 8          ; align stack for function call ABI
    // mov rax, dlopen
    // call rax
    // add rsp, 8
    // int3                 ; trap back to us
    unsigned char shellcode2[] = {
        // mov rdi, path_addr
        0x48, 0xbf, 0,0,0,0,0,0,0,0,
        // mov rsi, flags
        0x48, 0xbe, 0,0,0,0,0,0,0,0,
        // sub rsp, 8 (align for call)
        0x48, 0x83, 0xec, 0x08,
        // mov rax, dlopen_addr
        0x48, 0xb8, 0,0,0,0,0,0,0,0,
        // call rax
        0xff, 0xd0,
        // add rsp, 8
        0x48, 0x83, 0xc4, 0x08,
        // int3
        0xcc,
    };
    memcpy(shellcode2 + 2, &path_addr, 8);
    memcpy(shellcode2 + 12, &flags, 8);
    memcpy(shellcode2 + 26, &dlopen_addr, 8);

    ptrace_write(pid, remote_page, shellcode2, sizeof(shellcode2));

    call_regs.rip = remote_page;
    call_regs.rsp = new_stack;
    ptrace(PTRACE_SETREGS, pid, nullptr, &call_regs);

    // Continue — all threads resume, our thread runs dlopen, hits int3
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);
    waitpid(pid, &status, 0);

    struct user_regs_struct result_regs;
    ptrace(PTRACE_GETREGS, pid, nullptr, &result_regs);
    uintptr_t dlopen_result = result_regs.rax;

    // Restore original thread state immediately
    ptrace(PTRACE_SETREGS, pid, nullptr, &orig_regs);

    // Free our page (optional, but clean)
    remote_syscall(pid, orig_regs, SYS_munmap, remote_page, 4096);

    // Detach — JVM resumes normally, .so constructor thread does its thing
    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);

    if (dlopen_result == 0) {
        error_out = "dlopen failed in target — .so may have missing dependencies";
        return InjectResult::Failed;
    }

    return InjectResult::Ok;
}

std::string extract_runtime() {
    fs::path tmp = fs::temp_directory_path() / "mindless-RavenNative.so";
    std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
    if (!out) return {};
    out.write(reinterpret_cast<const char*>(raven_payload), raven_payload_len);
    out.close();
    chmod(tmp.c_str(), 0755);
    return tmp.string();
}

} // namespace mindless

#endif
