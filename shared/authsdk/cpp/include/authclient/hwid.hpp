#pragma once

#include <string>

namespace authclient {

/// Computes and caches the hardware ID for this machine.
///
/// Algorithm:
///   hwid = reverse( hex(sha256(cpu_id)) + hex(sha256(disk_serial)) )
///
/// The result is computed once and cached for the lifetime of the process.
/// If a hardware value cannot be read, an empty string is hashed instead
/// (sha256("") is deterministic), so the HWID remains stable on that machine.
///
/// @return 128-character hex string (reversed concatenation of two SHA-256
///         digests).
std::string getHWID();

}  // namespace authclient
