#pragma once
#include <atomic>
#include <mutex>
#include <string>
#include <thread>

namespace mindless {

// Listens for progress updates from the bootstrapper (named pipe on Windows,
// Unix socket on Linux). Runs in a background thread.
class ProgressListener {
public:
    void start();
    void stop();

    float progress() const { return progress_.load(); }
    std::string status() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return status_;
    }
    bool complete() const { return complete_.load(); }
    bool failed() const { return failed_.load(); }

private:
    std::atomic<float> progress_{0.0f};
    mutable std::mutex mutex_;
    std::string status_ = "Connecting";
    std::atomic<bool> complete_{false};
    std::atomic<bool> failed_{false};
    std::atomic<bool> running_{false};
    std::thread thread_;

    void run();
    void parse_line(const std::string& line);
};

} // namespace mindless
