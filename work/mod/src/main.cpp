// Native core: libil2cpp discovery, inline hooks, toggleable byte patches,
// feature registry and the JNI bridge used by com.toemod.Menu.
#include "mod.h"
#include "debug.h"
#include "../third_party/And64InlineHook.hpp"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

// ---------------------------------------------------------------- logging
void mlog(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, "toemod", fmt, ap);
    va_end(ap);
}

// ---------------------------------------------------------------- registry
static std::vector<Feature*>& features() {
    static std::vector<Feature*> v;
    return v;
}
static std::vector<void (*)()>& installers() {
    static std::vector<void (*)()> v;
    return v;
}

void feature_add(Feature* f) {
    f->on = f->def_on;
    f->value = f->def_value;
    features().push_back(f);
}
void install_add(void (*fn)()) { installers().push_back(fn); }

static std::atomic<bool> g_ready{false};
static std::atomic<unsigned> g_hooks_installed{0};
static std::atomic<unsigned> g_hooks_failed{0};
static std::atomic<unsigned> g_patches_failed{0};

// ---------------------------------------------------------------- memory
static uintptr_t g_base = 0;

static uintptr_t find_il2cpp_base() {
    FILE* fp = fopen("/proc/self/maps", "re");
    if (!fp) return 0;
    char line[512];
    uintptr_t base = 0;
    while (fgets(line, sizeof line, fp)) {
        if (!strstr(line, "/libil2cpp.so")) continue;
        unsigned long start = 0, off = 0;
        if (sscanf(line, "%lx-%*lx %*s %lx", &start, &off) == 2 && off == 0) {
            base = start;
            break;
        }
    }
    fclose(fp);
    return base;
}

uintptr_t il2cpp_base() { return g_base; }

static bool make_writable(uintptr_t addr, size_t len) {
    long pg = sysconf(_SC_PAGESIZE);
    uintptr_t start = addr & ~(uintptr_t)(pg - 1);
    uintptr_t end = (addr + len + pg - 1) & ~(uintptr_t)(pg - 1);
    return mprotect((void*)start, end - start, PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

bool hook(uintptr_t vaddr, void* replacement, void** orig) {
    void* target = VA(vaddr);
    void* tramp = nullptr;
    A64HookFunction(target, replacement, &tramp);
    if (orig) *orig = tramp;
    bool ok = tramp != nullptr;
    (ok ? g_hooks_installed : g_hooks_failed).fetch_add(1, std::memory_order_relaxed);
    mlog("hook 0x%lx -> %s", (unsigned long)vaddr, ok ? "ok" : "FAILED");
    return ok;
}

static std::vector<uint8_t> unhex(const char* s) {
    std::vector<uint8_t> out;
    for (size_t i = 0; s[i] && s[i + 1]; i += 2) {
        char b[3] = {s[i], s[i + 1], 0};
        out.push_back((uint8_t)strtoul(b, nullptr, 16));
    }
    return out;
}

static pthread_mutex_t g_patch_mx = PTHREAD_MUTEX_INITIALIZER;

bool patch_apply(const BytePatch& p, bool enable) {
    auto orig = unhex(p.orig_hex), pat = unhex(p.patch_hex);
    if (orig.size() != pat.size() || orig.empty()) {
        mlog("patch 0x%lx: bad hex", (unsigned long)p.vaddr);
        g_patches_failed.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    pthread_mutex_lock(&g_patch_mx);
    auto* dst = (uint8_t*)VA(p.vaddr);
    const auto& want = enable ? pat : orig;
    const auto& from = enable ? orig : pat;
    bool ok = false;
    if (memcmp(dst, want.data(), want.size()) == 0) {
        ok = true;  // already in requested state
    } else if (memcmp(dst, from.data(), from.size()) != 0) {
        mlog("patch 0x%lx: unexpected bytes, refusing", (unsigned long)p.vaddr);
    } else if (make_writable((uintptr_t)dst, want.size())) {
        memcpy(dst, want.data(), want.size());
        __builtin___clear_cache((char*)dst, (char*)dst + want.size());
        ok = true;
    }
    pthread_mutex_unlock(&g_patch_mx);
    if (!ok) g_patches_failed.fetch_add(1, std::memory_order_relaxed);
    mlog("patch 0x%lx %s -> %s", (unsigned long)p.vaddr, enable ? "on" : "off", ok ? "ok" : "FAILED");
    return ok;
}

// ---------------------------------------------------------------- startup
static void* install_thread(void*) {
    for (int i = 0; i < 600 && !g_base; ++i) {  // up to 60 s
        g_base = find_il2cpp_base();
        if (!g_base) usleep(100 * 1000);
    }
    if (!g_base) {
        mlog("libil2cpp.so not found; mod inactive");
        return nullptr;
    }
    mlog("libil2cpp base 0x%lx, %zu installers, %zu features", (unsigned long)g_base, installers().size(),
         features().size());
    for (auto fn : installers()) fn();
    g_ready = true;
    mlog("ready");
    return nullptr;
}

// ---------------------------------------------------------------- JNI
static void json_str(std::string& o, const char* s) {
    o += '"';
    for (; s && *s; ++s) {
        if (*s == '"' || *s == '\\') o += '\\';
        o += *s;
    }
    o += '"';
}

static jstring jni_features(JNIEnv* env, jclass) {
    std::string o = "[";
    char num[64];
    bool first = true;
    for (Feature* f : features()) {
        if (!first) o += ',';
        first = false;
        o += "{\"id\":";
        json_str(o, f->id);
        o += ",\"category\":";
        json_str(o, f->category);
        o += ",\"label\":";
        json_str(o, f->label);
        snprintf(num, sizeof num, ",\"type\":%d,\"def_on\":%s", f->type, f->def_on ? "true" : "false");
        o += num;
        snprintf(num, sizeof num, ",\"def_value\":%g,\"min\":%g,\"max\":%g", f->def_value, f->min, f->max);
        o += num;
        o += ",\"choices\":";
        json_str(o, f->choices ? f->choices : "");
        o += '}';
    }
    o += ']';
    return env->NewStringUTF(o.c_str());
}

static void jni_set(JNIEnv* env, jclass, jstring jid, jboolean on, jfloat value) {
    const char* id = env->GetStringUTFChars(jid, nullptr);
    for (Feature* f : features()) {
        if (strcmp(f->id, id) != 0) continue;
        bool changed = f->on != (bool)on || f->value != value;
        f->value = value;
        f->on = on;
        mlog("set %s on=%d value=%g", id, (int)on, value);
        if (changed && g_ready && f->on_change) f->on_change(f);
        break;
    }
    env->ReleaseStringUTFChars(jid, id);
}

static jboolean jni_ready(JNIEnv*, jclass) { return g_ready ? JNI_TRUE : JNI_FALSE; }

static void jni_debug_snapshot(JNIEnv* env, jclass, jdoubleArray values) {
    if (!values || env->GetArrayLength(values) < DEBUG_VALUES) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "A 32-value diagnostic buffer is required");
        return;
    }
    double sample[DEBUG_VALUES];
    debug_snapshot(sample);
    sample[0] = g_ready.load() ? 1.0 : 0.0;
    sample[1] = g_hooks_installed.load(std::memory_order_relaxed);
    sample[2] = g_hooks_failed.load(std::memory_order_relaxed);
    sample[3] = g_patches_failed.load(std::memory_order_relaxed);
    env->SetDoubleArrayRegion(values, 0, DEBUG_VALUES, sample);
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/toemod/Native");
    if (!cls) return JNI_ERR;
    static const JNINativeMethod methods[] = {
        {"features", "()Ljava/lang/String;", (void*)jni_features},
        {"set", "(Ljava/lang/String;ZF)V", (void*)jni_set},
        {"ready", "()Z", (void*)jni_ready},
        {"debugSnapshot", "([D)V", (void*)jni_debug_snapshot},
    };
    if (env->RegisterNatives(cls, methods, sizeof methods / sizeof methods[0]) != 0) return JNI_ERR;
    pthread_t t;
    pthread_create(&t, nullptr, install_thread, nullptr);
    pthread_detach(t);
    return JNI_VERSION_1_6;
}
