#include "progress.hpp"
#include <cstring>
#include <sstream>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <fcntl.h>
#endif

namespace mindless {

void ProgressListener::start() {
    running_ = true;
    thread_ = std::thread(&ProgressListener::run, this);
}

void ProgressListener::stop() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
}

void ProgressListener::parse_line(const std::string& line) {
    // Format: PROGRESS:<float>:<status text>
    if (line.size() < 10 || line.substr(0, 9) != "PROGRESS:") return;
    size_t sep = line.find(':', 9);
    if (sep == std::string::npos) return;
    try {
        float p = std::stof(line.substr(9, sep - 9));
        std::string msg = line.substr(sep + 1);
        float cur = progress_.load();
        if (p > cur) progress_.store(p);
        if (!msg.empty()) {
            std::lock_guard<std::mutex> lock(mutex_);
            status_ = msg;
        }
        if (p >= 1.0f) complete_.store(true);
    } catch (...) {}
}

#ifdef _WIN32

void ProgressListener::run() {
    HANDLE pipe = CreateNamedPipeW(
        L"\\\\.\\pipe\\MindlessProgress",
        PIPE_ACCESS_INBOUND,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_NOWAIT,
        1, 0, 4096, 0, nullptr);

    if (pipe == INVALID_HANDLE_VALUE) {
        failed_.store(true);
        return;
    }

    bool connected = false;
    std::string buf;

    while (running_) {
        if (!connected) {
            ConnectNamedPipe(pipe, nullptr);
            DWORD err = GetLastError();
            if (err == ERROR_PIPE_CONNECTED || err == 0) connected = true;
        }

        if (connected) {
            char data[512];
            DWORD read = 0;
            while (ReadFile(pipe, data, sizeof(data) - 1, &read, nullptr) && read > 0) {
                data[read] = '\0';
                buf += data;
            }

            size_t pos;
            while ((pos = buf.find('\n')) != std::string::npos) {
                parse_line(buf.substr(0, pos));
                buf = buf.substr(pos + 1);
            }
        }

        if (complete_.load()) break;
        Sleep(50);
    }

    CloseHandle(pipe);
}

#else // Linux

void ProgressListener::run() {
    const char* socket_path = "/tmp/mindless-progress.sock";
    unlink(socket_path);

    int server = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server < 0) { failed_.store(true); return; }

    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    if (bind(server, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0 ||
        listen(server, 1) < 0) {
        close(server);
        failed_.store(true);
        return;
    }

    // Set non-blocking
    int flags = fcntl(server, F_GETFL, 0);
    fcntl(server, F_SETFL, flags | O_NONBLOCK);

    // Set env for the bootstrapper to connect to
    setenv("MINDLESS_PROGRESS_SOCKET", socket_path, 1);

    int client = -1;
    std::string buf;

    while (running_) {
        if (client < 0) {
            client = accept(server, nullptr, nullptr);
            if (client >= 0) {
                int cflags = fcntl(client, F_GETFL, 0);
                fcntl(client, F_SETFL, cflags | O_NONBLOCK);
            }
        }

        if (client >= 0) {
            char data[512];
            ssize_t n;
            while ((n = read(client, data, sizeof(data) - 1)) > 0) {
                data[n] = '\0';
                buf += data;
            }
            if (n == 0) { close(client); client = -1; }

            size_t pos;
            while ((pos = buf.find('\n')) != std::string::npos) {
                parse_line(buf.substr(0, pos));
                buf = buf.substr(pos + 1);
            }
        }

        if (complete_.load()) break;
        usleep(50000);
    }

    if (client >= 0) close(client);
    close(server);
    unlink(socket_path);
}

#endif

} // namespace mindless
