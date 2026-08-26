#include "process_list.hpp"

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <tlhelp32.h>

namespace mindless {

static bool is_java(const wchar_t* exe) {
    return _wcsicmp(exe, L"java.exe") == 0 || _wcsicmp(exe, L"javaw.exe") == 0;
}

struct WindowSearchCtx {
    std::vector<ProcessEntry>* entries;
};

static BOOL CALLBACK find_window_title(HWND hwnd, LPARAM lp) {
    auto* ctx = reinterpret_cast<WindowSearchCtx*>(lp);
    if (!IsWindowVisible(hwnd) || GetWindowTextLengthW(hwnd) == 0) return TRUE;
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid == 0) return TRUE;

    wchar_t title[256] = {};
    GetWindowTextW(hwnd, title, 256);

    for (auto& e : *ctx->entries) {
        if (e.pid == pid && e.title.empty()) {
            char narrow[512] = {};
            WideCharToMultiByte(CP_UTF8, 0, title, -1, narrow, sizeof(narrow), nullptr, nullptr);
            e.title = narrow;
            break;
        }
    }
    return TRUE;
}

static bool is_minecraft_window(const std::string& title) {
    if (title.find("Minecraft") != std::string::npos) return true;
    if (title.find("Lunar Client") != std::string::npos) return true;
    if (title.find("Forge") != std::string::npos) return true;
    if (title.find("Fabric") != std::string::npos) return true;
    if (title.find("OptiFine") != std::string::npos) return true;
    if (title.find("Badlion") != std::string::npos) return true;
    if (title.find("LabyMod") != std::string::npos) return true;
    if (title.find("Feather") != std::string::npos) return true;
    return false;
}

std::vector<ProcessEntry> enumerate_java_processes() {
    std::vector<ProcessEntry> result;

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return result;

    PROCESSENTRY32W pe = {};
    pe.dwSize = sizeof(pe);
    if (Process32FirstW(snap, &pe)) {
        do {
            if (is_java(pe.szExeFile)) {
                result.push_back({ pe.th32ProcessID, "", {} });
            }
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);

    WindowSearchCtx ctx{ &result };
    EnumWindows(find_window_title, reinterpret_cast<LPARAM>(&ctx));

    // Only keep Minecraft windows
    std::erase_if(result, [](const ProcessEntry& e) {
        return e.title.empty() || !is_minecraft_window(e.title);
    });
    return result;
}

} // namespace mindless

#else // Linux

#include <dirent.h>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <unistd.h>

namespace mindless {

static bool is_java_process(pid_t pid) {
    char path[256];
    char buf[256];
    snprintf(path, sizeof(path), "/proc/%d/exe", pid);
    ssize_t len = readlink(path, buf, sizeof(buf) - 1);
    if (len <= 0) return false;
    buf[len] = '\0';
    return strstr(buf, "java") != nullptr;
}

static std::string get_full_cmdline(pid_t pid) {
    char path[256];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    std::string full;
    char c;
    std::ifstream f(path, std::ios::binary);
    while (f.get(c)) {
        full += c ? c : ' ';
    }
    return full;
}

static bool is_minecraft_cmdline(const std::string& cmdline) {
    if (cmdline.find("net.minecraft") != std::string::npos) return true;
    if (cmdline.find("minecraft") != std::string::npos) return true;
    if (cmdline.find("GradleStart") != std::string::npos) return true;
    if (cmdline.find("lunarclient") != std::string::npos) return true;
    if (cmdline.find("lunar") != std::string::npos) return true;
    if (cmdline.find("cpw.mods.bootstraplauncher") != std::string::npos) return true;
    if (cmdline.find("net.fabricmc") != std::string::npos) return true;
    if (cmdline.find("optifine") != std::string::npos) return true;
    if (cmdline.find("Minecraft") != std::string::npos) return true;
    return false;
}

static std::string extract_minecraft_title(const std::string& cmdline) {
    if (cmdline.find("lunarclient") != std::string::npos || cmdline.find("lunar") != std::string::npos)
        return "Lunar Client";
    if (cmdline.find("GradleStart") != std::string::npos)
        return "Minecraft (Dev)";
    if (cmdline.find("cpw.mods.bootstraplauncher") != std::string::npos || cmdline.find("net.minecraftforge") != std::string::npos)
        return "Minecraft (Forge)";
    if (cmdline.find("net.fabricmc") != std::string::npos)
        return "Minecraft (Fabric)";
    if (cmdline.find("optifine") != std::string::npos)
        return "Minecraft (OptiFine)";

    // Try to find version from --version arg
    auto pos = cmdline.find("--version ");
    if (pos != std::string::npos) {
        auto start = pos + 10;
        auto end = cmdline.find(' ', start);
        return "Minecraft " + cmdline.substr(start, end - start);
    }

    return "Minecraft";
}

std::vector<ProcessEntry> enumerate_java_processes() {
    std::vector<ProcessEntry> result;
    DIR* proc = opendir("/proc");
    if (!proc) return result;

    struct dirent* ent;
    while ((ent = readdir(proc)) != nullptr) {
        pid_t pid = atoi(ent->d_name);
        if (pid <= 0) continue;
        if (!is_java_process(pid)) continue;
        std::string cmdline = get_full_cmdline(pid);
        if (cmdline.empty()) continue;
        if (!is_minecraft_cmdline(cmdline)) continue;

        std::string title = extract_minecraft_title(cmdline);
        result.push_back({ static_cast<uint32_t>(pid), "java", title });
    }
    closedir(proc);
    return result;
}

} // namespace mindless

#endif
