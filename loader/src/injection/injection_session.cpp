#include "injection_session.hpp"
#include "manual_map.hpp"
#include <TlHelp32.h>
#include <cwchar>

namespace mindless
{

namespace
{

bool target_is_x64(HANDLE process)
{
    using is_wow64_process2_fn = BOOL(WINAPI*)(HANDLE, USHORT*, USHORT*);
    auto isWow64Process2 = (is_wow64_process2_fn)GetProcAddress(
        GetModuleHandleW(L"kernel32.dll"), "IsWow64Process2");
    if (isWow64Process2)
    {
        USHORT processMachine = IMAGE_FILE_MACHINE_UNKNOWN;
        USHORT nativeMachine = IMAGE_FILE_MACHINE_UNKNOWN;
        if (!isWow64Process2(process, &processMachine, &nativeMachine)) return false;
        return processMachine == IMAGE_FILE_MACHINE_UNKNOWN
            && nativeMachine == IMAGE_FILE_MACHINE_AMD64;
    }
    BOOL wow64 = FALSE;
    if (!IsWow64Process(process, &wow64)) return false;
    return !wow64 && sizeof(void*) == 8;
}

} // namespace

InjectionSession::~InjectionSession()
{
    if (injectThread_.joinable()) injectThread_.join();
}

bool InjectionSession::start(uint32_t processId, const void* dllData, size_t dllSize)
{
    if (phase_ != InjectionPhase::Idle) return false;

    targetProcessId_ = processId;
    phase_ = InjectionPhase::Injecting;
    status_ = "Injecting";

    if (!validate_target())
    {
        fail("Could not connect to Minecraft",
             "Keep the selected client open, then launch Mindless again.");
        return false;
    }
    if (!validate_session())
    {
        fail("The selected client session is not valid",
             "Select a Minecraft process running in your Windows session.");
        return false;
    }
    if (dllData && dllSize > 0)
    {
        dllData_ = dllData;
        dllSize_ = static_cast<DWORD>(dllSize);
    }
    else
    {
        fail("No client payload available", "Authentication may have failed.");
        return false;
    }

    injectDone_ = false;
    injectThread_ = std::thread([this] {
        injectSuccess_ = inject_remote();
        injectDone_ = true;
    });
    return true;
}

void InjectionSession::reset()
{
    if (injectThread_.joinable()) injectThread_.join();

    phase_ = InjectionPhase::Idle;
    status_ = "Idle";
    solution_.clear();
    injectError_.clear();
    injectDone_ = false;
    injectSuccess_ = false;
    dllData_ = nullptr;
    dllSize_ = 0;
}

bool InjectionSession::inject_remote()
{
    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION
                    | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
                    FALSE, targetProcessId_);
    if (!process)
    {
        injectError_ = "Allow Mindless through Windows Security, then try again.";
        return false;
    }
    if (!target_is_x64(process))
    {
        CloseHandle(process);
        injectError_ = "Restart Lunar at its main menu, then try again.";
        return false;
    }

    if (!manual_map_inject(process, targetProcessId_, dllData_, dllSize_))
    {
        CloseHandle(process);
        injectError_ = "Could not map Mindless into the target process.";
        return false;
    }

    CloseHandle(process);
    return true;
}

void InjectionSession::tick()
{
    if (phase_ != InjectionPhase::Injecting) return;
    if (!injectDone_) return;

    if (injectThread_.joinable()) injectThread_.join();

    if (injectSuccess_)
    {
        phase_ = InjectionPhase::Complete;
        status_ = "Ready";
    }
    else
    {
        fail("Could not load Mindless", injectError_);
    }
}

float InjectionSession::progress() const
{
    switch (phase_)
    {
    case InjectionPhase::Idle:      return 0.0f;
    case InjectionPhase::Injecting: return 0.5f;
    case InjectionPhase::Complete:  return 1.0f;
    case InjectionPhase::Failed:    return 0.0f;
    }
    return 0.0f;
}

bool InjectionSession::validate_target() const
{
    HANDLE target = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE,
                                targetProcessId_);
    if (!target) return false;
    DWORD exitCode = 0;
    bool valid = GetExitCodeProcess(target, &exitCode) && exitCode == STILL_ACTIVE;
    CloseHandle(target);
    return valid;
}

bool InjectionSession::validate_session() const
{
    DWORD targetSession = 0;
    DWORD currentSession = 0;
    if (!ProcessIdToSessionId(targetProcessId_, &targetSession) ||
        !ProcessIdToSessionId(GetCurrentProcessId(), &currentSession) ||
        targetSession != currentSession)
        return false;

    HANDLE target = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE,
                                targetProcessId_);
    if (!target) return false;
    wchar_t path[MAX_PATH] = {};
    DWORD length = _countof(path);
    bool queried = QueryFullProcessImageNameW(target, 0, path, &length) == TRUE;
    CloseHandle(target);
    if (!queried) return false;
    const wchar_t* name = std::wcsrchr(path, L'\\');
    name = name ? name + 1 : path;
    return _wcsicmp(name, L"javaw.exe") == 0 || _wcsicmp(name, L"java.exe") == 0;
}

void InjectionSession::fail(std::string status, std::string solution)
{
    phase_ = InjectionPhase::Failed;
    status_ = std::move(status);
    solution_ = std::move(solution);
}

} // namespace mindless
