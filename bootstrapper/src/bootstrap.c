#include "platform.h"
#include "raven_native.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*
 * bootstrap.c — portable JNI/JVMTI bootstrap logic for Raven.
 * Platform-specific entry points (DllMain / __attribute__((constructor)))
 * call raven_bootstrap_main() after spawning a background thread.
 *
 * The core sequence:
 *   1. Wait for the JVM
 *   2. Attach as a daemon thread
 *   3. Acquire JVMTI
 *   4. Find the Minecraft class loader
 *   5. Detect runtime namespace (SRG vs MCP)
 *   6. Extract and attach the appropriate payload JAR
 *   7. Install ClassFileLoadHook, retransform loaded classes
 *   8. Call NativeBootstrap.start()
 */

static jvmtiEnv *g_jvmti  = NULL;
static jclass    g_hooks_class = NULL;
static jmethodID g_hooks_transform = NULL;
static jobject   g_game_loader = NULL;
static volatile long g_hook_registered = 0;
static volatile long g_hook_ever_published = 0;

typedef enum {
    RAVEN_NAMESPACE_UNKNOWN = 0,
    RAVEN_NAMESPACE_MCP,
    RAVEN_NAMESPACE_SRG
} raven_runtime_namespace;

/* ---- Helpers ------------------------------------------------------------ */

static void log_pending_exception(JNIEnv *env, const char *context) {
    jthrowable ex;
    if (!env || !(*env)->ExceptionCheck(env)) {
        raven_log("%s failed without a Java exception", context);
        return;
    }
    ex = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    raven_log("%s raised an exception", context);
    if (ex) (*env)->DeleteLocalRef(env, ex);
}

static jclass load_class_via_loader(JNIEnv *env, jobject loader,
        jmethodID load_class, const char *dotted_name) {
    jstring name = (*env)->NewStringUTF(env, dotted_name);
    jclass result;
    if (!name) return NULL;
    result = (jclass)(*env)->CallObjectMethod(env, loader, load_class, name);
    (*env)->DeleteLocalRef(env, name);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    return result;
}

static int loader_has_class(JNIEnv *env, jobject loader, const char *dotted_name) {
    jclass lc = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID lm = lc ? (*env)->GetMethodID(env, lc, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    jclass found = lm ? load_class_via_loader(env, loader, lm, dotted_name) : NULL;
    int result = found != NULL;
    if (found) (*env)->DeleteLocalRef(env, found);
    if (lc) (*env)->DeleteLocalRef(env, lc);
    return result;
}

/* ---- Class loader discovery --------------------------------------------- */

static jobject find_client_class_loader(JNIEnv *env) {
    jint thread_count = 0;
    jthread *threads = NULL;
    jobject result = NULL;
    jint i;
    if (!g_jvmti) return NULL;
    if ((*g_jvmti)->GetAllThreads(g_jvmti, &thread_count, &threads) != JVMTI_ERROR_NONE)
        return NULL;
    for (i = 0; i < thread_count; ++i) {
        if (!result) {
            jvmtiThreadInfo info;
            memset(&info, 0, sizeof(info));
            if ((*g_jvmti)->GetThreadInfo(g_jvmti, threads[i], &info) == JVMTI_ERROR_NONE) {
                if (info.name && strcmp(info.name, "Client thread") == 0
                        && info.context_class_loader) {
                    result = (*env)->NewLocalRef(env, info.context_class_loader);
                }
                if (info.name) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)info.name);
                if (info.thread_group) (*env)->DeleteLocalRef(env, info.thread_group);
                if (info.context_class_loader) (*env)->DeleteLocalRef(env, info.context_class_loader);
            }
        }
        if (threads[i]) (*env)->DeleteLocalRef(env, threads[i]);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)threads);
    return result;
}

static jobject find_minecraft_defining_loader(JNIEnv *env, jobject context_loader) {
    jclass lc = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID lm = lc ? (*env)->GetMethodID(env, lc, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    jclass minecraft = lm ? load_class_via_loader(env, context_loader, lm,
            "net.minecraft.client.Minecraft") : NULL;
    jobject defining = NULL;
    if (minecraft && (*g_jvmti)->GetClassLoader(g_jvmti, minecraft, &defining) != JVMTI_ERROR_NONE)
        defining = NULL;
    if (minecraft) (*env)->DeleteLocalRef(env, minecraft);
    if (lc) (*env)->DeleteLocalRef(env, lc);
    return defining;
}

static jobject find_minecraft_class_loader_fallback(JNIEnv *env) {
    jint count = 0;
    jclass *classes = NULL;
    jobject result = NULL;
    jint i;
    int ambiguous = 0;
    if ((*g_jvmti)->GetLoadedClasses(g_jvmti, &count, &classes) != JVMTI_ERROR_NONE)
        return NULL;
    for (i = 0; i < count; ++i) {
        char *sig = NULL;
        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &sig, NULL) == JVMTI_ERROR_NONE
                && sig && strcmp(sig, "Lnet/minecraft/client/Minecraft;") == 0) {
            jobject dl = NULL;
            if ((*g_jvmti)->GetClassLoader(g_jvmti, classes[i], &dl) == JVMTI_ERROR_NONE && dl) {
                if (!result) { result = dl; dl = NULL; }
                else if (!(*env)->IsSameObject(env, result, dl)) ambiguous = 1;
            }
            if (dl) (*env)->DeleteLocalRef(env, dl);
        }
        if (sig) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)sig);
        if (classes[i]) (*env)->DeleteLocalRef(env, classes[i]);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
    if (ambiguous && result) { (*env)->DeleteLocalRef(env, result); return NULL; }
    return result;
}

static jobject find_game_loader(JNIEnv *env) {
    jobject ctx = find_client_class_loader(env);
    jobject defining = NULL;
    if (ctx) {
        defining = find_minecraft_defining_loader(env, ctx);
        (*env)->DeleteLocalRef(env, ctx);
    }
    if (defining) return defining;
    return find_minecraft_class_loader_fallback(env);
}

/* ---- Namespace detection ------------------------------------------------ */

static raven_runtime_namespace detect_namespace(JNIEnv *env, jobject loader) {
    jclass lc = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID lm = lc ? (*env)->GetMethodID(env, lc, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    jclass mc = lm ? load_class_via_loader(env, loader, lm,
            "net.minecraft.client.Minecraft") : NULL;
    jmethodID mcp_click, srg_click;
    raven_runtime_namespace ns = RAVEN_NAMESPACE_UNKNOWN;
    if (!mc) { if (lc) (*env)->DeleteLocalRef(env, lc); return ns; }

    mcp_click = (*env)->GetMethodID(env, mc, "clickMouse", "()V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    srg_click = (*env)->GetMethodID(env, mc, "func_147116_af", "()V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    if (mcp_click && !srg_click) ns = RAVEN_NAMESPACE_MCP;
    else if (srg_click && !mcp_click) ns = RAVEN_NAMESPACE_SRG;

    (*env)->DeleteLocalRef(env, mc);
    if (lc) (*env)->DeleteLocalRef(env, lc);
    return ns;
}

/* ---- Payload attachment ------------------------------------------------- */

static int append_payload_to_loader(JNIEnv *env, jobject loader, const char *jar_path) {
    jclass url_lc = (*env)->FindClass(env, "java/net/URLClassLoader");
    jclass file_c = (*env)->FindClass(env, "java/io/File");
    jclass uri_c  = (*env)->FindClass(env, "java/net/URI");
    jmethodID add_url, file_init, to_uri, to_url;
    jstring path;
    jobject file_obj, uri, url;

    if (!url_lc || !file_c || !uri_c) return 0;
    if (!(*env)->IsInstanceOf(env, loader, url_lc)) {
        raven_log("ClassLoader is not URLClassLoader");
        return 0;
    }
    add_url   = (*env)->GetMethodID(env, url_lc, "addURL", "(Ljava/net/URL;)V");
    file_init = (*env)->GetMethodID(env, file_c, "<init>", "(Ljava/lang/String;)V");
    to_uri    = (*env)->GetMethodID(env, file_c, "toURI", "()Ljava/net/URI;");
    to_url    = (*env)->GetMethodID(env, uri_c, "toURL", "()Ljava/net/URL;");
    if (!add_url || !file_init || !to_uri || !to_url) return 0;

    path = (*env)->NewStringUTF(env, jar_path);
    file_obj = path ? (*env)->NewObject(env, file_c, file_init, path) : NULL;
    uri  = file_obj ? (*env)->CallObjectMethod(env, file_obj, to_uri) : NULL;
    url  = uri ? (*env)->CallObjectMethod(env, uri, to_url) : NULL;
    if (!url || (*env)->ExceptionCheck(env)) {
        log_pending_exception(env, "build payload URL");
        return 0;
    }
    (*env)->CallVoidMethod(env, loader, add_url, url);
    if ((*env)->ExceptionCheck(env)) {
        log_pending_exception(env, "URLClassLoader.addURL");
        return 0;
    }
    return 1;
}

static int set_system_property(JNIEnv *env, const char *key, const char *value) {
    jclass sys = (*env)->FindClass(env, "java/lang/System");
    jmethodID sp = sys ? (*env)->GetStaticMethodID(env, sys, "setProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") : NULL;
    jstring jk, jv;
    if (!sp) return 0;
    jk = (*env)->NewStringUTF(env, key);
    jv = (*env)->NewStringUTF(env, value);
    if (!jk || !jv) return 0;
    (*env)->CallStaticObjectMethod(env, sys, sp, jk, jv);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return 1;
}

/* ---- ClassFileLoadHook -------------------------------------------------- */

static void JNICALL class_file_load_hook(
        jvmtiEnv *jvmti, JNIEnv *env, jclass class_being_redefined,
        jobject loader, const char *name, jobject protection_domain,
        jint class_data_len, const unsigned char *class_data,
        jint *new_class_data_len, unsigned char **new_class_data) {
    jbyteArray original, transformed;
    jstring name_str;
    jsize tlen;
    unsigned char *allocated;
    (void)jvmti; (void)class_being_redefined; (void)protection_domain;
    if (new_class_data_len) *new_class_data_len = 0;
    if (new_class_data) *new_class_data = NULL;
    if (!env || !name || !class_data || class_data_len <= 0) return;
    if (g_game_loader && (loader == NULL || !(*env)->IsSameObject(env, loader, g_game_loader)))
        return;
    if (!g_hooks_class || !g_hooks_transform) return;

    original = (*env)->NewByteArray(env, class_data_len);
    if (!original) return;
    (*env)->SetByteArrayRegion(env, original, 0, class_data_len, (const jbyte *)class_data);
    name_str = (*env)->NewStringUTF(env, name);
    if (!name_str) { (*env)->DeleteLocalRef(env, original); return; }

    transformed = (jbyteArray)(*env)->CallStaticObjectMethod(env,
            g_hooks_class, g_hooks_transform, name_str, original);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, name_str);
        (*env)->DeleteLocalRef(env, original);
        return;
    }
    (*env)->DeleteLocalRef(env, name_str);
    (*env)->DeleteLocalRef(env, original);
    if (!transformed) return;

    tlen = (*env)->GetArrayLength(env, transformed);
    if (tlen <= 0) { (*env)->DeleteLocalRef(env, transformed); return; }
    if ((*g_jvmti)->Allocate(g_jvmti, tlen, &allocated) != JVMTI_ERROR_NONE) {
        (*env)->DeleteLocalRef(env, transformed);
        return;
    }
    (*env)->GetByteArrayRegion(env, transformed, 0, tlen, (jbyte *)allocated);
    (*env)->DeleteLocalRef(env, transformed);
    *new_class_data = allocated;
    *new_class_data_len = tlen;
}

static int install_hook(void) {
    jvmtiCapabilities caps;
    jvmtiEventCallbacks cbs;
    if (raven_atomic_cmpxchg(&g_hook_registered, 0, 0) != 0) return 1;
    if (!g_jvmti) return 0;

    memset(&caps, 0, sizeof(caps));
    caps.can_retransform_classes = 1;
    (*g_jvmti)->AddCapabilities(g_jvmti, &caps);

    memset(&cbs, 0, sizeof(cbs));
    cbs.ClassFileLoadHook = class_file_load_hook;
    if ((*g_jvmti)->SetEventCallbacks(g_jvmti, &cbs, sizeof(cbs)) != JVMTI_ERROR_NONE)
        return 0;
    if ((*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE,
            JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL) != JVMTI_ERROR_NONE)
        return 0;
    raven_atomic_exchange(&g_hook_registered, 1);
    raven_atomic_exchange(&g_hook_ever_published, 1);
    return 1;
}

static int resolve_hooks(JNIEnv *env, jobject loader) {
    jclass lc, hooks;
    jmethodID lm;
    jstring name;
    if (g_hooks_class && g_hooks_transform) return 1;
    lc = (*env)->FindClass(env, "java/lang/ClassLoader");
    lm = lc ? (*env)->GetMethodID(env, lc, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    name = (*env)->NewStringUTF(env, "mindless.runtime.TransformerHooks");
    hooks = (lm && name) ? (jclass)(*env)->CallObjectMethod(env, loader, lm, name) : NULL;
    if (!hooks || (*env)->ExceptionCheck(env)) {
        log_pending_exception(env, "load TransformerHooks");
        return 0;
    }
    g_hooks_class = (jclass)(*env)->NewGlobalRef(env, hooks);
    g_hooks_transform = (*env)->GetStaticMethodID(env, hooks, "transform",
            "(Ljava/lang/String;[B)[B");
    if (!g_hooks_class || !g_hooks_transform) return 0;
    return 1;
}

static jclass find_loaded_class(JNIEnv *env, const char *internal_name, jobject expected_loader) {
    jint count = 0;
    jclass *classes = NULL;
    jclass result = NULL;
    jint i;
    size_t needle_len;
    if ((*g_jvmti)->GetLoadedClasses(g_jvmti, &count, &classes) != JVMTI_ERROR_NONE)
        return NULL;
    needle_len = strlen(internal_name);
    for (i = 0; i < count; ++i) {
        char *sig = NULL;
        if (!result && (*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &sig, NULL) == JVMTI_ERROR_NONE && sig) {
            size_t sl = strlen(sig);
            if (sl == needle_len + 2 && sig[0] == 'L' && sig[sl-1] == ';'
                    && strncmp(sig+1, internal_name, needle_len) == 0) {
                jobject al = NULL;
                if ((*g_jvmti)->GetClassLoader(g_jvmti, classes[i], &al) == JVMTI_ERROR_NONE) {
                    if (!expected_loader || (al && (*env)->IsSameObject(env, al, expected_loader))) {
                        result = classes[i];
                        classes[i] = NULL;
                    }
                }
                if (al) (*env)->DeleteLocalRef(env, al);
            }
            (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)sig);
        }
        if (classes[i]) (*env)->DeleteLocalRef(env, classes[i]);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
    return result;
}

static int retransform_targets(JNIEnv *env, jobject loader) {
    jclass lc, mgr_class, set_class;
    jmethodID lm, get_inst, target_names, to_array;
    jobject mgr, set;
    jobjectArray names;
    jsize name_count, i;
    jint succeeded = 0;

    lc = (*env)->FindClass(env, "java/lang/ClassLoader");
    lm = lc ? (*env)->GetMethodID(env, lc, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    mgr_class = lm ? load_class_via_loader(env, loader, lm,
            "mindless.runtime.RavenTransformerManager") : NULL;
    if (!mgr_class) { raven_log("RavenTransformerManager not found"); return 0; }

    get_inst = (*env)->GetStaticMethodID(env, mgr_class, "get",
            "()Lmindless/runtime/RavenTransformerManager;");
    target_names = (*env)->GetMethodID(env, mgr_class, "targetInternalNames", "()Ljava/util/Set;");
    mgr = get_inst ? (*env)->CallStaticObjectMethod(env, mgr_class, get_inst) : NULL;
    set = (mgr && target_names) ? (*env)->CallObjectMethod(env, mgr, target_names) : NULL;
    set_class = (*env)->FindClass(env, "java/util/Set");
    to_array = set_class ? (*env)->GetMethodID(env, set_class, "toArray", "()[Ljava/lang/Object;") : NULL;
    names = (set && to_array) ? (jobjectArray)(*env)->CallObjectMethod(env, set, to_array) : NULL;
    if (!names) { raven_log("no transform targets"); return 0; }

    name_count = (*env)->GetArrayLength(env, names);
    for (i = 0; i < name_count; ++i) {
        jstring n = (jstring)(*env)->GetObjectArrayElement(env, names, i);
        const char *chars;
        jclass klass;
        if (!n) continue;
        chars = (*env)->GetStringUTFChars(env, n, NULL);
        if (!chars) { (*env)->DeleteLocalRef(env, n); continue; }
        klass = find_loaded_class(env, chars, loader);
        if (klass) {
            if ((*g_jvmti)->RetransformClasses(g_jvmti, 1, &klass) == JVMTI_ERROR_NONE)
                ++succeeded;
            (*env)->DeleteLocalRef(env, klass);
        }
        (*env)->ReleaseStringUTFChars(env, n, chars);
        (*env)->DeleteLocalRef(env, n);
    }
    raven_log("retransformed %d/%d classes", (int)succeeded, (int)name_count);
    return succeeded == name_count;
}

static int apply_transformers(JNIEnv *env, jobject loader) {
    jclass lc;
    jmethodID lm, get_inst;
    jclass mgr_class;
    jobject mgr;

    raven_progress_send(0.75f, "Resolving transformer hooks");
    if (!resolve_hooks(env, loader)) return 0;

    lc = (*env)->FindClass(env, "java/lang/ClassLoader");
    lm = lc ? (*env)->GetMethodID(env, lc, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;

    raven_progress_send(0.77f, "Building transformer manager");
    mgr_class = lm ? load_class_via_loader(env, loader, lm,
            "mindless.runtime.RavenTransformerManager") : NULL;
    get_inst = mgr_class ? (*env)->GetStaticMethodID(env, mgr_class, "get",
            "()Lmindless/runtime/RavenTransformerManager;") : NULL;
    mgr = get_inst ? (*env)->CallStaticObjectMethod(env, mgr_class, get_inst) : NULL;
    if (!mgr || (*env)->ExceptionCheck(env)) {
        log_pending_exception(env, "prime RavenTransformerManager");
        return 0;
    }
    (*env)->DeleteLocalRef(env, mgr);

    raven_progress_send(0.81f, "Installing ClassFileLoadHook");
    if (!install_hook()) return 0;

    raven_progress_send(0.83f, "Retransforming loaded classes");
    return retransform_targets(env, loader);
}

/* ---- Main bootstrap ----------------------------------------------------- */

void raven_bootstrap_main(void *arg) {
    JavaVM *vm;
    JNIEnv *env = NULL;
    jobject loader = NULL;
    jclass bootstrap_class;
    jmethodID start;
    char jar_path[4096];
    raven_runtime_namespace ns;
    int payload_id;
    const char *profile;
    int embedded_forge;
    int attempt;
    (void)arg;

    raven_sleep_ms(150);
    raven_progress_init();
    raven_progress_send(0.21f, "Waiting for Java runtime");

    for (attempt = 0; attempt < 600; ++attempt) {
        vm = raven_find_jvm();
        if (vm) break;
        raven_sleep_ms(100);
    }
    if (!vm) { raven_log("JVM not found"); goto done; }

    raven_progress_send(0.43f, "Java VM ready");
    if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK || !env) {
        raven_log("AttachCurrentThreadAsDaemon failed");
        goto done;
    }

    raven_progress_send(0.45f, "Attached to Java VM");
    if ((*vm)->GetEnv(vm, (void **)&g_jvmti, JVMTI_VERSION_1_2) != JNI_OK || !g_jvmti) {
        raven_log("JVMTI unavailable");
        goto detach;
    }

    raven_progress_send(0.50f, "Searching for Minecraft class loader");
    for (attempt = 0; attempt < 600 && !loader; ++attempt) {
        loader = find_game_loader(env);
        if (!loader) raven_sleep_ms(100);
    }
    if (!loader) { raven_log("Minecraft class loader not found"); goto detach; }

    g_game_loader = (*env)->NewGlobalRef(env, loader);
    raven_progress_send(0.53f, "Minecraft class loader found");

    ns = detect_namespace(env, loader);
    if (ns == RAVEN_NAMESPACE_UNKNOWN) { raven_log("namespace detection failed"); goto detach; }

    embedded_forge = ns == RAVEN_NAMESPACE_MCP
            && !(loader_has_class(env, loader, "net.minecraftforge.common.MinecraftForge")
                 && loader_has_class(env, loader, "net.minecraftforge.fml.common.eventhandler.EventBus"));

    set_system_property(env, "raven.runtimeNamespace", ns == RAVEN_NAMESPACE_MCP ? "mcp" : "srg");
    set_system_property(env, "raven.runtimeProfile", ns == RAVEN_NAMESPACE_MCP ? "lunar" : "forge");
    set_system_property(env, "raven.embeddedForge", embedded_forge ? "true" : "false");

    payload_id = ns == RAVEN_NAMESPACE_MCP ? RAVEN_PAYLOAD_LUNAR : RAVEN_PAYLOAD_FORGE;
    profile = ns == RAVEN_NAMESPACE_MCP ? "lunar-mcp" : "forge-srg";

    raven_progress_send(0.61f, "Extracting payload");
    if (!raven_extract_payload(payload_id, profile, jar_path, sizeof(jar_path)))
        goto detach;

    raven_progress_send(0.65f, "Payload attached to class loader");
    if (!append_payload_to_loader(env, loader, jar_path))
        goto detach;

    raven_progress_send(0.71f, "Loading bootstrap class");
    {
        jclass lc = (*env)->FindClass(env, "java/lang/ClassLoader");
        jmethodID lm = lc ? (*env)->GetMethodID(env, lc, "loadClass",
                "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
        jstring bname = (*env)->NewStringUTF(env, "mindless.runtime.NativeBootstrap");
        bootstrap_class = (lm && bname) ? (jclass)(*env)->CallObjectMethod(env, loader, lm, bname) : NULL;
        if (!bootstrap_class || (*env)->ExceptionCheck(env)) {
            log_pending_exception(env, "load NativeBootstrap");
            goto detach;
        }
    }

    if (!apply_transformers(env, loader)) {
        raven_log("apply_transformers failed");
        goto detach;
    }

    raven_progress_send(0.98f, "Handing off to Java bootstrap");
    start = (*env)->GetStaticMethodID(env, bootstrap_class, "start", "()V");
    if (!start) { log_pending_exception(env, "resolve NativeBootstrap.start"); goto detach; }
    (*env)->CallStaticVoidMethod(env, bootstrap_class, start);
    if ((*env)->ExceptionCheck(env)) { log_pending_exception(env, "NativeBootstrap.start"); goto detach; }

    raven_progress_send(1.0f, "Ready");
    raven_log("NativeBootstrap.start completed; Raven is active");

    /* Try to clean up payload file */
    remove(jar_path);

detach:
    if (loader) (*env)->DeleteLocalRef(env, loader);
    if (vm) (*vm)->DetachCurrentThread(vm);
done:
    raven_progress_close();
}
