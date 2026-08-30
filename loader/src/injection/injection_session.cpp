#include "injection_session.hpp"
#include "resource.h"
#include <TlHelp32.h>
#include <cstdio>
#include <cwchar>
#include <vector>

namespace mindless
{

namespace
{

bool write_resource(int resourceId, const std::wstring& path)
{
    HMODULE module = GetModuleHandleW(nullptr);
    HRSRC resource = FindResourceW(module, MAKEINTRESOURCEW(resourceId), RT_RCDATA);
    if (!resource) return false;

    HGLOBAL loaded = LoadResource(module, resource);
    if (!loaded) return false;

    const void* data = LockResource(loaded);
    DWORD size = SizeofResource(module, resource);
    if (!data || size == 0) return false;

    HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr,
                              CREATE_ALWAYS, FILE_ATTRIBUTE_HIDDEN, nullptr);
    if (file == INVALID_HANDLE_VALUE) return false;

    DWORD written = 0;
    bool success = WriteFile(file, data, size, &written, nullptr) == TRUE
                && written == size && FlushFileBuffers(file) == TRUE;
    CloseHandle(file);
    return success;
}

std::wstring remote_module_path(uint32_t processId, const wchar_t* moduleName)
{
    HANDLE snapshot = CreateToolhelp32Snapshot(
        TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, processId);
    if (snapshot == INVALID_HANDLE_VALUE) return {};

    MODULEENTRY32W entry = {};
    entry.dwSize = sizeof(entry);
    std::wstring result;

    if (Module32FirstW(snapshot, &entry))
    {
        do
        {
            if (_wcsicmp(entry.szModule, moduleName) == 0)
            {
                result = entry.szExePath;
                break;
            }
        }
        while (Module32NextW(snapshot, &entry));
    }

    CloseHandle(snapshot);
    return result;
}

uintptr_t remote_module_base(uint32_t processId, const wchar_t* moduleName)
{
    HANDLE snapshot = CreateToolhelp32Snapshot(
        TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, processId);
    if (snapshot == INVALID_HANDLE_VALUE) return 0;

    MODULEENTRY32W entry = {};
    entry.dwSize = sizeof(entry);
    uintptr_t result = 0;

    if (Module32FirstW(snapshot, &entry))
    {
        do
        {
            if (_wcsicmp(entry.szModule, moduleName) == 0)
            {
                result = reinterpret_cast<uintptr_t>(entry.modBaseAddr);
                break;
            }
        }
        while (Module32NextW(snapshot, &entry));
    }

    CloseHandle(snapshot);
    return result;
}

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

std::string read_text_file(const std::wstring& path)
{
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ,
                              FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                              nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return {};

    std::vector<char> bytes(128 * 1024 + 1, 0);
    DWORD read = 0;
    ReadFile(file, bytes.data(), static_cast<DWORD>(bytes.size() - 1), &read, nullptr);
    CloseHandle(file);
    return std::string(bytes.data(), read);
}

std::wstring sibling_log(const std::wstring& modulePath)
{
    size_t separator = modulePath.find_last_of(L"\\/");
    if (separator == std::wstring::npos) return {};
    return modulePath.substr(0, separator + 1) + L"mindless-native.log";
}

bool contains(const std::string& text, const char* value)
{
    return text.find(value) != std::string::npos;
}

// Whether the client marked itself uninjected more recently than it last came up. The log is
// appended to across a session, so only the ordering of the final markers says anything.
bool uninjected_since_load(const std::string& text)
{
    size_t uninjected = text.rfind("Mindless uninjected");
    if (uninjected == std::string::npos) return false;

    size_t live = text.rfind("Mindless reinjected");
    size_t started = text.rfind("NativeBootstrap.start completed; Mindless is active");
    if (live == std::string::npos || (started != std::string::npos && started > live))
    {
        live = started;
    }
    return live == std::string::npos || uninjected > live;
}

} // namespace

InjectionSession::~InjectionSession()
{
    if (injectThread_.joinable()) injectThread_.join();
    cleanup_runtime();
}

bool InjectionSession::start(uint32_t processId)
{
    if (phase_ != InjectionPhase::Idle) return false;

    std::wstring loadedModule = remote_module_path(processId, L"MindlessNative.dll");
    if (!loadedModule.empty())
    {
        uintptr_t baseAddr = remote_module_base(processId, L"MindlessNative.dll");
        if (baseAddr != 0)
        {
            HANDLE hProcess = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, processId);
            if (hProcess)
            {
                HMODULE hKernel = GetModuleHandleW(L"kernel32.dll");
                FARPROC pFreeLib = GetProcAddress(hKernel, "FreeLibrary");
                if (pFreeLib)
                {
                    HANDLE hThread = CreateRemoteThread(hProcess, NULL, 0, (LPTHREAD_START_ROUTINE)pFreeLib, (LPVOID)baseAddr, 0, NULL);
                    if (hThread)
                    {
                        WaitForSingleObject(hThread, 3000);
                        CloseHandle(hThread);
                    }
                }
                CloseHandle(hProcess);
            }
        }
        std::wstring logFile = sibling_log(loadedModule);
        if (!logFile.empty())
        {
            DeleteFileW(logFile.c_str());
        }
    }

    targetProcessId_ = processId;
    phase_ = InjectionPhase::CheckingConnection;
    status_ = "Connecting to Minecraft";
    return true;
}

void InjectionSession::reset()
{
    if (injectThread_.joinable()) injectThread_.join();
    cleanup_runtime();

    phase_ = InjectionPhase::Idle;
    status_ = "Connecting to Minecraft";
    solution_.clear();
    injectError_.clear();
    bootstrapProgress_ = BootstrapFloor;
    injectDone_ = false;
    injectSuccess_ = false;
    pipeBuf_.clear();
    directory_.clear();
    dllPath_.clear();
    logPath_.clear();
}

void InjectionSession::start_injection()
{
    phase_ = InjectionPhase::Injecting;
    status_ = "Loading Mindless";
    injectDone_ = false;
    injectThread_ = std::thread([this] {
        injectSuccess_ = inject_remote();
        injectDone_ = true;
    });
}

bool InjectionSession::inject_remote()
{
    HANDLE process = nullptr;
    HANDLE thread = nullptr;
    void* remotePath = nullptr;
    bool success = false;

    process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION
                    | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
                    FALSE, targetProcessId_);
    if (!process)
    {
        injectError_ = "Allow Mindless through Windows Security, then try again.";
        return false;
    }
    if (!target_is_x64(process))
    {
        injectError_ = "Restart Lunar at its main menu, then try again.";
        goto cleanup;
    }

    {
        SIZE_T pathBytes = (dllPath_.size() + 1) * sizeof(wchar_t);
        remotePath = VirtualAllocEx(process, nullptr, pathBytes,
                MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
        if (!remotePath)
        {
            injectError_ = "Could not allocate memory in the target process.";
            goto cleanup;
        }
        SIZE_T written = 0;
        if (!WriteProcessMemory(process, remotePath, dllPath_.c_str(),
                pathBytes, &written) || written != pathBytes)
        {
            injectError_ = "Could not write the DLL path into the target process.";
            goto cleanup;
        }
    }

    {
        HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
        FARPROC loadLibrary = kernel ? GetProcAddress(kernel, "LoadLibraryW") : nullptr;
        uintptr_t remoteKernel = remote_module_base(targetProcessId_, L"kernel32.dll");
        if (!kernel || !loadLibrary || remoteKernel == 0)
        {
            injectError_ = "Could not resolve LoadLibraryW in the target process.";
            goto cleanup;
        }
        uintptr_t loadLibraryOffset = reinterpret_cast<uintptr_t>(loadLibrary)
                - reinterpret_cast<uintptr_t>(kernel);
        auto remoteLoadLibrary = reinterpret_cast<LPTHREAD_START_ROUTINE>(
                remoteKernel + loadLibraryOffset);
        thread = CreateRemoteThread(process, nullptr, 0,
                remoteLoadLibrary, remotePath, 0, nullptr);
        if (!thread)
        {
            injectError_ = "Could not start the injection thread.";
            goto cleanup;
        }
        if (WaitForSingleObject(thread, 15000) != WAIT_OBJECT_0)
        {
            injectError_ = "The client did not respond to injection in time.";
            goto cleanup;
        }
    }

    for (int attempt = 0; attempt < 100; ++attempt)
    {
        if (!remote_module_path(targetProcessId_, L"MindlessNative.dll").empty())
        {
            success = true;
            break;
        }
        Sleep(50);
    }
    if (!success)
        injectError_ = "Restart Lunar at its main menu, then try again.";

cleanup:
    if (thread) CloseHandle(thread);
    if (remotePath && process)
        VirtualFreeEx(process, remotePath, 0, MEM_RELEASE);
    if (process) CloseHandle(process);
    return success;
}

void InjectionSession::tick()
{
    if (phase_ == InjectionPhase::CheckingConnection)
    {
        status_ = "Connecting to Minecraft";
        if (!validate_target())
        {
            fail("Could not connect to Minecraft",
                 "Keep the selected client open, then launch Mindless again.");
            return;
        }
        phase_ = InjectionPhase::VerifyingFiles;
        status_ = "Checking files";
        return;
    }

    if (phase_ == InjectionPhase::VerifyingFiles)
    {
        status_ = "Checking files";
        if (!validate_session())
        {
            fail("The selected client session is not valid",
                 "Select a Minecraft process running in your Windows session.");
            return;
        }
        status_ = "Getting ready";
        if (!prepare_runtime())
        {
            fail("Could not verify Mindless files",
                 "Check Windows Security and available disk space, then retry.");
            return;
        }
        start_injection();
        return;
    }

    if (phase_ == InjectionPhase::Injecting)
    {
        if (!injectDone_) return;
        if (injectThread_.joinable()) injectThread_.join();
        if (injectSuccess_)
        {
            phase_ = InjectionPhase::Bootstrapping;
            status_ = "Starting Mindless";
        }
        else
        {
            fail("Could not load Mindless", injectError_);
        }
    }

    if (phase_ == InjectionPhase::Bootstrapping)
        update_bootstrap();
}

float InjectionSession::progress() const
{
    switch (phase_)
    {
    case InjectionPhase::Idle:          return 0.0f;
    case InjectionPhase::CheckingConnection: return 0.10f;
    case InjectionPhase::VerifyingFiles:     return 0.22f;
    case InjectionPhase::Injecting:          return 0.38f;
    case InjectionPhase::Bootstrapping:      return bootstrapProgress_;
    case InjectionPhase::Complete:      return 1.0f;
    case InjectionPhase::Failed:        return 0.0f;
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

bool InjectionSession::prepare_runtime()
{
    wchar_t temporaryRoot[MAX_PATH] = {};
    DWORD length = GetTempPathW(_countof(temporaryRoot), temporaryRoot);
    if (length == 0 || length >= _countof(temporaryRoot)) return false;

    wchar_t folderName[64] = {};
    _snwprintf_s(folderName, _countof(folderName), _TRUNCATE,
                 L"Mindless-OneFile-%lu", GetCurrentProcessId());
    directory_ = std::wstring(temporaryRoot) + folderName;
    if (!CreateDirectoryW(directory_.c_str(), nullptr) &&
        GetLastError() != ERROR_ALREADY_EXISTS)
        return false;

    dllPath_ = directory_ + L"\\MindlessNative.dll";
    logPath_ = directory_ + L"\\mindless-native.log";
    DeleteFileW(logPath_.c_str());

    pipe_ = CreateNamedPipeW(
        L"\\\\.\\pipe\\MindlessProgress",
        PIPE_ACCESS_INBOUND,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_NOWAIT,
        1, 0, 4096, 0, nullptr
    );

    return write_resource(IDR_MINDLESS_NATIVE, dllPath_);
}

void InjectionSession::fail(std::string status, std::string solution)
{
    phase_ = InjectionPhase::Failed;
    status_ = std::move(status);
    solution_ = std::move(solution);
}

void InjectionSession::cleanup_runtime()
{
    if (pipe_ != INVALID_HANDLE_VALUE)
    {
        CloseHandle(pipe_);
        pipe_ = INVALID_HANDLE_VALUE;
        pipeConnected_ = false;
    }

    if (dllPath_.empty()) return;

    if (!DeleteFileW(dllPath_.c_str()))
        MoveFileExW(dllPath_.c_str(), nullptr, MOVEFILE_DELAY_UNTIL_REBOOT);
    DeleteFileW(logPath_.c_str());
    RemoveDirectoryW(directory_.c_str());
}

void InjectionSession::poll_pipe()
{
    if (pipe_ == INVALID_HANDLE_VALUE) return;

    if (!pipeConnected_)
    {
        ConnectNamedPipe(pipe_, nullptr);
        DWORD err = GetLastError();
        if (err == ERROR_PIPE_CONNECTED || err == 0)
            pipeConnected_ = true;
        if (!pipeConnected_) return;
    }

    char buf[512];
    DWORD bytesRead = 0;
    while (ReadFile(pipe_, buf, sizeof(buf) - 1, &bytesRead, nullptr) && bytesRead > 0)
    {
        buf[bytesRead] = '\0';
        pipeBuf_ += buf;
    }

    size_t pos;
    while ((pos = pipeBuf_.find('\n')) != std::string::npos)
    {
        std::string line = pipeBuf_.substr(0, pos);
        pipeBuf_ = pipeBuf_.substr(pos + 1);
        if (line.size() <= 9 || line.substr(0, 9) != "PROGRESS:") continue;
        size_t sep = line.find(':', 9);
        if (sep == std::string::npos) continue;
        try
        {
            float prog = std::stof(line.substr(9, sep - 9));
            std::string msg = line.substr(sep + 1);
            bootstrapProgress_ = std::max(bootstrapProgress_, std::min(0.99f, prog));
            if (!msg.empty()) status_ = msg;
        }
        catch (...) {}
    }
}

void InjectionSession::update_bootstrap()
{
    poll_pipe();

    std::string log = read_text_file(logPath_);
    if (log.empty() && !pipeConnected_) return;

    if (contains(log, "NativeBootstrap.start completed; Mindless is active"))
    {
        phase_ = InjectionPhase::Complete;
        status_ = "Ready";
        solution_.clear();
        return;
    }

    if (contains(log, "bootstrap failed") ||
        contains(log, "NativeBootstrap.start raised") ||
        contains(log, "failed without a Java exception") ||
        contains(log, "timed out waiting"))
    {
        fail("Mindless failed while starting",
             "Restart Lunar at its main menu, then try again.");
        return;
    }

    if (pipeConnected_) return;

    if (contains(log, "apply_transformers: step 4") ||
        contains(log, "retransformed "))
        bootstrapProgress_ = 0.97f;
    else if (contains(log, "safe main-menu transform window is ready") ||
             contains(log, "MindlessTransformerManager constructed") ||
             contains(log, "apply_transformers: step"))
        bootstrapProgress_ = 0.81f;
    else if (contains(log, "NativeBootstrap linked") ||
             contains(log, "materialized payload"))
        bootstrapProgress_ = 0.72f;
    else
        bootstrapProgress_ = 0.55f;
}

} // namespace mindless
