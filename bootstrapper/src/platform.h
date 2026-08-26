#pragma once

#include <jni.h>
#include <jvmti.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* -------------------------------------------------------------------------
 * Platform abstraction for the Raven bootstrapper.
 * Each platform (win32, linux) implements these in its own translation unit.
 * ------------------------------------------------------------------------- */

/* Logging */
void raven_log(const char *format, ...);
void raven_log_wide(const char *format, ...);

/* Progress reporting to the loader */
void raven_progress_init(void);
void raven_progress_send(float progress, const char *status);
void raven_progress_close(void);

/* Threading */
typedef void (*raven_thread_fn)(void *arg);
int raven_thread_create(raven_thread_fn fn, void *arg);

/* Sleep */
void raven_sleep_ms(unsigned int ms);

/* Atomics */
long raven_atomic_cmpxchg(volatile long *target, long expected, long desired);
long raven_atomic_exchange(volatile long *target, long value);

/* Payload extraction.
 * Writes the embedded JAR (identified by resource_id: 421=forge, 422=lunar)
 * to a temporary file and fills jar_path with the absolute path.
 * Returns 1 on success, 0 on failure. */
#define RAVEN_PAYLOAD_FORGE 421
#define RAVEN_PAYLOAD_LUNAR 422
int raven_extract_payload(int resource_id, const char *profile_name,
        char *jar_path, size_t jar_capacity);

/* Module self-path (directory containing the .so/.dll) */
int raven_module_directory(char *output, size_t capacity);

/* JVM discovery — find a running JavaVM in this process.
 * Returns the JavaVM pointer or NULL. */
JavaVM *raven_find_jvm(void);

#ifdef __cplusplus
}
#endif
