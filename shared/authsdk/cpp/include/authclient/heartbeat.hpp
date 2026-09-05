#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <thread>

#include "authclient/types.hpp"

namespace authclient {

class AuthClient;  // forward declaration

/// Runs a background thread that periodically calls session-validate to keep
/// the session alive and detect revocation.
///
/// Behaviour on each tick:
///   - 200 OK  -> reset failure counter, fire onSuccess callback.
///   - 401/403 -> session is dead/banned; fire onInvalid, stop loop.
///   - 5xx / network error -> increment failure counter.  If it reaches
///     max_failures, fire onInvalid and stop.  Otherwise wait
///     retry_delay_seconds and retry.
class Heartbeat {
public:
    /// @param client  The AuthClient whose session to monitor (must outlive
    ///                the Heartbeat).
    /// @param config  Timing and retry parameters.
    Heartbeat(AuthClient& client, HeartbeatConfig config);
    ~Heartbeat();

    Heartbeat(const Heartbeat&) = delete;
    Heartbeat& operator=(const Heartbeat&) = delete;

    /// Called when the session is determined to be invalid.
    /// The string argument is a human-readable reason.
    void setOnInvalid(std::function<void(const std::string&)> cb);

    /// Called after each successful heartbeat.
    void setOnSuccess(std::function<void()> cb);

    /// Starts the background heartbeat thread.
    void start();

    /// Signals the thread to stop and blocks until it exits.
    void stop();

private:
    void run();

    AuthClient&    client_;
    HeartbeatConfig config_;

    std::function<void(const std::string&)> on_invalid_;
    std::function<void()>                   on_success_;

    std::atomic<bool> running_{false};
    std::thread       thread_;
};

}  // namespace authclient
