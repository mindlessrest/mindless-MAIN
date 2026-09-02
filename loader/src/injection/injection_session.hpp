#pragma once
#include <windows.h>
#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <chrono>
#include "auth/auth_shared.hpp"

namespace mindless
{

enum class InjectionPhase
{
    Idle,
    Injecting,
    Complete,
    Failed,
};

class InjectionSession
{
public:
    InjectionSession() = default;
    ~InjectionSession();

    InjectionSession(const InjectionSession&) = delete;
    InjectionSession& operator=(const InjectionSession&) = delete;

    bool start(uint32_t processId, const void* dllData = nullptr, size_t dllSize = 0);
    void reset();
    void tick();

    InjectionPhase phase() const { return phase_; }
    float progress() const;
    const std::string& status() const { return status_; }
    const std::string& solution() const { return solution_; }

private:
    InjectionPhase phase_ = InjectionPhase::Idle;
    uint32_t targetProcessId_ = 0;
    const void* dllData_ = nullptr;
    DWORD dllSize_ = 0;
    std::string status_ = "Idle";
    std::string solution_;

    std::thread injectThread_;
    std::atomic<bool> injectDone_{false};
    bool injectSuccess_ = false;
    std::string injectError_;
    HANDLE progressSection_ = nullptr;
    const AuthSharedData* progressView_ = nullptr;
    float progress_ = 0.0f;
    std::chrono::steady_clock::time_point startedAt_;

    bool validate_target() const;
    bool validate_session() const;
    bool inject_remote();
    bool open_progress_channel();
    void close_progress_channel();
    void poll_progress();
    void fail(std::string status, std::string solution);
};

} // namespace mindless
