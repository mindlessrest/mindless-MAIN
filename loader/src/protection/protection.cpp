#include "protection.hpp"
#include <authclient/authclient.hpp>
#include <windows.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace mindless::protection
{

namespace
{

authclient::AuthClient* authClient = nullptr;
std::atomic<bool> watchdogRunning{false};
std::atomic<bool> compromised{false};
std::thread watchdogThread;
std::mutex stateMutex;
std::string failureReason;
std::uint64_t executableHash = 0;
unsigned debuggerConfirmations = 0;

const IMAGE_NT_HEADERS* imageHeaders()
{
    auto* base = reinterpret_cast<const unsigned char*>(GetModuleHandleW(nullptr));
    if (!base) return nullptr;
    auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE || dos->e_lfanew <= 0) return nullptr;
    auto* headers = reinterpret_cast<const IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    if (headers->Signature != IMAGE_NT_SIGNATURE) return nullptr;
    if (headers->OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR_MAGIC) return nullptr;
    return headers;
}

std::uint64_t hashExecutableSections()
{
    const auto* headers = imageHeaders();
    if (!headers) return 0;
    auto* base = reinterpret_cast<const unsigned char*>(GetModuleHandleW(nullptr));
    const IMAGE_SECTION_HEADER* section = IMAGE_FIRST_SECTION(headers);
    std::uint64_t hash = 1469598103934665603ull;
    bool found = false;
    for (unsigned i = 0; i < headers->FileHeader.NumberOfSections; ++i, ++section)
    {
        if ((section->Characteristics & IMAGE_SCN_MEM_EXECUTE) == 0) continue;
        std::size_t size = section->Misc.VirtualSize;
        if (size == 0 || section->VirtualAddress > headers->OptionalHeader.SizeOfImage
            || size > headers->OptionalHeader.SizeOfImage - section->VirtualAddress) return 0;
        const unsigned char* bytes = base + section->VirtualAddress;
        for (std::size_t offset = 0; offset < size; ++offset)
        {
            hash ^= bytes[offset];
            hash *= 1099511628211ull;
        }
        found = true;
    }
    return found ? hash : 0;
}

bool debuggerPresent()
{
    BOOL remote = FALSE;
    if (IsDebuggerPresent()) return true;
    return CheckRemoteDebuggerPresent(GetCurrentProcess(), &remote) && remote != FALSE;
}

void report(const char* reason)
{
    authclient::AuthClient* client = nullptr;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        client = authClient;
    }
    if (!client) return;
    try
    {
        client->reportEvent(authclient::WebhookEventType::INTEGRITY_VIOLATION,
                            {{"reason", reason}, {"source", "loader"}});
    }
    catch (...)
    {
    }
}

void fail(const char* reason)
{
    bool expected = false;
    if (!compromised.compare_exchange_strong(expected, true)) return;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        failureReason = reason;
    }
    report(reason);
}

}

bool init()
{
    compromised = false;
    debuggerConfirmations = 0;
    executableHash = hashExecutableSections();
    if (executableHash == 0)
    {
        fail("Invalid loader image");
        return false;
    }
    return true;
}

void shutdown()
{
    watchdogRunning = false;
    if (watchdogThread.joinable()) watchdogThread.join();
    std::lock_guard<std::mutex> lock(stateMutex);
    authClient = nullptr;
}

void setAuthClient(authclient::AuthClient* client)
{
    std::lock_guard<std::mutex> lock(stateMutex);
    authClient = client;
}

bool checkAll()
{
    if (compromised) return false;
    std::uint64_t currentHash = hashExecutableSections();
    if (currentHash == 0 || currentHash != executableHash)
    {
        fail("Loader code integrity mismatch");
        return false;
    }
    if (debuggerPresent())
    {
        if (++debuggerConfirmations >= 2)
        {
            fail("Debugger attached");
            return false;
        }
    }
    else
    {
        debuggerConfirmations = 0;
    }
    return true;
}

void startWatchdog()
{
    if (watchdogRunning.exchange(true)) return;
    watchdogThread = std::thread([] {
        while (watchdogRunning)
        {
            std::this_thread::sleep_for(std::chrono::seconds(2));
            if (!watchdogRunning || !checkAll()) break;
        }
        watchdogRunning = false;
    });
}

bool isCompromised()
{
    return compromised;
}

const char* lastReason()
{
    std::lock_guard<std::mutex> lock(stateMutex);
    return failureReason.c_str();
}

}
