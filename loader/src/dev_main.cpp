#include "auth/auth_shared.hpp"
#include "injection/injection_session.hpp"
#include <windows.h>
#include <tlhelp32.h>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

namespace
{

struct Target
{
    DWORD pid;
    std::wstring executable;
};

void log(const char* message)
{
    std::string line = std::string("[MindlessDev] ") + message + "\n";
    OutputDebugStringA(line.c_str());
}

std::vector<Target> findTargets()
{
    std::vector<Target> targets;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return targets;

    PROCESSENTRY32W entry = {};
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry))
    {
        do
        {
            if (_wcsicmp(entry.szExeFile, L"javaw.exe") == 0 ||
                _wcsicmp(entry.szExeFile, L"java.exe") == 0)
                targets.push_back({ entry.th32ProcessID, entry.szExeFile });
        }
        while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);
    return targets;
}

std::vector<uint8_t> readPayload(const std::filesystem::path& path)
{
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) return {};
    const auto size = file.tellg();
    if (size <= 0) return {};
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    file.seekg(0);
    if (!file.read(reinterpret_cast<char*>(bytes.data()), size)) return {};
    return bytes;
}

}

int main()
{
    SetConsoleTitleW(L"Mindless Developer Loader");
    std::puts("Mindless developer loader");
    std::puts("Debug output: DebugView (filter: Mindless)\n");
    log("started");

    wchar_t executablePath[MAX_PATH] = {};
    GetModuleFileNameW(nullptr, executablePath, MAX_PATH);
    auto payloadPath = std::filesystem::path(executablePath).parent_path() /
        L"loader" / L"assets" / L"runtime" / L"MindlessNative.dll";

    auto payload = readPayload(payloadPath);
    if (payload.empty())
    {
        std::fwprintf(stderr, L"Payload missing: %ls\nRun dev-build.bat first.\n",
                     payloadPath.c_str());
        log("payload missing");
        return 1;
    }

    auto targets = findTargets();
    if (targets.empty())
    {
        std::puts("No Java/Minecraft process found.");
        log("no Java process found");
        return 1;
    }

    std::puts("Select a target:");
    for (size_t index = 0; index < targets.size(); ++index)
        std::wprintf(L"  %zu) %ls (PID %lu)\n", index + 1,
                     targets[index].executable.c_str(), targets[index].pid);
    std::printf("\n> ");

    size_t selection = 0;
    if (std::scanf("%zu", &selection) != 1 || selection == 0 || selection > targets.size())
    {
        std::puts("Invalid selection.");
        return 1;
    }

    const DWORD pid = targets[selection - 1].pid;
    mindless::AuthSharedData shared = {};
    HANDLE authSection = mindless::create_auth_section(pid, shared);
    if (!authSection)
    {
        std::printf("Could not create progress channel (error %lu).\n", GetLastError());
        log("progress channel creation failed");
        return 1;
    }

    mindless::InjectionSession injection;
    if (!injection.start(pid, payload.data(), payload.size()))
    {
        std::printf("Start failed: %s\n%s\n", injection.status().c_str(),
                    injection.solution().c_str());
        CloseHandle(authSection);
        return 1;
    }

    std::printf("Injecting into PID %lu...\n", pid);
    std::string lastStatus;
    while (injection.phase() == mindless::InjectionPhase::Injecting)
    {
        injection.tick();
        if (injection.status() != lastStatus)
        {
            lastStatus = injection.status();
            std::printf("[%3d%%] %s\n", static_cast<int>(injection.progress() * 100.0f),
                        lastStatus.c_str());
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    CloseHandle(authSection);
    if (injection.phase() == mindless::InjectionPhase::Complete)
    {
        std::puts("Mindless loaded successfully.");
        log("completed successfully");
        return 0;
    }

    std::printf("Failed: %s\n%s\n", injection.status().c_str(),
                injection.solution().c_str());
    log("injection failed");
    return 1;
}
