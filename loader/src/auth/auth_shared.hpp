#pragma once
#include <windows.h>
#include <cstdint>
#include <cstdio>
#include <string>
#include "shared/mindless_auth_shared.h"

namespace mindless
{

using AuthSharedData = MindlessAuthSharedData;

inline std::wstring auth_section_name(uint32_t pid)
{
    wchar_t buf[64];
    _snwprintf_s(buf, _countof(buf), _TRUNCATE, L"Local\\MindlessAuth_%lu",
                 static_cast<unsigned long>(pid));
    return buf;
}

inline HANDLE create_auth_section(uint32_t pid, const AuthSharedData& data)
{
    std::wstring name = auth_section_name(pid);
    HANDLE mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr,
        PAGE_READWRITE, 0, sizeof(AuthSharedData), name.c_str());
    if (!mapping) return nullptr;

    auto* view = static_cast<AuthSharedData*>(
        MapViewOfFile(mapping, FILE_MAP_WRITE, 0, 0, sizeof(AuthSharedData)));
    if (!view) { CloseHandle(mapping); return nullptr; }

    *view = data;
    UnmapViewOfFile(view);
    return mapping;
}

inline bool read_auth_section(uint32_t pid, AuthSharedData& data)
{
    std::wstring name = auth_section_name(pid);
    HANDLE mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, name.c_str());
    if (!mapping) return false;

    auto* view = static_cast<const AuthSharedData*>(
        MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, sizeof(AuthSharedData)));
    if (!view) { CloseHandle(mapping); return false; }

    data = *view;
    UnmapViewOfFile(view);
    CloseHandle(mapping);
    return true;
}

} // namespace mindless
