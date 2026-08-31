#include "mindless_native.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

/*
 * MindlessNative.dll — direct injection bootstrap for the Mindless b4/bS mod on
 * Minecraft 1.8.9 Forge and Forge-enabled Lunar. Loaded into javaw.exe via
 * CreateRemoteThread + LoadLibraryW by MindlessInjector.exe. Waits for the client
 * JVM, selects the SRG or MCP payload, appends it to the Minecraft class
 * loader, and invokes mindless.runtime.NativeBootstrap.start().
 *
 * Mindless uses ClassTransform plus JVMTI: already-loaded targets are
 * retransformed immediately and targets loaded later pass through the same
 * ClassFileLoadHook.
 */

JavaVM   *g_vm     = NULL;
jvmtiEnv *g_jvmti  = NULL;
HMODULE   g_module = NULL;
static void send_progress(float progress, const char *status) {
    (void)progress; (void)status;
}

#define MINDLESS_FORGE_PAYLOAD_RESOURCE_ID 421
#define MINDLESS_LUNAR_PAYLOAD_RESOURCE_ID 422

typedef enum mindless_runtime_namespace {
    MINDLESS_NAMESPACE_UNKNOWN = 0,
    MINDLESS_NAMESPACE_MCP,
    MINDLESS_NAMESPACE_SRG
} mindless_runtime_namespace;

/* ClassFileLoadHook plumbing */
static jclass    g_hooks_class = NULL;      /* mindless.runtime.TransformerHooks */
static jmethodID g_hooks_transform = NULL;  /* static byte[] transform(String, byte[]) */
static jobject   g_game_loader = NULL;      /* global ref; filters duplicate class names */
static volatile LONG g_hook_registered = 0;
static volatile LONG g_hook_ever_published = 0;

static jclass load_class_via_loader(JNIEnv *env, jobject class_loader,
        jmethodID load_class, const char *dotted_name);

void vape_log(const wchar_t *format, ...) {
    wchar_t message[2048];
    wchar_t line[2304];
    SYSTEMTIME now;
    va_list arguments;

    va_start(arguments, format);
    _vsnwprintf_s(message, sizeof(message) / sizeof(message[0]),
            _TRUNCATE, format, arguments);
    va_end(arguments);
    GetLocalTime(&now);
    _snwprintf_s(line, sizeof(line) / sizeof(line[0]), _TRUNCATE,
            L"[%04u-%02u-%02u %02u:%02u:%02u.%03u] %ls\r\n",
            now.wYear, now.wMonth, now.wDay, now.wHour, now.wMinute,
            now.wSecond, now.wMilliseconds, message);
    OutputDebugStringW(line);
}

static void log_throwable_line(JNIEnv *env, const wchar_t *prefix, jobject throwable) {
    jclass throwable_class;
    jmethodID to_string;
    jstring text;
    const jchar *characters;
    jsize length;
    wchar_t buffer[1536];
    throwable_class = (*env)->FindClass(env, "java/lang/Throwable");
    to_string = throwable_class == NULL ? NULL : (*env)->GetMethodID(
            env, throwable_class, "toString", "()Ljava/lang/String;");
    text = to_string == NULL ? NULL : (jstring)(*env)->CallObjectMethod(
            env, throwable, to_string);
    if (text == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        vape_log(L"%ls: <unreadable throwable>", prefix);
        return;
    }
    characters = (*env)->GetStringChars(env, text, NULL);
    length = (*env)->GetStringLength(env, text);
    if (characters != NULL) {
        size_t copied = (size_t)length < 1535 ? (size_t)length : 1535;
        memcpy(buffer, characters, copied * sizeof(wchar_t));
        buffer[copied] = L'\0';
        (*env)->ReleaseStringChars(env, text, characters);
        vape_log(L"%ls: %ls", prefix, buffer);
    }
}

static void log_java_string(JNIEnv *env, const wchar_t *prefix, jstring text) {
    const jchar *characters;
    jsize length;
    wchar_t buffer[1536];
    size_t copied;
    if (text == NULL) {
        vape_log(L"%ls: <null>", prefix);
        return;
    }
    characters = (*env)->GetStringChars(env, text, NULL);
    if (characters == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        vape_log(L"%ls: <unreadable>", prefix);
        return;
    }
    length = (*env)->GetStringLength(env, text);
    copied = (size_t)length < 1535 ? (size_t)length : 1535;
    memcpy(buffer, characters, copied * sizeof(wchar_t));
    buffer[copied] = L'\0';
    (*env)->ReleaseStringChars(env, text, characters);
    vape_log(L"%ls: %ls", prefix, buffer);
}

static void log_top_stack_frames(JNIEnv *env, jobject throwable, int max_frames) {
    jclass throwable_class;
    jclass frame_class;
    jmethodID get_stack;
    jmethodID frame_to_string;
    jobjectArray frames;
    jsize count;
    jsize index;
    throwable_class = (*env)->FindClass(env, "java/lang/Throwable");
    frame_class = (*env)->FindClass(env, "java/lang/StackTraceElement");
    get_stack = throwable_class == NULL ? NULL : (*env)->GetMethodID(
            env, throwable_class, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    frame_to_string = frame_class == NULL ? NULL : (*env)->GetMethodID(
            env, frame_class, "toString", "()Ljava/lang/String;");
    if (get_stack == NULL || frame_to_string == NULL) return;
    frames = (jobjectArray)(*env)->CallObjectMethod(env, throwable, get_stack);
    if (frames == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return;
    }
    count = (*env)->GetArrayLength(env, frames);
    if (count > max_frames) count = max_frames;
    for (index = 0; index < count; ++index) {
        jobject frame = (*env)->GetObjectArrayElement(env, frames, index);
        if (frame == NULL) continue;
        log_throwable_line(env, L"    at", frame);
        (*env)->DeleteLocalRef(env, frame);
    }
}

void vape_log_pending_exception(JNIEnv *env, const wchar_t *context) {
    jthrowable throwable;
    jclass throwable_class;
    jmethodID get_cause;
    jobject current;
    int depth;
    if (env == NULL || !(*env)->ExceptionCheck(env)) {
        vape_log(L"%ls failed without a Java exception", context);
        return;
    }
    throwable = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    throwable_class = (*env)->FindClass(env, "java/lang/Throwable");
    get_cause = throwable_class == NULL ? NULL : (*env)->GetMethodID(
            env, throwable_class, "getCause", "()Ljava/lang/Throwable;");
    vape_log(L"%ls raised:", context);
    current = throwable;
    depth = 0;
    while (current != NULL && depth < 8) {
        log_throwable_line(env, depth == 0 ? L"  " : L"  caused by", current);
        log_top_stack_frames(env, current, 10);
        if (get_cause == NULL) break;
        {
            jobject next = (*env)->CallObjectMethod(env, current, get_cause);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            if (depth > 0) (*env)->DeleteLocalRef(env, current);
            current = next;
        }
        ++depth;
    }
    if (current != NULL && depth > 0) (*env)->DeleteLocalRef(env, current);
    vape_log(L"  (java-side trace written to OutputDebugString)");
}

jint mindless_initialize_jvmti(JavaVM *vm) {
    jint get_env_result;
    if (vm == NULL) return JNI_ERR;
    g_vm = vm;
    get_env_result = (*vm)->GetEnv(vm, (void **)&g_jvmti, JVMTI_VERSION_1_2);
    if (get_env_result != JNI_OK || g_jvmti == NULL) {
        vape_log(L"JVMTI 1.2 unavailable: %d", get_env_result);
        g_jvmti = NULL;
        return JNI_ERR;
    }
    return JNI_OK;
}

static int load_payload_from_memory(JNIEnv *env, jobject loader,
        int resource_id) {
    HRSRC resource;
    HGLOBAL loaded_resource;
    const unsigned char *jar_data;
    DWORD jar_size;

    jclass bais_cls, zis_cls, ze_cls, baos_cls, map_cls;
    jmethodID bais_init, zis_init, zis_next, zis_close_entry, zis_read, zis_close;
    jmethodID ze_name, ze_is_dir;
    jmethodID baos_init, baos_write, baos_to_array;
    jmethodID map_init, map_put;

    jbyteArray jar_bytes, read_buf;
    jobject bais, zis, entries_map;
    jobject entry;

    jbyteArray store_bytes_ref = NULL;
    jbyteArray conn_bytes_ref = NULL;
    jbyteArray handler_bytes_ref = NULL;

    jclass store_cls, conn_cls, handler_cls;
    jmethodID store_initialize, handler_ctor;
    jobject handler_obj, url_obj;
    jclass url_cls, ucl_cls;
    jmethodID url_init, add_url;
    jstring proto, host, path;

    resource = FindResourceW(g_module,
            MAKEINTRESOURCEW(resource_id), MAKEINTRESOURCEW(10));
    if (resource == NULL) {
        vape_log(L"embedded payload JAR resource %d is missing", resource_id);
        return 0;
    }
    jar_size = SizeofResource(g_module, resource);
    loaded_resource = LoadResource(g_module, resource);
    jar_data = loaded_resource == NULL ? NULL
            : (const unsigned char *)LockResource(loaded_resource);
    if (jar_data == NULL || jar_size < 4 || jar_data[0] != 'P' || jar_data[1] != 'K') {
        vape_log(L"embedded payload JAR resource is invalid");
        return 0;
    }

    /* Resolve Java classes for ZIP parsing */
    bais_cls = (*env)->FindClass(env, "java/io/ByteArrayInputStream");
    zis_cls  = (*env)->FindClass(env, "java/util/zip/ZipInputStream");
    ze_cls   = (*env)->FindClass(env, "java/util/zip/ZipEntry");
    baos_cls = (*env)->FindClass(env, "java/io/ByteArrayOutputStream");
    map_cls  = (*env)->FindClass(env, "java/util/HashMap");
    if (!bais_cls || !zis_cls || !ze_cls || !baos_cls || !map_cls) {
        vape_log_pending_exception(env, L"resolve ZIP/IO classes for memory payload");
        return 0;
    }

    bais_init       = (*env)->GetMethodID(env, bais_cls, "<init>", "([B)V");
    zis_init        = (*env)->GetMethodID(env, zis_cls, "<init>", "(Ljava/io/InputStream;)V");
    zis_next        = (*env)->GetMethodID(env, zis_cls, "getNextEntry", "()Ljava/util/zip/ZipEntry;");
    zis_close_entry = (*env)->GetMethodID(env, zis_cls, "closeEntry", "()V");
    zis_read        = (*env)->GetMethodID(env, zis_cls, "read", "([B)I");
    zis_close       = (*env)->GetMethodID(env, zis_cls, "close", "()V");
    ze_name         = (*env)->GetMethodID(env, ze_cls, "getName", "()Ljava/lang/String;");
    ze_is_dir       = (*env)->GetMethodID(env, ze_cls, "isDirectory", "()Z");
    baos_init       = (*env)->GetMethodID(env, baos_cls, "<init>", "()V");
    baos_write      = (*env)->GetMethodID(env, baos_cls, "write", "([BII)V");
    baos_to_array   = (*env)->GetMethodID(env, baos_cls, "toByteArray", "()[B");
    map_init        = (*env)->GetMethodID(env, map_cls, "<init>", "()V");
    map_put         = (*env)->GetMethodID(env, map_cls, "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    /* Create JNI byte[] from the raw JAR data */
    jar_bytes = (*env)->NewByteArray(env, (jsize)jar_size);
    if (jar_bytes == NULL) { vape_log(L"OOM creating JAR byte array"); return 0; }
    (*env)->SetByteArrayRegion(env, jar_bytes, 0, (jsize)jar_size,
            (const jbyte *)jar_data);

    /* Open a ZipInputStream over the byte array */
    bais = (*env)->NewObject(env, bais_cls, bais_init, jar_bytes);
    zis  = (*env)->NewObject(env, zis_cls, zis_init, bais);
    entries_map = (*env)->NewObject(env, map_cls, map_init);
    read_buf = (*env)->NewByteArray(env, 8192);
    if (!bais || !zis || !entries_map || !read_buf) {
        vape_log(L"OOM setting up ZIP reader");
        return 0;
    }

    /* Iterate ZIP entries, decompress via Java, collect into HashMap */
    while ((entry = (*env)->CallObjectMethod(env, zis, zis_next)) != NULL) {
        jstring name_str;
        jobject baos_obj;
        jbyteArray entry_data;
        const char *name_chars;
        jint n;
        if ((*env)->ExceptionCheck(env)) {
            vape_log_pending_exception(env, L"ZipInputStream.getNextEntry");
            break;
        }
        if ((*env)->CallBooleanMethod(env, entry, ze_is_dir)) {
            (*env)->CallVoidMethod(env, zis, zis_close_entry);
            (*env)->DeleteLocalRef(env, entry);
            continue;
        }

        name_str = (jstring)(*env)->CallObjectMethod(env, entry, ze_name);

        /* Read decompressed entry bytes via ByteArrayOutputStream */
        baos_obj = (*env)->NewObject(env, baos_cls, baos_init);
        while ((n = (*env)->CallIntMethod(env, zis, zis_read, read_buf)) >= 0) {
            if ((*env)->ExceptionCheck(env)) break;
            (*env)->CallVoidMethod(env, baos_obj, baos_write, read_buf, (jint)0, n);
        }
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, baos_obj);
            (*env)->DeleteLocalRef(env, name_str);
            (*env)->DeleteLocalRef(env, entry);
            continue;
        }
        entry_data = (jbyteArray)(*env)->CallObjectMethod(env, baos_obj, baos_to_array);

        /* Store in HashMap */
        {
            jobject prev = (*env)->CallObjectMethod(env, entries_map, map_put,
                    name_str, entry_data);
            if (prev) (*env)->DeleteLocalRef(env, prev);
        }

        /* Track the three helper classes we need to define first */
        name_chars = (*env)->GetStringUTFChars(env, name_str, NULL);
        if (name_chars) {
            if (strcmp(name_chars, "mindless/runtime/MemoryResourceStore.class") == 0)
                store_bytes_ref = (jbyteArray)(*env)->NewGlobalRef(env, entry_data);
            else if (strcmp(name_chars, "mindless/runtime/MemoryURLConnection.class") == 0)
                conn_bytes_ref = (jbyteArray)(*env)->NewGlobalRef(env, entry_data);
            else if (strcmp(name_chars, "mindless/runtime/MemoryURLStreamHandler.class") == 0)
                handler_bytes_ref = (jbyteArray)(*env)->NewGlobalRef(env, entry_data);
            (*env)->ReleaseStringUTFChars(env, name_str, name_chars);
        }

        (*env)->DeleteLocalRef(env, baos_obj);
        (*env)->DeleteLocalRef(env, entry_data);
        (*env)->DeleteLocalRef(env, name_str);
        (*env)->DeleteLocalRef(env, entry);
        (*env)->CallVoidMethod(env, zis, zis_close_entry);
    }
    (*env)->CallVoidMethod(env, zis, zis_close);

    if (!store_bytes_ref || !conn_bytes_ref || !handler_bytes_ref) {
        vape_log(L"memory classloader helper classes not found in payload JAR");
        if (store_bytes_ref) (*env)->DeleteGlobalRef(env, store_bytes_ref);
        if (conn_bytes_ref)  (*env)->DeleteGlobalRef(env, conn_bytes_ref);
        if (handler_bytes_ref) (*env)->DeleteGlobalRef(env, handler_bytes_ref);
        return 0;
    }

    /* DefineClass the three helpers into the game's ClassLoader */
    {
        jsize len = (*env)->GetArrayLength(env, store_bytes_ref);
        jbyte *buf = (*env)->GetByteArrayElements(env, store_bytes_ref, NULL);
        store_cls = (*env)->DefineClass(env, "mindless/runtime/MemoryResourceStore",
                loader, buf, len);
        (*env)->ReleaseByteArrayElements(env, store_bytes_ref, buf, JNI_ABORT);
        (*env)->DeleteGlobalRef(env, store_bytes_ref);
        if (!store_cls || (*env)->ExceptionCheck(env)) {
            vape_log_pending_exception(env, L"DefineClass MemoryResourceStore");
            (*env)->DeleteGlobalRef(env, conn_bytes_ref);
            (*env)->DeleteGlobalRef(env, handler_bytes_ref);
            return 0;
        }
    }
    {
        jsize len = (*env)->GetArrayLength(env, conn_bytes_ref);
        jbyte *buf = (*env)->GetByteArrayElements(env, conn_bytes_ref, NULL);
        conn_cls = (*env)->DefineClass(env, "mindless/runtime/MemoryURLConnection",
                loader, buf, len);
        (*env)->ReleaseByteArrayElements(env, conn_bytes_ref, buf, JNI_ABORT);
        (*env)->DeleteGlobalRef(env, conn_bytes_ref);
        if (!conn_cls || (*env)->ExceptionCheck(env)) {
            vape_log_pending_exception(env, L"DefineClass MemoryURLConnection");
            (*env)->DeleteGlobalRef(env, handler_bytes_ref);
            return 0;
        }
    }
    {
        jsize len = (*env)->GetArrayLength(env, handler_bytes_ref);
        jbyte *buf = (*env)->GetByteArrayElements(env, handler_bytes_ref, NULL);
        handler_cls = (*env)->DefineClass(env, "mindless/runtime/MemoryURLStreamHandler",
                loader, buf, len);
        (*env)->ReleaseByteArrayElements(env, handler_bytes_ref, buf, JNI_ABORT);
        (*env)->DeleteGlobalRef(env, handler_bytes_ref);
        if (!handler_cls || (*env)->ExceptionCheck(env)) {
            vape_log_pending_exception(env, L"DefineClass MemoryURLStreamHandler");
            return 0;
        }
    }

    /* Populate MemoryResourceStore with all JAR entries */
    store_initialize = (*env)->GetStaticMethodID(env, store_cls, "initialize",
            "(Ljava/util/Map;)V");
    if (!store_initialize) {
        vape_log_pending_exception(env, L"resolve MemoryResourceStore.initialize");
        return 0;
    }
    (*env)->CallStaticVoidMethod(env, store_cls, store_initialize, entries_map);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"MemoryResourceStore.initialize");
        return 0;
    }

    /* Create URL with memory:// protocol backed by MemoryURLStreamHandler */
    handler_ctor = (*env)->GetMethodID(env, handler_cls, "<init>", "()V");
    handler_obj = (*env)->NewObject(env, handler_cls, handler_ctor);
    if (!handler_obj || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"create MemoryURLStreamHandler instance");
        return 0;
    }

    url_cls = (*env)->FindClass(env, "java/net/URL");
    url_init = (*env)->GetMethodID(env, url_cls, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;"
            "Ljava/net/URLStreamHandler;)V");
    proto = (*env)->NewStringUTF(env, "memory");
    host  = (*env)->NewStringUTF(env, "");
    path  = (*env)->NewStringUTF(env, "/");
    url_obj = (*env)->NewObject(env, url_cls, url_init,
            proto, host, (jint)-1, path, handler_obj);
    if (!url_obj || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"create memory:// URL");
        return 0;
    }

    /* Add the memory URL to the game's URLClassLoader search path */
    ucl_cls = (*env)->FindClass(env, "java/net/URLClassLoader");
    add_url = (*env)->GetMethodID(env, ucl_cls, "addURL", "(Ljava/net/URL;)V");
    if (!add_url) {
        vape_log_pending_exception(env, L"resolve URLClassLoader.addURL");
        return 0;
    }
    (*env)->CallVoidMethod(env, loader, add_url, url_obj);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"URLClassLoader.addURL(memory)");
        return 0;
    }

    vape_log(L"payload loaded from memory (%lu bytes)", (unsigned long)jar_size);
    return 1;
}

static jobject find_client_class_loader(JNIEnv *env) {
    jint thread_count = 0;
    jthread *threads = NULL;
    jobject result = NULL;
    jvmtiError error;
    jint index;
    if (g_jvmti == NULL) return NULL;
    error = (*g_jvmti)->GetAllThreads(g_jvmti, &thread_count, &threads);
    if (error != JVMTI_ERROR_NONE || threads == NULL) {
        vape_log(L"GetAllThreads failed: %d", error);
        return NULL;
    }
    for (index = 0; index < thread_count; ++index) {
        if (result == NULL) {
            jvmtiThreadInfo info;
            memset(&info, 0, sizeof(info));
            if ((*g_jvmti)->GetThreadInfo(g_jvmti, threads[index], &info)
                    == JVMTI_ERROR_NONE) {
                if (info.name != NULL
                        && strcmp(info.name, "Client thread") == 0
                        && info.context_class_loader != NULL) {
                    result = (*env)->NewLocalRef(env, info.context_class_loader);
                }
                if (info.name != NULL)
                    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)info.name);
                if (info.thread_group != NULL)
                    (*env)->DeleteLocalRef(env, info.thread_group);
                if (info.context_class_loader != NULL)
                    (*env)->DeleteLocalRef(env, info.context_class_loader);
            }
        }
        if (threads[index] != NULL) (*env)->DeleteLocalRef(env, threads[index]);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)threads);
    return result;
}

/* Resolve Minecraft through the active client thread, then return the loader
 * that actually defined that exact Class object. This avoids selecting an
 * unrelated duplicate from a diagnostic/plugin loader. */
static jobject find_client_minecraft_class_loader(JNIEnv *env) {
    jobject context_loader = find_client_class_loader(env);
    jclass loader_class = NULL;
    jmethodID load_class = NULL;
    jclass minecraft = NULL;
    jobject defining_loader = NULL;
    if (context_loader == NULL) return NULL;

    loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    load_class = loader_class == NULL ? NULL : (*env)->GetMethodID(env,
            loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class != NULL) {
        minecraft = load_class_via_loader(env, context_loader, load_class,
                "net.minecraft.client.Minecraft");
    }
    if (minecraft != NULL && (*g_jvmti)->GetClassLoader(g_jvmti, minecraft,
            &defining_loader) != JVMTI_ERROR_NONE) {
        defining_loader = NULL;
    }
    if (defining_loader != NULL) {
        vape_log(L"selected Minecraft defining loader through Client thread");
    }
    if (minecraft != NULL) (*env)->DeleteLocalRef(env, minecraft);
    if (loader_class != NULL) (*env)->DeleteLocalRef(env, loader_class);
    (*env)->DeleteLocalRef(env, context_loader);
    return defining_loader;
}

/* Fallback for launchers that rename the client thread. Accept a loaded
 * Minecraft class only when every matching definition uses the same loader;
 * an ambiguous loader is less safe than refusing injection. */
static jobject find_minecraft_class_loader(JNIEnv *env) {
    jint class_count = 0;
    jclass *classes = NULL;
    jobject result = NULL;
    jint index;
    jvmtiError error;
    int ambiguous = 0;
    if (g_jvmti == NULL) return NULL;
    error = (*g_jvmti)->GetLoadedClasses(g_jvmti, &class_count, &classes);
    if (error != JVMTI_ERROR_NONE || classes == NULL) return NULL;
    for (index = 0; index < class_count; ++index) {
        char *signature = NULL;
        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[index],
                        &signature, NULL) == JVMTI_ERROR_NONE
                && signature != NULL
                && strcmp(signature, "Lnet/minecraft/client/Minecraft;") == 0) {
            jobject defining_loader = NULL;
            if ((*g_jvmti)->GetClassLoader(g_jvmti, classes[index],
                    &defining_loader) == JVMTI_ERROR_NONE
                    && defining_loader != NULL) {
                if (result == NULL) {
                    result = defining_loader;
                    defining_loader = NULL;
                } else if (!(*env)->IsSameObject(env, result, defining_loader)) {
                    ambiguous = 1;
                }
            }
            if (defining_loader != NULL) (*env)->DeleteLocalRef(env, defining_loader);
        }
        if (signature != NULL) {
            (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)signature);
        }
        if (classes[index] != NULL) (*env)->DeleteLocalRef(env, classes[index]);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
    if (ambiguous && result != NULL) {
        (*env)->DeleteLocalRef(env, result);
        result = NULL;
        vape_log(L"multiple Minecraft definitions use different class loaders; refusing fallback");
    } else if (result != NULL) {
        vape_log(L"selected the unique loaded Minecraft defining loader");
    }
    return result;
}

static mindless_runtime_namespace detect_runtime_namespace(JNIEnv *env,
        jobject loader) {
    jclass loader_class = NULL;
    jmethodID load_class = NULL;
    jclass minecraft = NULL;
    jmethodID mcp_click = NULL;
    jmethodID srg_click = NULL;
    mindless_runtime_namespace result = MINDLESS_NAMESPACE_UNKNOWN;

    loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    load_class = loader_class == NULL ? NULL : (*env)->GetMethodID(env,
            loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class == NULL) {
        vape_log_pending_exception(env, L"resolve ClassLoader.loadClass for namespace probe");
        goto cleanup;
    }
    minecraft = load_class_via_loader(env, loader, load_class,
            "net.minecraft.client.Minecraft");
    if (minecraft == NULL) {
        vape_log(L"namespace probe could not load net.minecraft.client.Minecraft");
        goto cleanup;
    }

    mcp_click = (*env)->GetMethodID(env, minecraft, "clickMouse", "()V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    srg_click = (*env)->GetMethodID(env, minecraft, "func_147116_af", "()V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    if (mcp_click != NULL && srg_click == NULL) {
        result = MINDLESS_NAMESPACE_MCP;
        vape_log(L"runtime namespace: MCP/named (Lunar/deobfuscated)");
    } else if (srg_click != NULL && mcp_click == NULL) {
        result = MINDLESS_NAMESPACE_SRG;
        vape_log(L"runtime namespace: SRG (Forge)");
    } else {
        vape_log(L"runtime namespace is ambiguous (clickMouse=%d, func_147116_af=%d)",
                mcp_click != NULL, srg_click != NULL);
    }

cleanup:
    if (minecraft != NULL) (*env)->DeleteLocalRef(env, minecraft);
    if (loader_class != NULL) (*env)->DeleteLocalRef(env, loader_class);
    return result;
}

static int loader_has_class(JNIEnv *env, jobject loader,
        const char *dotted_name) {
    jclass loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID load_class = loader_class == NULL ? NULL : (*env)->GetMethodID(env,
            loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jclass found = load_class == NULL ? NULL : load_class_via_loader(
            env, loader, load_class, dotted_name);
    int result = found != NULL;
    if (found != NULL) (*env)->DeleteLocalRef(env, found);
    if (loader_class != NULL) (*env)->DeleteLocalRef(env, loader_class);
    return result;
}

static int validate_required_forge_api(JNIEnv *env, jobject loader) {
    static const char *required_classes[] = {
        "net.minecraftforge.common.MinecraftForge",
        "net.minecraftforge.common.ForgeHooks",
        "net.minecraftforge.fml.common.Mod",
        "net.minecraftforge.fml.common.Mod$EventHandler",
        "net.minecraftforge.fml.common.FMLCommonHandler",
        "net.minecraftforge.fml.common.event.FMLInitializationEvent",
        "net.minecraftforge.fml.common.eventhandler.Cancelable",
        "net.minecraftforge.fml.common.eventhandler.Event",
        "net.minecraftforge.fml.common.eventhandler.EventBus",
        "net.minecraftforge.fml.common.eventhandler.EventPriority",
        "net.minecraftforge.fml.common.eventhandler.SubscribeEvent",
        "net.minecraftforge.fml.common.gameevent.TickEvent",
        "net.minecraftforge.fml.common.gameevent.TickEvent$ClientTickEvent",
        "net.minecraftforge.fml.common.gameevent.TickEvent$PlayerTickEvent",
        "net.minecraftforge.fml.common.gameevent.TickEvent$RenderTickEvent",
        "net.minecraftforge.fml.common.network.ByteBufUtils",
        "net.minecraftforge.fml.common.network.handshake.FMLHandshakeMessage$ModList",
        "net.minecraftforge.fml.relauncher.ReflectionHelper",
        "net.minecraftforge.fml.client.config.GuiButtonExt",
        "net.minecraftforge.client.ClientCommandHandler",
        "net.minecraftforge.client.event.ClientChatReceivedEvent",
        "net.minecraftforge.client.event.DrawBlockHighlightEvent",
        "net.minecraftforge.client.event.GuiOpenEvent",
        "net.minecraftforge.client.event.GuiScreenEvent$InitGuiEvent$Pre",
        "net.minecraftforge.client.event.GuiScreenEvent$InitGuiEvent$Post",
        "net.minecraftforge.client.event.MouseEvent",
        "net.minecraftforge.client.event.RenderLivingEvent$Specials$Pre",
        "net.minecraftforge.client.event.RenderPlayerEvent$Pre",
        "net.minecraftforge.client.event.RenderPlayerEvent$Post",
        "net.minecraftforge.client.event.RenderWorldLastEvent",
        "net.minecraftforge.event.entity.EntityJoinWorldEvent",
        "net.minecraftforge.event.entity.living.LivingEvent",
        "net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent",
        "net.minecraftforge.event.entity.player.AttackEntityEvent"
    };
    size_t index;
    for (index = 0; index < sizeof(required_classes) / sizeof(required_classes[0]);
            ++index) {
        if (!loader_has_class(env, loader, required_classes[index])) {
            vape_log(L"required Forge/FML class is absent: %hs", required_classes[index]);
            vape_log(L"the selected runtime payload is incomplete or was not "
                    L"attached to Minecraft's defining class loader");
            return 0;
        }
    }
    vape_log(L"validated %u required Forge/FML classes",
            (unsigned int)(sizeof(required_classes) / sizeof(required_classes[0])));
    return 1;
}

static int set_system_property(JNIEnv *env, const char *property_name,
        const char *property_value) {
    jclass system_class = (*env)->FindClass(env, "java/lang/System");
    jmethodID set_property = system_class == NULL ? NULL : (*env)->GetStaticMethodID(
            env, system_class, "setProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring key = NULL;
    jstring value = NULL;
    jobject previous = NULL;
    int result = 0;
    if (set_property == NULL) {
        vape_log_pending_exception(env, L"resolve System.setProperty");
        goto cleanup;
    }
    key = (*env)->NewStringUTF(env, property_name);
    value = (*env)->NewStringUTF(env, property_value);
    if (key == NULL || value == NULL) goto cleanup;
    previous = (*env)->CallStaticObjectMethod(env, system_class, set_property, key, value);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"System.setProperty(runtime selector)");
        goto cleanup;
    }
    result = 1;

cleanup:
    if (previous != NULL) (*env)->DeleteLocalRef(env, previous);
    if (value != NULL) (*env)->DeleteLocalRef(env, value);
    if (key != NULL) (*env)->DeleteLocalRef(env, key);
    if (system_class != NULL) (*env)->DeleteLocalRef(env, system_class);
    return result;
}

static int set_runtime_properties(JNIEnv *env,
        mindless_runtime_namespace runtime_namespace, int embedded_forge) {
    const char *namespace_name = runtime_namespace == MINDLESS_NAMESPACE_MCP
            ? "mcp" : "srg";
    const char *profile_name = runtime_namespace == MINDLESS_NAMESPACE_MCP
            ? "lunar" : "forge";
    return set_system_property(env, "mindless.runtimeNamespace", namespace_name)
            && set_system_property(env, "mindless.runtimeProfile", profile_name)
            && set_system_property(env, "mindless.embeddedForge",
                    embedded_forge ? "true" : "false");
}

static int set_current_context_class_loader(JNIEnv *env, jobject loader) {
    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
    jmethodID current_thread;
    jmethodID set_context_loader;
    jobject thread;
    if (thread_class == NULL) {
        vape_log_pending_exception(env, L"resolve java.lang.Thread");
        return 0;
    }
    current_thread = (*env)->GetStaticMethodID(env, thread_class,
            "currentThread", "()Ljava/lang/Thread;");
    set_context_loader = (*env)->GetMethodID(env, thread_class,
            "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
    if (current_thread == NULL || set_context_loader == NULL) {
        vape_log_pending_exception(env, L"resolve Thread context ClassLoader methods");
        return 0;
    }
    thread = (*env)->CallStaticObjectMethod(env, thread_class, current_thread);
    if (thread == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"Thread.currentThread");
        return 0;
    }
    (*env)->CallVoidMethod(env, thread, set_context_loader, loader);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"Thread.setContextClassLoader");
        return 0;
    }
    return 1;
}

static jclass load_bootstrap_class(JNIEnv *env, jobject loader) {
    jclass loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID load_class;
    jstring name;
    jclass result;
    if (loader_class == NULL) return NULL;
    load_class = (*env)->GetMethodID(env, loader_class,
            "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    name = (*env)->NewStringUTF(env, "mindless.runtime.NativeBootstrap");
    if (load_class == NULL || name == NULL) return NULL;
    result = (jclass)(*env)->CallObjectMethod(env, loader, load_class, name);
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"load mindless.runtime.NativeBootstrap");
        return NULL;
    }
    return result;
}

static int call_bootstrap_start(JNIEnv *env, jclass bootstrap) {
    jmethodID start = (*env)->GetStaticMethodID(env, bootstrap, "start", "()V");
    if (start == NULL) {
        vape_log_pending_exception(env, L"resolve NativeBootstrap.start");
        return 0;
    }
    (*env)->CallStaticVoidMethod(env, bootstrap, start);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"NativeBootstrap.start");
        return 0;
    }
    return 1;
}

static int pin_native_module(void) {
    return 1;
}

/* -------------------------------------------------------------------------
 * ClassTransform bridge:
 *   1) Install a ClassFileLoadHook that routes every retransform through
 *      mindless.runtime.TransformerHooks.transform(String, byte[]).
 *   2) Enumerate JVMTI GetLoadedClasses, filter to targets, RetransformClasses.
 * ------------------------------------------------------------------------- */

static void JNICALL class_file_load_hook(
        jvmtiEnv *jvmti_env, JNIEnv *env, jclass class_being_redefined,
        jobject loader, const char *name, jobject protection_domain,
        jint class_data_len, const unsigned char *class_data,
        jint *new_class_data_len, unsigned char **new_class_data) {
    jbyteArray original;
    jbyteArray transformed;
    jstring name_string;
    jsize transformed_length;
    unsigned char *allocated;
    (void)jvmti_env; (void)protection_domain;
    if (new_class_data_len != NULL) *new_class_data_len = 0;
    if (new_class_data != NULL) *new_class_data = NULL;
    if (env == NULL || name == NULL || class_data == NULL || class_data_len <= 0) return;
    if (g_game_loader != NULL
            && (loader == NULL || !(*env)->IsSameObject(env, loader, g_game_loader))) {
        return;
    }
    if (g_hooks_class == NULL || g_hooks_transform == NULL) return;

    original = (*env)->NewByteArray(env, class_data_len);
    if (original == NULL) return;
    (*env)->SetByteArrayRegion(env, original, 0, class_data_len, (const jbyte *)class_data);
    name_string = (*env)->NewStringUTF(env, name);
    if (name_string == NULL) {
        (*env)->DeleteLocalRef(env, original);
        return;
    }
    transformed = (jbyteArray)(*env)->CallStaticObjectMethod(env,
            g_hooks_class, g_hooks_transform, name_string, original);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"TransformerHooks.transform");
        (*env)->DeleteLocalRef(env, name_string);
        (*env)->DeleteLocalRef(env, original);
        return;
    }
    (*env)->DeleteLocalRef(env, name_string);
    (*env)->DeleteLocalRef(env, original);
    if (transformed == NULL) return;

    transformed_length = (*env)->GetArrayLength(env, transformed);
    if (transformed_length <= 0) {
        (*env)->DeleteLocalRef(env, transformed);
        return;
    }
    if ((*g_jvmti)->Allocate(g_jvmti, transformed_length, &allocated) != JVMTI_ERROR_NONE) {
        (*env)->DeleteLocalRef(env, transformed);
        return;
    }
    (*env)->GetByteArrayRegion(env, transformed, 0, transformed_length, (jbyte *)allocated);
    (*env)->DeleteLocalRef(env, transformed);
    *new_class_data = allocated;
    *new_class_data_len = transformed_length;
}

JNIEXPORT jboolean JNICALL Java_mindless_runtime_TransformerHooks_untransformNative0(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL Java_mindless_runtime_TransformerHooks_retransformNative0(JNIEnv *env, jclass clazz);

static int resolve_transformer_hooks(JNIEnv *env, jobject class_loader) {
    jclass local_loader_class;
    jmethodID load_class;
    jstring hooks_name;
    jclass local_hooks;
    JNINativeMethod hook_methods[2];
    if (g_hooks_class != NULL && g_hooks_transform != NULL) return 1;
    local_loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    if (local_loader_class == NULL) return 0;
    load_class = (*env)->GetMethodID(env, local_loader_class,
            "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    hooks_name = (*env)->NewStringUTF(env, "mindless.runtime.TransformerHooks");
    if (load_class == NULL || hooks_name == NULL) return 0;
    local_hooks = (jclass)(*env)->CallObjectMethod(env, class_loader, load_class, hooks_name);
    if (local_hooks == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"load TransformerHooks");
        return 0;
    }
    g_hooks_class = (jclass)(*env)->NewGlobalRef(env, local_hooks);
    g_hooks_transform = (*env)->GetStaticMethodID(env, local_hooks,
            "transform", "(Ljava/lang/String;[B)[B");
    if (g_hooks_class == NULL || g_hooks_transform == NULL) {
        vape_log_pending_exception(env, L"resolve TransformerHooks.transform");
        return 0;
    }

    hook_methods[0].name = (char *)"untransformNative0";
    hook_methods[0].signature = (char *)"()Z";
    hook_methods[0].fnPtr = (void *)&Java_mindless_runtime_TransformerHooks_untransformNative0;

    hook_methods[1].name = (char *)"retransformNative0";
    hook_methods[1].signature = (char *)"()Z";
    hook_methods[1].fnPtr = (void *)&Java_mindless_runtime_TransformerHooks_retransformNative0;

    if ((*env)->RegisterNatives(env, g_hooks_class, hook_methods, 2) != 0) {
        vape_log_pending_exception(env, L"RegisterNatives on TransformerHooks");
    } else {
        vape_log(L"RegisterNatives succeeded on TransformerHooks");
    }

    return 1;
}

static int install_class_file_load_hook(void) {
    jvmtiCapabilities requested;
    jvmtiEventCallbacks callbacks;
    jvmtiError error;
    jvmtiCapabilities have;
    if (InterlockedCompareExchange(&g_hook_registered, 0, 0) != 0) return 1;
    if (g_jvmti == NULL) return 0;

    memset(&requested, 0, sizeof(requested));
    memset(&have, 0, sizeof(have));
    (*g_jvmti)->GetCapabilities(g_jvmti, &have);
    if (!have.can_retransform_classes) {
        requested.can_retransform_classes = 1;
        error = (*g_jvmti)->AddCapabilities(g_jvmti, &requested);
        if (error != JVMTI_ERROR_NONE) {
            vape_log(L"AddCapabilities(can_retransform_classes) failed: %d", error);
            return 0;
        }
    }

    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = class_file_load_hook;
    error = (*g_jvmti)->SetEventCallbacks(g_jvmti, &callbacks, sizeof(callbacks));
    if (error != JVMTI_ERROR_NONE) {
        vape_log(L"SetEventCallbacks failed: %d", error);
        return 0;
    }
    error = (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    if (error != JVMTI_ERROR_NONE) {
        jvmtiEventCallbacks empty_callbacks;
        vape_log(L"SetEventNotificationMode(ENABLE) failed: %d", error);
        memset(&empty_callbacks, 0, sizeof(empty_callbacks));
        (*g_jvmti)->SetEventCallbacks(g_jvmti, &empty_callbacks,
                sizeof(empty_callbacks));
        return 0;
    }
    InterlockedExchange(&g_hook_registered, 1);
    InterlockedExchange(&g_hook_ever_published, 1);
    return 1;
}

/* Stop future callbacks before abandoning a failed bootstrap. The DLL stays
 * pinned once a callback has ever been published, so a callback already in
 * flight can safely finish even while this thread tears down its JNI attach. */
static int disable_class_file_load_hook(void) {
    jvmtiEventCallbacks empty_callbacks;
    jvmtiError notification_error;
    jvmtiError callbacks_error;
    int notification_disabled;
    int callbacks_cleared;
    if (g_jvmti == NULL
            || InterlockedCompareExchange(&g_hook_registered, 0, 0) == 0) {
        return 1;
    }

    notification_error = (*g_jvmti)->SetEventNotificationMode(g_jvmti,
            JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    notification_disabled = notification_error == JVMTI_ERROR_NONE;
    if (!notification_disabled) {
        vape_log(L"SetEventNotificationMode(DISABLE) failed: %d",
                notification_error);
    }

    memset(&empty_callbacks, 0, sizeof(empty_callbacks));
    callbacks_error = (*g_jvmti)->SetEventCallbacks(g_jvmti,
            &empty_callbacks, sizeof(empty_callbacks));
    callbacks_cleared = callbacks_error == JVMTI_ERROR_NONE;
    if (!callbacks_cleared) {
        vape_log(L"SetEventCallbacks(clear) failed: %d", callbacks_error);
    }

    if (notification_disabled && callbacks_cleared) {
        InterlockedExchange(&g_hook_registered, 0);
        vape_log(L"ClassFileLoadHook disabled");
        return 1;
    }
    return 0;
}

/* Loads a class through the LaunchClassLoader (dotted name). Returns NULL and
 * clears any pending exception on failure. */
static jclass load_class_via_loader(JNIEnv *env, jobject class_loader,
        jmethodID load_class, const char *dotted_name) {
    jstring name = (*env)->NewStringUTF(env, dotted_name);
    jclass result;
    if (name == NULL) return NULL;
    result = (jclass)(*env)->CallObjectMethod(env, class_loader, load_class, name);
    (*env)->DeleteLocalRef(env, name);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    return result;
}

/* Enumerates loaded classes via JVMTI and returns the one whose class signature
 * matches Lname; (internal form). Cost is O(loaded_classes) but only runs during
 * the retransform pass. */
static jclass find_loaded_class_by_internal_name(JNIEnv *env,
        const char *internal_name, jobject expected_loader) {
    jint count = 0;
    jclass *classes = NULL;
    jclass result = NULL;
    jvmtiError error;
    jint i;
    size_t needle_len;
    if (g_jvmti == NULL || internal_name == NULL) return NULL;
    error = (*g_jvmti)->GetLoadedClasses(g_jvmti, &count, &classes);
    if (error != JVMTI_ERROR_NONE || classes == NULL) return NULL;
    needle_len = strlen(internal_name);
    for (i = 0; i < count; ++i) {
        char *signature = NULL;
        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &signature, NULL)
                == JVMTI_ERROR_NONE && signature != NULL) {
            size_t sig_len = strlen(signature);
            if (sig_len == needle_len + 2 && signature[0] == 'L'
                    && signature[sig_len - 1] == ';'
                    && strncmp(signature + 1, internal_name, needle_len) == 0) {
                jobject actual_loader = NULL;
                int loader_matches = 0;
                if ((*g_jvmti)->GetClassLoader(g_jvmti, classes[i], &actual_loader)
                        == JVMTI_ERROR_NONE) {
                    loader_matches = expected_loader == NULL
                            ? 1
                            : actual_loader != NULL && (*env)->IsSameObject(
                                    env, actual_loader, expected_loader);
                }
                if (actual_loader != NULL) (*env)->DeleteLocalRef(env, actual_loader);
                if (loader_matches) {
                    /* Keep this local reference and release every other
                     * reference returned by GetLoadedClasses below. */
                    result = classes[i];
                    classes[i] = NULL;
                }
            }
            (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)signature);
        }
        if (result != NULL) break;
    }
    for (i = 0; i < count; ++i) {
        if (classes[i] != NULL) (*env)->DeleteLocalRef(env, classes[i]);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
    return result;
}

static int collect_loaded_registered_targets(JNIEnv *env, jobject class_loader,
        jclass **classes_out, jsize *count_out) {
    jclass loader_class = NULL;
    jmethodID load_class;
    jclass manager_class = NULL;
    jmethodID get_instance;
    jmethodID target_names;
    jobject manager = NULL;
    jobject set = NULL;
    jclass set_class = NULL;
    jmethodID to_array;
    jobjectArray names = NULL;
    jclass *classes = NULL;
    jsize name_count;
    jsize count = 0;
    jsize i;
    int result = 0;

    if (classes_out == NULL || count_out == NULL) return 0;
    *classes_out = NULL;
    *count_out = 0;

    loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    if (loader_class == NULL) {
        vape_log_pending_exception(env, L"resolve java.lang.ClassLoader");
        goto cleanup;
    }
    load_class = (*env)->GetMethodID(env, loader_class,
            "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class == NULL) {
        vape_log_pending_exception(env, L"resolve ClassLoader.loadClass");
        goto cleanup;
    }

    manager_class = load_class_via_loader(env, class_loader, load_class,
            "mindless.runtime.MindlessTransformerManager");
    if (manager_class == NULL) {
        vape_log(L"MindlessTransformerManager not visible via LaunchClassLoader");
        goto cleanup;
    }
    get_instance = (*env)->GetStaticMethodID(env, manager_class,
            "get", "()Lmindless/runtime/MindlessTransformerManager;");
    target_names = (*env)->GetMethodID(env, manager_class,
            "targetInternalNames", "()Ljava/util/Set;");
    if (get_instance == NULL || target_names == NULL) {
        vape_log_pending_exception(env, L"resolve MindlessTransformerManager methods");
        goto cleanup;
    }

    manager = (*env)->CallStaticObjectMethod(env, manager_class, get_instance);
    if (manager == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"MindlessTransformerManager.get");
        goto cleanup;
    }
    set = (*env)->CallObjectMethod(env, manager, target_names);
    if (set == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"MindlessTransformerManager.targetInternalNames");
        goto cleanup;
    }
    set_class = (*env)->FindClass(env, "java/util/Set");
    if (set_class == NULL) {
        vape_log_pending_exception(env, L"resolve java.util.Set");
        goto cleanup;
    }
    to_array = (*env)->GetMethodID(env, set_class, "toArray", "()[Ljava/lang/Object;");
    if (to_array == NULL) {
        vape_log_pending_exception(env, L"resolve Set.toArray");
        goto cleanup;
    }
    names = (jobjectArray)(*env)->CallObjectMethod(env, set, to_array);
    if (names == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"Set.toArray");
        goto cleanup;
    }
    name_count = (*env)->GetArrayLength(env, names);
    if (name_count <= 0) {
        vape_log(L"transformer manager has no targets");
        goto cleanup;
    }

    classes = (jclass *)calloc((size_t)name_count, sizeof(jclass));
    if (classes == NULL) {
        vape_log(L"could not allocate loaded-target list");
        goto cleanup;
    }

    for (i = 0; i < name_count; ++i) {
        jstring name = (jstring)(*env)->GetObjectArrayElement(env, names, i);
        const char *chars;
        jclass klass = NULL;
        if (name == NULL) continue;
        chars = (*env)->GetStringUTFChars(env, name, NULL);
        if (chars == NULL) {
            (*env)->DeleteLocalRef(env, name);
            vape_log_pending_exception(env, L"read transformer target name");
            goto cleanup;
        }
        /* JVMTI GetLoadedClasses walks every loaded class (not just those the
         * app loader can resolve), which is what we need for MC classes owned
         * by LaunchClassLoader. FindClass with an internal-name would only look
         * in the current loader chain and would miss most targets. */
        klass = find_loaded_class_by_internal_name(env, chars, class_loader);
        if (klass == NULL) {
            vape_log(L"retransform target %hs not loaded yet, skipping", chars);
        } else {
            classes[count++] = klass;
        }
        (*env)->ReleaseStringUTFChars(env, name, chars);
        (*env)->DeleteLocalRef(env, name);
    }

    *classes_out = classes;
    *count_out = count;
    classes = NULL;
    result = 1;

cleanup:
    if (classes != NULL) {
        for (i = 0; i < count; ++i) {
            (*env)->DeleteLocalRef(env, classes[i]);
        }
        free(classes);
    }
    if (names != NULL) (*env)->DeleteLocalRef(env, names);
    if (set_class != NULL) (*env)->DeleteLocalRef(env, set_class);
    if (set != NULL) (*env)->DeleteLocalRef(env, set);
    if (manager != NULL) (*env)->DeleteLocalRef(env, manager);
    if (manager_class != NULL) (*env)->DeleteLocalRef(env, manager_class);
    if (loader_class != NULL) (*env)->DeleteLocalRef(env, loader_class);
    return result;
}

/* Targets the client cannot run without.
 *
 * Minecraft carries the tick and input bridge, so without it nothing updates, no keybind is read
 * and no event is posted -- a client that started anyway would be indistinguishable from one that
 * never started. Everything else is a single feature, and a host update that refactors one of
 * those classes should cost that feature and nothing more. Requiring the whole batch is what
 * turned one refactored class into a client that never called NativeBootstrap.start. */
static int is_required_target(const char *class_signature) {
    if (class_signature == NULL) return 0;
    return strcmp(class_signature, "Lnet/minecraft/client/Minecraft;") == 0;
}

/* JVMTI numbers alone say nothing about what went wrong; 62 in particular is the one a host
 * update produces and the one worth recognising on sight. */
static const wchar_t *jvmti_error_name(jvmtiError error) {
    switch (error) {
        case JVMTI_ERROR_UNMODIFIABLE_CLASS: return L"UNMODIFIABLE_CLASS";
        case JVMTI_ERROR_INVALID_CLASS: return L"INVALID_CLASS";
        case JVMTI_ERROR_UNSUPPORTED_VERSION: return L"UNSUPPORTED_VERSION";
        case JVMTI_ERROR_INVALID_CLASS_FORMAT: return L"INVALID_CLASS_FORMAT";
        case JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION: return L"CIRCULAR_CLASS_DEFINITION";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED: return L"REDEFINITION_METHOD_ADDED";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED: return L"REDEFINITION_SCHEMA_CHANGED";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED: return L"REDEFINITION_HIERARCHY_CHANGED";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED: return L"REDEFINITION_METHOD_DELETED";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED: return L"REDEFINITION_CLASS_MODIFIERS_CHANGED";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED: return L"REDEFINITION_METHOD_MODIFIERS_CHANGED";
        case JVMTI_ERROR_FAILS_VERIFICATION: return L"FAILS_VERIFICATION";
        case JVMTI_ERROR_NAMES_DONT_MATCH: return L"NAMES_DONT_MATCH";
        case JVMTI_ERROR_OUT_OF_MEMORY: return L"OUT_OF_MEMORY";
        default: return L"?";
    }
}

static int retransform_registered_targets(JNIEnv *env, jobject class_loader) {
    jsize i;
    jclass *classes_to_retransform = NULL;
    unsigned char *retransform_succeeded;
    jsize retransform_count = 0;

    if (!collect_loaded_registered_targets(env, class_loader,
            &classes_to_retransform, &retransform_count)) {
        return 0;
    }
    if (retransform_count == 0) {
        free(classes_to_retransform);
        vape_log(L"no registered target is loaded; Minecraft itself should be present");
        return 0;
    }
    retransform_succeeded = (unsigned char *)calloc(
            (size_t)retransform_count, sizeof(unsigned char));
    if (retransform_succeeded == NULL) {
        for (i = 0; i < retransform_count; ++i) {
            (*env)->DeleteLocalRef(env, classes_to_retransform[i]);
        }
        free(classes_to_retransform);
        vape_log(L"could not allocate retransform rollback tracking");
        return 0;
    }
    /* Retransform one at a time so the log identifies the exact failing class.
     * Startup is accepted only if every loaded target succeeds. The per-class
     * result also prevents one failure from hiding which target caused it. */
    {
        jint succeeded = 0;
        jint required_failed = 0;
        int batch_succeeded;
        for (i = 0; i < retransform_count; ++i) {
            jvmtiError err;
            char *sig = NULL;
            (*g_jvmti)->GetClassSignature(g_jvmti,
                    classes_to_retransform[i], &sig, NULL);
            err = (*g_jvmti)->RetransformClasses(g_jvmti, 1,
                    &classes_to_retransform[i]);
            {
                float step = 0.83f + 0.14f * (float)(i + 1)
                        / (float)retransform_count;
                char status[256];
                _snprintf_s(status, sizeof(status), _TRUNCATE,
                        "Transforming %s", sig ? sig : "<unknown>");
                send_progress(step, status);
            }
            if (err == JVMTI_ERROR_NONE) {
                retransform_succeeded[i] = 1;
                ++succeeded;
            } else if (is_required_target(sig)) {
                ++required_failed;
                vape_log(L"RetransformClasses FAILED (err=%d %ls) for REQUIRED target %hs",
                        err, jvmti_error_name(err), sig ? sig : "<unknown>");
            } else {
                vape_log(L"RetransformClasses skipped (err=%d %ls) for %hs — "
                        L"that hook will be missing, startup continues",
                        err, jvmti_error_name(err), sig ? sig : "<unknown>");
            }
            if (sig) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)sig);
        }
        vape_log(L"retransformed %d/%d classes", succeeded, retransform_count);
        /* A partial batch is no longer fatal on its own. Every class that failed kept its
         * original definition -- JVMTI is atomic per call -- so the cost is the hooks in that one
         * class, and the Java side names it in the transformer log. Only a required target being
         * unusable is a reason to put the process back the way it was found. */
        batch_succeeded = required_failed == 0;
        if (succeeded != retransform_count && batch_succeeded) {
            vape_log(L"%d optional target(s) were left untransformed; continuing startup",
                    retransform_count - succeeded);
        }

        if (!batch_succeeded) {
            jint rollback_succeeded = 0;
            vape_log(L"a required target could not be transformed; disabling hook and rolling back %d classes",
                    succeeded);
            if (disable_class_file_load_hook()) {
                for (i = 0; i < retransform_count; ++i) {
                    jvmtiError rollback_error;
                    if (!retransform_succeeded[i]) continue;
                    rollback_error = (*g_jvmti)->RetransformClasses(g_jvmti, 1,
                            &classes_to_retransform[i]);
                    if (rollback_error == JVMTI_ERROR_NONE) {
                        ++rollback_succeeded;
                    } else {
                        char *sig = NULL;
                        (*g_jvmti)->GetClassSignature(g_jvmti,
                                classes_to_retransform[i], &sig, NULL);
                        vape_log(L"rollback RetransformClasses failed (err=%d) for %hs",
                                rollback_error, sig ? sig : "<unknown>");
                        if (sig) (*g_jvmti)->Deallocate(g_jvmti,
                                (unsigned char *)sig);
                    }
                }
                vape_log(L"rollback restored %d/%d successfully retransformed classes",
                        rollback_succeeded, succeeded);
            } else {
                vape_log(L"rollback skipped because ClassFileLoadHook could not be disabled");
            }
        }

        for (i = 0; i < retransform_count; ++i) {
            (*env)->DeleteLocalRef(env, classes_to_retransform[i]);
        }
        free(retransform_succeeded);
        free(classes_to_retransform);
        /* A JVMTI success can also mean TransformerHooks returned NULL. The
         * Java-side NativeBootstrap assertion remains responsible for turning
         * that preflight result into a bootstrap failure. */
        return batch_succeeded;
    }
}

/* With Mindless's hook disabled, a JVMTI retransformation starts from the VM's
 * baseline class bytes and therefore removes bytecode supplied by our hook. */
static int restore_loaded_registered_targets(JNIEnv *env, jobject class_loader) {
    jclass *classes_to_restore = NULL;
    jsize restore_count = 0;
    jsize i;
    jint restored = 0;

    if (InterlockedCompareExchange(&g_hook_registered, 0, 0) != 0) {
        vape_log(L"refusing target restoration while ClassFileLoadHook is enabled");
        return 0;
    }
    if (!collect_loaded_registered_targets(env, class_loader,
            &classes_to_restore, &restore_count)) {
        vape_log(L"could not enumerate loaded targets for restoration");
        return 0;
    }
    if (restore_count == 0) {
        free(classes_to_restore);
        vape_log(L"no loaded registered targets required restoration");
        return 1;
    }

    for (i = 0; i < restore_count; ++i) {
        jvmtiError error = (*g_jvmti)->RetransformClasses(g_jvmti, 1,
                &classes_to_restore[i]);
        if (error == JVMTI_ERROR_NONE) {
            ++restored;
        } else {
            char *sig = NULL;
            (*g_jvmti)->GetClassSignature(g_jvmti,
                    classes_to_restore[i], &sig, NULL);
            vape_log(L"target restoration failed (err=%d) for %hs",
                    error, sig ? sig : "<unknown>");
            if (sig) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)sig);
        }
        (*env)->DeleteLocalRef(env, classes_to_restore[i]);
    }
    free(classes_to_restore);
    vape_log(L"restored %d/%d loaded registered targets with hook disabled",
            restored, restore_count);
    return restored == restore_count;
}

/* Force the MindlessTransformerManager singleton to be built now, WITH the hook
 * still disabled. Building it calls addTransformer(...) for every Transformer*
 * class, each of which triggers class loading via the LaunchClassLoader; if
 * the ClassFileLoadHook were already live those loads would recurse into
 * TransformerHooks.transform -> MindlessTransformerManager.get() before the
 * singleton finished initialising. */
static int prime_transformer_manager(JNIEnv *env, jobject class_loader,
        jmethodID load_class) {
    jclass manager_class;
    jmethodID get_instance;
    jobject manager;
    manager_class = load_class_via_loader(env, class_loader, load_class,
            "mindless.runtime.MindlessTransformerManager");
    if (manager_class == NULL) {
        vape_log(L"prime: MindlessTransformerManager not visible via loader");
        return 0;
    }
    get_instance = (*env)->GetStaticMethodID(env, manager_class,
            "get", "()Lmindless/runtime/MindlessTransformerManager;");
    if (get_instance == NULL) {
        vape_log_pending_exception(env, L"prime: resolve MindlessTransformerManager.get");
        return 0;
    }
    manager = (*env)->CallStaticObjectMethod(env, manager_class, get_instance);
    if (manager == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"prime: MindlessTransformerManager.get()");
        return 0;
    }
    vape_log(L"prime: MindlessTransformerManager constructed");
    (*env)->DeleteLocalRef(env, manager);
    (*env)->DeleteLocalRef(env, manager_class);
    return 1;
}

static int apply_transformers(JNIEnv *env, jobject class_loader) {
    jclass loader_class;
    jmethodID load_class;
    int result;

    vape_log(L"apply_transformers: step 1 — resolve TransformerHooks");
    send_progress(0.75f, "Resolving transformer hooks");
    if (!resolve_transformer_hooks(env, class_loader)) return 0;

    loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    load_class = loader_class == NULL ? NULL : (*env)->GetMethodID(env,
            loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class == NULL) {
        vape_log_pending_exception(env, L"apply_transformers: ClassLoader.loadClass");
        return 0;
    }

    vape_log(L"apply_transformers: step 2 — prime manager (hook still OFF)");
    send_progress(0.77f, "Building transformer manager");
    if (!prime_transformer_manager(env, class_loader, load_class)) return 0;

    send_progress(0.79f, "Registering transformation targets");
    vape_log(L"apply_transformers: step 3 — install JVMTI ClassFileLoadHook");
    send_progress(0.81f, "Installing ClassFileLoadHook");
    if (!install_class_file_load_hook()) return 0;

    vape_log(L"apply_transformers: step 4 — retransform loaded targets");
    send_progress(0.83f, "Retransforming loaded classes");
    result = retransform_registered_targets(env, class_loader);
    if (!result
            && InterlockedCompareExchange(&g_hook_registered, 0, 0) != 0
            && !disable_class_file_load_hook()) {
        vape_log(L"apply_transformers could not disable ClassFileLoadHook after failure");
    }
    return result;
}

static DWORD WINAPI bootstrap_thread(LPVOID parameter) {
    HMODULE jvm_module = NULL;
    FARPROC created_vms_address;
    typedef jint (JNICALL *get_created_vms_fn)(JavaVM **, jsize, jsize *);
    get_created_vms_fn get_created_vms;
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jsize vm_count = 0;
    jobject loader = NULL;
    jclass bootstrap_class = NULL;
    mindless_runtime_namespace runtime_namespace = MINDLESS_NAMESPACE_UNKNOWN;
    int payload_resource_id = 0;
    int embedded_forge = 0;
    int attached = 0;
    int module_pinned = 0;
    int attempt;
    HMODULE worker_module = (HMODULE)parameter;
    DWORD exit_code = 1;

    Sleep(150);
    send_progress(0.21f, "Waiting for Java runtime");
    for (attempt = 0; attempt < 600; ++attempt) {
        jvm_module = GetModuleHandleW(L"jvm.dll");
        if (jvm_module != NULL) break;
        if (attempt == 10)  send_progress(0.23f, "Waiting for Java runtime");
        if (attempt == 30)  send_progress(0.25f, "Waiting for Java runtime");
        if (attempt == 60)  send_progress(0.27f, "Waiting for Java runtime");
        if (attempt == 100) send_progress(0.29f, "Waiting for Java runtime");
        if (attempt == 150) send_progress(0.31f, "Waiting for Java runtime");
        if (attempt == 200) send_progress(0.33f, "Waiting for Java runtime");
        Sleep(100);
    }
    if (jvm_module == NULL) {
        vape_log(L"jvm.dll is not loaded");
        exit_code = 2;
        goto cleanup;
    }
    send_progress(0.34f, "Java runtime detected");
    created_vms_address = GetProcAddress(jvm_module, "JNI_GetCreatedJavaVMs");
    if (created_vms_address == NULL) {
        vape_log(L"JNI_GetCreatedJavaVMs export unavailable");
        exit_code = 3;
        goto cleanup;
    }
    send_progress(0.35f, "Locating Java VM");
    get_created_vms = (get_created_vms_fn)created_vms_address;
    for (attempt = 0; attempt < 600; ++attempt) {
        if (get_created_vms(&vm, 1, &vm_count) == JNI_OK && vm != NULL && vm_count >= 1) break;
        vm = NULL; vm_count = 0;
        if (attempt == 5)  send_progress(0.37f, "Waiting for Java VM to initialize");
        if (attempt == 20) send_progress(0.39f, "Waiting for Java VM to initialize");
        if (attempt == 50) send_progress(0.41f, "Waiting for Java VM to initialize");
        Sleep(100);
    }
    if (vm == NULL || vm_count < 1) {
        vape_log(L"JNI_GetCreatedJavaVMs returned no VM");
        exit_code = 4;
        goto cleanup;
    }
    send_progress(0.43f, "Java VM ready");
    if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK || env == NULL) {
        vape_log(L"AttachCurrentThreadAsDaemon failed");
        exit_code = 5;
        goto cleanup;
    }
    attached = 1;
    send_progress(0.45f, "Attached to Java VM");

    /* Read auth data from the shared memory section the loader created */
    {
        wchar_t section_name[128];
        HANDLE mapping;
        _snwprintf_s(section_name, sizeof(section_name) / sizeof(section_name[0]),
                _TRUNCATE, L"Local\\MindlessAuth_%lu",
                (unsigned long)GetCurrentProcessId());
        mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, section_name);
        if (mapping != NULL) {
            struct { char token[512]; char api_url[256]; char hwid[256]; } *auth_data;
            auth_data = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0,
                    sizeof(*auth_data));
            if (auth_data != NULL) {
                jclass sys = (*env)->FindClass(env, "java/lang/System");
                jmethodID setProp = sys == NULL ? NULL
                        : (*env)->GetStaticMethodID(env, sys, "setProperty",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
                if (setProp != NULL) {
                    jstring k, v; jobject prev;
                    if (auth_data->token[0]) {
                        k = (*env)->NewStringUTF(env, "mindless.auth.token");
                        v = (*env)->NewStringUTF(env, auth_data->token);
                        prev = (*env)->CallStaticObjectMethod(env, sys, setProp, k, v);
                        if (prev) (*env)->DeleteLocalRef(env, prev);
                        (*env)->DeleteLocalRef(env, k);
                        (*env)->DeleteLocalRef(env, v);
                    }
                    if (auth_data->api_url[0]) {
                        k = (*env)->NewStringUTF(env, "mindless.auth.apiUrl");
                        v = (*env)->NewStringUTF(env, auth_data->api_url);
                        prev = (*env)->CallStaticObjectMethod(env, sys, setProp, k, v);
                        if (prev) (*env)->DeleteLocalRef(env, prev);
                        (*env)->DeleteLocalRef(env, k);
                        (*env)->DeleteLocalRef(env, v);
                    }
                    if (auth_data->hwid[0]) {
                        k = (*env)->NewStringUTF(env, "mindless.auth.hwid");
                        v = (*env)->NewStringUTF(env, auth_data->hwid);
                        prev = (*env)->CallStaticObjectMethod(env, sys, setProp, k, v);
                        if (prev) (*env)->DeleteLocalRef(env, prev);
                        (*env)->DeleteLocalRef(env, k);
                        (*env)->DeleteLocalRef(env, v);
                    }
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                }
                UnmapViewOfFile(auth_data);
            }
            CloseHandle(mapping);
            vape_log(L"auth data received from loader");
        } else {
            vape_log(L"no auth shared section found (standalone injection?)");
        }
    }

    if (mindless_initialize_jvmti(vm) != JNI_OK) {
        exit_code = 6;
        goto cleanup;
    }
    send_progress(0.48f, "JVMTI agent initialized");
    send_progress(0.50f, "Searching for Minecraft class loader");
    for (attempt = 0; attempt < 600 && loader == NULL; ++attempt) {
        loader = find_client_minecraft_class_loader(env);
        if (loader == NULL) loader = find_minecraft_class_loader(env);
        if (loader == NULL) {
            if (attempt == 10) send_progress(0.51f, "Waiting for Minecraft to initialize");
            Sleep(100);
        }
    }
    if (loader == NULL) {
        vape_log(L"active Minecraft defining ClassLoader not found safely within 60s");
        exit_code = 8;
        goto cleanup;
    }
    g_game_loader = (*env)->NewGlobalRef(env, loader);
    if (g_game_loader == NULL) {
        vape_log_pending_exception(env, L"create global game ClassLoader reference");
        exit_code = 18;
        goto cleanup;
    }
    send_progress(0.53f, "Minecraft class loader found");
    runtime_namespace = detect_runtime_namespace(env, loader);
    if (runtime_namespace == MINDLESS_NAMESPACE_UNKNOWN) {
        exit_code = 15;
        goto cleanup;
    }
    send_progress(0.55f, "Detected runtime namespace");
    /* Plain Lunar + OptiFine has named Minecraft classes but no Forge API.
     * The MCP payload embeds the required API and a loader-independent event
     * bus. Record whether that compatibility layer must emit lifecycle events. */
    embedded_forge = runtime_namespace == MINDLESS_NAMESPACE_MCP
            && !(loader_has_class(env, loader,
                    "net.minecraftforge.common.MinecraftForge")
                 && loader_has_class(env, loader,
                    "net.minecraftforge.fml.common.eventhandler.EventBus"));
    if (!set_runtime_properties(env, runtime_namespace, embedded_forge)) {
        exit_code = 17;
        goto cleanup;
    }
    send_progress(0.57f, "Runtime properties configured");
    if (runtime_namespace == MINDLESS_NAMESPACE_MCP) {
        payload_resource_id = MINDLESS_LUNAR_PAYLOAD_RESOURCE_ID;
    } else {
        payload_resource_id = MINDLESS_FORGE_PAYLOAD_RESOURCE_ID;
    }
    send_progress(0.59f, "Loading payload");
    if (!load_payload_from_memory(env, loader, payload_resource_id)) {
        exit_code = 7;
        goto cleanup;
    }
    send_progress(0.65f, "Payload loaded");
    /* Validate after appending: resource 422 supplies these classes for a
     * direct Lunar/OptiFine launch, while Forge/SRG still resolves its own. */
    if (!validate_required_forge_api(env, loader)) {
        exit_code = 16;
        goto cleanup;
    }
    send_progress(0.67f, "Forge API validated");
    if (embedded_forge) {
        vape_log(L"using embedded Forge event contracts for Lunar OptiFine");
    }
    send_progress(0.69f, "Configuring class loader context");
    if (!set_current_context_class_loader(env, loader)) {
        exit_code = 10;
        goto cleanup;
    }
    send_progress(0.71f, "Loading bootstrap class");
    bootstrap_class = load_bootstrap_class(env, loader);
    if (bootstrap_class == NULL) {
        exit_code = 11;
        goto cleanup;
    }
    if (!pin_native_module()) {
        exit_code = 12;
        goto cleanup;
    }
    module_pinned = 1;
    send_progress(0.73f, "Native module pinned");
    vape_log(L"NativeBootstrap linked from in-memory payload");
    if (!apply_transformers(env, loader)) {
        vape_log(L"apply_transformers failed; Mindless will not start with missing hooks");
        exit_code = 14;
        goto cleanup;
    }
    send_progress(0.98f, "Handing off to Java bootstrap");
    if (!call_bootstrap_start(env, bootstrap_class)) {
        exit_code = 13;
        goto cleanup;
    }
    send_progress(1.0f, "Ready");
    vape_log(L"NativeBootstrap.start completed; Mindless is active");
    exit_code = 0;

cleanup:
    if (exit_code != 0
            && InterlockedCompareExchange(&g_hook_registered, 0, 0) != 0) {
        if (!disable_class_file_load_hook()) {
            vape_log(L"bootstrap cleanup could not disable ClassFileLoadHook; "
                    L"the pinned DLL will keep the callback code valid");
        } else if (env != NULL && loader != NULL) {
            if (!restore_loaded_registered_targets(env, loader)) {
                vape_log(L"bootstrap cleanup could not restore every loaded target");
            }
        } else {
            vape_log(L"bootstrap cleanup could not enumerate targets for restoration");
        }
    }
    if (bootstrap_class != NULL) (*env)->DeleteLocalRef(env, bootstrap_class);
    if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
    if (exit_code != 0 && g_game_loader != NULL
            && InterlockedCompareExchange(&g_hook_ever_published, 0, 0) == 0) {
        (*env)->DeleteGlobalRef(env, g_game_loader);
        g_game_loader = NULL;
    }
    if (attached) (*vm)->DetachCurrentThread(vm);
    if (exit_code != 0 && worker_module != NULL) {
        if (module_pinned) {
            vape_log(L"bootstrap failed (%lu); DLL remains pinned for callback safety",
                    exit_code);
        } else {
            vape_log(L"bootstrap failed (%lu); unloading DLL", exit_code);
            FreeLibraryAndExitThread(worker_module, exit_code);
        }
    }
    return exit_code;
}

JNIEXPORT jboolean JNICALL Java_mindless_runtime_TransformerHooks_untransformNative0(JNIEnv *env, jclass clazz) {
    (void)clazz;
    int ok;
    vape_log(L"JNI untransformNative0 called — disabling hook and restoring original target bytes");
    disable_class_file_load_hook();
    if (env == NULL) return JNI_FALSE;
    ok = restore_loaded_registered_targets(env, g_game_loader);
    if (g_game_loader != NULL) {
        (*env)->DeleteGlobalRef(env, g_game_loader);
        g_game_loader = NULL;
    }
    if (g_hooks_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_hooks_class);
        g_hooks_class = NULL;
        g_hooks_transform = NULL;
    }
    vape_log(L"target restoration complete; scheduling DLL unload");
    if (g_module != NULL) {
        HANDLE thread = CreateThread(NULL, 0, (LPTHREAD_START_ROUTINE)FreeLibraryAndExitThread, g_module, 0, NULL);
        if (thread != NULL) CloseHandle(thread);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_mindless_runtime_TransformerHooks_retransformNative0(JNIEnv *env, jclass clazz) {
    (void)clazz;
    vape_log(L"JNI retransformNative0 called — enabling hook and retransforming targets");
    if (!install_class_file_load_hook()) return JNI_FALSE;
    if (env == NULL) return JNI_FALSE;
    return retransform_registered_targets(env, g_game_loader) ? JNI_TRUE : JNI_FALSE;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    HANDLE thread;
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = instance;
        DisableThreadLibraryCalls(instance);
        thread = CreateThread(NULL, 0, bootstrap_thread, instance, 0, NULL);
        if (thread == NULL) {
            OutputDebugStringW(L"MindlessNative: CreateThread for bootstrap failed\r\n");
            g_module = NULL;
            return FALSE;
        }
        CloseHandle(thread);
    }
    return TRUE;
}
