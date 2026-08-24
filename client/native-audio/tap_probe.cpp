// Standalone probe for MindlessAudioTap.dll.
//
// Prints the tap status and the RMS level of whatever it is capturing, once every 250ms. Used to
// confirm by hand that the capture really is Spotify-only: play something in another application
// with Spotify paused, and the level here must stay at zero.
//
//   tap_probe.exe [seconds]

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

typedef int(__cdecl *StartFn)();
typedef void(__cdecl *StopFn)();
typedef int(__cdecl *PollFn)(float *, int);
typedef int(__cdecl *StatusFn)();
typedef int(__cdecl *RateFn)();
typedef int(__cdecl *PidFn)();
typedef const char *(__cdecl *ErrorFn)();

static const char *statusName(int status) {
    switch (status) {
        case 0: return "STOPPED";
        case 1: return "SEARCHING";
        case 2: return "CAPTURING";
        case 3: return "UNSUPPORTED";
        case 4: return "FAILED";
        default: return "?";
    }
}

int main(int argc, char **argv) {
    int seconds = argc > 1 ? atoi(argv[1]) : 20;

    HMODULE lib = LoadLibraryW(L"MindlessAudioTap.dll");
    if (!lib) {
        printf("LoadLibrary failed: %lu\n", GetLastError());
        return 1;
    }

    StartFn start = (StartFn)GetProcAddress(lib, "MindlessTapStart");
    StopFn stop = (StopFn)GetProcAddress(lib, "MindlessTapStop");
    PollFn poll = (PollFn)GetProcAddress(lib, "MindlessTapPoll");
    StatusFn status = (StatusFn)GetProcAddress(lib, "MindlessTapStatus");
    RateFn rate = (RateFn)GetProcAddress(lib, "MindlessTapSampleRate");
    PidFn pid = (PidFn)GetProcAddress(lib, "MindlessTapTargetPid");
    ErrorFn error = (ErrorFn)GetProcAddress(lib, "MindlessTapError");

    if (!start || !stop || !poll || !status || !rate || !pid || !error) {
        printf("missing exports\n");
        return 1;
    }

    start();
    printf("started; sampling for %d seconds\n", seconds);

    std::vector<float> buffer(48000 * 2);
    for (int tick = 0; tick < seconds * 4; ++tick) {
        Sleep(250);
        int frames = poll(buffer.data(), 48000);
        double sum = 0.0;
        float peak = 0.0f;
        for (int i = 0; i < frames * 2; ++i) {
            sum += (double)buffer[i] * buffer[i];
            float magnitude = fabsf(buffer[i]);
            if (magnitude > peak) peak = magnitude;
        }
        double rms = frames > 0 ? sqrt(sum / (frames * 2)) : 0.0;
        printf("[%5.1fs] %-11s pid=%-6d rate=%d frames=%-5d rms=%.5f peak=%.5f %s\n",
               tick * 0.25, statusName(status()), pid(), rate(), frames, rms, peak, error());
        fflush(stdout);
    }

    stop();
    printf("stopped\n");
    FreeLibrary(lib);
    return 0;
}
