#include "injection_session.hpp"
#include "manual_map.hpp"
#include "auth/xorstr.hpp"
#include <TlHelp32.h>
#include <cstdarg>
#include <cstdio>
#include <cwchar>

namespace mindless
{

namespace
{

void inj_log(const char* format, ...)
{
    char message[512];
    char line[640];
    va_list arguments;
    va_start(arguments, format);
    _vsnprintf_s(message, sizeof(message), _TRUNCATE, format, arguments);
    va_end(arguments);
    _snprintf_s(line, sizeof(line), _TRUNCATE, "[Mindless] Inject: %s\n", message);
    OutputDebugStringA(line);
}

bool target_is_x64(HANDLE process)
{
    using is_wow64_process2_fn = BOOL(WINAPI*)(HANDLE, USHORT*, USHORT*);
    auto isWow64Process2 = (is_wow64_process2_fn)GetProcAddress(
        GetModuleHandleW(XORSTRW(L"kernel32.dll")), XORSTR("IsWow64Process2"));
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
    close_progress_channel();
}

bool InjectionSession::start(uint32_t processId, const void* dllData, size_t dllSize)
{
    if (phase_ != InjectionPhase::Idle)
    {
        inj_log("start ignored, phase is not Idle");
        return false;
    }

    inj_log("start pid=%lu payload=%zu bytes", (unsigned long)processId, dllSize);
    targetProcessId_ = processId;
    phase_ = InjectionPhase::Injecting;
    status_ = "Connecting to Minecraft";
    progress_ = 0.02f;
    startedAt_ = std::chrono::steady_clock::now();

    if (!validate_target())
    {
        inj_log("validate_target failed, err=%lu", GetLastError());
        fail("Could not connect to Minecraft",
             "Keep the selected client open, then launch Mindless again.");
        return false;
    }
    if (!validate_session())
    {
        inj_log("validate_session failed, err=%lu", GetLastError());
        fail("The selected client session is not valid",
             "Select a Minecraft process running in your Windows session.");
        return false;
    }
    if (!open_progress_channel())
    {
        inj_log("open_progress_channel failed, err=%lu", GetLastError());
        fail("Could not create the loading channel",
             "Close the loader and Minecraft, then try again.");
        return false;
    }
    if (dllData && dllSize > 0)
    {
        const auto* bytes = static_cast<const uint8_t*>(dllData);
        dllBytes_.assign(bytes, bytes + dllSize);
    }
    else
    {
        inj_log("no payload handed to start()");
        fail("No client payload available", "Authentication may have failed.");
        return false;
    }

    injectDone_ = false;
    inj_log("preflight ok, spawning inject thread");
    injectThread_ = std::thread([this] {
        injectSuccess_ = inject_remote();
        injectDone_ = true;
    });
    return true;
}

void InjectionSession::reset()
{
    if (injectThread_.joinable()) injectThread_.join();
    close_progress_channel();

    phase_ = InjectionPhase::Idle;
    status_ = "Idle";
    solution_.clear();
    injectError_.clear();
    injectDone_ = false;
    injectSuccess_ = false;
    progress_ = 0.0f;
    if (!dllBytes_.empty())
        SecureZeroMemory(dllBytes_.data(), dllBytes_.size());
    dllBytes_.clear();
    dllBytes_.shrink_to_fit();
}

bool InjectionSession::inject_remote()
{
    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION
                    | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
                    FALSE, targetProcessId_);
    if (!process)
    {
        inj_log("OpenProcess(%lu) failed, err=%lu",
            (unsigned long)targetProcessId_, GetLastError());
        injectError_ = "Allow Mindless through Windows Security, then try again.";
        return false;
    }
    if (!target_is_x64(process))
    {
        inj_log("target is not native x64");
        CloseHandle(process);
        injectError_ = "Restart Lunar at its main menu, then try again.";
        return false;
    }
    inj_log("target opened, entering manual map");

    if (!manual_map_inject(process, targetProcessId_, dllBytes_.data(), dllBytes_.size()))
    {
        inj_log("manual_map_inject returned false");
        CloseHandle(process);
        injectError_ = "Could not map Mindless into the target process.";
        return false;
    }

    inj_log("manual map complete, waiting for native progress");
    CloseHandle(process);
    return true;
}

void InjectionSession::tick()
{
    if (phase_ != InjectionPhase::Injecting) return;
    poll_progress();

    if (phase_ != InjectionPhase::Injecting) return;
    if (!validate_target())
    {
        inj_log("target vanished during injection");
        fail("Minecraft closed while loading",
             "Keep Minecraft open until Mindless finishes loading.");
        return;
    }

    if (std::chrono::steady_clock::now() - startedAt_ > std::chrono::seconds(90))
    {
        inj_log("90s timeout, native never signalled progress");
        fail("Mindless took too long to start",
             "Restart Minecraft and try again. Check mindless-native.log if it repeats.");
        return;
    }

    if (!injectDone_) return;

    if (injectThread_.joinable()) injectThread_.join();

    if (!injectSuccess_)
    {
        fail("Could not load Mindless", injectError_);
    }
}

float InjectionSession::progress() const
{
    switch (phase_)
    {
    case InjectionPhase::Idle:      return 0.0f;
    case InjectionPhase::Injecting: return progress_;
    case InjectionPhase::Complete:  return 1.0f;
    case InjectionPhase::Failed:    return 0.0f;
    }
    return 0.0f;
}

bool InjectionSession::open_progress_channel()
{
    close_progress_channel();
    std::wstring name = auth_section_name(targetProcessId_);
    progressSection_ = OpenFileMappingW(FILE_MAP_READ, FALSE, name.c_str());
    if (!progressSection_) return false;
    progressView_ = static_cast<const AuthSharedData*>(MapViewOfFile(
        progressSection_, FILE_MAP_READ, 0, 0, sizeof(AuthSharedData)));
    if (!progressView_)
    {
        CloseHandle(progressSection_);
        progressSection_ = nullptr;
        return false;
    }
    return true;
}

void InjectionSession::close_progress_channel()
{
    if (progressView_)
    {
        UnmapViewOfFile(progressView_);
        progressView_ = nullptr;
    }
    if (progressSection_)
    {
        CloseHandle(progressSection_);
        progressSection_ = nullptr;
    }
}

void InjectionSession::poll_progress()
{
    if (!progressView_) return;

    LONG before = progressView_->progress_sequence;
    if ((before & 1) != 0) return;
    MemoryBarrier();
    LONG state = progressView_->progress_state;
    LONG milli = progressView_->progress_milli;
    LONG error = progressView_->error_code;
    char status[sizeof(progressView_->progress_status)] = {};
    strncpy_s(status, sizeof(status), progressView_->progress_status, _TRUNCATE);
    MemoryBarrier();
    LONG after = progressView_->progress_sequence;
    if (before != after || (after & 1) != 0) return;

    if (milli >= 0 && milli <= 1000) progress_ = static_cast<float>(milli) / 1000.0f;
    if (status[0] != '\0') status_ = status;

    if (state == MINDLESS_PROGRESS_COMPLETE)
    {
        inj_log("native reported COMPLETE");
        phase_ = InjectionPhase::Complete;
        status_ = "Ready";
        progress_ = 1.0f;
        close_progress_channel();
    }
    else if (state == MINDLESS_PROGRESS_FAILED)
    {
        inj_log("native reported FAILED, error=%ld status=%s", error, status);
        std::string stage = status[0] == '\0' ? "native bootstrap" : status;
        if (stage.size() > 72)
        {
            stage.resize(69);
            stage += "...";
        }
        fail("Mindless failed to start",
             "Stopped during " + stage + " (error " + std::to_string(error) + "). Restart Minecraft and try again.");
        close_progress_channel();
    }
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
    return _wcsicmp(name, XORSTRW(L"javaw.exe")) == 0 || _wcsicmp(name, XORSTRW(L"java.exe")) == 0;
}

void InjectionSession::fail(std::string status, std::string solution)
{
    inj_log("FAILED: %s | %s", status.c_str(), solution.c_str());
    close_progress_channel();
    phase_ = InjectionPhase::Failed;
    status_ = std::move(status);
    solution_ = std::move(solution);
}

} // namespace mindless
