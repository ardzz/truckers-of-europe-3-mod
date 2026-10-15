// Diagnostics caches behind Native.debugSnapshot, plus the read-only TruckController sampler.
#include "debug.h"
#include "mod.h"

#include <atomic>
#include <cmath>
#include <cstdint>
#include <ctime>

namespace {

int64_t now_ns() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

// Single-writer seqlock. Payload words are atomics (release stores / acquire loads), so a reader that
// observed any value of an unfinished write also observes its odd sequence number and retries.
template <int N>
class Cache {
public:
    void write(const double (&v)[N], int64_t stamp) {
        const uint32_t s = seq_.load(std::memory_order_relaxed);
        seq_.store(s + 1, std::memory_order_relaxed);
        for (int i = 0; i < N; ++i) v_[i].store(v[i], std::memory_order_release);
        stamp_.store(stamp, std::memory_order_release);
        seq_.store(s + 2, std::memory_order_release);
    }

    // False if never written or every attempt raced a write.
    bool read(double (&v)[N], int64_t& stamp) const {
        for (int attempt = 0; attempt < 64; ++attempt) {
            const uint32_t s = seq_.load(std::memory_order_acquire);
            if (s == 0) return false;
            if (s & 1u) continue;
            for (int i = 0; i < N; ++i) v[i] = v_[i].load(std::memory_order_acquire);
            stamp = stamp_.load(std::memory_order_acquire);
            if (seq_.load(std::memory_order_relaxed) == s) return true;
        }
        return false;
    }

private:
    std::atomic<uint32_t> seq_{0};
    std::atomic<int64_t> stamp_{0};
    std::atomic<double> v_[N]{};
};
static_assert(std::atomic<double>::is_always_lock_free && std::atomic<int64_t>::is_always_lock_free,
              "lock-free diagnostics cache");

constexpr int kWeatherSlots[] = {
    DBG_WEATHER_ENABLED, DBG_WEATHER_REQUESTED, DBG_WEATHER_CURRENT, DBG_WEATHER_NEXT,
    DBG_WEATHER_TIMER,   DBG_WEATHER_CHANGING,  DBG_RAIN,            DBG_CLOUD,
    DBG_MIST,            DBG_WEATHER_TARGET,    DBG_NO_RAIN_AREA,    DBG_STOP_RAIN_AREA,
    DBG_HOUR,            DBG_REQUEST_GENERATION, DBG_APPLIED_GENERATION, DBG_TRANSITIONS,
};
constexpr int kTruckSlots[] = {
    DBG_SPEED_KPH, DBG_FUEL_LITERS, DBG_FUEL_FRACTION, DBG_HEALTH,
    DBG_DIRTINESS, DBG_DUSTINESS,   DBG_TRUCK_RAINING,
};
constexpr int kWeatherN = sizeof kWeatherSlots / sizeof kWeatherSlots[0];
constexpr int kTruckN = sizeof kTruckSlots / sizeof kTruckSlots[0];

Cache<kWeatherN> g_weather;
Cache<kTruckN> g_truck;

template <int N>
void fill(const Cache<N>& cache, const int (&slots)[N], int age_slot, int64_t now, double* out) {
    double v[N];
    int64_t stamp = 0;
    if (!cache.read(v, stamp)) return;
    out[age_slot] = now > stamp ? (double)(now - stamp) / 1e6 : 0.0;
    for (int i = 0; i < N; ++i) out[slots[i]] = v[i];
}

}  // namespace

void debug_weather_sample(const WeatherDebugSample& d) {
    const double v[kWeatherN] = {
        d.enabled ? 1.0 : 0.0, (double)d.requested, (double)d.current, (double)d.next,
        (double)d.timer,       d.transitioning ? 1.0 : 0.0, d.rain, d.cloud,
        d.mist,                d.target, d.noRainArea ? 1.0 : 0.0, d.stopRainArea ? 1.0 : 0.0,
        d.hour,                (double)d.requestGeneration, (double)d.appliedGeneration, (double)d.appliedCount,
    };
    g_weather.write(v, now_ns());
}

void debug_truck_sample(const TruckDebugSample& d) {
    const double v[kTruckN] = {
        d.speedKph, d.fuelLiters, d.fuelFraction, d.health, d.dirtiness, d.dustiness, d.raining ? 1.0 : 0.0,
    };
    g_truck.write(v, now_ns());
}

void debug_snapshot(double out[DEBUG_VALUES]) {
    for (int i = 0; i < DEBUG_VALUES; ++i) out[i] = NAN;
    const int64_t now = now_ns();
    fill(g_weather, kWeatherSlots, DBG_WEATHER_AGE_MS, now, out);
    fill(g_truck, kTruckSlots, DBG_TRUCK_AGE_MS, now, out);
}

// ---------------------------------------------------------------- truck sampler
// TruckController::Update (0x19b2dbc): entry claimed by nobody; the first 5 instructions (the 20 bytes
// the inline hook may overwrite) are prologue stp's, and the fuel/dirt/speed-limit byte patches sit
// at 0x19b3578.. inside the body. Fields: fuel 0x258 (= fuelLeft / fuelTankCapacity, stored by
// Update), health 0x260, fuelLeft 0x264, dirtiness 0x268, dustiness 0x270, speedInKph 0x2D8,
// isRaining 0x47C (SkyValues.SetRainItems).
static constexpr uintptr_t kTruckUpdate = 0x19b2dbc;
static constexpr int64_t kTruckSampleNs = 100000000;      // 10 Hz is plenty for a 2 Hz viewer
static constexpr int64_t kPlayerFreshNs = 5000000000LL;  // sky's controller identity still trusted

static const void* g_player = nullptr;  // main thread only; compared, never dereferenced
static int64_t g_player_seen = 0;
static int64_t g_truck_sampled = 0;

void debug_player_truck(const void* controller) {
    g_player = controller;
    g_player_seen = now_ns();
}

static void (*o_TruckUpdate)(void*, const MethodInfo*);
static void h_TruckUpdate(void* tc, const MethodInfo* mi) {
    o_TruckUpdate(tc, mi);
    if (!tc) return;
    const int64_t now = now_ns();
    if (g_player && now - g_player_seen < kPlayerFreshNs && tc != g_player) return;  // not the player truck
    if (now - g_truck_sampled < kTruckSampleNs) return;
    g_truck_sampled = now;
    debug_truck_sample({FIELD(float, tc, 0x2D8), FIELD(float, tc, 0x264), FIELD(float, tc, 0x258),
                        FIELD(float, tc, 0x260), FIELD(float, tc, 0x268), FIELD(float, tc, 0x270),
                        FIELD(bool, tc, 0x47C)});
}

INSTALL(install_debug) { HOOK(kTruckUpdate, h_TruckUpdate, o_TruckUpdate); }
