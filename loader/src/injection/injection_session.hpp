#pragma once
#include <Windows.h>
#include <atomic>
#include <cstdint>
#include <string>
#include <thread>

namespace mindless
{

enum class InjectionPhase
{
    Idle,
    CheckingConnection,
    VerifyingFiles,
    Injecting,
    Bootstrapping,
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

    bool start(uint32_t processId);
    void reset();
    void tick();

    InjectionPhase phase() const { return phase_; }
    float progress() const;
    const std::string& status() const { return status_; }
    const std::string& solution() const { return solution_; }

private:
    InjectionPhase phase_ = InjectionPhase::Idle;
    uint32_t targetProcessId_ = 0;
    std::wstring directory_;
    std::wstring dllPath_;
    std::wstring logPath_;
    std::string status_ = "Connecting to Minecraft";
    std::string solution_;
    static constexpr float BootstrapFloor = 0.45f;
    float bootstrapProgress_ = BootstrapFloor;

    HANDLE pipe_ = INVALID_HANDLE_VALUE;
    bool pipeConnected_ = false;
    std::string pipeBuf_;

    std::thread injectThread_;
    std::atomic<bool> injectDone_{false};
    bool injectSuccess_ = false;
    std::string injectError_;

    bool prepare_runtime();
    bool validate_target() const;
    bool validate_session() const;
    bool inject_remote();
    void start_injection();
    void fail(std::string status, std::string solution);
    void cleanup_runtime();
    void update_bootstrap();
    void poll_pipe();
};

} // namespace mindless
