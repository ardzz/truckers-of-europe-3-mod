// Read-only game diagnostics for Native.debugSnapshot(double[32]).
//
// Writers are hooks on the Unity main thread; they copy game field values (never pointers) into
// single-writer seqlock caches stamped with CLOCK_MONOTONIC. debug_snapshot() runs on the JNI/UI
// thread and only reads those caches: it never touches a game object. Nothing here writes game data.
#pragma once

constexpr int DEBUG_VALUES = 32;

// Index contract. NaN = no value: never sampled, or a read that raced a write (retry next poll).
// Ages are milliseconds since the block was sampled; sampled values are never replaced by NaN later,
// so staleness (scene gone, game paused, menu/home screen) shows only as a growing age.
enum DebugIndex : int {
    DBG_NATIVE_READY = 0,     // \ filled by main.cpp after debug_snapshot()
    DBG_HOOKS_INSTALLED = 1,  //  |
    DBG_HOOKS_FAILED = 2,     //  |
    DBG_PATCHES_FAILED = 3,   // /
    // Weather block: SkyValues after the original UpdateSkyValues (every 1 s idle, 0.05 s changing).
    DBG_WEATHER_AGE_MS = 4,
    DBG_WEATHER_ENABLED = 5,    // "weather" feature on (0/1)
    DBG_WEATHER_REQUESTED = 6,  // menu choice 0 Clear 1 Rainy 2 Cloudy 3 Foggy
    DBG_WEATHER_CURRENT = 7,    // currentWeather 0..3 (stays 1 while drying after rain)
    DBG_WEATHER_NEXT = 8,       // nextWeather 0..4 (4 AfterRain = drying)
    DBG_WEATHER_TIMER = 9,      // nextWeatherTime, seconds of game time (scaled) until the next change
    DBG_WEATHER_CHANGING = 10,  // weatherChancing (0/1): a transition is running
    DBG_RAIN = 11,              // rainIntensity 0..1
    DBG_CLOUD = 12,             // cloudIntensity 0..1
    DBG_MIST = 13,              // mistIntensity 0..1
    DBG_WEATHER_TARGET = 14,    // weatherIntensity: ramp target / fade scale
    DBG_NO_RAIN_AREA = 15,      // noRainArea (0/1): tunnel-like zone, window rain drops hidden
    DBG_STOP_RAIN_AREA = 16,    // _stopRainArea (0/1): the game stops rain here; a Rainy choice waits
    DBG_HOUR = 17,              // SkyValues.hour = TOD_Sky.Cycle.Hour copied by UpdateSkyValues, 0..24
    // Truck block: player TruckController after the original Update (at most every 100 ms).
    DBG_TRUCK_AGE_MS = 18,
    DBG_SPEED_KPH = 19,      // speedInKph (negative when reversing)
    DBG_FUEL_LITERS = 20,    // fuelLeft
    DBG_FUEL_FRACTION = 21,  // fuel = fuelLeft / fuelTankCapacity as stored by the game, 0..1
    DBG_HEALTH = 22,         // health 0..1
    DBG_DIRTINESS = 23,      // dirtiness 0..1
    DBG_DUSTINESS = 24,      // dustiness (raw game value)
    DBG_TRUCK_RAINING = 25,  // isRaining (0/1), set by SkyValues.SetRainItems
    // Weather controller (part of the weather block).
    DBG_REQUEST_GENERATION = 26,  // menu weather changes so far (at sample time)
    DBG_APPLIED_GENERATION = 27,  // request generation the controller last acted on (< 26: queued)
    DBG_TRANSITIONS = 28,         // transitions the controller started or redirected since start
    DBG_RESERVED_FIRST = 29,      // 29..31 always NaN
};
static_assert(DBG_TRANSITIONS == 28 && DBG_RESERVED_FIRST + 3 == DEBUG_VALUES, "debug index contract");

struct WeatherDebugSample {
    bool enabled;
    int requested, current, next, timer;
    bool transitioning;
    float rain, cloud, mist, target;
    bool noRainArea, stopRainArea;
    float hour;
    int requestGeneration, appliedGeneration;
    unsigned appliedCount;
};

struct TruckDebugSample {
    float speedKph, fuelLiters, fuelFraction, health, dirtiness, dustiness;
    bool raining;
};

// Unity main thread only (one writer per block).
void debug_weather_sample(const WeatherDebugSample& sample);
void debug_truck_sample(const TruckDebugSample& sample);
// SkyValues.controller of the ticking scene: the truck block follows that TruckController.
// The pointer is only compared on the main thread, never dereferenced or exposed.
void debug_player_truck(const void* controller);

// Any thread: fills all DEBUG_VALUES entries from the caches (0..3 and 29..31 NaN).
void debug_snapshot(double out[DEBUG_VALUES]);
