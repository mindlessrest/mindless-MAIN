#include "raven_native.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

/*
 * RavenNative.dll — direct injection bootstrap for the Raven b4/bS mod on
 * Minecraft 1.8.9 Forge and Forge-enabled Lunar. Loaded into javaw.exe via
 * CreateRemoteThread + LoadLibraryW by RavenInjector.exe. Waits for the client
 * JVM, selects the SRG or MCP payload, appends it to the Minecraft class
 * loader, and invokes mindless.runtime.NativeBootstrap.start().
 *
 * Raven uses ClassTransform plus JVMTI: already-loaded targets are
 * retransformed immediately and targets loaded later pass through the same
 * ClassFileLoadHook.
 */

JavaVM   *g_vm     = NULL;
jvmtiEnv *g_jvmti  = NULL;
HMODULE   g_module = NULL;
HANDLE    g_progress_pipe = INVALID_HANDLE_VALUE;

static void send_progress(float progress, const char *status) {
    char message[512];
    DWORD written;
    if (g_progress_pipe == INVALID_HANDLE_VALUE) {
        g_progress_pipe = CreateFileW(L"\\\\.\\pipe\\MindlessProgress",
                GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
    }
    if (g_progress_pipe != INVALID_HANDLE_VALUE) {
        int len = _snprintf_s(message, sizeof(message), _TRUNCATE,
                "PROGRESS:%f:%s\n", progress, status ? status : "");
        if (len > 0) {
            WriteFile(g_progress_pipe, message, (DWORD)len, &written, NULL);
        }
    }
}

#define RAVEN_FORGE_PAYLOAD_RESOURCE_ID 421
#define RAVEN_LUNAR_PAYLOAD_RESOURCE_ID 422

typedef enum raven_runtime_namespace {
    RAVEN_NAMESPACE_UNKNOWN = 0,
    RAVEN_NAMESPACE_MCP,
    RAVEN_NAMESPACE_SRG
} raven_runtime_namespace;

/* ClassFileLoadHook plumbing */
static jclass    g_hooks_class = NULL;      /* mindless.runtime.TransformerHooks */
static jmethodID g_hooks_transform = NULL;  /* static byte[] transform(String, byte[]) */
static jobject   g_game_loader = NULL;      /* global ref; filters duplicate class names */
static volatile LONG g_hook_registered = 0;
static volatile LONG g_hook_ever_published = 0;

static jclass load_class_via_loader(JNIEnv *env, jobject class_loader,
        jmethodID load_class, const char *dotted_name);

static int module_directory(wchar_t *output, size_t capacity) {
    DWORD length;
    wchar_t *separator;
    if (output == NULL || capacity == 0 || g_module == NULL) return 0;
    length = GetModuleFileNameW(g_module, output, (DWORD)capacity);
    if (length == 0 || length >= capacity) return 0;
    separator = wcsrchr(output, L'\\');
    if (separator == NULL) return 0;
    *separator = L'\0';
    return 1;
}

void vape_log(const wchar_t *format, ...) {
    wchar_t message[2048];
    wchar_t line[2304];
    wchar_t directory[MAX_PATH];
    wchar_t log_path[MAX_PATH];
    SYSTEMTIME now;
    FILE *file = NULL;
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

    if (!module_directory(directory, sizeof(directory) / sizeof(directory[0]))) return;
    _snwprintf_s(log_path, sizeof(log_path) / sizeof(log_path[0]), _TRUNCATE,
            L"%ls\\raven-native.log", directory);
    if (_wfopen_s(&file, log_path, L"a, ccs=UTF-8") == 0 && file != NULL) {
        fputws(line, file);
        fclose(file);
    }
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
    vape_log(L"  (java-side trace also written to %%TEMP%%\\RavenNative\\raven-native-java.log)");
}

jint raven_initialize_jvmti(JavaVM *vm) {
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

static jstring new_wide_string(JNIEnv *env, const wchar_t *value) {
    if (value == NULL) return NULL;
    return (*env)->NewString(env, (const jchar *)value, (jsize)wcslen(value));
}

static int materialize_embedded_payload(int resource_id,
        const wchar_t *profile_name, wchar_t *jar_path, size_t jar_capacity) {
    HRSRC resource;
    HGLOBAL loaded_resource;
    const unsigned char *bytes;
    DWORD size;
    DWORD temp_length;
    wchar_t temp_root[MAX_PATH];
    wchar_t temp_directory[MAX_PATH];
    HANDLE file = INVALID_HANDLE_VALUE;
    DWORD offset = 0;
    int result = 0;

    resource = FindResourceW(g_module,
            MAKEINTRESOURCEW(resource_id),
            MAKEINTRESOURCEW(10));
    if (resource == NULL) {
        vape_log(L"embedded payload JAR resource %d is missing",
                resource_id);
        return 0;
    }
    size = SizeofResource(g_module, resource);
    loaded_resource = LoadResource(g_module, resource);
    bytes = loaded_resource == NULL ? NULL
            : (const unsigned char *)LockResource(loaded_resource);
    if (bytes == NULL || size < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
        vape_log(L"embedded payload JAR resource is invalid");
        return 0;
    }
    temp_length = GetTempPathW(
            (DWORD)(sizeof(temp_root) / sizeof(temp_root[0])), temp_root);
    if (temp_length == 0
            || temp_length >= (DWORD)(sizeof(temp_root) / sizeof(temp_root[0]))) {
        vape_log(L"GetTempPathW failed: %lu", GetLastError());
        return 0;
    }
    if (_snwprintf_s(temp_directory,
            sizeof(temp_directory) / sizeof(temp_directory[0]),
            _TRUNCATE, L"%lsRavenNative", temp_root) < 0) {
        vape_log(L"temporary directory path too long");
        return 0;
    }
    if (!CreateDirectoryW(temp_directory, NULL) && GetLastError() != ERROR_ALREADY_EXISTS) {
        vape_log(L"CreateDirectoryW failed: %lu", GetLastError());
        return 0;
    }
    if (_snwprintf_s(jar_path, jar_capacity, _TRUNCATE,
            L"%ls\\raven-%ls-payload-%lu.jar", temp_directory,
            profile_name, GetCurrentProcessId()) < 0) {
        vape_log(L"temporary payload path too long");
        return 0;
    }
    file = CreateFileW(jar_path, GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_DELETE, NULL, CREATE_ALWAYS,
            FILE_ATTRIBUTE_TEMPORARY, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        vape_log(L"CreateFileW for payload failed: %lu", GetLastError());
        return 0;
    }
    while (offset < size) {
        DWORD written = 0;
        DWORD remaining = size - offset;
        if (!WriteFile(file, bytes + offset, remaining, &written, NULL) || written == 0) {
            vape_log(L"WriteFile for payload failed: %lu", GetLastError());
            goto cleanup;
        }
        offset += written;
    }
    if (!FlushFileBuffers(file)) {
        vape_log(L"FlushFileBuffers for payload failed: %lu", GetLastError());
        goto cleanup;
    }
    result = 1;

cleanup:
    CloseHandle(file);
    if (!result) {
        DeleteFileW(jar_path);
    } else {
        vape_log(L"materialized payload: %ls (%lu bytes)", jar_path, size);
    }
    return result;
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

static raven_runtime_namespace detect_runtime_namespace(JNIEnv *env,
        jobject loader) {
    jclass loader_class = NULL;
    jmethodID load_class = NULL;
    jclass minecraft = NULL;
    jmethodID mcp_click = NULL;
    jmethodID srg_click = NULL;
    raven_runtime_namespace result = RAVEN_NAMESPACE_UNKNOWN;

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
        result = RAVEN_NAMESPACE_MCP;
        vape_log(L"runtime namespace: MCP/named (Lunar/deobfuscated)");
    } else if (srg_click != NULL && mcp_click == NULL) {
        result = RAVEN_NAMESPACE_SRG;
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
        raven_runtime_namespace runtime_namespace, int embedded_forge) {
    const char *namespace_name = runtime_namespace == RAVEN_NAMESPACE_MCP
            ? "mcp" : "srg";
    const char *profile_name = runtime_namespace == RAVEN_NAMESPACE_MCP
            ? "lunar" : "forge";
    return set_system_property(env, "raven.runtimeNamespace", namespace_name)
            && set_system_property(env, "raven.runtimeProfile", profile_name)
            && set_system_property(env, "raven.embeddedForge",
                    embedded_forge ? "true" : "false");
}

/*
 * LaunchClassLoader (Forge 1.8.9) extends URLClassLoader, so addURL is enough.
 * But its addURL override also caches the URL for its transformer chain — we
 * still call the URLClassLoader.addURL directly to guarantee resolution.
 */
static int append_payload_to_loader(JNIEnv *env, jobject loader,
        const wchar_t *jar_path) {
    jclass url_loader_class = (*env)->FindClass(env, "java/net/URLClassLoader");
    jclass url_class = (*env)->FindClass(env, "java/net/URL");
    jclass file_class = (*env)->FindClass(env, "java/io/File");
    jclass uri_class = (*env)->FindClass(env, "java/net/URI");
    jmethodID add_url;
    jmethodID file_init;
    jmethodID to_uri;
    jmethodID to_url;
    jstring path;
    jobject file, uri, url;

    if (url_loader_class == NULL || url_class == NULL
            || file_class == NULL || uri_class == NULL) {
        vape_log_pending_exception(env, L"resolve URLClassLoader helpers");
        return 0;
    }
    if (!(*env)->IsInstanceOf(env, loader, url_loader_class)) {
        vape_log(L"context ClassLoader is not URLClassLoader");
        return 0;
    }
    add_url    = (*env)->GetMethodID(env, url_loader_class, "addURL", "(Ljava/net/URL;)V");
    file_init  = (*env)->GetMethodID(env, file_class, "<init>", "(Ljava/lang/String;)V");
    to_uri     = (*env)->GetMethodID(env, file_class, "toURI", "()Ljava/net/URI;");
    to_url     = (*env)->GetMethodID(env, uri_class, "toURL", "()Ljava/net/URL;");
    if (add_url == NULL || file_init == NULL || to_uri == NULL || to_url == NULL) {
        vape_log_pending_exception(env, L"resolve URL construction methods");
        return 0;
    }
    path = new_wide_string(env, jar_path);
    file = path == NULL ? NULL : (*env)->NewObject(env, file_class, file_init, path);
    uri  = file == NULL ? NULL : (*env)->CallObjectMethod(env, file, to_uri);
    url  = uri  == NULL ? NULL : (*env)->CallObjectMethod(env, uri, to_url);
    if (url == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"build payload URL");
        return 0;
    }
    (*env)->CallVoidMethod(env, loader, add_url, url);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"URLClassLoader.addURL");
        return 0;
    }
    return 1;
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

/* Parent-first class loading can otherwise reuse an older Raven JAR that was
 * already present in the process. Require NativeBootstrap to come from the
 * payload materialized by this exact injection attempt. */
static int verify_bootstrap_code_source(JNIEnv *env, jobject expected_loader,
        jclass bootstrap, const wchar_t *expected_jar_path) {
    jobject actual_loader = NULL;
    jclass class_class = NULL;
    jclass protection_domain_class = NULL;
    jclass code_source_class = NULL;
    jclass url_class = NULL;
    jclass jar_url_connection_class = NULL;
    jclass file_class = NULL;
    jmethodID get_protection_domain = NULL;
    jmethodID get_code_source = NULL;
    jmethodID get_location = NULL;
    jmethodID open_connection = NULL;
    jmethodID get_jar_file_url = NULL;
    jmethodID to_external_form = NULL;
    jmethodID to_uri = NULL;
    jmethodID file_from_uri = NULL;
    jmethodID file_from_string = NULL;
    jmethodID get_canonical_path = NULL;
    jobject protection_domain = NULL;
    jobject code_source = NULL;
    jobject location = NULL;
    jobject normalized_location = NULL;
    jobject connection = NULL;
    jobject nested_location = NULL;
    jobject uri = NULL;
    jobject actual_file = NULL;
    jobject expected_file = NULL;
    jstring expected_path = NULL;
    jstring location_text = NULL;
    jstring actual_canonical = NULL;
    jstring expected_canonical = NULL;
    wchar_t *actual_chars = NULL;
    wchar_t *expected_chars = NULL;
    jsize actual_length = 0;
    jsize expected_length = 0;
    int result = 0;
    int unwrap_depth;

    if ((*g_jvmti)->GetClassLoader(g_jvmti, bootstrap, &actual_loader)
            != JVMTI_ERROR_NONE || actual_loader == NULL
            || !(*env)->IsSameObject(env, actual_loader, expected_loader)) {
        vape_log(L"NativeBootstrap was not defined by the selected game ClassLoader");
        goto cleanup;
    }

    class_class = (*env)->FindClass(env, "java/lang/Class");
    protection_domain_class = (*env)->FindClass(env,
            "java/security/ProtectionDomain");
    code_source_class = (*env)->FindClass(env, "java/security/CodeSource");
    url_class = (*env)->FindClass(env, "java/net/URL");
    jar_url_connection_class = (*env)->FindClass(env,
            "java/net/JarURLConnection");
    file_class = (*env)->FindClass(env, "java/io/File");
    if (class_class == NULL || protection_domain_class == NULL
            || code_source_class == NULL || url_class == NULL
            || jar_url_connection_class == NULL
            || file_class == NULL) {
        vape_log_pending_exception(env, L"resolve payload CodeSource classes");
        goto cleanup;
    }

    get_protection_domain = (*env)->GetMethodID(env, class_class,
            "getProtectionDomain", "()Ljava/security/ProtectionDomain;");
    get_code_source = (*env)->GetMethodID(env, protection_domain_class,
            "getCodeSource", "()Ljava/security/CodeSource;");
    get_location = (*env)->GetMethodID(env, code_source_class,
            "getLocation", "()Ljava/net/URL;");
    open_connection = (*env)->GetMethodID(env, url_class,
            "openConnection", "()Ljava/net/URLConnection;");
    get_jar_file_url = (*env)->GetMethodID(env, jar_url_connection_class,
            "getJarFileURL", "()Ljava/net/URL;");
    to_external_form = (*env)->GetMethodID(env, url_class,
            "toExternalForm", "()Ljava/lang/String;");
    to_uri = (*env)->GetMethodID(env, url_class,
            "toURI", "()Ljava/net/URI;");
    file_from_uri = (*env)->GetMethodID(env, file_class,
            "<init>", "(Ljava/net/URI;)V");
    file_from_string = (*env)->GetMethodID(env, file_class,
            "<init>", "(Ljava/lang/String;)V");
    get_canonical_path = (*env)->GetMethodID(env, file_class,
            "getCanonicalPath", "()Ljava/lang/String;");
    if (get_protection_domain == NULL || get_code_source == NULL
            || get_location == NULL || open_connection == NULL
            || get_jar_file_url == NULL || to_external_form == NULL
            || to_uri == NULL
            || file_from_uri == NULL || file_from_string == NULL
            || get_canonical_path == NULL) {
        vape_log_pending_exception(env, L"resolve payload CodeSource methods");
        goto cleanup;
    }

    protection_domain = (*env)->CallObjectMethod(env, bootstrap,
            get_protection_domain);
    code_source = protection_domain == NULL ? NULL : (*env)->CallObjectMethod(
            env, protection_domain, get_code_source);
    location = code_source == NULL ? NULL : (*env)->CallObjectMethod(
            env, code_source, get_location);
    location_text = location == NULL ? NULL : (jstring)(*env)->CallObjectMethod(
            env, location, to_external_form);
    if (location == NULL || location_text == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"read NativeBootstrap CodeSource");
        goto cleanup;
    }
    log_java_string(env, L"NativeBootstrap raw CodeSource", location_text);

    /* LaunchClassLoader commonly records the class-entry URL as the
     * CodeSource (jar:file:/payload.jar!/mindless/...). Such a jar: URI
     * is opaque, so File(URI) rejects it as non-hierarchical. Peel every
     * standard JarURLConnection layer until the underlying file: URL remains. */
    normalized_location = (*env)->NewLocalRef(env, location);
    for (unwrap_depth = 0; unwrap_depth < 4 && normalized_location != NULL;
            ++unwrap_depth) {
        connection = (*env)->CallObjectMethod(env, normalized_location,
                open_connection);
        if (connection == NULL || (*env)->ExceptionCheck(env)) {
            vape_log_pending_exception(env, L"open NativeBootstrap CodeSource URL");
            goto cleanup;
        }
        if (!(*env)->IsInstanceOf(env, connection, jar_url_connection_class)) {
            (*env)->DeleteLocalRef(env, connection);
            connection = NULL;
            break;
        }
        nested_location = (*env)->CallObjectMethod(env, connection,
                get_jar_file_url);
        (*env)->DeleteLocalRef(env, connection);
        connection = NULL;
        if (nested_location == NULL || (*env)->ExceptionCheck(env)) {
            vape_log_pending_exception(env, L"unwrap NativeBootstrap JAR URL");
            goto cleanup;
        }
        (*env)->DeleteLocalRef(env, normalized_location);
        normalized_location = nested_location;
        nested_location = NULL;
    }
    uri = normalized_location == NULL ? NULL : (*env)->CallObjectMethod(
            env, normalized_location, to_uri);
    if (uri == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"convert NativeBootstrap CodeSource to URI");
        goto cleanup;
    }

    expected_path = new_wide_string(env, expected_jar_path);
    actual_file = (*env)->NewObject(env, file_class, file_from_uri, uri);
    expected_file = expected_path == NULL ? NULL : (*env)->NewObject(
            env, file_class, file_from_string, expected_path);
    actual_canonical = actual_file == NULL ? NULL : (jstring)(*env)->CallObjectMethod(
            env, actual_file, get_canonical_path);
    expected_canonical = expected_file == NULL ? NULL : (jstring)(*env)->CallObjectMethod(
            env, expected_file, get_canonical_path);
    if (actual_canonical == NULL || expected_canonical == NULL
            || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"canonicalize NativeBootstrap CodeSource");
        goto cleanup;
    }

    actual_length = (*env)->GetStringLength(env, actual_canonical);
    expected_length = (*env)->GetStringLength(env, expected_canonical);
    actual_chars = (wchar_t *)calloc((size_t)actual_length + 1, sizeof(wchar_t));
    expected_chars = (wchar_t *)calloc((size_t)expected_length + 1, sizeof(wchar_t));
    if (actual_chars == NULL || expected_chars == NULL) {
        vape_log(L"could not allocate CodeSource path comparison");
        goto cleanup;
    }
    (*env)->GetStringRegion(env, actual_canonical, 0, actual_length,
            (jchar *)actual_chars);
    (*env)->GetStringRegion(env, expected_canonical, 0, expected_length,
            (jchar *)expected_chars);
    if ((*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"read canonical CodeSource paths");
        goto cleanup;
    }
    if (_wcsicmp(actual_chars, expected_chars) != 0) {
        vape_log(L"NativeBootstrap CodeSource mismatch: expected %ls, got %ls",
                expected_chars, actual_chars);
        goto cleanup;
    }
    vape_log(L"verified NativeBootstrap CodeSource: %ls", actual_chars);
    result = 1;

cleanup:
    free(expected_chars);
    free(actual_chars);
    if (expected_canonical != NULL) (*env)->DeleteLocalRef(env, expected_canonical);
    if (actual_canonical != NULL) (*env)->DeleteLocalRef(env, actual_canonical);
    if (expected_file != NULL) (*env)->DeleteLocalRef(env, expected_file);
    if (actual_file != NULL) (*env)->DeleteLocalRef(env, actual_file);
    if (expected_path != NULL) (*env)->DeleteLocalRef(env, expected_path);
    if (uri != NULL) (*env)->DeleteLocalRef(env, uri);
    if (nested_location != NULL) (*env)->DeleteLocalRef(env, nested_location);
    if (connection != NULL) (*env)->DeleteLocalRef(env, connection);
    if (normalized_location != NULL) (*env)->DeleteLocalRef(env,
            normalized_location);
    if (location_text != NULL) (*env)->DeleteLocalRef(env, location_text);
    if (location != NULL) (*env)->DeleteLocalRef(env, location);
    if (code_source != NULL) (*env)->DeleteLocalRef(env, code_source);
    if (protection_domain != NULL) (*env)->DeleteLocalRef(env, protection_domain);
    if (file_class != NULL) (*env)->DeleteLocalRef(env, file_class);
    if (jar_url_connection_class != NULL) (*env)->DeleteLocalRef(env,
            jar_url_connection_class);
    if (url_class != NULL) (*env)->DeleteLocalRef(env, url_class);
    if (code_source_class != NULL) (*env)->DeleteLocalRef(env, code_source_class);
    if (protection_domain_class != NULL) (*env)->DeleteLocalRef(env,
            protection_domain_class);
    if (class_class != NULL) (*env)->DeleteLocalRef(env, class_class);
    if (actual_loader != NULL) (*env)->DeleteLocalRef(env, actual_loader);
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
            "mindless.runtime.RavenTransformerManager");
    if (manager_class == NULL) {
        vape_log(L"RavenTransformerManager not visible via LaunchClassLoader");
        goto cleanup;
    }
    get_instance = (*env)->GetStaticMethodID(env, manager_class,
            "get", "()Lmindless/runtime/RavenTransformerManager;");
    target_names = (*env)->GetMethodID(env, manager_class,
            "targetInternalNames", "()Ljava/util/Set;");
    if (get_instance == NULL || target_names == NULL) {
        vape_log_pending_exception(env, L"resolve RavenTransformerManager methods");
        goto cleanup;
    }

    manager = (*env)->CallStaticObjectMethod(env, manager_class, get_instance);
    if (manager == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"RavenTransformerManager.get");
        goto cleanup;
    }
    set = (*env)->CallObjectMethod(env, manager, target_names);
    if (set == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"RavenTransformerManager.targetInternalNames");
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

/* With Raven's hook disabled, a JVMTI retransformation starts from the VM's
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

/* Force the RavenTransformerManager singleton to be built now, WITH the hook
 * still disabled. Building it calls addTransformer(...) for every Transformer*
 * class, each of which triggers class loading via the LaunchClassLoader; if
 * the ClassFileLoadHook were already live those loads would recurse into
 * TransformerHooks.transform -> RavenTransformerManager.get() before the
 * singleton finished initialising. */
static int prime_transformer_manager(JNIEnv *env, jobject class_loader,
        jmethodID load_class) {
    jclass manager_class;
    jmethodID get_instance;
    jobject manager;
    manager_class = load_class_via_loader(env, class_loader, load_class,
            "mindless.runtime.RavenTransformerManager");
    if (manager_class == NULL) {
        vape_log(L"prime: RavenTransformerManager not visible via loader");
        return 0;
    }
    get_instance = (*env)->GetStaticMethodID(env, manager_class,
            "get", "()Lmindless/runtime/RavenTransformerManager;");
    if (get_instance == NULL) {
        vape_log_pending_exception(env, L"prime: resolve RavenTransformerManager.get");
        return 0;
    }
    manager = (*env)->CallStaticObjectMethod(env, manager_class, get_instance);
    if (manager == NULL || (*env)->ExceptionCheck(env)) {
        vape_log_pending_exception(env, L"prime: RavenTransformerManager.get()");
        return 0;
    }
    vape_log(L"prime: RavenTransformerManager constructed");
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
    wchar_t jar_path[MAX_PATH];
    raven_runtime_namespace runtime_namespace = RAVEN_NAMESPACE_UNKNOWN;
    int payload_resource_id = 0;
    const wchar_t *payload_profile = NULL;
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
    if (raven_initialize_jvmti(vm) != JNI_OK) {
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
    if (runtime_namespace == RAVEN_NAMESPACE_UNKNOWN) {
        exit_code = 15;
        goto cleanup;
    }
    send_progress(0.55f, "Detected runtime namespace");
    /* Plain Lunar + OptiFine has named Minecraft classes but no Forge API.
     * The MCP payload embeds the required API and a loader-independent event
     * bus. Record whether that compatibility layer must emit lifecycle events. */
    embedded_forge = runtime_namespace == RAVEN_NAMESPACE_MCP
            && !(loader_has_class(env, loader,
                    "net.minecraftforge.common.MinecraftForge")
                 && loader_has_class(env, loader,
                    "net.minecraftforge.fml.common.eventhandler.EventBus"));
    if (!set_runtime_properties(env, runtime_namespace, embedded_forge)) {
        exit_code = 17;
        goto cleanup;
    }
    send_progress(0.57f, "Runtime properties configured");
    if (runtime_namespace == RAVEN_NAMESPACE_MCP) {
        payload_resource_id = RAVEN_LUNAR_PAYLOAD_RESOURCE_ID;
        payload_profile = L"lunar-mcp";
    } else {
        payload_resource_id = RAVEN_FORGE_PAYLOAD_RESOURCE_ID;
        payload_profile = L"forge-srg";
    }
    send_progress(0.59f, "Selecting payload profile");
    send_progress(0.61f, "Extracting payload");
    if (!materialize_embedded_payload(payload_resource_id, payload_profile,
            jar_path, sizeof(jar_path) / sizeof(jar_path[0]))) {
        exit_code = 7;
        goto cleanup;
    }
    send_progress(0.63f, "Payload extracted");
    if (!append_payload_to_loader(env, loader, jar_path)) {
        exit_code = 9;
        goto cleanup;
    }
    send_progress(0.65f, "Payload attached to class loader");
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
    send_progress(0.72f, "Verifying payload integrity");
    if (!verify_bootstrap_code_source(env, loader, bootstrap_class, jar_path)) {
        exit_code = 19;
        goto cleanup;
    }
    if (!pin_native_module()) {
        exit_code = 12;
        goto cleanup;
    }
    module_pinned = 1;
    send_progress(0.73f, "Native module pinned");
    vape_log(L"NativeBootstrap linked from %ls", jar_path);
    if (!apply_transformers(env, loader)) {
        vape_log(L"apply_transformers failed; Raven will not start with missing hooks");
        exit_code = 14;
        goto cleanup;
    }
    send_progress(0.98f, "Handing off to Java bootstrap");
    if (!call_bootstrap_start(env, bootstrap_class)) {
        exit_code = 13;
        goto cleanup;
    }
    send_progress(1.0f, "Ready");
    vape_log(L"NativeBootstrap.start completed; Raven is active");
    /* Try to delete payload from disk. URLClassLoader holds the jar open via a
     * ZipFile handle, so DeleteFile will fail — but the FILE_DISPOSITION_INFO
     * trick marks it for deletion when the last handle closes (process exit). */
    {
        HANDLE h = CreateFileW(jar_path, DELETE, FILE_SHARE_READ | FILE_SHARE_DELETE,
                NULL, OPEN_EXISTING, FILE_FLAG_DELETE_ON_CLOSE, NULL);
        if (h != INVALID_HANDLE_VALUE) {
            CloseHandle(h);
            vape_log(L"payload jar marked for deletion on process exit");
        }
    }
    exit_code = 0;

cleanup:
    if (g_progress_pipe != INVALID_HANDLE_VALUE) {
        CloseHandle(g_progress_pipe);
        g_progress_pipe = INVALID_HANDLE_VALUE;
    }
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
            OutputDebugStringW(L"RavenNative: CreateThread for bootstrap failed\r\n");
            g_module = NULL;
            return FALSE;
        }
        CloseHandle(thread);
    }
    return TRUE;
}
