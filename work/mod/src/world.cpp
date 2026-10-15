// World: forced weather, time-of-day freeze / clock hour, AI traffic count.
//
// Weather (SkyValues, one instance per game scene; state machine and steering in weather_policy.h)
//   weatherChancing 0x30C, currentWeather 0x310 / nextWeather 0x314 (Clear 0, Rainy 1, Cloudy 2,
//   Foggy 3, AfterRain 4), nextWeatherTime 0x318, weatherIntensity 0x31C (ramp target),
//   weatherChangeSpeed 0x320, rain/mist/cloud intensity 0x178/0x17C/0x180, _stopRainArea 0x248,
//   noRainArea 0x33C, hour 0x28 (TOD_Sky.Cycle.Hour copied at 0x1b74e0c), controller 0x168.
//   <UpdateValues>d__151::MoveNext (0x1b7cae8) calls UpdateSkyValues (0x1b74714), whose switch at
//   0x1b748fc runs only while weatherChancing; every finished ramp calls SetNextWeather (0x1b7756c).
//   Root cause of "takes several switches": the old hooks applied a menu change only while
//   weatherChancing was false (UpdateSkyValues) or at the next SetNextWeather, i.e. after the
//   running transition completed. Ramps take tens of seconds and Rainy -> Clear adds AfterRain
//   drying (nextWeatherTime 60, idle until <= 30, then drying), so a choice made during any
//   transition - including the one toward the previous choice - first watched the scene finish
//   going the wrong way and only then reversed, 1-3 minutes without a visible reaction. In
//   addition h_SetNextWeather re-read the choice before storing the generation, so a change landing
//   between the two reads was marked applied without being planned.
//   Fix: weather::steer() runs on the Unity main thread before every original UpdateSkyValues and
//   after every SetNextWeather and redirects the running machine to the latest choice (idle: start
//   within 1 s; changing: fade a partly ramped weather through Clear, ramp a fading/drying weather
//   back to its own target, otherwise let Clear fades and AfterRain drying finish; held weather:
//   180 s re-check, Sleep re-ramp). Intensities and wetness are never written. Off: steer() is not
//   called and vanilla SetNextWeather takes over at the next re-check.
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
#include "debug.h"
#include "weather_policy.h"

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
static constexpr uintptr_t kSetNextWeather = 0x1b7756c;
static constexpr uintptr_t kUpdateSkyValues = 0x1b74714;

enum : uintptr_t {
    kSkyHour = 0x28,
    kSkyController = 0x168,
    kSkyRain = 0x178,
    kSkyMist = 0x17C,
    kSkyCloud = 0x180,
    kSkyStopRainArea = 0x248,
    kSkyChancing = 0x30C,
    kSkyCurrent = 0x310,
    kSkyNext = 0x314,
    kSkyTimer = 0x318,
    kSkyIntensity = 0x31C,
    kSkySpeed = 0x320,
    kSkyNoRainArea = 0x33C,
};

// Bumped by every menu change on the UI thread, after the feature's on/value stores.
static std::atomic<unsigned> g_weather_request{0};

struct WeatherControl {  // Unity main thread only
    void* sky = nullptr;       // SkyValues instance `memory` belongs to (compared, never dereferenced)
    weather::Memory memory{};
    unsigned applied = 0;      // request generation the controller last acted on
    unsigned transitions = 0;  // transitions it started or redirected
};
static WeatherControl g_wx;

static uint32_t g_rng = 0x9e3779b9u;
static uint32_t rng_next() {
    g_rng ^= g_rng << 13;
    g_rng ^= g_rng >> 17;
    g_rng ^= g_rng << 5;
    return g_rng;
}
static float rng_range(float lo, float hi) { return lo + (hi - lo) * (float)(rng_next() >> 8) * (1.0f / 16777216.0f); }
static int rng_range(int lo, int hi_excl) { return lo + (int)(rng_next() % (uint32_t)(hi_excl - lo)); }

// Same ranges as the vanilla per-type tail of SetNextWeather.
static float roll_intensity(int w) {
    return w == weather::kRainy ? rng_range(0.6f, 1.0f) : w == weather::kCloudy ? rng_range(0.4f, 1.0f)
                                                                                 : rng_range(0.2f, 1.0f);
}
static float roll_speed(int w) {
    return (float)(w == weather::kRainy ? rng_range(10, 15) : w == weather::kCloudy ? rng_range(4, 10)
                                                                                     : rng_range(5, 10));
}
static constexpr weather::Roll kWeatherRoll{roll_intensity, roll_speed};

static int weather_choice() {
    int c = (int)(f_weather.value + 0.5f);
    return c < weather::kClear ? weather::kClear : c > weather::kFoggy ? weather::kFoggy : c;
}

static void weather_changed(Feature*) { g_weather_request.fetch_add(1); }

// Steer SkyValues `sv` toward the latest menu choice (Unity main thread).
static void weather_control(void* sv) {
    if (sv != g_wx.sky) {  // new scene instance
        g_wx.sky = sv;
        g_wx.memory = {};
    }
    // Load the generation first: the on/value read below are at least that request's.
    unsigned gen = g_weather_request.load(std::memory_order_acquire);
    if (f_weather.on) {
        weather::Sky s;
        s.changing = FIELD(bool, sv, kSkyChancing);
        s.stopRain = FIELD(bool, sv, kSkyStopRainArea);
        s.current = FIELD(int, sv, kSkyCurrent);
        s.next = FIELD(int, sv, kSkyNext);
        s.timer = FIELD(int, sv, kSkyTimer);
        s.target = FIELD(float, sv, kSkyIntensity);
        s.speed = FIELD(float, sv, kSkySpeed);
        s.rain = FIELD(float, sv, kSkyRain);
        s.cloud = FIELD(float, sv, kSkyCloud);
        s.mist = FIELD(float, sv, kSkyMist);
        weather::Step st = weather::steer(s, g_wx.memory, weather_choice(), kWeatherRoll);
        if (st != weather::kNone) {
            FIELD(int, sv, kSkyCurrent) = s.current;
            FIELD(int, sv, kSkyNext) = s.next;
            FIELD(int, sv, kSkyTimer) = s.timer;
            FIELD(float, sv, kSkyIntensity) = s.target;
            FIELD(float, sv, kSkySpeed) = s.speed;
            if (weather::counts(st)) ++g_wx.transitions;
        }
    }
    g_wx.applied = gen;
}

// Copy the actual SkyValues state for the Debug/World screens (after the original update).
static void weather_sample(void* sv) {
    WeatherDebugSample d;
    d.requestGeneration = (int)g_weather_request.load(std::memory_order_acquire);
    d.enabled = f_weather.on;
    d.requested = weather_choice();
    d.current = FIELD(int, sv, kSkyCurrent);
    d.next = FIELD(int, sv, kSkyNext);
    d.timer = FIELD(int, sv, kSkyTimer);
    d.transitioning = FIELD(bool, sv, kSkyChancing);
    d.rain = FIELD(float, sv, kSkyRain);
    d.cloud = FIELD(float, sv, kSkyCloud);
    d.mist = FIELD(float, sv, kSkyMist);
    d.target = FIELD(float, sv, kSkyIntensity);
    d.noRainArea = FIELD(bool, sv, kSkyNoRainArea);
    d.stopRainArea = FIELD(bool, sv, kSkyStopRainArea);
    d.hour = FIELD(float, sv, kSkyHour);
    d.appliedGeneration = (int)g_wx.applied;
    d.appliedCount = g_wx.transitions;
    debug_weather_sample(d);
    debug_player_truck(FIELD(void*, sv, kSkyController));
}

static void (*o_SetNextWeather)(void*, const MethodInfo*);
static void h_SetNextWeather(void* sv, const MethodInfo* mi) {
    o_SetNextWeather(sv, mi);  // vanilla pick, weatherChancing = false
    if (sv) weather_control(sv);
}

static void (*o_UpdateSkyValues)(void*, const MethodInfo*);
static void h_UpdateSkyValues(void* sv, const MethodInfo* mi) {
    if (sv) weather_control(sv);
    o_UpdateSkyValues(sv, mi);
    if (sv) weather_sample(sv);
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
