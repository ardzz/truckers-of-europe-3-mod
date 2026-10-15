// World: forced weather, time-of-day freeze / clock hour, AI traffic count.
//
// Weather (SkyValues, one instance per game scene)
//   currentWeather 0x310 / nextWeather 0x314 (Condition: Clear 0, Rainy 1,
//   Cloudy 2, Foggy 3, AfterRain 4), nextWeatherTime 0x318 (1 tick = 1 s),
//   weatherIntensity 0x31C (ramp target), weatherChangeSpeed 0x320,
//   weatherChancing 0x30C, rain/mist/cloud intensity 0x178/0x17C/0x180.
//   <UpdateValues>d__151::MoveNext (0x1b7cae8) calls UpdateSkyValues each tick,
//   decrements nextWeatherTime and sets weatherChancing once it reaches 0.
//   UpdateSkyValues (0x1b74714) then ramps the intensity of nextWeather
//   (switch at 0x1b748fc) and, once reached, sets currentWeather and calls
//   SetNextWeather (0x1b7756c). Vanilla SetNextWeather alternates
//   Clear -> random -> Clear and rolls per-type timer/intensity/speed.
//   Rain -> Clear goes through nextWeather = AfterRain (drying) first.
//   Hook SetNextWeather (after orig) to re-plan toward the chosen weather; a
//   different non-clear weather first fades out through Clear so no stale
//   rain/mist/cloud intensity is left behind. Hook UpdateSkyValues (before
//   orig, main thread) to apply menu changes while no transition is running,
//   and to re-trigger the ramp when something zeroed the intensity (Sleep).
//
// Time of day (TOD_Time component)
//   FixedUpdate (0x1a05d40): if (ProgressTime && DayLengthInMinutes > 0)
//   AddHours(1440 / DayLength * fixedDeltaTime / 3600) — the only clock
//   advance (AddHours has no other callers); everything else (UI clock, job
//   deadlines, fatigue, saves via Prefs.Time) reads sky.Cycle.Ticks.
//   Freeze = skip orig. Hour = write sky(0x70).Cycle(0x40).Hour(0x10).
//
// Traffic (TrafficSpawner)
//   SpawnCars (0x19a2718, called from Update and from SpawnAll's foreach over
//   spawnedCars) counts active cars in spawnedCars (0x50) and spawns one more
//   while count < allowedTrafficCount (0x84, ctor 100) and the pool has an
//   inactive car. Pool size is cars.Length + Prefs.TrafficIntensity
//   (Start -> SetIntensity 0x19a15fc). SpawnCars hook: temporarily set
//   allowedTrafficCount. Update hook (0x19a2690, never inside SpawnAll's list
//   enumeration, so growing the list is safe there): grow the pool with
//   SetIntensity and despawn excess active cars with TrafficCar::ResetCar
//   (0x198a6dc, what GarageManager uses). Prefs are never written.
#include "mod.h"

#include <atomic>
#include <ctime>

// ---------------------------------------------------------------- features
static void weather_changed(Feature*);
static void time_hour_changed(Feature*);

FEATURE(f_weather, "weather", "World", "Force weather", F_CHOICE, true, 1, 0, 3, "Clear|Rainy|Cloudy|Foggy",
        weather_changed);
FEATURE(f_time_freeze, "time_freeze", "World", "Freeze time of day", F_TOGGLE, false, 0, 0, 0, nullptr, nullptr);
FEATURE(f_time_hour, "time_hour", "World", "Set clock hour", F_TOGGLE_FLOAT, false, 12, 0, 24, nullptr,
        time_hour_changed);
FEATURE(f_traffic, "traffic", "World", "AI traffic count", F_TOGGLE_INT, false, 30, 0, 200, nullptr, nullptr);

// ---------------------------------------------------------------- weather
enum { W_CLEAR = 0, W_RAINY = 1, W_CLOUDY = 2, W_FOGGY = 3 };

static constexpr uintptr_t kSetNextWeather = 0x1b7756c;
static constexpr uintptr_t kUpdateSkyValues = 0x1b74714;

static constexpr int kChangeTicks = 3;    // seconds until a needed transition starts
static constexpr int kSteadyTicks = 180;  // re-check period while already at the target

static std::atomic<int> g_weather_gen{1};  // bumped on every menu change
static int g_weather_applied = 0;           // main thread only

static uint32_t g_rng = 0x9e3779b9u;
static uint32_t rng_next() {
    g_rng ^= g_rng << 13;
    g_rng ^= g_rng >> 17;
    g_rng ^= g_rng << 5;
    return g_rng;
}
static float rng_range(float lo, float hi) { return lo + (hi - lo) * (float)(rng_next() >> 8) * (1.0f / 16777216.0f); }
static int rng_range(int lo, int hi_excl) { return lo + (int)(rng_next() % (uint32_t)(hi_excl - lo)); }

static int weather_choice() {
    int c = (int)(f_weather.value + 0.5f);
    return c < W_CLEAR ? W_CLEAR : c > W_FOGGY ? W_FOGGY : c;
}

static void weather_changed(Feature*) { g_weather_gen.fetch_add(1); }

// Point nextWeather/timer/intensity of SkyValues `sv` at the chosen weather.
static void weather_plan(void* sv) {
    int c = weather_choice();
    int cur = FIELD(int, sv, 0x310);
    int target = (c == W_CLEAR || cur == W_CLEAR || cur == c) ? c : W_CLEAR;
    FIELD(int, sv, 0x314) = target;
    if (target == cur) {  // already there: keep intensity, re-check later
        FIELD(int, sv, 0x318) = kSteadyTicks;
        return;
    }
    FIELD(int, sv, 0x318) = kChangeTicks;
    // Same ranges as the vanilla per-type tail of SetNextWeather.
    switch (target) {
        case W_RAINY:
            FIELD(float, sv, 0x31C) = rng_range(0.6f, 1.0f);
            FIELD(float, sv, 0x320) = (float)rng_range(10, 15);
            break;
        case W_CLOUDY:
            FIELD(float, sv, 0x320) = (float)rng_range(4, 10);
            FIELD(float, sv, 0x31C) = rng_range(0.4f, 1.0f);
            break;
        case W_FOGGY:
            FIELD(float, sv, 0x320) = (float)rng_range(5, 10);
            FIELD(float, sv, 0x31C) = rng_range(0.2f, 1.0f);
            break;
        default:  // clear: fade the current weather out at its own intensity scale
            FIELD(float, sv, 0x320) = (float)rng_range(5, 10);
            break;
    }
}

static void (*o_SetNextWeather)(void*, const MethodInfo*);
static void h_SetNextWeather(void* sv, const MethodInfo* mi) {
    o_SetNextWeather(sv, mi);  // keeps vanilla bookkeeping (weatherChancing = false, ...)
    if (!sv || !f_weather.on) return;
    weather_plan(sv);
    g_weather_applied = g_weather_gen.load();
}

static void (*o_UpdateSkyValues)(void*, const MethodInfo*);
static void h_UpdateSkyValues(void* sv, const MethodInfo* mi) {
    if (sv && !FIELD(bool, sv, 0x30C)) {  // no transition in progress
        int gen = g_weather_gen.load();
        if (gen != g_weather_applied) {
            g_weather_applied = gen;
            if (f_weather.on) weather_plan(sv);  // off: vanilla resumes at the next SetNextWeather
        } else if (f_weather.on) {
            // At the target but its intensity was zeroed (Sleep::Update sets rainIntensity = 0):
            // start the ramp back up soon instead of after the steady period.
            int c = weather_choice();
            if (c != W_CLEAR && FIELD(int, sv, 0x310) == c && FIELD(int, sv, 0x314) == c) {
                float cur = c == W_RAINY ? FIELD(float, sv, 0x178) : c == W_FOGGY ? FIELD(float, sv, 0x17C)
                                                                                 : FIELD(float, sv, 0x180);
                if (cur + 0.05f < 0.5f * FIELD(float, sv, 0x31C) && FIELD(int, sv, 0x318) > kChangeTicks)
                    FIELD(int, sv, 0x318) = kChangeTicks;
            }
        }
    }
    o_UpdateSkyValues(sv, mi);
}

// ---------------------------------------------------------------- time of day
static constexpr uintptr_t kTodFixedUpdate = 0x1a05d40;
static constexpr int kHourHoldCalls = 100;  // ~2 s of FixedUpdate: survives a late save-time load

static std::atomic<int> g_hour_gen{1};
static int g_hour_applied = 0;          // main thread only
static void* g_hour_tod = nullptr;      // TOD_Time instance the one-shot jump was applied to
static int g_hour_hold = 0;

static void time_hour_changed(Feature*) { g_hour_gen.fetch_add(1); }

static void (*o_TodFixedUpdate)(void*, const MethodInfo*);
static void h_TodFixedUpdate(void* tod, const MethodInfo* mi) {
    bool freeze = f_time_freeze.on;
    if (!freeze) o_TodFixedUpdate(tod, mi);
    if (!tod || !f_time_hour.on) return;

    // Frozen: pin the hour. Running: jump once per change / per new TOD_Time.
    int gen = g_hour_gen.load();
    if (gen != g_hour_applied || tod != g_hour_tod) {
        g_hour_applied = gen;
        g_hour_tod = tod;
        g_hour_hold = kHourHoldCalls;
    }
    if (!freeze) {
        if (g_hour_hold <= 0) return;
        --g_hour_hold;
    }
    void* sky = FIELD(void*, tod, 0x70);
    void* cycle = sky ? FIELD(void*, sky, 0x40) : nullptr;
    if (!cycle) return;
    float h = f_time_hour.value;
    FIELD(float, cycle, 0x10) = h < 0.f ? 0.f : h > 23.99f ? 23.99f : h;
}

// ---------------------------------------------------------------- traffic
static constexpr uintptr_t kTrafficUpdate = 0x19a2690;
static constexpr uintptr_t kSpawnCars = 0x19a2718;
static constexpr uintptr_t kSetIntensity = 0x19a15fc;
static constexpr uintptr_t kResetCar = 0x198a6dc;
static constexpr uintptr_t kGetGameObject = 0x3561ed8;  // Component.get_gameObject
static constexpr uintptr_t kGetActiveSelf = 0x35651fc;  // GameObject.get_activeSelf
static constexpr int kPoolGrowStep = 8;                 // cars instantiated per maintenance pass
static constexpr int kMaintainEvery = 30;               // Update calls between maintenance passes

struct SpawnerState {
    void* spawner = nullptr;
    int vanilla_pool = 0;  // largest pool seen before we grew it
    bool grown = false;
    int tick = 0;
};
static SpawnerState g_ts;  // main thread only

// Active-car cap to enforce, or -1 for untouched vanilla behaviour.
static int traffic_cap(void* ts, void* list) {
    if (ts != g_ts.spawner) g_ts = SpawnerState{ts, 0, false, 0};
    int pool = FIELD(int, list, 0x18);
    if (!g_ts.grown && pool > g_ts.vanilla_pool) g_ts.vanilla_pool = pool;
    if (f_traffic.on) {
        int cap = (int)(f_traffic.value + 0.5f);
        return cap < 0 ? 0 : cap > 200 ? 200 : cap;
    }
    if (!g_ts.grown) return -1;
    // Vanilla limit was min(allowedTrafficCount, original pool); emulate it on the grown pool.
    int allowed = FIELD(int, ts, 0x84);
    return allowed < g_ts.vanilla_pool ? allowed : g_ts.vanilla_pool;
}

static bool car_active(void* car) {
    void* go = ((void* (*)(void*, const MethodInfo*))VA(kGetGameObject))(car, nullptr);
    return go && ((bool (*)(void*, const MethodInfo*))VA(kGetActiveSelf))(go, nullptr);
}

// Despawn active cars above `cap`, cars away from the player first.
static void traffic_trim(void* list, int cap) {
    void* items = FIELD(void*, list, 0x10);
    int n = FIELD(int, list, 0x18);
    if (!items || n > ARR_LEN(items)) return;
    int active = 0;
    for (int i = 0; i < n; ++i) {
        void* car = ARR_AT(void*, items, i);
        if (car && car_active(car)) ++active;
    }
    int excess = active - cap;
    for (int pass = 0; pass < 2 && excess > 0; ++pass) {
        for (int i = n - 1; i >= 0 && excess > 0; --i) {
            void* car = ARR_AT(void*, items, i);
            if (!car || (pass == 0 && FIELD(bool, car, 0x291 /* closeToPlayer */)) || !car_active(car)) continue;
            ((void (*)(void*, const MethodInfo*))VA(kResetCar))(car, nullptr);
            --excess;
        }
    }
}

static void (*o_TrafficUpdate)(void*, const MethodInfo*);
static void h_TrafficUpdate(void* ts, const MethodInfo* mi) {
    void* list = ts ? FIELD(void*, ts, 0x50) : nullptr;
    if (list && !FIELD(bool, ts, 0x64 /* hasErrors */)) {
        int cap = traffic_cap(ts, list);
        if (cap >= 0 && ++g_ts.tick >= kMaintainEvery) {
            g_ts.tick = 0;
            int pool = FIELD(int, list, 0x18);
            void* cars = FIELD(void*, ts, 0x28);
            if (f_traffic.on && pool < cap && cars) {
                int target = pool + kPoolGrowStep < cap ? pool + kPoolGrowStep : cap;
                int intensity = target - ARR_LEN(cars);
                if (intensity > 0) {
                    ((void (*)(void*, int, const MethodInfo*))VA(kSetIntensity))(ts, intensity, nullptr);
                    g_ts.grown = true;
                }
            }
            traffic_trim(list, cap);
        }
    }
    o_TrafficUpdate(ts, mi);
}

static void (*o_SpawnCars)(void*, const MethodInfo*);
static void h_SpawnCars(void* ts, const MethodInfo* mi) {
    void* list = ts ? FIELD(void*, ts, 0x50) : nullptr;
    int cap = list ? traffic_cap(ts, list) : -1;
    if (cap < 0) return o_SpawnCars(ts, mi);
    int allowed = FIELD(int, ts, 0x84);
    FIELD(int, ts, 0x84) = cap;
    o_SpawnCars(ts, mi);
    FIELD(int, ts, 0x84) = allowed;
}

// ---------------------------------------------------------------- install
INSTALL(install_world) {
    g_rng ^= (uint32_t)time(nullptr) ^ (uint32_t)(uintptr_t)&g_ts;
    if (!g_rng) g_rng = 0x9e3779b9u;
    HOOK(kSetNextWeather, h_SetNextWeather, o_SetNextWeather);
    HOOK(kUpdateSkyValues, h_UpdateSkyValues, o_UpdateSkyValues);
    HOOK(kTodFixedUpdate, h_TodFixedUpdate, o_TodFixedUpdate);
    HOOK(kTrafficUpdate, h_TrafficUpdate, o_TrafficUpdate);
    HOOK(kSpawnCars, h_SpawnCars, o_SpawnCars);
}
