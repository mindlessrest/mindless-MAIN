#pragma once

#include <jni.h>
#include <jvmti.h>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
extern HMODULE g_module;
#endif

#ifdef __cplusplus
extern "C" {
#endif

void raven_bootstrap_main(void *arg);

#ifdef __cplusplus
}
#endif
