#include "raven_native.h"
#include "platform.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

/*
 * Windows DLL entry point. Spawns a background thread that runs the
 * portable bootstrap logic in bootstrap.c.
 */

HMODULE g_module = NULL;

static DWORD WINAPI bootstrap_thread(LPVOID parameter) {
    (void)parameter;
    raven_bootstrap_main(NULL);
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    HANDLE thread;
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = instance;
        DisableThreadLibraryCalls(instance);
        thread = CreateThread(NULL, 0, bootstrap_thread, instance, 0, NULL);
        if (!thread) {
            OutputDebugStringW(L"RavenNative: CreateThread failed\r\n");
            g_module = NULL;
            return FALSE;
        }
        CloseHandle(thread);
    }
    return TRUE;
}
