#pragma once
#include <windows.h>
#include <winternl.h>
#include <cstdint>

namespace mindless
{

bool manual_map_inject(HANDLE process, uint32_t processId,
                       const void* dllData, size_t dllSize);

} // namespace mindless
