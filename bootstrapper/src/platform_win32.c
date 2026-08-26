#include "platform.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern HMODULE g_module;

static HANDLE g_progress_pipe = INVALID_HANDLE_VALUE;

/* ---- Logging ------------------------------------------------------------ */

void raven_log(const char *format, ...) {
    char message[2048];
    char line[2304];
    char dir[MAX_PATH];
    char log_path[MAX_PATH];
    SYSTEMTIME now;
    FILE *file = NULL;
    va_list args;

    va_start(args, format);
    _vsnprintf_s(message, sizeof(message), _TRUNCATE, format, args);
    va_end(args);

    GetLocalTime(&now);
    _snprintf_s(line, sizeof(line), _TRUNCATE,
            "[%04u-%02u-%02u %02u:%02u:%02u.%03u] %s\r\n",
            now.wYear, now.wMonth, now.wDay, now.wHour, now.wMinute,
            now.wSecond, now.wMilliseconds, message);
    OutputDebugStringA(line);

    if (raven_module_directory(dir, sizeof(dir))) {
        _snprintf_s(log_path, sizeof(log_path), _TRUNCATE,
                "%s\\raven-native.log", dir);
        if (fopen_s(&file, log_path, "a") == 0 && file != NULL) {
            fputs(line, file);
            fclose(file);
        }
    }
}

void raven_log_wide(const char *format, ...) {
    char buf[2048];
    va_list args;
    va_start(args, format);
    _vsnprintf_s(buf, sizeof(buf), _TRUNCATE, format, args);
    va_end(args);
    raven_log("%s", buf);
}

/* ---- Progress ----------------------------------------------------------- */

void raven_progress_init(void) {
    g_progress_pipe = CreateFileW(L"\\\\.\\pipe\\MindlessProgress",
            GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
}

void raven_progress_send(float progress, const char *status) {
    char message[512];
    DWORD written;
    int len;
    if (g_progress_pipe == INVALID_HANDLE_VALUE) {
        raven_progress_init();
    }
    if (g_progress_pipe == INVALID_HANDLE_VALUE) return;
    len = _snprintf_s(message, sizeof(message), _TRUNCATE,
            "PROGRESS:%f:%s\n", progress, status ? status : "");
    if (len > 0) {
        WriteFile(g_progress_pipe, message, (DWORD)len, &written, NULL);
    }
}

void raven_progress_close(void) {
    if (g_progress_pipe != INVALID_HANDLE_VALUE) {
        CloseHandle(g_progress_pipe);
        g_progress_pipe = INVALID_HANDLE_VALUE;
    }
}

/* ---- Threading ---------------------------------------------------------- */

struct thread_wrapper_ctx {
    raven_thread_fn fn;
    void *arg;
};

static DWORD WINAPI thread_wrapper(LPVOID ctx) {
    struct thread_wrapper_ctx *w = (struct thread_wrapper_ctx *)ctx;
    w->fn(w->arg);
    free(w);
    return 0;
}

int raven_thread_create(raven_thread_fn fn, void *arg) {
    HANDLE thread;
    struct thread_wrapper_ctx *ctx = (struct thread_wrapper_ctx *)malloc(sizeof(*ctx));
    if (!ctx) return 0;
    ctx->fn = fn;
    ctx->arg = arg;
    thread = CreateThread(NULL, 0, thread_wrapper, ctx, 0, NULL);
    if (thread == NULL) {
        free(ctx);
        return 0;
    }
    CloseHandle(thread);
    return 1;
}

/* ---- Misc --------------------------------------------------------------- */

void raven_sleep_ms(unsigned int ms) {
    Sleep(ms);
}

long raven_atomic_cmpxchg(volatile long *target, long expected, long desired) {
    return InterlockedCompareExchange(target, desired, expected);
}

long raven_atomic_exchange(volatile long *target, long value) {
    return InterlockedExchange(target, value);
}

/* ---- Module path -------------------------------------------------------- */

int raven_module_directory(char *output, size_t capacity) {
    DWORD length;
    char *sep;
    if (!output || capacity == 0 || g_module == NULL) return 0;
    length = GetModuleFileNameA(g_module, output, (DWORD)capacity);
    if (length == 0 || length >= capacity) return 0;
    sep = strrchr(output, '\\');
    if (!sep) return 0;
    *sep = '\0';
    return 1;
}

/* ---- Payload extraction (Windows: RCDATA resources) --------------------- */

int raven_extract_payload(int resource_id, const char *profile_name,
        char *jar_path, size_t jar_capacity) {
    HRSRC resource;
    HGLOBAL loaded;
    const unsigned char *bytes;
    DWORD size;
    char temp_root[MAX_PATH];
    char temp_dir[MAX_PATH];
    HANDLE file;
    DWORD offset = 0;
    int result = 0;

    resource = FindResourceW(g_module, MAKEINTRESOURCEW(resource_id), MAKEINTRESOURCEW(10));
    if (!resource) {
        raven_log("embedded payload resource %d is missing", resource_id);
        return 0;
    }
    size = SizeofResource(g_module, resource);
    loaded = LoadResource(g_module, resource);
    bytes = loaded ? (const unsigned char *)LockResource(loaded) : NULL;
    if (!bytes || size < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
        raven_log("embedded payload resource is invalid");
        return 0;
    }

    if (!GetTempPathA(MAX_PATH, temp_root)) {
        raven_log("GetTempPathA failed: %lu", GetLastError());
        return 0;
    }
    _snprintf_s(temp_dir, sizeof(temp_dir), _TRUNCATE, "%sRavenNative", temp_root);
    CreateDirectoryA(temp_dir, NULL);

    _snprintf_s(jar_path, jar_capacity, _TRUNCATE,
            "%s\\raven-%s-payload-%lu.jar", temp_dir, profile_name, GetCurrentProcessId());

    file = CreateFileA(jar_path, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_DELETE,
            NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        raven_log("CreateFileA for payload failed: %lu", GetLastError());
        return 0;
    }
    while (offset < size) {
        DWORD written = 0;
        if (!WriteFile(file, bytes + offset, size - offset, &written, NULL) || written == 0) {
            raven_log("WriteFile for payload failed: %lu", GetLastError());
            goto cleanup;
        }
        offset += written;
    }
    FlushFileBuffers(file);
    result = 1;

cleanup:
    CloseHandle(file);
    if (!result) DeleteFileA(jar_path);
    else raven_log("materialized payload: %s (%lu bytes)", jar_path, (unsigned long)size);
    return result;
}

/* ---- JVM discovery ------------------------------------------------------ */

JavaVM *raven_find_jvm(void) {
    typedef jint (JNICALL *get_created_vms_fn)(JavaVM **, jsize, jsize *);
    HMODULE jvm_module;
    FARPROC addr;
    get_created_vms_fn get_created_vms;
    JavaVM *vm = NULL;
    jsize count = 0;

    jvm_module = GetModuleHandleW(L"jvm.dll");
    if (!jvm_module) return NULL;

    addr = GetProcAddress(jvm_module, "JNI_GetCreatedJavaVMs");
    if (!addr) return NULL;

    get_created_vms = (get_created_vms_fn)addr;
    if (get_created_vms(&vm, 1, &count) != JNI_OK || count < 1)
        return NULL;
    return vm;
}
