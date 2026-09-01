#include "protection.hpp"
#include "auth/xorstr.hpp"
#include "auth/lazy_importer.hpp"
#include <authclient/authclient.hpp>
#include <windows.h>
#include <winternl.h>
#include <TlHelp32.h>
#include <intrin.h>
#include <vector>
#include <string>
#include <thread>
#include <atomic>

namespace mindless::protection
{

namespace
{

authclient::AuthClient* g_client = nullptr;
std::atomic<bool> g_watchdogRunning{false};
std::thread g_watchdogThread;

using NtQueryInformationProcess_t = NTSTATUS(NTAPI*)(HANDLE, ULONG, PVOID, ULONG, PULONG);
using NtSetInformationThread_t = NTSTATUS(NTAPI*)(HANDLE, ULONG, PVOID, ULONG);
using NtQueryObject_t = NTSTATUS(NTAPI*)(HANDLE, ULONG, PVOID, ULONG, PULONG);

NtQueryInformationProcess_t pNtQIP = nullptr;
NtSetInformationThread_t pNtSIT = nullptr;
NtQueryObject_t pNtQueryObject = nullptr;

void resolve_nt()
{
    auto* ntdll = li::detail::find_module(LI_HASH("ntdll.dll"));
    if (!ntdll) return;
    pNtQIP = (NtQueryInformationProcess_t)li::detail::find_export(ntdll, LI_HASH("NtQueryInformationProcess"));
    pNtSIT = (NtSetInformationThread_t)li::detail::find_export(ntdll, LI_HASH("NtSetInformationThread"));
    pNtQueryObject = (NtQueryObject_t)li::detail::find_export(ntdll, LI_HASH("NtQueryObject"));
}

void hide_thread()
{
    if (pNtSIT) pNtSIT(LI_FN(kernel32.dll, GetCurrentThread)(), 0x11, nullptr, 0);
}

void patch_debug_functions()
{
    auto* ntdll = li::detail::find_module(LI_HASH("ntdll.dll"));
    if (!ntdll) return;
    auto patch = [](void* addr) {
        if (!addr) return;
        DWORD old;
        if (LI_FN(kernel32.dll, VirtualProtect)(addr, 1, PAGE_EXECUTE_READWRITE, &old))
        {
            *static_cast<unsigned char*>(addr) = 0xC3;
            LI_FN(kernel32.dll, VirtualProtect)(addr, 1, old, &old);
        }
    };
    patch(li::detail::find_export(ntdll, LI_HASH("DbgBreakPoint")));
    patch(li::detail::find_export(ntdll, LI_HASH("DbgUiRemoteBreakin")));
}

const wchar_t* BAN_KEY = L"SOFTWARE\\Microsoft\\DirectX\\UserGpuPreferences";
const wchar_t* BAN_VAL = L"DXGIAdapterLUID";

void report(const char* reason, authclient::WebhookEventType type)
{
    if (!g_client) return;
    try { g_client->reportEvent(type, {{ "reason", reason }, { "loader", "true" }}); }
    catch (...) {}
}

bool process_exists(const wchar_t* name)
{
    HANDLE snap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return false;
    PROCESSENTRY32W pe = {};
    pe.dwSize = sizeof(pe);
    bool found = false;
    if (LI_FN(kernel32.dll, Process32FirstW)(snap, &pe))
    {
        do {
            if (_wcsicmp(pe.szExeFile, name) == 0) { found = true; break; }
        } while (LI_FN(kernel32.dll, Process32NextW)(snap, &pe));
    }
    LI_FN(kernel32.dll, CloseHandle)(snap);
    return found;
}

bool module_loaded(const wchar_t* name)
{
    return LI_FN(kernel32.dll, GetModuleHandleW)(name) != nullptr;
}

struct OBJECT_TYPE_INFORMATION {
    UNICODE_STRING TypeName;
    ULONG Reserved[22];
};

void take_module_snapshot();

} // namespace

namespace
{

// VEH handler — catches int2d and other debug exceptions injectors use
LONG CALLBACK veh_handler(PEXCEPTION_POINTERS ep)
{
    // INT 2D: debugger swallows this, without debugger we get EXCEPTION_BREAKPOINT
    if (ep->ExceptionRecord->ExceptionCode == EXCEPTION_BREAKPOINT)
    {
        #ifdef _M_X64
        ep->ContextRecord->Rip++;
        #else
        ep->ContextRecord->Eip++;
        #endif
        return EXCEPTION_CONTINUE_EXECUTION;
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

// Set our process as a critical process — killing us causes BSOD
// This makes attaching debuggers very risky
void set_critical_process()
{
    using RtlSetProcessIsCritical_t = NTSTATUS(NTAPI*)(BOOLEAN, PBOOLEAN, BOOLEAN);
    auto fn = (RtlSetProcessIsCritical_t)li::detail::find_export(
        li::detail::find_module(LI_HASH("ntdll.dll")),
        LI_HASH("RtlSetProcessIsCritical"));
    // Disabled by default — uncomment to enable (BSOD on kill!)
    // if (fn) fn(TRUE, nullptr, FALSE);
    (void)fn;
}

} // namespace

void init()
{
    resolve_nt();
    hide_thread();
    patch_debug_functions();

    // Install VEH for exception-based anti-debug
    AddVectoredExceptionHandler(1, veh_handler);
    take_module_snapshot();
}

void set_auth_client(authclient::AuthClient* client)
{
    g_client = client;
}

// ===== ANTI-DEBUG =====

DetectionResult check_debugger()
{
    if (LI_FN(kernel32.dll, IsDebuggerPresent)())
        return { true, "IsDebuggerPresent" };

#ifdef _M_X64
    auto peb = reinterpret_cast<const unsigned char*>(__readgsqword(0x60));
    DWORD ntGlobalFlag = *reinterpret_cast<const DWORD*>(peb + 0xBC);
    auto* processHeap = *reinterpret_cast<void* const*>(peb + 0x30);
    DWORD heapFlags = *reinterpret_cast<const DWORD*>(
        static_cast<const unsigned char*>(processHeap) + 0x70);
#else
    auto peb = reinterpret_cast<const unsigned char*>(__readfsdword(0x30));
    DWORD ntGlobalFlag = *reinterpret_cast<const DWORD*>(peb + 0x68);
    auto* processHeap = *reinterpret_cast<void* const*>(peb + 0x18);
    DWORD heapFlags = *reinterpret_cast<const DWORD*>(
        static_cast<const unsigned char*>(processHeap) + 0x40);
#endif

    if (peb[2] != 0) return { true, "PEB.BeingDebugged" };
    if (ntGlobalFlag & 0x70) return { true, "NtGlobalFlag" };
    if (heapFlags & ~2u) return { true, "HeapFlags" };

    return {};
}

DetectionResult check_remote_debugger()
{
    BOOL debugged = FALSE;
    LI_FN(kernel32.dll, CheckRemoteDebuggerPresent)(
        LI_FN(kernel32.dll, GetCurrentProcess)(), &debugged);
    return debugged ? DetectionResult{ true, "RemoteDebugger" } : DetectionResult{};
}

DetectionResult check_nt_debug()
{
    if (!pNtQIP) return {};
    auto proc = LI_FN(kernel32.dll, GetCurrentProcess)();

    DWORD_PTR debugPort = 0;
    if (pNtQIP(proc, 7, &debugPort, sizeof(debugPort), nullptr) == 0 && debugPort != 0)
        return { true, "DebugPort" };

    HANDLE debugObj = nullptr;
    if (pNtQIP(proc, 30, &debugObj, sizeof(debugObj), nullptr) == 0)
        return { true, "DebugObject" };

    DWORD debugFlags = 1;
    if (pNtQIP(proc, 31, &debugFlags, sizeof(debugFlags), nullptr) == 0 && debugFlags == 0)
        return { true, "DebugFlags" };

    return {};
}

DetectionResult check_hardware_breakpoints()
{
    CONTEXT ctx = {};
    ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;
    if (LI_FN(kernel32.dll, GetThreadContext)(
        LI_FN(kernel32.dll, GetCurrentThread)(), &ctx))
    {
        if (ctx.Dr0 || ctx.Dr1 || ctx.Dr2 || ctx.Dr3)
            return { true, "HardwareBreakpoint" };
    }
    return {};
}

DetectionResult check_timing()
{
    // RDTSC
    unsigned long long t1 = __rdtsc();
    volatile int dummy = 0;
    for (int i = 0; i < 100; ++i) dummy += i;
    unsigned long long t2 = __rdtsc();
    if ((t2 - t1) > 10000000) return { true, "RDTSC" };

    // QueryPerformanceCounter
    LARGE_INTEGER freq, c1, c2;
    LI_FN(kernel32.dll, QueryPerformanceFrequency)(&freq);
    LI_FN(kernel32.dll, QueryPerformanceCounter)(&c1);
    LI_FN(kernel32.dll, Sleep)(1);
    LI_FN(kernel32.dll, QueryPerformanceCounter)(&c2);
    double elapsed = (double)(c2.QuadPart - c1.QuadPart) / freq.QuadPart * 1000.0;
    if (elapsed > 100.0) return { true, "QPCTiming" };

    return {};
}

// Exception-based: INT2D raises EXCEPTION_BREAKPOINT only when not debugged
DetectionResult check_exception_based()
{
    __try
    {
        __try
        {
            // OutputDebugStringW with a specific string — if debugger is present
            // the return value from GetLastError differs
            LI_FN(kernel32.dll, SetLastError)(0x1234);
            LI_FN(kernel32.dll, OutputDebugStringW)(L"\x01");
            if (LI_FN(kernel32.dll, GetLastError)() == 0x1234)
                return { true, "OutputDebugString" };
        }
        __except (EXCEPTION_EXECUTE_HANDLER) {}
    }
    __except (EXCEPTION_EXECUTE_HANDLER) {}

    return {};
}

// CloseHandle with invalid handle — debugger catches the exception
DetectionResult check_close_handle()
{
    __try
    {
        LI_FN(kernel32.dll, CloseHandle)(reinterpret_cast<HANDLE>(0xDEADBEEF));
    }
    __except (EXCEPTION_EXECUTE_HANDLER)
    {
        return { true, "CloseHandleTrap" };
    }
    return {};
}

// Check if parent process is suspicious (not explorer/cmd/powershell)
DetectionResult check_parent_process()
{
    if (!pNtQIP) return {};
    struct { PVOID Reserved1; PVOID PebBaseAddress; PVOID Reserved2[2];
             ULONG_PTR UniqueProcessId; PVOID InheritedFromUniqueProcessId; } pbi = {};
    if (pNtQIP(LI_FN(kernel32.dll, GetCurrentProcess)(), 0,
        &pbi, sizeof(pbi), nullptr) != 0)
        return {};

    DWORD parentPid = static_cast<DWORD>(
        reinterpret_cast<ULONG_PTR>(pbi.InheritedFromUniqueProcessId));
    if (parentPid == 0) return {};

    HANDLE parent = LI_FN(kernel32.dll, OpenProcess)(
        PROCESS_QUERY_LIMITED_INFORMATION, FALSE, parentPid);
    if (!parent) return {};

    wchar_t path[MAX_PATH] = {};
    DWORD pathLen = MAX_PATH;
    LI_FN(kernel32.dll, QueryFullProcessImageNameW)(parent, 0, path, &pathLen);
    LI_FN(kernel32.dll, CloseHandle)(parent);

    const wchar_t* name = wcsrchr(path, L'\\');
    name = name ? name + 1 : path;

    // These are normal parents
    if (_wcsicmp(name, XORSTRW(L"explorer.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"cmd.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"powershell.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"pwsh.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"WindowsTerminal.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"conhost.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"svchost.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"RuntimeBroker.exe")) == 0) return {};
    if (_wcsicmp(name, XORSTRW(L"sihost.exe")) == 0) return {};
    if (pathLen == 0) return {};

    // x64dbg, IDA, etc. as parent = launched from debugger
    const wchar_t* badParents[] = {
        XORSTRW(L"x64dbg.exe"), XORSTRW(L"x32dbg.exe"), XORSTRW(L"ollydbg.exe"),
        XORSTRW(L"ida.exe"), XORSTRW(L"ida64.exe"), XORSTRW(L"idaq.exe"),
        XORSTRW(L"idaq64.exe"), XORSTRW(L"windbg.exe"), XORSTRW(L"windbgx.exe"),
        XORSTRW(L"DbgX.Shell.exe"), XORSTRW(L"devenv.exe"),
        XORSTRW(L"cheatengine-x86_64.exe"), XORSTRW(L"cheatengine.exe"),
        XORSTRW(L"ProcessHacker.exe"), XORSTRW(L"SystemInformer.exe"),
    };
    for (auto* bad : badParents)
    {
        if (_wcsicmp(name, bad) == 0)
            return { true, "DebuggerParent" };
    }

    return {};
}

// Scan window titles for debugger/RE tool windows
DetectionResult check_debug_windows()
{
    struct Ctx { bool found; const char* tag; };
    Ctx ctx = { false, nullptr };

    auto callback = [](HWND hwnd, LPARAM lParam) -> BOOL {
        auto* c = reinterpret_cast<Ctx*>(lParam);
        wchar_t title[256] = {};
        GetWindowTextW(hwnd, title, 256);
        if (title[0] == 0) return TRUE;

        _wcslwr_s(title);

        struct { const wchar_t* substr; const char* tag; } patterns[] = {
            { L"x64dbg",          "x64dbg_wnd" },
            { L"x32dbg",          "x32dbg_wnd" },
            { L"ollydbg",         "OllyDbg_wnd" },
            { L"ida -",           "IDA_wnd" },
            { L"ida pro",         "IDA_wnd" },
            { L"ghidra",          "Ghidra_wnd" },
            { L"cheat engine",    "CE_wnd" },
            { L"process hacker",  "PH_wnd" },
            { L"system informer", "SI_wnd" },
            { L"wireshark",       "Wireshark_wnd" },
            { L"fiddler",         "Fiddler_wnd" },
            { L"http debugger",   "HTTPDbg_wnd" },
            { L"scylla",          "Scylla_wnd" },
            { L"megadumper",      "Dumper_wnd" },
            { L"extremedumper",   "Dumper_wnd" },
            { L"windbg",          "WinDbg_wnd" },
            { L"immunity",        "Immunity_wnd" },
            { L"reclass",         "ReClass_wnd" },
            { L"detect it easy",  "DIE_wnd" },
            { L"pe-bear",         "PEBear_wnd" },
            { L"pestudio",        "PEStudio_wnd" },
            { L"api monitor",     "APIMonitor_wnd" },
        };

        for (auto& p : patterns)
        {
            if (wcsstr(title, p.substr))
            {
                c->found = true;
                c->tag = p.tag;
                return FALSE;
            }
        }
        return TRUE;
    };

    EnumWindows(callback, reinterpret_cast<LPARAM>(&ctx));
    if (ctx.found) return { true, ctx.tag };
    return {};
}

// Scan all threads for hardware breakpoints (not just our own)
DetectionResult check_debug_registers_thread_scan()
{
    DWORD pid = LI_FN(kernel32.dll, GetCurrentProcessId)();
    DWORD tid = LI_FN(kernel32.dll, GetCurrentThreadId)();
    HANDLE snap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(TH32CS_SNAPTHREAD, 0);
    if (snap == INVALID_HANDLE_VALUE) return {};

    THREADENTRY32 te = {};
    te.dwSize = sizeof(te);
    if (LI_FN(kernel32.dll, Thread32First)(snap, &te))
    {
        do {
            if (te.th32OwnerProcessID != pid) continue;
            if (te.th32ThreadID == tid) continue;
            HANDLE thread = LI_FN(kernel32.dll, OpenThread)(
                THREAD_GET_CONTEXT | THREAD_QUERY_INFORMATION, FALSE, te.th32ThreadID);
            if (!thread) continue;
            CONTEXT ctx = {};
            ctx.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            if (LI_FN(kernel32.dll, GetThreadContext)(thread, &ctx))
            {
                if (ctx.Dr0 || ctx.Dr1 || ctx.Dr2 || ctx.Dr3)
                {
                    LI_FN(kernel32.dll, CloseHandle)(thread);
                    LI_FN(kernel32.dll, CloseHandle)(snap);
                    return { true, "ThreadHWBP" };
                }
            }
            LI_FN(kernel32.dll, CloseHandle)(thread);
        } while (LI_FN(kernel32.dll, Thread32Next)(snap, &te));
    }
    LI_FN(kernel32.dll, CloseHandle)(snap);
    return {};
}

// Detect if someone suspended our threads (for analysis)
DetectionResult check_suspended_threads()
{
    DWORD pid = LI_FN(kernel32.dll, GetCurrentProcessId)();
    DWORD tid = LI_FN(kernel32.dll, GetCurrentThreadId)();
    HANDLE snap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(TH32CS_SNAPTHREAD, 0);
    if (snap == INVALID_HANDLE_VALUE) return {};

    int suspended = 0;
    int total = 0;
    THREADENTRY32 te = {};
    te.dwSize = sizeof(te);
    if (LI_FN(kernel32.dll, Thread32First)(snap, &te))
    {
        do {
            if (te.th32OwnerProcessID != pid) continue;
            if (te.th32ThreadID == tid) continue;
            ++total;
            HANDLE thread = LI_FN(kernel32.dll, OpenThread)(
                THREAD_SUSPEND_RESUME, FALSE, te.th32ThreadID);
            if (!thread) continue;
            DWORD prev = LI_FN(kernel32.dll, ResumeThread)(thread);
            if (prev > 0)
            {
                // Was suspended — re-suspend to keep state, flag it
                LI_FN(kernel32.dll, SuspendThread)(thread);
                ++suspended;
            }
            LI_FN(kernel32.dll, CloseHandle)(thread);
        } while (LI_FN(kernel32.dll, Thread32Next)(snap, &te));
    }
    LI_FN(kernel32.dll, CloseHandle)(snap);

    if (total > 0 && suspended > 0 && suspended >= total / 2)
        return { true, "ThreadsSuspended" };
    return {};
}

// ===== ANTI-VM / SANDBOX =====

DetectionResult check_vm()
{
    int cpuInfo[4] = {};
    __cpuid(cpuInfo, 1);
    if (cpuInfo[2] & (1 << 31))
    {
        __cpuid(cpuInfo, 0x40000000);
        char vendor[13] = {};
        memcpy(vendor, &cpuInfo[1], 4);
        memcpy(vendor + 4, &cpuInfo[2], 4);
        memcpy(vendor + 8, &cpuInfo[3], 4);
        if (strstr(vendor, "Microsoft") == nullptr)
            return { true, "Hypervisor" };
    }

    HKEY hKey;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE,
        XORSTRW(L"SOFTWARE\\VMware, Inc.\\VMware Tools"), 0, KEY_READ, &hKey) == ERROR_SUCCESS)
    { RegCloseKey(hKey); return { true, "VMware" }; }
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE,
        XORSTRW(L"SOFTWARE\\Oracle\\VirtualBox Guest Additions"), 0, KEY_READ, &hKey) == ERROR_SUCCESS)
    { RegCloseKey(hKey); return { true, "VirtualBox" }; }
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE,
        XORSTRW(L"SYSTEM\\CurrentControlSet\\Services\\VBoxSF"), 0, KEY_READ, &hKey) == ERROR_SUCCESS)
    { RegCloseKey(hKey); return { true, "VBoxService" }; }
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE,
        XORSTRW(L"HARDWARE\\ACPI\\DSDT\\VBOX__"), 0, KEY_READ, &hKey) == ERROR_SUCCESS)
    { RegCloseKey(hKey); return { true, "VBoxACPI" }; }

    if (module_loaded(XORSTRW(L"vmGuestLib.dll")))  return { true, "VMwareModule" };
    if (module_loaded(XORSTRW(L"VBoxHook.dll")))    return { true, "VBoxModule" };
    if (module_loaded(XORSTRW(L"SbieDll.dll")))      return { true, "Sandboxie" };
    if (module_loaded(XORSTRW(L"cmdvrt32.dll")))     return { true, "Comodo" };
    if (module_loaded(XORSTRW(L"cmdvrt64.dll")))     return { true, "Comodo" };
    if (module_loaded(XORSTRW(L"pstorec.dll")))      return { true, "Sandbox" };
    if (module_loaded(XORSTRW(L"avghookx.dll")))     return { true, "AVGHook" };
    if (module_loaded(XORSTRW(L"avghooka.dll")))     return { true, "AVGHook" };
    if (module_loaded(XORSTRW(L"snxhk.dll")))        return { true, "Avast" };

    // VM processes
    if (process_exists(XORSTRW(L"vmtoolsd.exe")))    return { true, "VMwareTools" };
    if (process_exists(XORSTRW(L"vmwaretray.exe")))   return { true, "VMwareTray" };
    if (process_exists(XORSTRW(L"VBoxService.exe")))  return { true, "VBoxService" };
    if (process_exists(XORSTRW(L"VBoxTray.exe")))     return { true, "VBoxTray" };
    if (process_exists(XORSTRW(L"xenservice.exe")))   return { true, "XenVM" };
    if (process_exists(XORSTRW(L"qemu-ga.exe")))      return { true, "QEMU" };

    // Low spec VM
    MEMORYSTATUSEX mem = {};
    mem.dwLength = sizeof(mem);
    if (LI_FN(kernel32.dll, GlobalMemoryStatusEx)(&mem))
    {
        if (mem.ullTotalPhys < 2ULL * 1024 * 1024 * 1024)
            return { true, "LowRAM" };
    }
    SYSTEM_INFO si = {};
    LI_FN(kernel32.dll, GetSystemInfo)(&si);
    if (si.dwNumberOfProcessors < 2) return { true, "SingleCore" };

    // Disk size check — VMs usually have tiny disks
    ULARGE_INTEGER diskFree = {}, diskTotal = {};
    if (LI_FN(kernel32.dll, GetDiskFreeSpaceExW)(
        XORSTRW(L"C:\\"), &diskFree, &diskTotal, nullptr))
    {
        if (diskTotal.QuadPart < 50ULL * 1024 * 1024 * 1024)
            return { true, "SmallDisk" };
    }

    return {};
}

// ===== ANTI-TAMPER =====

DetectionResult check_tamper()
{
    auto* self = reinterpret_cast<unsigned char*>(
        LI_FN(kernel32.dll, GetModuleHandleW)(nullptr));
    auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(self);
    auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(self + dos->e_lfanew);
    auto* sec = IMAGE_FIRST_SECTION(nt);

    for (WORD i = 0; i < nt->FileHeader.NumberOfSections; ++i)
    {
        if (memcmp(sec[i].Name, ".text", 5) == 0)
        {
            MEMORY_BASIC_INFORMATION mbi = {};
            LI_FN(kernel32.dll, VirtualQuery)(
                self + sec[i].VirtualAddress, &mbi, sizeof(mbi));
            if (mbi.Protect == PAGE_EXECUTE_READWRITE)
                return { true, "TextWritable" };

            // INT3 scan
            auto* code = self + sec[i].VirtualAddress;
            DWORD size = sec[i].Misc.VirtualSize;
            int bp = 0;
            for (DWORD j = 0; j < size && j < 0x100000; ++j)
            {
                if (code[j] == 0xCC) ++bp;
                if (bp > 5) return { true, "INT3Breakpoints" };
            }
            break;
        }
    }

    // Hook check on critical APIs
    auto hookCheck = [](void* mod, unsigned long hash) -> bool {
        auto* addr = static_cast<const unsigned char*>(
            li::detail::find_export(mod, hash));
        if (!addr) return false;
        return addr[0] == 0xE9 || addr[0] == 0xEB ||
               (addr[0] == 0xFF && addr[1] == 0x25) ||
               (addr[0] == 0x48 && addr[1] == 0xB8); // mov rax, imm64 (trampoline)
    };

    auto* k32 = li::detail::find_module(LI_HASH("kernel32.dll"));
    auto* ntdll = li::detail::find_module(LI_HASH("ntdll.dll"));
    if (k32)
    {
        if (hookCheck(k32, LI_HASH("VirtualAlloc")))       return { true, "HookVA" };
        if (hookCheck(k32, LI_HASH("VirtualProtect")))     return { true, "HookVP" };
        if (hookCheck(k32, LI_HASH("CreateRemoteThread"))) return { true, "HookCRT" };
        if (hookCheck(k32, LI_HASH("WriteProcessMemory"))) return { true, "HookWPM" };
        if (hookCheck(k32, LI_HASH("ReadProcessMemory")))  return { true, "HookRPM" };
        if (hookCheck(k32, LI_HASH("OpenProcess")))        return { true, "HookOP" };
    }
    if (ntdll)
    {
        if (hookCheck(ntdll, LI_HASH("NtWriteVirtualMemory")))    return { true, "HookNtWVM" };
        if (hookCheck(ntdll, LI_HASH("NtAllocateVirtualMemory"))) return { true, "HookNtAVM" };
        if (hookCheck(ntdll, LI_HASH("NtCreateThreadEx")))        return { true, "HookNtCTE" };
        if (hookCheck(ntdll, LI_HASH("NtReadVirtualMemory")))     return { true, "HookNtRVM" };
    }

    return {};
}

// ===== BLACKLISTS =====

DetectionResult check_blacklisted_processes()
{
    struct { const wchar_t* name; const char* tag; } procs[] = {
        { XORSTRW(L"x64dbg.exe"),              "x64dbg" },
        { XORSTRW(L"x32dbg.exe"),              "x32dbg" },
        { XORSTRW(L"ollydbg.exe"),             "OllyDbg" },
        { XORSTRW(L"ida.exe"),                 "IDA" },
        { XORSTRW(L"ida64.exe"),               "IDA64" },
        { XORSTRW(L"idaq.exe"),                "IDAq" },
        { XORSTRW(L"idaq64.exe"),              "IDAq64" },
        { XORSTRW(L"idat.exe"),                "IDABatch" },
        { XORSTRW(L"idat64.exe"),              "IDABatch" },
        { XORSTRW(L"radare2.exe"),             "r2" },
        { XORSTRW(L"r2.exe"),                  "r2" },
        { XORSTRW(L"cutter.exe"),              "Cutter" },
        { XORSTRW(L"ghidra.exe"),              "Ghidra" },
        { XORSTRW(L"ghidraRun.exe"),           "Ghidra" },
        { XORSTRW(L"Binary Ninja.exe"),        "BinNinja" },
        { XORSTRW(L"binaryninja.exe"),         "BinNinja" },
        { XORSTRW(L"cheatengine-x86_64.exe"),  "CE" },
        { XORSTRW(L"cheatengine.exe"),         "CE" },
        { XORSTRW(L"HxD.exe"),                 "HxD" },
        { XORSTRW(L"010Editor.exe"),           "010Editor" },
        { XORSTRW(L"ReClass.NET.exe"),         "ReClass" },
        { XORSTRW(L"scylla.exe"),              "Scylla" },
        { XORSTRW(L"scylla_x64.exe"),          "Scylla" },
        { XORSTRW(L"dnSpy.exe"),               "dnSpy" },
        { XORSTRW(L"de4dot.exe"),              "de4dot" },
        { XORSTRW(L"ProcessHacker.exe"),       "PH" },
        { XORSTRW(L"SystemInformer.exe"),      "SI" },
        { XORSTRW(L"Fiddler.exe"),             "Fiddler" },
        { XORSTRW(L"Wireshark.exe"),           "Wireshark" },
        { XORSTRW(L"dumpcap.exe"),             "Wireshark" },
        { XORSTRW(L"HTTPDebugger.exe"),        "HTTPDbg" },
        { XORSTRW(L"MegaDumper.exe"),          "Dumper" },
        { XORSTRW(L"ExtremeDumper.exe"),       "Dumper" },
        { XORSTRW(L"pe-bear.exe"),             "PEBear" },
        { XORSTRW(L"pestudio.exe"),            "PEStudio" },
        { XORSTRW(L"die.exe"),                 "DIE" },
        { XORSTRW(L"diel.exe"),                "DIE" },
        { XORSTRW(L"windbg.exe"),              "WinDbg" },
        { XORSTRW(L"windbgx.exe"),             "WinDbg" },
        { XORSTRW(L"DbgX.Shell.exe"),          "WinDbg" },
        { XORSTRW(L"immunitydebugger.exe"),    "ImmDbg" },
        { XORSTRW(L"apimonitor-x64.exe"),      "APIMon" },
        { XORSTRW(L"apimonitor-x86.exe"),      "APIMon" },
        { XORSTRW(L"procmon.exe"),             "ProcMon" },
        { XORSTRW(L"procmon64.exe"),           "ProcMon" },
        { XORSTRW(L"procexp.exe"),             "ProcExp" },
        { XORSTRW(L"procexp64.exe"),           "ProcExp" },
        { XORSTRW(L"tcpview.exe"),             "TCPView" },
        { XORSTRW(L"autoruns.exe"),            "Autoruns" },
        { XORSTRW(L"jd-gui.exe"),              "JDGui" },
        { XORSTRW(L"bytecodeviewer.exe"),      "BCV" },
        { XORSTRW(L"Recaf.exe"),               "Recaf" },
        { XORSTRW(L"cfr.exe"),                 "CFR" },
    };

    for (auto& p : procs)
        if (process_exists(p.name)) return { true, p.tag };
    return {};
}

DetectionResult check_blacklisted_modules()
{
    struct { const wchar_t* name; const char* tag; } mods[] = {
        { XORSTRW(L"HTTPDebuggerBrowser.dll"),   "HTTPDbg" },
        { XORSTRW(L"FiddlerCore4.dll"),          "Fiddler" },
        { XORSTRW(L"RestSharp.dll"),             "RestSharp" },
        { XORSTRW(L"Titanium.Web.Proxy.dll"),    "TitanProxy" },
        { XORSTRW(L"SharpPcap.dll"),             "SharpPcap" },
        { XORSTRW(L"DotNetDetour.dll"),          "Detour" },
        { XORSTRW(L"EasyHook32.dll"),            "EasyHook" },
        { XORSTRW(L"EasyHook64.dll"),            "EasyHook" },
        { XORSTRW(L"minhook.x64.dll"),           "MinHook" },
        { XORSTRW(L"minhook.x86.dll"),           "MinHook" },
        { XORSTRW(L"frida-agent.dll"),           "Frida" },
        { XORSTRW(L"frida-gadget.dll"),          "Frida" },
        { XORSTRW(L"vehdebug-x86_64.dll"),      "VEHDbg" },
        { XORSTRW(L"ScyllaHide.dll"),            "ScyllaHide" },
        { XORSTRW(L"HookShark.dll"),             "HookShark" },
        { XORSTRW(L"TitanHide.dll"),             "TitanHide" },
    };

    for (auto& m : mods)
        if (module_loaded(m.name)) return { true, m.tag };
    return {};
}

// ===== ANTI-INJECTION =====

namespace
{

// Snapshot of modules loaded at startup — anything new is injected
std::vector<HMODULE> g_moduleSnapshot;
int g_initialThreadCount = 0;

void take_module_snapshot()
{
    g_moduleSnapshot.clear();
    DWORD pid = LI_FN(kernel32.dll, GetCurrentProcessId)();
    HANDLE snap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(
        TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
    if (snap == INVALID_HANDLE_VALUE) return;
    MODULEENTRY32W me = {};
    me.dwSize = sizeof(me);
    if (LI_FN(kernel32.dll, Module32FirstW)(snap, &me))
    {
        do {
            g_moduleSnapshot.push_back(reinterpret_cast<HMODULE>(me.modBaseAddr));
        } while (LI_FN(kernel32.dll, Module32NextW)(snap, &me));
    }
    LI_FN(kernel32.dll, CloseHandle)(snap);

    // Count initial threads
    g_initialThreadCount = 0;
    HANDLE tsnap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(TH32CS_SNAPTHREAD, 0);
    if (tsnap == INVALID_HANDLE_VALUE) return;
    THREADENTRY32 te = {};
    te.dwSize = sizeof(te);
    if (LI_FN(kernel32.dll, Thread32First)(tsnap, &te))
    {
        do {
            if (te.th32OwnerProcessID == pid) ++g_initialThreadCount;
        } while (LI_FN(kernel32.dll, Thread32Next)(tsnap, &te));
    }
    LI_FN(kernel32.dll, CloseHandle)(tsnap);
}

} // namespace

void snapshot_modules()
{
    take_module_snapshot();
}

DetectionResult check_injection()
{
    DWORD pid = LI_FN(kernel32.dll, GetCurrentProcessId)();

    // 1. Check for new modules that weren't in our snapshot
    if (!g_moduleSnapshot.empty())
    {
        HANDLE snap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(
            TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
        if (snap != INVALID_HANDLE_VALUE)
        {
            MODULEENTRY32W me = {};
            me.dwSize = sizeof(me);
            if (LI_FN(kernel32.dll, Module32FirstW)(snap, &me))
            {
                do {
                    auto* base = reinterpret_cast<HMODULE>(me.modBaseAddr);
                    bool known = false;
                    for (auto& m : g_moduleSnapshot)
                    {
                        if (m == base) { known = true; break; }
                    }
                    if (!known)
                    {
                        // New module appeared — possible DLL injection
                        // Allow system DLLs that get lazy-loaded (from system32/syswow64)
                        wchar_t sysDir[MAX_PATH] = {};
                        LI_FN(kernel32.dll, GetSystemDirectoryW)(sysDir, MAX_PATH);
                        bool isSystem = (wcsstr(me.szExePath, sysDir) == me.szExePath);
                        if (!isSystem)
                        {
                            LI_FN(kernel32.dll, CloseHandle)(snap);
                            return { true, "InjectedModule" };
                        }
                        // Add to snapshot so we don't re-flag system lazy loads
                        g_moduleSnapshot.push_back(base);
                    }
                } while (LI_FN(kernel32.dll, Module32NextW)(snap, &me));
            }
            LI_FN(kernel32.dll, CloseHandle)(snap);
        }
    }

    // 2. Scan for suspicious RWX memory regions (shellcode injection)
    MEMORY_BASIC_INFORMATION mbi = {};
    auto* addr = static_cast<unsigned char*>(nullptr);
    int rwxCount = 0;
    auto* self = reinterpret_cast<unsigned char*>(
        LI_FN(kernel32.dll, GetModuleHandleW)(nullptr));

    while (LI_FN(kernel32.dll, VirtualQuery)(addr, &mbi, sizeof(mbi)))
    {
        if (mbi.State == MEM_COMMIT &&
            mbi.Protect == PAGE_EXECUTE_READWRITE &&
            mbi.Type == MEM_PRIVATE)
        {
            // Ignore our own module's sections
            bool ours = (addr >= self && addr < self + 0x1000000);
            if (!ours) ++rwxCount;
        }
        addr += mbi.RegionSize;
        if (addr < static_cast<unsigned char*>(mbi.BaseAddress))
            break; // overflow
    }
    if (rwxCount > 2) return { true, "RWXShellcode" };

    // 3. Unexpected thread count spike (remote thread creation)
    if (g_initialThreadCount > 0)
    {
        int current = 0;
        HANDLE tsnap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(TH32CS_SNAPTHREAD, 0);
        if (tsnap != INVALID_HANDLE_VALUE)
        {
            THREADENTRY32 te = {};
            te.dwSize = sizeof(te);
            if (LI_FN(kernel32.dll, Thread32First)(tsnap, &te))
            {
                do {
                    if (te.th32OwnerProcessID == pid) ++current;
                } while (LI_FN(kernel32.dll, Thread32Next)(tsnap, &te));
            }
            LI_FN(kernel32.dll, CloseHandle)(tsnap);

            // If thread count jumped by more than 5 unexpectedly
            if (current > g_initialThreadCount + 5)
                return { true, "ThreadSpike" };
        }
    }

    // 4. Check for APC injection — look for threads with unusual start addresses
    {
        HANDLE tsnap = LI_FN(kernel32.dll, CreateToolhelp32Snapshot)(TH32CS_SNAPTHREAD, 0);
        if (tsnap != INVALID_HANDLE_VALUE)
        {
            DWORD myTid = LI_FN(kernel32.dll, GetCurrentThreadId)();
            THREADENTRY32 te = {};
            te.dwSize = sizeof(te);
            if (LI_FN(kernel32.dll, Thread32First)(tsnap, &te))
            {
                do {
                    if (te.th32OwnerProcessID != pid) continue;
                    if (te.th32ThreadID == myTid) continue;
                    HANDLE th = LI_FN(kernel32.dll, OpenThread)(
                        THREAD_QUERY_INFORMATION, FALSE, te.th32ThreadID);
                    if (!th) continue;
                    // NtQueryInformationThread ThreadQuerySetWin32StartAddress = 9
                    if (pNtQIP)
                    {
                        PVOID startAddr = nullptr;
                        using NtQIT_t = NTSTATUS(NTAPI*)(HANDLE, ULONG, PVOID, ULONG, PULONG);
                        auto pNtQIT = (NtQIT_t)li::detail::find_export(
                            li::detail::find_module(LI_HASH("ntdll.dll")),
                            LI_HASH("NtQueryInformationThread"));
                        if (pNtQIT && pNtQIT(th, 9, &startAddr, sizeof(startAddr), nullptr) == 0)
                        {
                            // Check if start address is in a known module
                            MEMORY_BASIC_INFORMATION tmbi = {};
                            if (LI_FN(kernel32.dll, VirtualQuery)(startAddr, &tmbi, sizeof(tmbi)))
                            {
                                if (tmbi.Type == MEM_PRIVATE && tmbi.State == MEM_COMMIT)
                                {
                                    // Thread started from private (non-image) memory = shellcode
                                    LI_FN(kernel32.dll, CloseHandle)(th);
                                    LI_FN(kernel32.dll, CloseHandle)(tsnap);
                                    return { true, "ShellcodeThread" };
                                }
                            }
                        }
                    }
                    LI_FN(kernel32.dll, CloseHandle)(th);
                } while (LI_FN(kernel32.dll, Thread32Next)(tsnap, &te));
            }
            LI_FN(kernel32.dll, CloseHandle)(tsnap);
        }
    }

    return {};
}

// ===== BAN =====

DetectionResult check_banned()
{
    HKEY hKey;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, BAN_KEY, 0, KEY_READ, &hKey) != ERROR_SUCCESS)
        return {};
    DWORD val = 0, size = sizeof(val);
    bool banned = RegQueryValueExW(hKey, BAN_VAL, nullptr, nullptr,
        reinterpret_cast<BYTE*>(&val), &size) == ERROR_SUCCESS && val == 0xDEAD;
    RegCloseKey(hKey);
    return banned ? DetectionResult{ true, "Banned" } : DetectionResult{};
}

void ban()
{
    HKEY hKey;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, BAN_KEY, 0, nullptr,
        0, KEY_WRITE, nullptr, &hKey, nullptr) == ERROR_SUCCESS)
    {
        DWORD val = 0xDEAD;
        RegSetValueExW(hKey, BAN_VAL, 0, REG_DWORD,
            reinterpret_cast<const BYTE*>(&val), sizeof(val));
        RegCloseKey(hKey);
    }
}

void erase_pe_headers()
{
    auto* self = reinterpret_cast<unsigned char*>(
        LI_FN(kernel32.dll, GetModuleHandleW)(nullptr));
    DWORD old;
    if (LI_FN(kernel32.dll, VirtualProtect)(self, 4096, PAGE_READWRITE, &old))
        SecureZeroMemory(self, 4096);
}

// ===== ACTIONS =====

void on_detected(const char* reason)
{
    auto type = authclient::WebhookEventType::SUSPICIOUS_ACTIVITY;
    if (strstr(reason, "Debug") || strstr(reason, "Breakpoint") || strstr(reason, "HWBP") ||
        strstr(reason, "PEB") || strstr(reason, "RDTSC") || strstr(reason, "Tick") ||
        strstr(reason, "QPC") || strstr(reason, "NtGlobal") || strstr(reason, "Port") ||
        strstr(reason, "Heap") || strstr(reason, "Object") || strstr(reason, "Flags") ||
        strstr(reason, "CloseHandle") || strstr(reason, "OutputDebug") ||
        strstr(reason, "Remote") || strstr(reason, "Suspended") || strstr(reason, "Parent"))
        type = authclient::WebhookEventType::DEBUG_DETECTED;
    else if (strstr(reason, "Hook") || strstr(reason, "Text") ||
             strstr(reason, "INT3") || strstr(reason, "Writable"))
        type = authclient::WebhookEventType::TAMPER_DETECTED;
    else if (strstr(reason, "Injected") || strstr(reason, "RWX") ||
             strstr(reason, "Shellcode") || strstr(reason, "ThreadSpike"))
        type = authclient::WebhookEventType::INJECTION_DETECTED;
    else if (strstr(reason, "x64dbg") || strstr(reason, "x32dbg") || strstr(reason, "IDA") ||
             strstr(reason, "Ghidra") || strstr(reason, "Scylla") || strstr(reason, "Dumper") ||
             strstr(reason, "CE") || strstr(reason, "dnSpy") || strstr(reason, "ReClass") ||
             strstr(reason, "WinDbg") || strstr(reason, "OllyDbg") || strstr(reason, "PEBear") ||
             strstr(reason, "DIE") || strstr(reason, "PEStudio") || strstr(reason, "Frida") ||
             strstr(reason, "BinNinja") || strstr(reason, "Cutter") || strstr(reason, "r2") ||
             strstr(reason, "wnd"))
        type = authclient::WebhookEventType::CRACK_ATTEMPT;

    report(reason, type);
    ban();
    erase_pe_headers();
    LI_FN(kernel32.dll, TerminateProcess)(
        LI_FN(kernel32.dll, GetCurrentProcess)(), 0);
}

// ===== CHECK ALL =====

bool check_all()
{
    DetectionResult checks[] = {
        check_banned(),
        check_debugger(),
        check_remote_debugger(),
        check_nt_debug(),
        check_hardware_breakpoints(),
        check_timing(),
        check_exception_based(),
        check_close_handle(),
        check_parent_process(),
        check_vm(),
        check_tamper(),
        check_blacklisted_processes(),
        check_blacklisted_modules(),
        check_debug_windows(),
        check_debug_registers_thread_scan(),
        check_suspended_threads(),
        check_injection(),
    };

    for (auto& r : checks)
    {
        if (r.detected)
        {
            on_detected(r.reason);
            return false;
        }
    }
    return true;
}

// ===== WATCHDOG =====

void start_watchdog()
{
    if (g_watchdogRunning.exchange(true)) return;

    g_watchdogThread = std::thread([] {
        // Hide watchdog thread from debugger too
        if (pNtSIT) pNtSIT(LI_FN(kernel32.dll, GetCurrentThread)(), 0x11, nullptr, 0);

        while (g_watchdogRunning)
        {
            // Stagger checks randomly so pattern isn't predictable
            LI_FN(kernel32.dll, Sleep)(1500 + (__rdtsc() & 0x3FF));
            check_all();
        }
    });
    g_watchdogThread.detach();
}

} // namespace mindless::protection
