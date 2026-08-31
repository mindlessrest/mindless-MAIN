#include "process_list.hpp"
#include "renderer/image.hpp"
#include "resource.h"
#include <windows.h>
#include <tlhelp32.h>
#include <psapi.h>
#include <shellapi.h>
#include <string>
#include <unordered_map>

#pragma comment(lib, "Psapi.lib")

namespace mindless
{

struct WindowScanCtx
{
    std::unordered_map<DWORD, std::string> titleByPid;
};

static BOOL CALLBACK enum_windows_proc(HWND hwnd, LPARAM lp)
{
    auto* ctx = reinterpret_cast<WindowScanCtx*>(lp);

    if (!IsWindowVisible(hwnd)) return TRUE;

    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);

    if (ctx->titleByPid.count(pid)) return TRUE;

    wchar_t buf[256] = {};
    if (GetWindowTextW(hwnd, buf, 256) > 0)
    {
        int len = WideCharToMultiByte(CP_UTF8, 0, buf, -1, nullptr, 0, nullptr, nullptr);
        if (len > 1)
        {
            std::string title(static_cast<size_t>(len - 1), '\0');
            WideCharToMultiByte(CP_UTF8, 0, buf, -1, title.data(), len, nullptr, nullptr);
            ctx->titleByPid[pid] = std::move(title);
        }
    }
    return TRUE;
}

static std::string detect_display(const std::string& windowTitle)
{
    if (windowTitle.find("Lunar Client") != std::string::npos)
        return "Lunar Client";
    return "Minecraft";
}

static std::string detect_subtitle(const std::string& windowTitle, DWORD pid)
{
    char buf[48];
    if (windowTitle.find("Lunar Client") != std::string::npos)
    {
        snprintf(buf, sizeof(buf), "PID %lu", pid);
        return buf;
    }
    snprintf(buf, sizeof(buf), "javaw.exe - PID %lu", pid);
    return buf;
}

// Lunar runs several helper JVMs alongside the game, and none of them own a window. Listing
// every javaw process put four indistinguishable "Minecraft" rows on screen when only one of
// them could actually be injected into, so a titled visible window is the requirement.
static std::unordered_map<DWORD, std::string> scan_window_titles()
{
    WindowScanCtx ctx;
    EnumWindows(enum_windows_proc, reinterpret_cast<LPARAM>(&ctx));
    return std::move(ctx.titleByPid);
}

static bool is_game_process(const wchar_t* exeName)
{
    return _wcsicmp(exeName, L"javaw.exe") == 0
        || _wcsicmp(exeName, L"java.exe") == 0;
}

static bool is_minecraft_window(const std::string& title)
{
    if (title.find("Minecraft") != std::string::npos) return true;
    if (title.find("Lunar Client") != std::string::npos) return true;
    if (title.find("Badlion") != std::string::npos) return true;
    if (title.find("Forge") != std::string::npos) return true;
    if (title.find("Fabric") != std::string::npos) return true;
    return false;
}

// javaw.exe carries the generic Java icon whatever is running inside it, so a Lunar instance
// would otherwise show the same coffee cup as every other launcher.
static Image load_lunar_icon(ID3D11Device* device)
{
    HMODULE mod  = GetModuleHandleW(nullptr);
    HRSRC   rsrc = FindResourceW(mod, MAKEINTRESOURCEW(IDR_LUNAR_PNG), RT_RCDATA);
    if (!rsrc) return {};

    HGLOBAL hgl = LoadResource(mod, rsrc);
    if (!hgl) return {};

    return load_image_from_memory(LockResource(hgl), SizeofResource(mod, rsrc), device);
}

static Image extract_process_icon(DWORD pid, ID3D11Device* device)
{
    HANDLE proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!proc) return {};

    wchar_t path[MAX_PATH] = {};
    DWORD   size = MAX_PATH;
    QueryFullProcessImageNameW(proc, 0, path, &size);
    CloseHandle(proc);

    if (path[0] == L'\0') return {};

    HICON large = nullptr;
    HICON small_ = nullptr;
    ExtractIconExW(path, 0, &large, &small_, 1);

    HICON chosen = large ? large : small_;
    if (!chosen) return {};

    Image img = load_image_from_hicon(chosen, device);

    if (large)  DestroyIcon(large);
    if (small_) DestroyIcon(small_);
    return img;
}

std::vector<uint32_t> enumerate_target_pids()
{
    std::vector<uint32_t> result;

    std::unordered_map<DWORD, std::string> titles = scan_window_titles();

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return result;

    PROCESSENTRY32W entry = {};
    entry.dwSize = sizeof(entry);

    if (Process32FirstW(snap, &entry))
    {
        do
        {
            if (!is_game_process(entry.szExeFile)) continue;
            auto it = titles.find(entry.th32ProcessID);
            if (it == titles.end()) continue;
            if (!is_minecraft_window(it->second)) continue;
            result.push_back(entry.th32ProcessID);
        }
        while (Process32NextW(snap, &entry));
    }

    CloseHandle(snap);
    return result;
}

std::vector<ProcessEntry> enumerate_targets(ID3D11Device* device)
{
    std::vector<ProcessEntry> result;

    std::unordered_map<DWORD, std::string> titles = scan_window_titles();

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return result;

    PROCESSENTRY32W entry = {};
    entry.dwSize = sizeof(entry);

    if (Process32FirstW(snap, &entry))
    {
        do
        {
            if (!is_game_process(entry.szExeFile)) continue;

            DWORD pid = entry.th32ProcessID;
            auto it   = titles.find(pid);
            if (it == titles.end()) continue;
            std::string title = it->second;
            if (!is_minecraft_window(title)) continue;

            ProcessEntry pe;
            pe.pid      = pid;
            pe.name     = "javaw.exe";
            pe.display  = detect_display(title);
            pe.subtitle = detect_subtitle(title, pid);
            pe.icon     = pe.display == "Lunar Client" ? load_lunar_icon(device) : Image{};
            if (!pe.icon.valid())
                pe.icon = extract_process_icon(pid, device);
            pe.demo     = false;
            result.push_back(std::move(pe));
        }
        while (Process32NextW(snap, &entry));
    }

    CloseHandle(snap);
    return result;
}

} // namespace mindless
