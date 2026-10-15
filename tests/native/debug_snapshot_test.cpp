// Host tests for the diagnostics caches in work/mod/src/debug.cpp (Native.debugSnapshot contract):
// unsampled -> NaN, index mapping, sample age, game NaNs passed through, and torn-read freedom while
// the Unity-thread writers run concurrently with the UI-thread reader.
#include "debug.h"
#include "mod.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>

// Mod core symbols referenced by debug.cpp's installer (never run here).
bool hook(uintptr_t, void*, void** orig) {
    if (orig) *orig = nullptr;
    return false;
}
void install_add(void (*)()) {}

static int g_failures = 0;
#define CHECK(cond)                                                                     \
    do {                                                                                \
        if (!(cond)) {                                                                  \
            std::fprintf(stderr, "%s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                               \
        }                                                                               \
    } while (0)

static WeatherDebugSample weather_sample(int i) {
    WeatherDebugSample d;
    d.enabled = i % 2 == 1;
    d.requested = i % 4;
    d.current = (i + 1) % 4;
    d.next = i % 5;
    d.timer = i;
    d.transitioning = i % 3 == 0;
    d.rain = (float)i * 0.25f;
    d.cloud = (float)i * 0.5f;
    d.mist = (float)i * 0.125f;
    d.target = (float)i;
    d.noRainArea = i % 2 == 0;
    d.stopRainArea = i % 3 == 1;
    d.hour = (float)(i % 24);
    d.requestGeneration = i;
    d.appliedGeneration = i;
    d.appliedCount = (unsigned)i;
    return d;
}

static TruckDebugSample truck_sample(int i) {
    return {(float)-i, (float)i, (float)i * 0.5f, (float)i * 0.25f, (float)i * 2.f, (float)i * 4.f, i % 2 == 1};
}

static void test_unsampled_is_nan() {
    double out[DEBUG_VALUES];
    debug_snapshot(out);
    for (int i = 0; i < DEBUG_VALUES; ++i) CHECK(std::isnan(out[i]));
}

static void test_weather_mapping_and_age() {
    debug_weather_sample(weather_sample(7));
    double out[DEBUG_VALUES];
    debug_snapshot(out);
    CHECK(out[DBG_WEATHER_AGE_MS] >= 0.0 && out[DBG_WEATHER_AGE_MS] < 1000.0);
    CHECK(out[DBG_WEATHER_ENABLED] == 1.0);
    CHECK(out[DBG_WEATHER_REQUESTED] == 3.0);
    CHECK(out[DBG_WEATHER_CURRENT] == 0.0);
    CHECK(out[DBG_WEATHER_NEXT] == 2.0);
    CHECK(out[DBG_WEATHER_TIMER] == 7.0);
    CHECK(out[DBG_WEATHER_CHANGING] == 0.0);
    CHECK(out[DBG_RAIN] == 1.75);
    CHECK(out[DBG_CLOUD] == 3.5);
    CHECK(out[DBG_MIST] == 0.875);
    CHECK(out[DBG_WEATHER_TARGET] == 7.0);
    CHECK(out[DBG_NO_RAIN_AREA] == 0.0);
    CHECK(out[DBG_STOP_RAIN_AREA] == 1.0);
    CHECK(out[DBG_HOUR] == 7.0);
    CHECK(out[DBG_REQUEST_GENERATION] == 7.0);
    CHECK(out[DBG_APPLIED_GENERATION] == 7.0);
    CHECK(out[DBG_TRANSITIONS] == 7.0);
    for (int i = DBG_TRUCK_AGE_MS; i <= DBG_TRUCK_RAINING; ++i) CHECK(std::isnan(out[i]));  // truck never sampled
    for (int i = 0; i < 4; ++i) CHECK(std::isnan(out[i]));  // main.cpp fills these
    for (int i = DBG_RESERVED_FIRST; i < DEBUG_VALUES; ++i) CHECK(std::isnan(out[i]));

    std::this_thread::sleep_for(std::chrono::milliseconds(30));
    debug_snapshot(out);
    CHECK(out[DBG_WEATHER_AGE_MS] >= 30.0);  // stale values keep their data, age keeps growing
    CHECK(out[DBG_WEATHER_TIMER] == 7.0);
    debug_weather_sample(weather_sample(8));
    debug_snapshot(out);
    CHECK(out[DBG_WEATHER_AGE_MS] < 30.0 && out[DBG_WEATHER_TIMER] == 8.0);
}

static void test_truck_mapping_and_game_nan() {
    debug_truck_sample(truck_sample(3));
    double out[DEBUG_VALUES];
    debug_snapshot(out);
    CHECK(out[DBG_TRUCK_AGE_MS] >= 0.0 && out[DBG_TRUCK_AGE_MS] < 1000.0);
    CHECK(out[DBG_SPEED_KPH] == -3.0);
    CHECK(out[DBG_FUEL_LITERS] == 3.0);
    CHECK(out[DBG_FUEL_FRACTION] == 1.5);
    CHECK(out[DBG_HEALTH] == 0.75);
    CHECK(out[DBG_DIRTINESS] == 6.0);
    CHECK(out[DBG_DUSTINESS] == 12.0);
    CHECK(out[DBG_TRUCK_RAINING] == 1.0);
    CHECK(out[DBG_WEATHER_TIMER] == 8.0);  // weather block untouched

    TruckDebugSample t = truck_sample(4);
    t.fuelLiters = NAN;  // a game NaN stays NaN; the rest of the block is still valid
    debug_truck_sample(t);
    debug_snapshot(out);
    CHECK(std::isnan(out[DBG_FUEL_LITERS]));
    CHECK(out[DBG_SPEED_KPH] == -4.0 && out[DBG_HEALTH] == 1.0 && out[DBG_TRUCK_RAINING] == 0.0);
}

// Each sample i is internally consistent, so a mixed-sample (torn) read is detectable.
static void test_concurrent_reads_are_never_torn() {
    debug_weather_sample(weather_sample(0));
    debug_truck_sample(truck_sample(0));
    std::atomic<bool> stop{false};
    std::thread weather([&] {
        for (int i = 1; !stop.load(std::memory_order_relaxed); ++i) {
            debug_weather_sample(weather_sample(i % 1000000));
            if (i % 64 == 0) std::this_thread::yield();
        }
    });
    std::thread truck([&] {
        for (int i = 1; !stop.load(std::memory_order_relaxed); ++i) {
            debug_truck_sample(truck_sample(i % 1000000));
            if (i % 64 == 0) std::this_thread::yield();
        }
    });
    int weather_reads = 0, truck_reads = 0, torn = 0;
    double out[DEBUG_VALUES];
    const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(300);
    while (std::chrono::steady_clock::now() < end) {
        debug_snapshot(out);
        if (!std::isnan(out[DBG_WEATHER_AGE_MS])) {
            ++weather_reads;
            double i = out[DBG_WEATHER_TIMER];
            if (out[DBG_REQUEST_GENERATION] != i || out[DBG_APPLIED_GENERATION] != i ||
                out[DBG_TRANSITIONS] != i || out[DBG_WEATHER_TARGET] != i || out[DBG_RAIN] != i * 0.25 ||
                out[DBG_CLOUD] != i * 0.5 || out[DBG_MIST] != i * 0.125)
                ++torn;
        } else {
            for (int k = DBG_WEATHER_ENABLED; k <= DBG_HOUR; ++k) torn += !std::isnan(out[k]);
        }
        if (!std::isnan(out[DBG_TRUCK_AGE_MS])) {
            ++truck_reads;
            double i = out[DBG_FUEL_LITERS];
            if (out[DBG_SPEED_KPH] != -i || out[DBG_FUEL_FRACTION] != i * 0.5 || out[DBG_HEALTH] != i * 0.25 ||
                out[DBG_DIRTINESS] != i * 2.0 || out[DBG_DUSTINESS] != i * 4.0)
                ++torn;
        }
    }
    stop = true;
    weather.join();
    truck.join();
    CHECK(torn == 0);
    CHECK(weather_reads > 0 && truck_reads > 0);
}

int main() {
    test_unsampled_is_nan();
    test_weather_mapping_and_age();
    test_truck_mapping_and_game_nan();
    test_concurrent_reads_are_never_torn();
    if (g_failures) {
        std::fprintf(stderr, "%d check(s) failed\n", g_failures);
        return 1;
    }
    std::puts("debug snapshot: all checks passed");
    return 0;
}
