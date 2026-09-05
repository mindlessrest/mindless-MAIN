#include "authclient/ipc.hpp"
#include "authclient/crypto.hpp"
#include "authclient/types.hpp"

#include <cstring>
#include <memory>
#include <vector>

#ifdef _WIN32
#  define WIN32_LEAN_AND_MEAN
#  include <windows.h>
#else
#  include <sys/socket.h>
#  include <sys/un.h>
#  include <unistd.h>
#  include <poll.h>
#endif

namespace authclient {

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/// Generate 8 random hex characters for a unique channel suffix.
static std::string randomSuffix() {
    auto bytes = crypto::randomBytes(4);
    return crypto::toHex(bytes);
}

/// Encrypt the session token for transit over the IPC channel.
/// Key = sha256(hwid) as raw bytes (32 bytes).
/// Wire format: [12-byte nonce][ciphertext + GCM tag]
static std::vector<uint8_t> encryptForIPC(const std::string& token,
                                          const std::string& hwid) {
    auto key = crypto::fromHex(crypto::sha256Hex(hwid));
    return crypto::aesGcmEncrypt(token, key);
}

/// Decrypt the IPC payload back to the session token string.
static std::string decryptFromIPC(const std::vector<uint8_t>& payload,
                                  const std::string& hwid) {
    auto key = crypto::fromHex(crypto::sha256Hex(hwid));
    auto plaintext = crypto::aesGcmDecrypt(payload, key);
    return std::string(plaintext.begin(), plaintext.end());
}

// ===========================================================================
// Windows implementation
// ===========================================================================

#ifdef _WIN32

static const DWORD PIPE_BUFFER_SIZE = 4096;

// -- IPCServer::Impl --------------------------------------------------------

struct IPCServer::Impl {
    std::string channel_name;
    HANDLE pipe_handle = INVALID_HANDLE_VALUE;

    Impl() {
        channel_name = "\\\\.\\pipe\\authsys_" + randomSuffix();

        pipe_handle = CreateNamedPipeA(
            channel_name.c_str(),
            PIPE_ACCESS_DUPLEX,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
            1,                 // max instances
            PIPE_BUFFER_SIZE,
            PIPE_BUFFER_SIZE,
            0,                 // default timeout
            nullptr);

        if (pipe_handle == INVALID_HANDLE_VALUE) {
            throw AuthException("IPC_ERROR", "failed to create named pipe");
        }
    }

    ~Impl() { closePipe(); }

    void closePipe() {
        if (pipe_handle != INVALID_HANDLE_VALUE) {
            DisconnectNamedPipe(pipe_handle);
            CloseHandle(pipe_handle);
            pipe_handle = INVALID_HANDLE_VALUE;
        }
    }
};

IPCServer::IPCServer() : impl_(std::make_unique<Impl>()) {}
IPCServer::~IPCServer() = default;

std::string IPCServer::getChannelName() const { return impl_->channel_name; }

void IPCServer::waitForConnection(int timeout_seconds) {
    // Create an overlapped event so we can enforce a timeout on ConnectNamedPipe
    OVERLAPPED ov{};
    ov.hEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    if (!ov.hEvent) {
        throw AuthException("IPC_ERROR", "failed to create event object");
    }

    BOOL connected = ConnectNamedPipe(impl_->pipe_handle, &ov);
    DWORD err = GetLastError();

    if (!connected && err == ERROR_IO_PENDING) {
        DWORD wait = WaitForSingleObject(ov.hEvent,
                                          static_cast<DWORD>(timeout_seconds) * 1000);
        if (wait == WAIT_TIMEOUT) {
            CancelIo(impl_->pipe_handle);
            CloseHandle(ov.hEvent);
            throw AuthException("IPC_TIMEOUT", "timed out waiting for software to connect");
        }
    } else if (!connected && err != ERROR_PIPE_CONNECTED) {
        CloseHandle(ov.hEvent);
        throw AuthException("IPC_ERROR", "ConnectNamedPipe failed");
    }

    CloseHandle(ov.hEvent);
}

void IPCServer::sendToken(const std::string& token, const std::string& hwid) {
    auto payload = encryptForIPC(token, hwid);

    // Write 4-byte length prefix, then the payload
    uint32_t len = static_cast<uint32_t>(payload.size());
    DWORD written = 0;

    if (!WriteFile(impl_->pipe_handle, &len, sizeof(len), &written, nullptr)
        || written != sizeof(len)) {
        throw AuthException("IPC_ERROR", "failed to write payload length");
    }
    if (!WriteFile(impl_->pipe_handle, payload.data(),
                   static_cast<DWORD>(payload.size()), &written, nullptr)
        || written != payload.size()) {
        throw AuthException("IPC_ERROR", "failed to write payload data");
    }

    FlushFileBuffers(impl_->pipe_handle);
}

void IPCServer::close() { impl_->closePipe(); }

// -- IPCClient::Impl --------------------------------------------------------

struct IPCClient::Impl {
    std::string channel_name;
    HANDLE pipe_handle = INVALID_HANDLE_VALUE;

    explicit Impl(const std::string& name) : channel_name(name) {
        pipe_handle = CreateFileA(
            channel_name.c_str(),
            GENERIC_READ | GENERIC_WRITE,
            0, nullptr,
            OPEN_EXISTING,
            0, nullptr);

        if (pipe_handle == INVALID_HANDLE_VALUE) {
            throw AuthException("IPC_ERROR", "failed to open named pipe");
        }
    }

    ~Impl() { closePipe(); }

    void closePipe() {
        if (pipe_handle != INVALID_HANDLE_VALUE) {
            CloseHandle(pipe_handle);
            pipe_handle = INVALID_HANDLE_VALUE;
        }
    }
};

IPCClient::IPCClient(const std::string& channel_name)
    : impl_(std::make_unique<Impl>(channel_name)) {}
IPCClient::~IPCClient() = default;

std::string IPCClient::receiveToken(const std::string& hwid) {
    // Read 4-byte length prefix
    uint32_t len = 0;
    DWORD bytes_read = 0;

    if (!ReadFile(impl_->pipe_handle, &len, sizeof(len), &bytes_read, nullptr)
        || bytes_read != sizeof(len)) {
        throw AuthException("IPC_ERROR", "failed to read payload length");
    }

    if (len > PIPE_BUFFER_SIZE) {
        throw AuthException("IPC_ERROR", "payload too large");
    }

    std::vector<uint8_t> payload(len);
    if (!ReadFile(impl_->pipe_handle, payload.data(), len, &bytes_read, nullptr)
        || bytes_read != len) {
        throw AuthException("IPC_ERROR", "failed to read payload data");
    }

    return decryptFromIPC(payload, hwid);
}

void IPCClient::close() { impl_->closePipe(); }

// ===========================================================================
// Linux implementation
// ===========================================================================

#else  // Unix domain sockets

struct IPCServer::Impl {
    std::string channel_name;
    int listen_fd = -1;
    int client_fd = -1;

    Impl() {
        channel_name = "/tmp/authsys_" + randomSuffix() + ".sock";

        listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (listen_fd < 0) {
            throw AuthException("IPC_ERROR", "failed to create unix socket");
        }

        struct sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, channel_name.c_str(), sizeof(addr.sun_path) - 1);

        // Remove stale socket file if it exists
        unlink(channel_name.c_str());

        if (bind(listen_fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
            ::close(listen_fd);
            throw AuthException("IPC_ERROR", "failed to bind unix socket");
        }

        if (listen(listen_fd, 1) < 0) {
            ::close(listen_fd);
            unlink(channel_name.c_str());
            throw AuthException("IPC_ERROR", "failed to listen on unix socket");
        }
    }

    ~Impl() { cleanup(); }

    void cleanup() {
        if (client_fd >= 0) { ::close(client_fd); client_fd = -1; }
        if (listen_fd >= 0) { ::close(listen_fd); listen_fd = -1; }
        if (!channel_name.empty()) {
            unlink(channel_name.c_str());
        }
    }
};

IPCServer::IPCServer() : impl_(std::make_unique<Impl>()) {}
IPCServer::~IPCServer() = default;

std::string IPCServer::getChannelName() const { return impl_->channel_name; }

void IPCServer::waitForConnection(int timeout_seconds) {
    struct pollfd pfd{};
    pfd.fd = impl_->listen_fd;
    pfd.events = POLLIN;

    int ret = poll(&pfd, 1, timeout_seconds * 1000);
    if (ret == 0) {
        throw AuthException("IPC_TIMEOUT", "timed out waiting for software to connect");
    }
    if (ret < 0) {
        throw AuthException("IPC_ERROR", "poll failed on unix socket");
    }

    impl_->client_fd = accept(impl_->listen_fd, nullptr, nullptr);
    if (impl_->client_fd < 0) {
        throw AuthException("IPC_ERROR", "failed to accept connection");
    }
}

void IPCServer::sendToken(const std::string& token, const std::string& hwid) {
    auto payload = encryptForIPC(token, hwid);

    uint32_t len = static_cast<uint32_t>(payload.size());
    if (write(impl_->client_fd, &len, sizeof(len)) != sizeof(len)) {
        throw AuthException("IPC_ERROR", "failed to write payload length");
    }
    if (write(impl_->client_fd, payload.data(), payload.size())
        != static_cast<ssize_t>(payload.size())) {
        throw AuthException("IPC_ERROR", "failed to write payload data");
    }
}

void IPCServer::close() { impl_->cleanup(); }

// -- IPCClient::Impl --------------------------------------------------------

struct IPCClient::Impl {
    std::string channel_name;
    int fd = -1;

    explicit Impl(const std::string& name) : channel_name(name) {
        fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) {
            throw AuthException("IPC_ERROR", "failed to create unix socket");
        }

        struct sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, channel_name.c_str(), sizeof(addr.sun_path) - 1);

        if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
            ::close(fd);
            fd = -1;
            throw AuthException("IPC_ERROR", "failed to connect to unix socket");
        }
    }

    ~Impl() { closeFd(); }

    void closeFd() {
        if (fd >= 0) { ::close(fd); fd = -1; }
    }
};

IPCClient::IPCClient(const std::string& channel_name)
    : impl_(std::make_unique<Impl>(channel_name)) {}
IPCClient::~IPCClient() = default;

std::string IPCClient::receiveToken(const std::string& hwid) {
    uint32_t len = 0;
    if (read(impl_->fd, &len, sizeof(len)) != sizeof(len)) {
        throw AuthException("IPC_ERROR", "failed to read payload length");
    }

    if (len > 4096) {
        throw AuthException("IPC_ERROR", "payload too large");
    }

    std::vector<uint8_t> payload(len);
    size_t total_read = 0;
    while (total_read < len) {
        ssize_t n = read(impl_->fd, payload.data() + total_read, len - total_read);
        if (n <= 0) {
            throw AuthException("IPC_ERROR", "failed to read payload data");
        }
        total_read += static_cast<size_t>(n);
    }

    return decryptFromIPC(payload, hwid);
}

void IPCClient::close() { impl_->closeFd(); }

#endif  // _WIN32

}  // namespace authclient
