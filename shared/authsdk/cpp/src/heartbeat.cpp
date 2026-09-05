#include "authclient/heartbeat.hpp"
#include "authclient/authclient.hpp"

#include <chrono>

namespace authclient {

Heartbeat::Heartbeat(AuthClient& client, HeartbeatConfig config)
    : client_(client), config_(config) {}

Heartbeat::~Heartbeat() {
    stop();
}

void Heartbeat::setOnInvalid(std::function<void(const std::string&)> cb) {
    on_invalid_ = std::move(cb);
}

void Heartbeat::setOnSuccess(std::function<void()> cb) {
    on_success_ = std::move(cb);
}

void Heartbeat::start() {
    if (running_.exchange(true)) return;  // already running
    thread_ = std::thread(&Heartbeat::run, this);
}

void Heartbeat::stop() {
    running_ = false;
    if (thread_.joinable()) {
        thread_.join();
    }
}

void Heartbeat::run() {
    int consecutive_failures = 0;

    while (running_) {
        // Sleep in small increments so we can respond to stop() promptly
        for (int elapsed = 0; elapsed < config_.interval_seconds && running_; ++elapsed) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
        if (!running_) break;

        try {
            client_.validateSession();

            // Success — reset failure counter
            consecutive_failures = 0;
            if (on_success_) on_success_();

        } catch (const AuthException& e) {
            // 401 or 403 → session is permanently dead
            if (e.code() == "UNAUTHORIZED" || e.code() == "BANNED"
                || e.code() == "HWID_MISMATCH" || e.code() == "IP_MISMATCH"
                || e.code() == "INVALID_TOKEN" || e.code() == "FORBIDDEN") {
                if (on_invalid_) on_invalid_(e.message());
                running_ = false;
                return;
            }

            // Transient error (network, 5xx, etc.)
            ++consecutive_failures;
            if (consecutive_failures >= config_.max_failures) {
                if (on_invalid_) {
                    on_invalid_("max heartbeat failures reached: " + e.message());
                }
                running_ = false;
                return;
            }

            // Wait retry_delay before the next attempt (still checking running_)
            for (int i = 0; i < config_.retry_delay_seconds && running_; ++i) {
                std::this_thread::sleep_for(std::chrono::seconds(1));
            }
        }
    }
}

}  // namespace authclient
