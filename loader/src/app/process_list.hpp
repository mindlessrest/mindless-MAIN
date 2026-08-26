#pragma once
#include "renderer/image.hpp"
#include <string>
#include <vector>
#include <cstdint>
#include <d3d11.h>

namespace mindless
{

struct ProcessEntry
{
    uint32_t    pid;
    std::string name;
    std::string display;
    std::string subtitle;
    Image       icon;
    bool        demo = false;
};

std::vector<ProcessEntry> enumerate_targets(ID3D11Device* device);

// Just the pids, with no window scan, icon extraction or texture upload. Used to check
// whether the list actually changed before paying for a full rebuild.
std::vector<uint32_t> enumerate_target_pids();

} // namespace mindless
