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

} // namespace mindless
