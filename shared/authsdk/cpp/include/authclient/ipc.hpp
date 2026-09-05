#pragma once

#include <memory>
#include <string>

namespace authclient {

/// Server side of the loader-to-software IPC handoff.
///
/// The loader creates an IPCServer, launches the software binary with the
/// channel name as argv[1], then waits for the software to connect and
/// sends the encrypted session token.
///
/// On Windows the channel is a named pipe; on Linux it is a Unix domain socket.
class IPCServer {
public:
    IPCServer();
    ~IPCServer();

    // Not copyable or movable — owns an OS handle.
    IPCServer(const IPCServer&) = delete;
    IPCServer& operator=(const IPCServer&) = delete;

    /// Returns the channel name to pass to the software as argv[1].
    /// Windows: `\\.\pipe\authsys_<hex>`, Linux: `/tmp/authsys_<hex>.sock`.
    std::string getChannelName() const;

    /// Blocks until the software connects or `timeout_seconds` elapses.
    /// @throws AuthException on timeout or OS error.
    void waitForConnection(int timeout_seconds);

    /// Encrypts the token with AES-256-GCM keyed by sha256(hwid) and sends
    /// the encrypted payload over the pipe/socket.
    /// @throws AuthException on write failure or crypto error.
    void sendToken(const std::string& token, const std::string& hwid);

    /// Closes the pipe/socket and cleans up OS resources.
    void close();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

/// Client side of the loader-to-software IPC handoff.
///
/// The software receives the channel name from argv[1], connects, reads
/// the encrypted payload, decrypts it using the local HWID, and obtains
/// the session token.
class IPCClient {
public:
    /// @param channel_name  The channel name received from argv[1].
    explicit IPCClient(const std::string& channel_name);
    ~IPCClient();

    IPCClient(const IPCClient&) = delete;
    IPCClient& operator=(const IPCClient&) = delete;

    /// Blocks until the encrypted token is received, then decrypts it.
    /// @param hwid  The local machine HWID (used to derive the decryption key).
    /// @return      The plaintext session token string.
    /// @throws AuthException if decryption fails (GCM tag mismatch) or on
    ///         read error.
    std::string receiveToken(const std::string& hwid);

    /// Closes the pipe/socket handle.
    void close();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace authclient
