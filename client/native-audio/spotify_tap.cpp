// MindlessAudioTap -- captures the audio Spotify renders, and only that audio.
//
// Windows offers two very different things that both get called "loopback". The old one,
// IAudioClient on a render endpoint with AUDCLNT_STREAMFLAGS_LOOPBACK, taps the endpoint after
// every application has been mixed into it: Minecraft, Discord, a browser tab and Spotify all
// arrive as one indistinguishable signal, and no amount of processing downstream can pull them
// apart again. That is not what this does, and there is no fallback here that does it.
//
// The other one is process loopback, added in Windows 10 build 20348. ActivateAudioInterfaceAsync
// is given the pseudo-device VAD\Process_Loopback together with a target process id, and the
// audio engine hands back a client carrying only what that process tree rendered. Everything else
// on the machine is already excluded by the time we see a sample.
//
// Spotify runs as a tree -- a main process plus a renderer per surface -- and which member of it
// actually opens the audio device is an implementation detail that has changed between versions.
// PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE against the root covers all of them without
// having to guess.
//
// Everything here runs on one worker thread. Java calls Start/Stop/Poll and never blocks on the
// audio engine.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
// The process-loopback declarations are gated behind NTDDI_WIN10_FE (Windows 10 20348). The SDK
// default target can be older than the SDK itself, so ask for it explicitly.
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#ifndef NTDDI_VERSION
#define NTDDI_VERSION 0x0A00000A
#endif

#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <mmreg.h>
#include <tlhelp32.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define TAP_API extern "C" __declspec(dllexport)

// Status codes shared with the Java side. Plain ints, so JNA needs no marshalling.
enum TapStatus {
    TAP_STOPPED = 0,      // not running
    TAP_SEARCHING = 1,    // running, but Spotify is not there to listen to
    TAP_CAPTURING = 2,    // attached to the Spotify process tree
    TAP_UNSUPPORTED = 3,  // this Windows build has no process loopback
    TAP_FAILED = 4        // attaching failed for some other reason
};

namespace {

const wchar_t *const kSpotifyImage = L"spotify.exe";

// One second of stereo float at 48k. Java drains this about sixty times a second, so it only has
// to survive a stall, not buffer for its own sake.
const size_t kRingFloats = 48000 * 2;

// ---------------------------------------------------------------------------------------------
// Ring buffer. Written by the WASAPI pump, drained by whatever thread Java polls from.
// ---------------------------------------------------------------------------------------------
class Ring {
  public:
    Ring() : data_(kRingFloats, 0.0f), read_(0), write_(0), available_(0) {}

    void push(const float *src, size_t count) {
        std::lock_guard<std::mutex> guard(lock_);
        for (size_t i = 0; i < count; ++i) {
            data_[write_] = src[i];
            write_ = (write_ + 1) % kRingFloats;
        }
        advance(count);
    }

    void pushSilence(size_t count) {
        std::lock_guard<std::mutex> guard(lock_);
        for (size_t i = 0; i < count; ++i) {
            data_[write_] = 0.0f;
            write_ = (write_ + 1) % kRingFloats;
        }
        advance(count);
    }

    size_t pull(float *dst, size_t max) {
        std::lock_guard<std::mutex> guard(lock_);
        size_t count = available_ < max ? available_ : max;
        for (size_t i = 0; i < count; ++i) {
            dst[i] = data_[read_];
            read_ = (read_ + 1) % kRingFloats;
        }
        available_ -= count;
        return count;
    }

    void clear() {
        std::lock_guard<std::mutex> guard(lock_);
        read_ = write_ = available_ = 0;
    }

  private:
    // Caller holds the lock.
    void advance(size_t count) {
        available_ += count;
        if (available_ > kRingFloats) {
            // Overrun: the reader stalled. Keep the newest second and drop the rest, so the next
            // frame shows what is playing now rather than replaying what it missed.
            available_ = kRingFloats;
            read_ = write_;
        }
    }

    std::vector<float> data_;
    size_t read_;
    size_t write_;
    size_t available_;
    std::mutex lock_;
};

// ---------------------------------------------------------------------------------------------
// ActivateAudioInterfaceAsync hands its result to a callback rather than returning it. This is
// the smallest object that can receive one.
// ---------------------------------------------------------------------------------------------
class ActivationHandler : public IActivateAudioInterfaceCompletionHandler, public IAgileObject {
  public:
    ActivationHandler() : refs_(1), result_(E_FAIL), client_(nullptr) {
        done_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **out) override {
        if (!out) return E_POINTER;
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IAgileObject)) {
            *out = static_cast<IAgileObject *>(this);
        } else if (riid == __uuidof(IActivateAudioInterfaceCompletionHandler)) {
            *out = static_cast<IActivateAudioInterfaceCompletionHandler *>(this);
        } else {
            *out = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }

    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs_; }

    ULONG STDMETHODCALLTYPE Release() override {
        ULONG remaining = --refs_;
        if (remaining == 0) delete this;
        return remaining;
    }

    HRESULT STDMETHODCALLTYPE
    ActivateCompleted(IActivateAudioInterfaceAsyncOperation *operation) override {
        IUnknown *unknown = nullptr;
        HRESULT hr = operation->GetActivateResult(&result_, &unknown);
        if (SUCCEEDED(hr) && SUCCEEDED(result_) && unknown) {
            result_ = unknown->QueryInterface(__uuidof(IAudioClient),
                                              reinterpret_cast<void **>(&client_));
        } else if (SUCCEEDED(result_)) {
            result_ = FAILED(hr) ? hr : E_NOINTERFACE;
        }
        if (unknown) unknown->Release();
        SetEvent(done_);
        return S_OK;
    }

    // Blocks until ActivateCompleted fires. The callback arrives on an MTA worker, so waiting
    // here cannot deadlock against it.
    HRESULT wait(DWORD timeoutMs, IAudioClient **out) {
        if (!done_) return E_FAIL;
        if (WaitForSingleObject(done_, timeoutMs) != WAIT_OBJECT_0) {
            return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
        }
        if (FAILED(result_)) return result_;
        *out = client_;
        client_ = nullptr;  // ownership moves to the caller
        return S_OK;
    }

  private:
    ~ActivationHandler() {
        if (done_) CloseHandle(done_);
        if (client_) client_->Release();
    }

    std::atomic<ULONG> refs_;
    HANDLE done_;
    HRESULT result_;
    IAudioClient *client_;
};

// ---------------------------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------------------------
Ring g_ring;
std::mutex g_lifecycle;
std::thread g_worker;
std::atomic<bool> g_running(false);
std::atomic<int> g_status(TAP_STOPPED);
std::atomic<int> g_sampleRate(48000);
std::atomic<unsigned long> g_targetPid(0);
std::mutex g_errorLock;
std::string g_error;

void setError(const char *stage, HRESULT hr) {
    char buffer[256];
    _snprintf_s(buffer, sizeof(buffer), _TRUNCATE, "%s failed (0x%08lX)", stage,
                static_cast<unsigned long>(hr));
    std::lock_guard<std::mutex> guard(g_errorLock);
    g_error = buffer;
}

void setError(const char *message) {
    std::lock_guard<std::mutex> guard(g_errorLock);
    g_error = message;
}

// The root of the Spotify process tree, or 0 when Spotify is not running.
//
// The renderers are children of the main process and any of them may be the one holding the audio
// device, so what we want is the ancestor covering all of them: the Spotify.exe whose own parent
// is not another Spotify.exe.
DWORD findSpotifyRootPid() {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return 0;

    struct Entry {
        DWORD pid;
        DWORD parent;
    };
    std::vector<Entry> spotify;

    PROCESSENTRY32W entry;
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) {
        do {
            if (_wcsicmp(entry.szExeFile, kSpotifyImage) == 0) {
                Entry found = {entry.th32ProcessID, entry.th32ParentProcessID};
                spotify.push_back(found);
            }
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);

    if (spotify.empty()) return 0;

    for (size_t i = 0; i < spotify.size(); ++i) {
        bool parentIsSpotify = false;
        for (size_t j = 0; j < spotify.size(); ++j) {
            if (spotify[j].pid == spotify[i].parent) {
                parentIsSpotify = true;
                break;
            }
        }
        if (!parentIsSpotify) return spotify[i].pid;
    }

    // Every one of them claims a Spotify parent, which should not happen -- though a stale parent
    // id reused by a new process could produce it. The lowest id is the oldest, so start there.
    DWORD lowest = spotify[0].pid;
    for (size_t i = 1; i < spotify.size(); ++i) {
        if (spotify[i].pid < lowest) lowest = spotify[i].pid;
    }
    return lowest;
}

bool processAlive(DWORD pid) {
    if (pid == 0) return false;
    HANDLE handle = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (handle == nullptr) {
        // Access denied still means it exists; only an invalid id means it is gone.
        return GetLastError() != ERROR_INVALID_PARAMETER;
    }
    DWORD wait = WaitForSingleObject(handle, 0);
    CloseHandle(handle);
    return wait == WAIT_TIMEOUT;
}

void describeFloatFormat(WAVEFORMATEXTENSIBLE &format, int sampleRate) {
    ZeroMemory(&format, sizeof(format));
    format.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    format.Format.nChannels = 2;
    format.Format.nSamplesPerSec = sampleRate;
    format.Format.wBitsPerSample = 32;
    format.Format.nBlockAlign = (format.Format.nChannels * format.Format.wBitsPerSample) / 8;
    format.Format.nAvgBytesPerSec = format.Format.nSamplesPerSec * format.Format.nBlockAlign;
    format.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    format.Samples.wValidBitsPerSample = 32;
    format.dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
    format.SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
}

void describePcm16Format(WAVEFORMATEX &format, int sampleRate) {
    ZeroMemory(&format, sizeof(format));
    format.wFormatTag = WAVE_FORMAT_PCM;
    format.nChannels = 2;
    format.nSamplesPerSec = sampleRate;
    format.wBitsPerSample = 16;
    format.nBlockAlign = (format.nChannels * format.wBitsPerSample) / 8;
    format.nAvgBytesPerSec = format.nSamplesPerSec * format.nBlockAlign;
    format.cbSize = 0;
}

// Activates a process-loopback client against the Spotify tree, or returns null.
IAudioClient *activateForPid(DWORD pid, HRESULT *outResult) {
    AUDIOCLIENT_ACTIVATION_PARAMS params;
    ZeroMemory(&params, sizeof(params));
    params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    params.ProcessLoopbackParams.TargetProcessId = pid;
    params.ProcessLoopbackParams.ProcessLoopbackMode =
            PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;

    PROPVARIANT activation;
    PropVariantInit(&activation);
    activation.vt = VT_BLOB;
    activation.blob.cbSize = sizeof(params);
    activation.blob.pBlobData = reinterpret_cast<BYTE *>(&params);

    ActivationHandler *handler = new ActivationHandler();
    IActivateAudioInterfaceAsyncOperation *operation = nullptr;
    HRESULT hr = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK,
                                             __uuidof(IAudioClient), &activation, handler,
                                             &operation);
    if (FAILED(hr)) {
        handler->Release();
        *outResult = hr;
        return nullptr;
    }

    IAudioClient *client = nullptr;
    hr = handler->wait(3000, &client);
    if (operation) operation->Release();
    handler->Release();
    *outResult = hr;
    return SUCCEEDED(hr) ? client : nullptr;
}

// One capture session: attach, pump until Spotify exits or we are told to stop, tear down.
// Returns false when the caller should give up entirely rather than retry.
bool runSession(DWORD pid) {
    HRESULT activationResult = S_OK;
    IAudioClient *client = activateForPid(pid, &activationResult);
    if (!client) {
        if (activationResult == E_NOINTERFACE || activationResult == E_NOTIMPL ||
            activationResult == E_INVALIDARG) {
            g_status.store(TAP_UNSUPPORTED);
            setError("this Windows build has no per-process loopback");
            return false;
        }
        g_status.store(TAP_SEARCHING);
        setError("ActivateAudioInterfaceAsync", activationResult);
        return true;
    }

    // The virtual device has no mix format of its own -- GetMixFormat is not implemented for it,
    // so the format is ours to choose and the engine converts into it. Float is what the rest of
    // the pipeline wants; 16-bit integer is the fallback for builds that refuse float here.
    bool floatSamples = true;
    int rate = 48000;
    WAVEFORMATEXTENSIBLE preferred;
    describeFloatFormat(preferred, rate);

    const DWORD flags = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
    HRESULT hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 200000, 0,
                                   reinterpret_cast<WAVEFORMATEX *>(&preferred), nullptr);
    if (FAILED(hr)) {
        rate = 44100;
        WAVEFORMATEX pcm;
        describePcm16Format(pcm, rate);
        floatSamples = false;
        hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 200000, 0, &pcm, nullptr);
    }
    if (FAILED(hr)) {
        client->Release();
        g_status.store(TAP_FAILED);
        setError("IAudioClient::Initialize", hr);
        return true;
    }

    g_sampleRate.store(rate);

    HANDLE ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!ready) {
        client->Release();
        return true;
    }
    hr = client->SetEventHandle(ready);
    if (FAILED(hr)) {
        CloseHandle(ready);
        client->Release();
        setError("SetEventHandle", hr);
        return true;
    }

    IAudioCaptureClient *capture = nullptr;
    hr = client->GetService(__uuidof(IAudioCaptureClient), reinterpret_cast<void **>(&capture));
    if (FAILED(hr)) {
        CloseHandle(ready);
        client->Release();
        setError("GetService(IAudioCaptureClient)", hr);
        return true;
    }

    hr = client->Start();
    if (FAILED(hr)) {
        capture->Release();
        CloseHandle(ready);
        client->Release();
        setError("IAudioClient::Start", hr);
        return true;
    }

    g_targetPid.store(pid);
    g_status.store(TAP_CAPTURING);
    setError("");

    std::vector<float> scratch;
    DWORD aliveCheck = GetTickCount();

    while (g_running.load()) {
        DWORD waited = WaitForSingleObject(ready, 200);
        if (!g_running.load()) break;

        if (waited == WAIT_OBJECT_0) {
            for (;;) {
                BYTE *data = nullptr;
                UINT32 frames = 0;
                DWORD packetFlags = 0;
                hr = capture->GetBuffer(&data, &frames, &packetFlags, nullptr, nullptr);
                if (hr == AUDCLNT_S_BUFFER_EMPTY || FAILED(hr) || frames == 0) {
                    if (SUCCEEDED(hr) && frames == 0) capture->ReleaseBuffer(0);
                    break;
                }

                size_t floats = static_cast<size_t>(frames) * 2;
                if (packetFlags & AUDCLNT_BUFFERFLAGS_SILENT) {
                    // Spotify is attached but rendering nothing -- paused, or between tracks.
                    // Feeding the silence through rather than skipping it lets the bars fall at
                    // the same rate they would for a quiet passage.
                    g_ring.pushSilence(floats);
                } else {
                    if (scratch.size() < floats) scratch.resize(floats);
                    if (floatSamples) {
                        memcpy(scratch.data(), data, floats * sizeof(float));
                    } else {
                        const short *pcm = reinterpret_cast<const short *>(data);
                        for (size_t i = 0; i < floats; ++i) {
                            scratch[i] = static_cast<float>(pcm[i]) / 32768.0f;
                        }
                    }
                    g_ring.push(scratch.data(), floats);
                }

                capture->ReleaseBuffer(frames);
            }
        }

        // Spotify closing is normal, not an error. Drop the session and let the caller look for it
        // again, so reopening Spotify reconnects without anything else being restarted.
        DWORD now = GetTickCount();
        if (now - aliveCheck > 1000) {
            aliveCheck = now;
            if (!processAlive(pid)) break;
        }
    }

    client->Stop();
    capture->Release();
    CloseHandle(ready);
    client->Release();
    g_targetPid.store(0);
    return true;
}

void workerMain() {
    HRESULT comInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool comOwned = SUCCEEDED(comInit);

    while (g_running.load()) {
        DWORD pid = findSpotifyRootPid();
        if (pid == 0) {
            g_status.store(TAP_SEARCHING);
            g_ring.clear();
            // Nothing to listen to. Note what is deliberately absent here: no path falls back to
            // the default render endpoint, because that endpoint carries every other application
            // on the machine.
            for (int i = 0; i < 15 && g_running.load(); ++i) Sleep(100);
            continue;
        }

        bool keepTrying = runSession(pid);
        g_ring.clear();
        if (!keepTrying) break;  // unsupported build; retrying cannot help

        if (g_status.load() == TAP_CAPTURING) {
            g_status.store(TAP_SEARCHING);
        } else if (g_running.load()) {
            for (int i = 0; i < 20 && g_running.load(); ++i) Sleep(100);
        }
    }

    if (comOwned) CoUninitialize();
    if (g_status.load() != TAP_UNSUPPORTED) g_status.store(TAP_STOPPED);
}

}  // namespace

// ---------------------------------------------------------------------------------------------
// Exports
// ---------------------------------------------------------------------------------------------

TAP_API int MindlessTapStart() {
    std::lock_guard<std::mutex> guard(g_lifecycle);
    if (g_running.load()) return g_status.load();
    g_ring.clear();
    g_running.store(true);
    g_status.store(TAP_SEARCHING);
    g_worker = std::thread(workerMain);
    return g_status.load();
}

TAP_API void MindlessTapStop() {
    std::lock_guard<std::mutex> guard(g_lifecycle);
    if (!g_running.load()) return;
    g_running.store(false);
    if (g_worker.joinable()) g_worker.join();
    g_ring.clear();
    g_status.store(TAP_STOPPED);
}

// Drains up to maxFrames interleaved stereo frames. Returns frames written, 0 when nothing new.
TAP_API int MindlessTapPoll(float *out, int maxFrames) {
    if (out == nullptr || maxFrames <= 0) return 0;
    size_t pulled = g_ring.pull(out, static_cast<size_t>(maxFrames) * 2);
    return static_cast<int>(pulled / 2);
}

TAP_API int MindlessTapStatus() { return g_status.load(); }

TAP_API int MindlessTapSampleRate() { return g_sampleRate.load(); }

TAP_API int MindlessTapTargetPid() { return static_cast<int>(g_targetPid.load()); }

TAP_API const char *MindlessTapError() {
    std::lock_guard<std::mutex> guard(g_errorLock);
    static char buffer[256];
    _snprintf_s(buffer, sizeof(buffer), _TRUNCATE, "%s", g_error.c_str());
    return buffer;
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(module);
    }
    // Nothing on detach, on purpose. Stopping the worker needs COM and a thread join, neither of
    // which is legal under the loader lock; Java stops the tap explicitly and the process is going
    // away regardless.
    (void)reserved;
    return TRUE;
}
