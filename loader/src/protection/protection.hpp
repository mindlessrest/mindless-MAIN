#pragma once
#include <string>
#include <cstdint>
#include <memory>

namespace authclient { class AuthClient; }

namespace mindless::protection
{

struct DetectionResult
{
    bool detected = false;
    const char* reason = nullptr;
};

void init();
void set_auth_client(authclient::AuthClient* client);

bool check_all();

// Starts background watchdog thread (continuous monitoring)
void start_watchdog();

DetectionResult check_debugger();
DetectionResult check_remote_debugger();
DetectionResult check_nt_debug();
DetectionResult check_hardware_breakpoints();
DetectionResult check_timing();
DetectionResult check_exception_based();
DetectionResult check_close_handle();
DetectionResult check_parent_process();
DetectionResult check_debug_windows();
DetectionResult check_vm();
DetectionResult check_tamper();
DetectionResult check_blacklisted_processes();
DetectionResult check_blacklisted_modules();
DetectionResult check_debug_registers_thread_scan();
DetectionResult check_suspended_threads();
DetectionResult check_injection();
DetectionResult check_banned();

// Call once after init to snapshot the expected module list
void snapshot_modules();

void on_detected(const char* reason);
void ban();
void erase_pe_headers();

} // namespace mindless::protection
