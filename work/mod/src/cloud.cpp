// Cloud save: the game's Save/Load buttons go to the mod's backups instead of Google Play Games, which
// cannot sign in to the re-signed APK. Works whether or not "Mods enabled" is on.
#include "cloud.h"
#include "mod.h"

#include <atomic>

static constexpr uintptr_t kCloudSaveSave = 0x1b44a94;      // CloudSave::Save()
static constexpr uintptr_t kCloudSaveLoad = 0x1b46708;      // CloudSave::Load()
static constexpr uintptr_t kEventSystemUpdate = 0x379f820;  // UnityEngine.EventSystems.EventSystem::Update()
static constexpr uintptr_t kPlayerPrefsSave = 0x355f1d8;    // static UnityEngine.PlayerPrefs::Save()

typedef void (*InstanceVoid)(void* self, const MethodInfo* mi);
static InstanceVoid o_save, o_load, o_event_update;

static std::atomic<bool> g_flush_requested{false};

static void save_player_prefs() { ((void (*)(const MethodInfo*))VA(kPlayerPrefsSave))(nullptr); }

void cloud_request_flush() { g_flush_requested.store(true, std::memory_order_release); }

// Save button: write PlayerPrefs, then let the mod back them up. Google Play Games runs only when Java
// cannot take the action.
static void h_save(void* self, const MethodInfo* mi) {
    save_player_prefs();
    if (!cloud_upcall(CLOUD_SAVE)) o_save(self, mi);
}

static void h_load(void* self, const MethodInfo* mi) {
    if (!cloud_upcall(CLOUD_LOAD)) o_load(self, mi);
}

// Runs every frame on the Unity thread while a scene has an EventSystem, which every game screen has.
// The plain load keeps the common no-request frame free of an atomic read-modify-write.
static void h_event_update(void* self, const MethodInfo* mi) {
    o_event_update(self, mi);
    if (g_flush_requested.load(std::memory_order_relaxed) && g_flush_requested.exchange(false, std::memory_order_acq_rel)) {
        save_player_prefs();
        cloud_upcall(CLOUD_FLUSHED);
    }
}

INSTALL(install_cloud) {
    HOOK(kCloudSaveSave, h_save, o_save);
    HOOK(kCloudSaveLoad, h_load, o_load);
    HOOK(kEventSystemUpdate, h_event_update, o_event_update);
}
