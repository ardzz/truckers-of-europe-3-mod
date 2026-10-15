// Truckers of Europe 3 runtime mod — shared API for all feature modules.
//
// Address model: every address in this codebase is a *vaddr* as printed by
// `work/re.py` / rizin (= Cpp2IL RVA + 0x4000). VA(vaddr) turns it into a live
// pointer inside the loaded libil2cpp.so.
//
// IL2CPP calling convention (arm64):
//   instance method : ret fn(Self* self, args..., const MethodInfo* mi)
//   static method   : ret fn(args..., const MethodInfo* mi)
// Pass nullptr for MethodInfo when calling non-generic methods yourself.
#pragma once
#include <cstdint>
#include <cstddef>

struct MethodInfo;

// ---------------------------------------------------------------- features
enum FeatureType : int {
    F_TOGGLE = 0,        // on/off only
    F_TOGGLE_FLOAT = 1,  // on/off + float value in [min,max]
    F_TOGGLE_INT = 2,    // on/off + integer value in [min,max] (stored in .value)
    F_CHOICE = 3,        // on/off + index into `choices` ("A|B|C"), stored in .value
};

struct Feature {
    const char* id;        // stable snake_case key (persistence + JNI)
    const char* category;  // menu section title
    const char* label;     // menu label
    int type;              // FeatureType
    bool def_on;           // default enabled state
    float def_value;       // default value (ignored for F_TOGGLE)
    float min, max;        // value range (ignored for F_TOGGLE / F_CHOICE)
    const char* choices;   // F_CHOICE only, '|' separated; else nullptr
    void (*on_change)(Feature*);  // optional; called after on/value changes (any thread)

    // runtime state — read these from hooks
    volatile bool on;
    volatile float value;
};

void feature_add(Feature* f);
void install_add(void (*fn)());

// Declare a feature:  FEATURE(f_inf_fuel, "inf_fuel", "Fuel", "Infinite fuel", F_TOGGLE, false, 0, 0, 0, nullptr, nullptr);
#define FEATURE(var, ...)                                              \
    static Feature var{__VA_ARGS__, false, 0.0f};                        \
    static const int var##_registered = (feature_add(&var), 0)

// Declare an installer run once libil2cpp.so is mapped:  INSTALL(install_fuel) { hook(...); }
#define INSTALL(fn)                                                     \
    static void fn();                                                    \
    static const int fn##_registered = (install_add(fn), 0);            \
    static void fn()

// ---------------------------------------------------------------- memory
uintptr_t il2cpp_base();                   // load base of libil2cpp.so
inline void* VA(uintptr_t vaddr) { return (void*)(il2cpp_base() + vaddr); }

// Inline hook at vaddr. *orig receives a callable trampoline to the original.
bool hook(uintptr_t vaddr, void* replacement, void** orig);
#define HOOK(vaddr, repl, orig) hook((vaddr), (void*)(repl), (void**)&(orig))

// Toggleable code patch. orig_hex is verified against live memory before writing.
struct BytePatch {
    uintptr_t vaddr;
    const char* orig_hex;   // e.g. "e8090034"
    const char* patch_hex;  // same length
};
bool patch_apply(const BytePatch& p, bool enable);  // enable=false restores orig_hex

// Field access on IL2CPP objects:  FIELD(float, truck, 0x258) = 100.f;
#define FIELD(T, obj, off) (*(T*)((uintptr_t)(obj) + (off)))

// Il2CppArray layout: max_length (int32 at 0x18 as il2cpp_array_size_t), elements from 0x20
#define ARR_LEN(arr) ((int)*(uintptr_t*)((uintptr_t)(arr) + 0x18))
#define ARR_AT(T, arr, i) (((T*)((uintptr_t)(arr) + 0x20))[i])

void mlog(const char* fmt, ...);  // logcat tag "toemod"
