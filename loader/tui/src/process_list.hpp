#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace mindless {

struct ProcessEntry {
    uint32_t    pid;
    std::string name;
    std::string title;
};

std::vector<ProcessEntry> enumerate_java_processes();

} // namespace mindless
