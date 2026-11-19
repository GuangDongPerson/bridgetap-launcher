/*
 * BridgeTap native payload.
 *
 * Loaded into a running client JVM with CreateRemoteThread + LoadLibraryW by btinject.exe. This is
 * the only way in when the target starts with -XX:+DisableAttachMechanism (Lunar Client does):
 * the attach listener is never created, so the whole com.sun.tools.attach protocol is unavailable.
 *
 * What it does, entirely from inside the target process:
 *   1. locate jvm.dll among the already-loaded modules
 *   2. JNI_GetCreatedJavaVMs  -> JavaVM*
 *   3. AttachCurrentThread    -> JNIEnv*
 *   4. JavaVM->GetEnv(JVMTI_VERSION_1_2) -> jvmtiEnv*   (JVMTI is usable in the live phase and is
 *      not gated by DisableAttachMechanism)
 *   5. AddCapabilities(can_retransform_classes)
 *   6. AddToBootstrapClassLoaderSearch(agent jar)
 *   7. Class.forName("...Bootstrap", true, null).runNative()
 *   8. register ClassFileLoadHook + RetransformClasses on every loaded org.lwjgl.opengl.Display
 *
 * Step 6 is why the java side needs no classloader gymnastics: the entire agent jar lands on the
 * bootstrap loader, so the class we inject a call to (com.bridger.compat.boot.Hook) is resolvable
 * from any child-first game classloader, and its sink shares the interface identity.
 *
 * Build: cl /LD /MT /O2 payload.c /Fe:bridgetap-payload.dll /link psapi.lib
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>

#include "jni.h"
#include "jvmti.h"

#define BT_MAX_PATH 1024
#define BT_AGENT_CLASS "com.bridger.compat.agent.Bootstrap"

/* Every class with a frame-presenting method worth hooking. The Java side decides which of the
   candidate methods exist per class; this list only limits how many classes cross the JNI boundary.
   The platform context implementation at the bottom of the chain is what actually calls
   wglSwapBuffers, so it fires no matter which route the client takes to present a frame. */
static const char *BT_TARGET_CLASSES[] = {
    /* LWJGL is never obfuscated: always the presenting path. */
    "org/lwjgl/opengl/Display",
    /* Lunar: full MCP names. */
    "net/minecraft/client/Minecraft",
    "net/minecraft/client/gui/GuiIngame",
    "net/minecraft/client/multiplayer/PlayerControllerMP",
    /* Chat interception target: the player's own sendChatMessage. */
    "net/minecraft/client/entity/EntityPlayerSP",
    /* Take-off target when the player class does not override jump(). */
    "net/minecraft/entity/EntityLivingBase",
    /* The offscreen framebuffer's blit - the only point where a panel is both on screen and in the
       image a screenshot reads back. */
    "net/minecraft/client/shader/Framebuffer",
    /* Badlion: plain 1.8.9 obfuscation, mapped from 1.8.9-mcp.tsrg2 and verified against
       BLClient.jar. ave = Minecraft, avo = GuiIngame, bda = PlayerControllerMP,
       bew = EntityPlayerSP, pr = EntityLivingBase (its bF() is jump). Absent on other clients,
       harmless - is_target_class simply never matches. */
    "ave",
    "avo",
    "bda",
    "bew",
    "pr",
    /* EntityRenderer. Not hooked - listed only so the agent dumps its runtime bytes, which is the
       one way to see the render path Badlion's odin actually leaves behind (the class on disk
       still calls GuiIngame.a(F)V, and that method never runs). */
    "bfk",
    /* Framebuffer: bfw. Its c(II)V is framebufferRender. */
    "bfw",
};

static const char *BT_TARGET_SIGS[] = {
    "Lorg/lwjgl/opengl/Display;",
    "Lnet/minecraft/client/Minecraft;",
    "Lnet/minecraft/client/gui/GuiIngame;",
    "Lnet/minecraft/client/multiplayer/PlayerControllerMP;",
    "Lnet/minecraft/client/entity/EntityPlayerSP;",
    "Lnet/minecraft/entity/EntityLivingBase;",
    "Lnet/minecraft/client/shader/Framebuffer;",
    "Lave;",
    "Lavo;",
    "Lbda;",
    "Lbew;",
    "Lpr;",
    "Lbfk;",
    "Lbfw;",
};

static int is_target_class(const char *name) {
    size_t i;
    if (name == NULL) {
        return 0;
    }
    for (i = 0; i < sizeof(BT_TARGET_CLASSES) / sizeof(BT_TARGET_CLASSES[0]); i++) {
        if (strcmp(name, BT_TARGET_CLASSES[i]) == 0) {
            return 1;
        }
    }
    return 0;
}

static int is_target_sig(const char *sig) {
    size_t i;
    if (sig == NULL) {
        return 0;
    }
    for (i = 0; i < sizeof(BT_TARGET_SIGS) / sizeof(BT_TARGET_SIGS[0]); i++) {
        if (strcmp(sig, BT_TARGET_SIGS[i]) == 0) {
            return 1;
        }
    }
    return 0;
}

static HMODULE g_self;
static char g_logPath[BT_MAX_PATH];
static char g_agentJar[BT_MAX_PATH];

static JavaVM *g_vm;
static jvmtiEnv *g_jvmti;
static jclass g_agentClass;
static jmethodID g_runNative;
static jmethodID g_transformBytes;

static void bt_log(const char *fmt, ...) {
    char line[4096];
    va_list ap;
    int n;
    HANDLE h;
    DWORD written = 0;

    va_start(ap, fmt);
    n = _vsnprintf_s(line, sizeof(line) - 2, _TRUNCATE, fmt, ap);
    va_end(ap);
    if (n < 0) {
        n = (int)strlen(line);
    }
    if (n > (int)sizeof(line) - 3) {
        n = (int)sizeof(line) - 3;
    }
    line[n++] = '\r';
    line[n++] = '\n';
    line[n] = '\0';

    h = CreateFileA(g_logPath, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE,
                    NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h != INVALID_HANDLE_VALUE) {
        SetFilePointer(h, 0, NULL, FILE_END);
        WriteFile(h, line, (DWORD)n, &written, NULL);
        CloseHandle(h);
    }
    OutputDebugStringA(line);
}

/*
 * Log the pending JNI exception's type and message, then clear it.
 *
 * ExceptionDescribe writes to stdout, and a client launched with javaw.exe has no console - so
 * every failure ended up in the log as the same bare "not loadable from bootstrap". The type is
 * the whole answer: UnsupportedClassVersionError means the jar was built for a newer JVM,
 * NoClassDefFoundError means a dependency is missing, and a plain ClassNotFoundException means the
 * jar never made it onto the search path.
 *
 * The reference returned by ExceptionOccurred stays valid after ExceptionClear; clearing only
 * drops the "pending" flag, and JNI calls are refused while that flag is set.
 */
static void log_pending_exception(JNIEnv *env, const char *where) {
    jthrowable thrown;
    jclass throwableClass;
    jmethodID to_string;
    jstring text;
    const char *utf;

    if (!(*env)->ExceptionCheck(env)) {
        return;
    }
    thrown = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (thrown == NULL) {
        bt_log("%s: exception (reference lost)", where);
        return;
    }

    throwableClass = (*env)->FindClass(env, "java/lang/Throwable");
    if (throwableClass == NULL) {
        (*env)->ExceptionClear(env);
        bt_log("%s: exception (Throwable not resolvable)", where);
        (*env)->DeleteLocalRef(env, thrown);
        return;
    }
    to_string = (*env)->GetMethodID(env, throwableClass, "toString", "()Ljava/lang/String;");
    if (to_string == NULL) {
        (*env)->ExceptionClear(env);
        bt_log("%s: exception (toString missing)", where);
        (*env)->DeleteLocalRef(env, thrown);
        return;
    }

    text = (jstring)(*env)->CallObjectMethod(env, thrown, to_string);
    if (text == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        bt_log("%s: exception (toString threw)", where);
        (*env)->DeleteLocalRef(env, thrown);
        return;
    }
    utf = (*env)->GetStringUTFChars(env, text, NULL);
    if (utf != NULL) {
        bt_log("%s: %s", where, utf);
        (*env)->ReleaseStringUTFChars(env, text, utf);
    } else {
        (*env)->ExceptionClear(env);
        bt_log("%s: exception (text unreadable)", where);
    }
    (*env)->DeleteLocalRef(env, text);
    (*env)->DeleteLocalRef(env, thrown);
}

/* Directory of this DLL, so bootstrap.ini can sit next to it. */
static void self_dir(char *out, size_t cap) {
    char full[BT_MAX_PATH];
    DWORD n = GetModuleFileNameA(g_self, full, BT_MAX_PATH);
    char *slash;
    if (n == 0 || n >= BT_MAX_PATH) {
        out[0] = '\0';
        return;
    }
    slash = strrchr(full, '\\');
    if (slash != NULL) {
        *slash = '\0';
    }
    _snprintf_s(out, cap, _TRUNCATE, "%s", full);
}

static void init_paths(void) {
    char dir[BT_MAX_PATH];
    char cfg[BT_MAX_PATH];
    const char *local;

    local = getenv("LOCALAPPDATA");
    if (local == NULL || local[0] == '\0') {
        local = getenv("TEMP");
    }
    if (local == NULL) {
        local = ".";
    }
    _snprintf_s(g_logPath, sizeof(g_logPath), _TRUNCATE, "%s\\BridgeTap\\inject-native.log", local);

    /* bootstrap.ini: single line "agent=<absolute path to bridgetap-agent.jar>" */
    self_dir(dir, sizeof(dir));
    _snprintf_s(cfg, sizeof(cfg), _TRUNCATE, "%s\\bootstrap.ini", dir);

    {
        FILE *f = NULL;
        if (fopen_s(&f, cfg, "r") == 0 && f != NULL) {
            char line[BT_MAX_PATH];
            while (fgets(line, sizeof(line), f) != NULL) {
                char *p = line;
                /* A UTF-8 BOM in front of "agent=" makes the prefix test fail, and the loader then
                   silently falls back to the jar next to the DLL - which is the previous build. That
                   is a stale agent with no error anywhere except "injected but nothing happens". */
                if ((unsigned char)p[0] == 0xEF && (unsigned char)p[1] == 0xBB
                    && (unsigned char)p[2] == 0xBF) {
                    p += 3;
                }
                while (*p == ' ' || *p == '\t') {
                    p++;
                }
                if (_strnicmp(p, "agent=", 6) == 0) {
                    char *v = p + 6;
                    size_t len;
                    while (*v == ' ' || *v == '\t' || *v == '"') {
                        v++;
                    }
                    len = strlen(v);
                    while (len > 0 && (v[len - 1] == '\r' || v[len - 1] == '\n'
                                       || v[len - 1] == ' ' || v[len - 1] == '"')) {
                        v[--len] = '\0';
                    }
                    _snprintf_s(g_agentJar, sizeof(g_agentJar), _TRUNCATE, "%s", v);
                }
            }
            fclose(f);
        }
    }

    if (g_agentJar[0] == '\0') {
        /* Default layout: the jar sits next to the DLL. */
        _snprintf_s(g_agentJar, sizeof(g_agentJar), _TRUNCATE, "%s\\bridgetap-agent.jar", dir);
    }
}

/* jvm.dll may have been loaded by full path; GetModuleHandle by basename usually still matches. */
static HMODULE find_jvm(void) {
    HMODULE mods[1024];
    DWORD needed = 0;
    DWORD i;
    HMODULE direct = GetModuleHandleA("jvm.dll");
    if (direct != NULL) {
        return direct;
    }
    if (!EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) {
        return NULL;
    }
    for (i = 0; i < needed / sizeof(HMODULE); i++) {
        char name[MAX_PATH];
        if (GetModuleBaseNameA(GetCurrentProcess(), mods[i], name, sizeof(name)) == 0) {
            continue;
        }
        if (_stricmp(name, "jvm.dll") == 0) {
            return mods[i];
        }
    }
    return NULL;
}

/*
 * ClassFileLoadHook.
 *
 * Signature note, this cost a debugging round: the JVMTI specification describes the 1.2 form
 * (class_data is a jbyteArray) but HotSpot does not implement a version switch. JDK 17's
 * jvmtiExport.cpp, JvmtiClassFileLoadHookPoster::post_to_env, always dispatches with
 *
 *     (env, jni_env, class_being_redefined, loader, name,
 *      protection_domain, len, raw_bytes_ptr, &new_len, &new_data)
 *
 * i.e. the JVMTI 1.0 parameter list, regardless of the version GetEnv was asked for. Matching
 * jvmti.h's own typedef is therefore the only correct thing to do; the byte array has to be built
 * here.
 */
static void JNICALL on_class_file_load_hook(jvmtiEnv *jvmti, JNIEnv *env,
                                            jclass class_being_redefined, jobject loader,
                                            const char *name, jobject protection_domain,
                                            jint class_data_len, const unsigned char *class_data,
                                            jint *new_class_data_len, unsigned char **new_class_data) {
    jbyteArray input;
    jbyteArray patched;
    jstring jname;
    jsize len;
    unsigned char *buf = NULL;

    (void)class_being_redefined;
    (void)loader;
    (void)protection_domain;

    if (!is_target_class(name)) {
        return;
    }
    if (g_agentClass == NULL || g_transformBytes == NULL || class_data == NULL || class_data_len < 8) {
        return;
    }

    bt_log("hook fired: %s len=%d", name, (int)class_data_len);

    input = (*env)->NewByteArray(env, class_data_len);
    if (input == NULL) {
        return;
    }
    (*env)->SetByteArrayRegion(env, input, 0, class_data_len, (const jbyte *)class_data);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return;
    }

    jname = (*env)->NewStringUTF(env, name);
    if (jname == NULL) {
        return;
    }

    patched = (jbyteArray)(*env)->CallStaticObjectMethod(env, g_agentClass, g_transformBytes,
                                                         jname, input);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        bt_log("transformBytes threw; class left untouched");
        return;
    }
    if (patched == NULL) {
        return;
    }

    len = (*env)->GetArrayLength(env, patched);
    if (len <= 0) {
        return;
    }
    if ((*jvmti)->Allocate(jvmti, (jlong)len, &buf) != JVMTI_ERROR_NONE) {
        bt_log("jvmti Allocate(%d) failed", (int)len);
        return;
    }
    (*env)->GetByteArrayRegion(env, patched, 0, len, (jbyte *)buf);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*jvmti)->Deallocate(jvmti, buf);
        return;
    }
    *new_class_data = buf;
    *new_class_data_len = len;
    bt_log("transformed %s (%d -> %d bytes)", name, (int)class_data_len, (int)len);
}

/* Class.forName(name, true, loader): resolves through an explicit loader.
   Passing NULL means the bootstrap loader, which only sees jars added with
   AddToBootstrapClassLoaderSearch; passing the game loader lets the class see net.minecraft.*.
   Returns a global ref, or NULL. */
static jclass load_class_with_loader(JNIEnv *env, const char *name, jobject loader) {
    jclass classClass;
    jmethodID forName;
    jstring jname;
    jclass loaded;
    jclass global = NULL;

    classClass = (*env)->FindClass(env, "java/lang/Class");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    if (classClass == NULL) {
        return NULL;
    }
    forName = (*env)->GetStaticMethodID(env, classClass, "forName",
        "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (forName == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, classClass);
        return NULL;
    }
    jname = (*env)->NewStringUTF(env, name);
    if (jname == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, classClass);
        return NULL;
    }
    loaded = (jclass)(*env)->CallStaticObjectMethod(env, classClass, forName, jname, JNI_TRUE, loader);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        bt_log("load %s: threw (loader %s)", name,
               loader == NULL ? "bootstrap" : "game");
        loaded = NULL;
    }
    if (loaded != NULL) {
        global = (jclass)(*env)->NewGlobalRef(env, loaded);
        (*env)->DeleteLocalRef(env, loaded);
    }
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, classClass);
    return global;
}

static jclass load_bootstrap_class(JNIEnv *env, const char *name) {
    return load_class_with_loader(env, name, NULL);
}

/* A loaded class by JVMTI signature, e.g. "Lnet/minecraft/client/Minecraft;". Global ref or NULL. */
static jclass find_loaded_class(JNIEnv *env, const char *wanted) {
    jint count = 0;
    jclass *classes = NULL;
    jclass found = NULL;
    jint i;

    if (g_jvmti == NULL) {
        return NULL;
    }
    if ((*g_jvmti)->GetLoadedClasses(g_jvmti, &count, &classes) != JVMTI_ERROR_NONE || count <= 0) {
        return NULL;
    }
    for (i = 0; i < count; i++) {
        char *sig = NULL;
        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &sig, NULL) == JVMTI_ERROR_NONE && sig != NULL) {
            int match = strcmp(sig, wanted) == 0;
            (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)sig);
            if (match) {
                found = (jclass)(*env)->NewGlobalRef(env, classes[i]);
                break;
            }
        }
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
    return found;
}

static int retransform_targets(void) {
    jint count = 0;
    jclass *classes = NULL;
    jclass *targets = NULL;
    jint found = 0;
    jint i;
    jvmtiError err;

    err = (*g_jvmti)->GetLoadedClasses(g_jvmti, &count, &classes);
    if (err != JVMTI_ERROR_NONE) {
        bt_log("GetLoadedClasses failed: %d", (int)err);
        return -1;
    }
    if (count <= 0) {
        return 0;
    }
    targets = (jclass *)calloc((size_t)count, sizeof(jclass));
    if (targets == NULL) {
        return -1;
    }

    for (i = 0; i < count; i++) {
        char *sig = NULL;
        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &sig, NULL) == JVMTI_ERROR_NONE && sig != NULL) {
            if (is_target_sig(sig)) {
                targets[found++] = classes[i];
            }
            (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)sig);
        }
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);

    if (found == 0) {
        free(targets);
        bt_log("no hookable LWJGL class loaded yet; hook stays armed for the first load");
        return 0;
    }

    /* One at a time on purpose: RetransformClasses rejects the whole batch when a single class
       refuses (a class with no live instances is fine, but a class already being redefined is not),
       and losing all of them because of one would be silent. */
    {
        jint ok = 0;
        for (i = 0; i < found; i++) {
            err = (*g_jvmti)->RetransformClasses(g_jvmti, 1, &targets[i]);
            if (err == JVMTI_ERROR_NONE) {
                ok++;
            } else {
                bt_log("retransform #%d failed: %d", (int)i, (int)err);
            }
        }
        free(targets);
        bt_log("retransform requested for %d of %d class(es)", (int)ok, (int)found);
        return ok;
    }
}

static DWORD WINAPI worker(LPVOID param) {
    HMODULE jvm;
    typedef jint(JNICALL *pfn_get_vms)(JavaVM **, jsize, jsize *);
    pfn_get_vms get_vms;
    JNIEnv *env = NULL;
    jsize vmCount = 0;
    jvmtiError jerr;
    jvmtiCapabilities caps;
    jvmtiEventCallbacks callbacks;
    jclass classClass;
    jmethodID forName;
    jstring clsName;
    jclass agentClass;
    jmethodID runNative;
    jmethodID transformBytes;

    (void)param;
    bt_log("payload attached (pid %lu)", (unsigned long)GetCurrentProcessId());
    bt_log("agent jar: %s", g_agentJar);

    jvm = find_jvm();
    if (jvm == NULL) {
        bt_log("FATAL: jvm.dll not found in this process");
        return 1;
    }
    bt_log("jvm.dll at %p", (void *)jvm);

    get_vms = (pfn_get_vms)GetProcAddress(jvm, "JNI_GetCreatedJavaVMs");
    if (get_vms == NULL) {
        bt_log("FATAL: JNI_GetCreatedJavaVMs not exported");
        return 1;
    }
    if (get_vms(&g_vm, 1, &vmCount) != JNI_OK || g_vm == NULL || vmCount < 1) {
        bt_log("FATAL: no JavaVM (count=%d)", (int)vmCount);
        return 1;
    }
    bt_log("JavaVM acquired (count=%d)", (int)vmCount);

    if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != JNI_OK || env == NULL) {
        bt_log("FATAL: AttachCurrentThread failed");
        return 1;
    }
    bt_log("thread attached");

    jerr = (*g_vm)->GetEnv(g_vm, (void **)&g_jvmti, JVMTI_VERSION_1_2);
    if (jerr != JNI_OK || g_jvmti == NULL) {
        bt_log("FATAL: GetEnv(JVMTI) failed: %d", (int)jerr);
        return 1;
    }
    bt_log("JVMTI environment acquired");
    {
        jint ver = 0;
        if ((*g_jvmti)->GetVersionNumber(g_jvmti, &ver) == JVMTI_ERROR_NONE) {
            bt_log("jvmti version = 0x%08x (1.0=0x30010000 1.1=0x30010100 1.2=0x30010200)", (unsigned)ver);
        }
    }
    /* The JVMTI version alone cannot tell Java 8 from 17 - it has been 1.2 since 9. Without this
       line a class-version mismatch is indistinguishable from a missing class in the log. */
    {
        jclass systemClass = (*env)->FindClass(env, "java/lang/System");
        if (systemClass != NULL) {
            jmethodID getProperty = (*env)->GetStaticMethodID(env, systemClass, "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;");
            if (getProperty != NULL) {
                jstring key = (*env)->NewStringUTF(env, "java.version");
                jstring value = (jstring)(*env)->CallStaticObjectMethod(env, systemClass, getProperty, key);
                if (value != NULL && !(*env)->ExceptionCheck(env)) {
                    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
                    if (utf != NULL) {
                        bt_log("java.version = %s", utf);
                        (*env)->ReleaseStringUTFChars(env, value, utf);
                    }
                    (*env)->DeleteLocalRef(env, value);
                } else {
                    (*env)->ExceptionClear(env);
                }
                (*env)->DeleteLocalRef(env, key);
            } else {
                (*env)->ExceptionClear(env);
            }
            (*env)->DeleteLocalRef(env, systemClass);
        } else {
            (*env)->ExceptionClear(env);
        }
    }

    memset(&caps, 0, sizeof(caps));
    caps.can_retransform_classes = 1;
    caps.can_redefine_classes = 1;
    jerr = (*g_jvmti)->AddCapabilities(g_jvmti, &caps);
    if (jerr != JVMTI_ERROR_NONE) {
        bt_log("WARN: AddCapabilities: %d", (int)jerr);
    }

    jerr = (*g_jvmti)->AddToBootstrapClassLoaderSearch(g_jvmti, g_agentJar);
    if (jerr != JVMTI_ERROR_NONE) {
        bt_log("FATAL: AddToBootstrapClassLoaderSearch(%s): %d", g_agentJar, (int)jerr);
        return 1;
    }
    bt_log("agent jar appended to bootstrap search");

    /* Class.forName(name, true, null) -> bootstrap loader, regardless of the system loader. */
    classClass = (*env)->FindClass(env, "java/lang/Class");
    if (classClass == NULL) {
        (*env)->ExceptionClear(env);
        bt_log("FATAL: java.lang.Class not found");
        return 1;
    }
    forName = (*env)->GetStaticMethodID(env, classClass, "forName",
                                        "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (forName == NULL) {
        (*env)->ExceptionClear(env);
        bt_log("FATAL: Class.forName(String,boolean,ClassLoader) missing");
        return 1;
    }
    clsName = (*env)->NewStringUTF(env, BT_AGENT_CLASS);
    agentClass = (jclass)(*env)->CallStaticObjectMethod(env, classClass, forName,
                                                        clsName, JNI_TRUE, NULL);
    if (agentClass == NULL || (*env)->ExceptionCheck(env)) {
        log_pending_exception(env, "FATAL: Bootstrap load failed");
        bt_log("FATAL: %s not loadable from bootstrap", BT_AGENT_CLASS);
        return 1;
    }
    g_agentClass = (jclass)(*env)->NewGlobalRef(env, agentClass);
    bt_log("bootstrap class resolved: %s", BT_AGENT_CLASS);

    runNative = (*env)->GetStaticMethodID(env, agentClass, "runNative", "()V");
    transformBytes = (*env)->GetStaticMethodID(env, agentClass, "transformBytes",
                                               "(Ljava/lang/String;[B)[B");
    if (runNative == NULL || transformBytes == NULL) {
        (*env)->ExceptionClear(env);
        bt_log("FATAL: Bootstrap entry points missing (runNative / transformBytes)");
        return 1;
    }
    g_runNative = runNative;
    g_transformBytes = transformBytes;

    /* Hand a snapshot of every loaded class to the java side. The instrumentation path gets this
       from Instrumentation.getAllLoadedClasses(); with no attach mechanism there is no
       Instrumentation, so JVMTI's GetLoadedClasses is the only equivalent. Must happen before
       runNative(): the first hooked frame may already try to resolve the game. */
    {
        jint count = 0;
        jclass *classes = NULL;
        if ((*g_jvmti)->GetLoadedClasses(g_jvmti, &count, &classes) == JVMTI_ERROR_NONE && count > 0) {
            jclass classCls = (*env)->FindClass(env, "java/lang/Class");
            if (classCls != NULL) {
                jobjectArray snapshot = (*env)->NewObjectArray(env, count, classCls, NULL);
                if (snapshot != NULL) {
                    jint k;
                    jmethodID setLoaded;
                    for (k = 0; k < count; k++) {
                        (*env)->SetObjectArrayElement(env, snapshot, k, classes[k]);
                    }
                    setLoaded = (*env)->GetStaticMethodID(env, agentClass, "setLoadedClasses",
                                                          "([Ljava/lang/Class;)V");
                    if (setLoaded != NULL) {
                        (*env)->CallStaticVoidMethod(env, agentClass, setLoaded, snapshot);
                        if ((*env)->ExceptionCheck(env)) {
                            (*env)->ExceptionClear(env);
                        }
                        bt_log("handed %d loaded classes to java", (int)count);
                    } else {
                        (*env)->ExceptionClear(env);
                        bt_log("WARN: setLoadedClasses missing");
                    }
                    (*env)->DeleteLocalRef(env, snapshot);
                }
            } else {
                (*env)->ExceptionClear(env);
            }
            (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
        }
    }

    (*env)->CallStaticVoidMethod(env, agentClass, runNative);
    if ((*env)->ExceptionCheck(env)) {
        log_pending_exception(env, "WARN: runNative threw");
    }

    /* Live class-map dump.
       Deliberately addresses a class that earlier builds did not contain: Bootstrap itself is pinned
       to whichever jar defined it first, so a repeat injection cannot run new Bootstrap code, but a
       brand-new class name is still resolved from the jar we just appended. This is what makes
       reconnaissance possible without restarting the client.
       Loaded through Class.forName(name, true, null) rather than FindClass: the latter resolves
       against the calling thread's loader, which on an attached native thread is the system loader,
       while these classes live on the bootstrap search path. */
    {
        jclass meta = load_bootstrap_class(env, "com.bridger.compat.agent.ClassMapDump");
        if (meta == NULL) {
            bt_log("class map: class not resolvable");
        } else {
            jmethodID run = (*env)->GetStaticMethodID(env, meta, "run", "()Ljava/lang/String;");
            if (run == NULL) {
                (*env)->ExceptionClear(env);
            } else {
                jstring result = (jstring)(*env)->CallStaticObjectMethod(env, meta, run);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                    bt_log("class map: threw");
                } else if (result != NULL) {
                    const char *text = (*env)->GetStringUTFChars(env, result, NULL);
                    if (text != NULL) {
                        bt_log("class map: %s", text);
                        (*env)->ReleaseStringUTFChars(env, result, text);
                    }
                    (*env)->DeleteLocalRef(env, result);
                }
            }
            (*env)->DeleteGlobalRef(env, meta);
        }
    }

    /* Resolvability check for the injection target.
       The client's loader is child-first and throws instead of falling back for classes it claims;
       org.objectweb.asm.* is on its allowlist, so the bridge class should resolve THROUGH THE GAME
       LOADER. Verifying that here proves the injected invokestatic will link, without restarting. */
    {
        jclass mcForProbe = find_loaded_class(env, "Lnet/minecraft/client/Minecraft;");
        if (mcForProbe != NULL) {
            /* getClassLoader() belongs to java.lang.Class, not to Minecraft: the jclass here is a
               Class *instance*, so the method has to be looked up on the Class type itself. */
            jclass classType = (*env)->FindClass(env, "java/lang/Class");
            jmethodID getLoader = NULL;
            if (classType != NULL) {
                getLoader = (*env)->GetMethodID(env, classType, "getClassLoader",
                    "()Ljava/lang/ClassLoader;");
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                }
            }
            if (getLoader == NULL) {
                bt_log("hook target: getClassLoader lookup failed");
            } else {
                jobject loader = (*env)->CallObjectMethod(env, mcForProbe, getLoader);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                    loader = NULL;
                }
                if (loader != NULL) {
                    jclass bridge = load_class_with_loader(env,
                        "org.objectweb.asm.BridgeTapHook", loader);
                    bt_log("hook target via game loader: %s",
                           bridge != NULL ? "RESOLVED" : "NOT RESOLVABLE");
                    if (bridge != NULL) {
                        (*env)->DeleteGlobalRef(env, bridge);
                    }
                    (*env)->DeleteLocalRef(env, loader);
                } else {
                    bt_log("hook target: Minecraft has no classloader");
                }
            }
            if (classType != NULL) {
                (*env)->DeleteLocalRef(env, classType);
            }
            (*env)->DeleteGlobalRef(env, mcForProbe);
        } else {
            bt_log("hook target: Minecraft not loaded");
        }
    }

    /* Method table of the game's own class. Names may or may not survive remapping, so read them.
       The probe is loaded on the bootstrap loader and handed the game's Class object: the client's
       own loader refuses to resolve net.minecraft.client.Minecraft for outside callers, but a Class
       object crosses loaders fine. */
    {
        jclass mcClass = find_loaded_class(env, "Lnet/minecraft/client/Minecraft;");
        if (mcClass == NULL) {
            bt_log("mc table: Minecraft not loaded");
        } else {
            jclass probe = load_bootstrap_class(env, "com.bridger.compat.agent.McTable");
            if (probe == NULL) {
                bt_log("mc table: class not resolvable on bootstrap");
            } else {
                jmethodID run = (*env)->GetStaticMethodID(env, probe, "run",
                    "(Ljava/lang/Class;)Ljava/lang/String;");
                if (run == NULL) {
                    (*env)->ExceptionClear(env);
                    bt_log("mc table: run(Class) missing");
                } else {
                    jstring result = (jstring)(*env)->CallStaticObjectMethod(env, probe, run, mcClass);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionClear(env);
                        bt_log("mc table: threw");
                    } else if (result != NULL) {
                        const char *text = (*env)->GetStringUTFChars(env, result, NULL);
                        if (text != NULL) {
                            bt_log("mc table: %s", text);
                            (*env)->ReleaseStringUTFChars(env, result, text);
                        }
                        (*env)->DeleteLocalRef(env, result);
                    }
                }
                (*env)->DeleteGlobalRef(env, probe);
            }
            (*env)->DeleteGlobalRef(env, mcClass);
        }
    }

    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = on_class_file_load_hook;
    jerr = (*g_jvmti)->SetEventCallbacks(g_jvmti, &callbacks, (jint)sizeof(callbacks));
    if (jerr != JVMTI_ERROR_NONE) {
        bt_log("FATAL: SetEventCallbacks: %d", (int)jerr);
        return 1;
    }
    jerr = (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE,
                                                JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    if (jerr != JVMTI_ERROR_NONE) {
        bt_log("FATAL: enable CLASS_FILE_LOAD_HOOK: %d", (int)jerr);
        return 1;
    }
    bt_log("class file hook armed");

    retransform_targets();

    /* Display state, read back through JNI.
       "No hook ever fires" has two very different causes: the client replaced its frame loop, or
       the loop was simply not running when we attached (Lunar's menu is its own UI, not Minecraft
       rendering). isCreated/width/height distinguish them without another round trip. */
    {
        jclass display = (*env)->FindClass(env, "org/lwjgl/opengl/Display");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            display = NULL;
        }
        if (display == NULL) {
            bt_log("display: class not loaded yet");
        } else {
            jmethodID mCreated = (*env)->GetStaticMethodID(env, display, "isCreated", "()Z");
            jmethodID mWidth = (*env)->GetStaticMethodID(env, display, "getWidth", "()I");
            jmethodID mHeight = (*env)->GetStaticMethodID(env, display, "getHeight", "()I");
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
            if (mCreated != NULL && mWidth != NULL && mHeight != NULL) {
                jboolean created = (*env)->CallStaticBooleanMethod(env, display, mCreated);
                jint w = (*env)->CallStaticIntMethod(env, display, mWidth);
                jint h = (*env)->CallStaticIntMethod(env, display, mHeight);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                    bt_log("display: state query threw (window likely not created)");
                } else {
                    bt_log("display: created=%d size=%dx%d", (int)created, (int)w, (int)h);
                }
            }
            (*env)->DeleteLocalRef(env, display);
        }
    }

    /* Active probe: call Hook.onFrame(5), the index reserved for "not a real frame site".
       An injected invokestatic can vanish silently in two ways - the client never reaches that
       method, or the class loader refuses to resolve our class from inside a game frame. Calling
       the very same entry point out-of-band separates them: a "site 6" line in hook.log means the
       Java chain works and the frame path is the problem; no line means the class never resolves. */
    {
        jclass hookClass = (*env)->FindClass(env, "com/bridger/compat/boot/Hook");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            hookClass = NULL;
        }
        if (hookClass == NULL) {
            bt_log("probe: Hook class NOT resolvable from a native frame");
        } else {
            jmethodID probe = (*env)->GetStaticMethodID(env, hookClass, "onFrame", "(I)V");
            if (probe == NULL) {
                (*env)->ExceptionClear(env);
                bt_log("probe: Hook.onFrame(I)V signature missing");
            } else {
                (*env)->CallStaticVoidMethod(env, hookClass, probe, (jint)5);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionDescribe(env);
                    (*env)->ExceptionClear(env);
                    bt_log("probe: Hook.onFrame(5) threw");
                } else {
                    bt_log("probe: Hook.onFrame(5) invoked");
                }
            }
            (*env)->DeleteLocalRef(env, hookClass);
        }
    }

    bt_log("payload init complete");
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_self = instance;
        DisableThreadLibraryCalls(instance);
        init_paths();
        /* Never touch JNI from DllMain: the loader lock is held and AddToBootstrapClassLoaderSearch
           triggers class loading. Hand off to a fresh thread and return immediately. */
        if (CreateThread(NULL, 0, worker, NULL, 0, NULL) == NULL) {
            bt_log("FATAL: CreateThread failed: %lu", (unsigned long)GetLastError());
            return FALSE;
        }
    }
    return TRUE;
}

__declspec(dllexport) int BtPayloadPing(void) {
    return 0x4254;
}
