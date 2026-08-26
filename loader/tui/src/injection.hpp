#pragma once
#include <cstdint>
#include <string>

namespace mindless {

enum class InjectResult { Ok, AlreadyLoaded, Failed };

struct InjectOptions {
    uint32_t    pid;
    std::string library_path; // absolute path to RavenNative.dll / .so
};

InjectResult inject(const InjectOptions& opts, std::string& error_out);

// Writes the embedded RavenNative library to a temp location and returns
// its path. Returns empty string on failure.
std::string extract_runtime();

} // namespace mindless
