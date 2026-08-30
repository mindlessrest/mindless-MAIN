#pragma once

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <jni.h>
#include <jvmti.h>

#include <stdarg.h>

#ifdef __cplusplus
extern "C" {
#endif

extern JavaVM   *g_vm;
extern jvmtiEnv *g_jvmti;
extern HMODULE   g_module;

void  vape_log(const wchar_t *format, ...);
void  vape_log_pending_exception(JNIEnv *env, const wchar_t *context);
jint  mindless_initialize_jvmti(JavaVM *vm);

#ifdef __cplusplus
}
#endif
