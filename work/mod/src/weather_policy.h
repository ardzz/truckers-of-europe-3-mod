// Weather steering policy: pure logic shared by world.cpp (Unity main thread) and the host tests in
// tests/native. No il2cpp access here; world.cpp copies SkyValues fields in and writes them back.
//
// Vanilla state machine this steers (libil2cpp vaddrs, see world.cpp for the field offsets):
//  - <UpdateValues>d__151::MoveNext (0x1b7cae8) resumes every 1 s (wfs) while idle and every 0.05 s
//    (wfsf) while weatherChancing. Each resume calls UpdateSkyValues, then counts nextWeatherTime down
//    (every resume while idle, every 20th resume while changing). At 0 while idle it sets
//    weatherChancing: the transition toward nextWeather starts.
//  - UpdateSkyValues (switch at 0x1b748fc), only while weatherChancing, by nextWeather:
//      Rainy/Cloudy/Foggy  ramp that weather's intensity toward weatherIntensity (whatever
//                          currentWeather is); within 0.01 -> currentWeather = it, SetNextWeather.
//      Clear               fade currentWeather's intensity to 0 (Clear current: at once), then
//                          currentWeather = Clear and SetNextWeather. Rainy instead hands over to
//                          nextWeather = AfterRain with currentWeather still Rainy.
//      AfterRain           drying: waits until nextWeatherTime <= 30 (set to 60, or 30 inside a
//                          StopRain area), dries the road, then currentWeather = Clear,
//                          SetRainItems(false) and SetNextWeather. Only this path turns rain items off.
//  - SetNextWeather (0x1b7756c) rolls the next weather/timer/intensity/speed, clears weatherChancing.
//  - NoRain StopRain trigger (0x1b38774) with currentWeather Rainy: _stopRainArea = true,
//    nextWeather = Clear, nextWeatherTime = 10. Leaving it clears _stopRainArea.
//
// steer() is evaluated on every UpdateSkyValues tick (before the original) and after every
// SetNextWeather. It only redirects that machine (currentWeather/nextWeather/timer/ramp target/speed);
// intensities, road wetness and rain items stay with vanilla code, so every visible change still runs
// through the vanilla ramps, the Clear fade and AfterRain drying.
#pragma once

namespace weather {

enum : int { kClear = 0, kRainy = 1, kCloudy = 2, kFoggy = 3, kAfterRain = 4 };

constexpr int kChangeTicks = 1;    // nextWeatherTime for a needed change: starts on the next resume
constexpr int kSteadyTicks = 180;  // re-check period while holding the chosen weather
constexpr float kSleepGap = 0.05f;

// Copy of the SkyValues fields the policy reads. steer() writes only current, next, timer, target, speed.
struct Sky {
    bool changing;            // weatherChancing
    bool stopRain;            // _stopRainArea
    int current, next, timer; // currentWeather, nextWeather, nextWeatherTime
    float target, speed;      // weatherIntensity (ramp target / fade scale), weatherChangeSpeed
    float rain, cloud, mist;  // rainIntensity, cloudIntensity, mistIntensity
};

// Per-SkyValues-instance memory: ramp target of each weather that is present or being ramped to.
struct Memory {
    float target[4];
};

// Vanilla SetNextWeather random ranges, supplied by the caller.
struct Roll {
    float (*intensity)(int weather);
    float (*speed)(int weather);
};

enum Step : int {
    kNone = 0,
    kHold,      // keep the current weather (steady re-check timer, Sleep re-ramp)
    kSchedule,  // idle: next transition set up to start within kChangeTicks
    kRetarget,  // running transition redirected to a fresh weather / cancelled before anything showed
    kFade,      // running ramp-up turned into a fade of that weather through Clear
    kRestore,   // fading or drying weather ramped back to its own target
};

// Steps that start or redirect a weather transition (the diagnostics transition count).
inline bool counts(Step st) { return st >= kSchedule; }

inline bool is_weather(int w) { return w == kRainy || w == kCloudy || w == kFoggy; }

inline float amount(const Sky& s, int w) {
    return w == kRainy ? s.rain : w == kCloudy ? s.cloud : w == kFoggy ? s.mist : 0.f;
}

// Non-clear weather whose effects are in the scene: currentWeather (Rainy also while drying), else a
// ramp-up from Clear that has already raised its intensity.
inline int present(const Sky& s) {
    if (is_weather(s.current)) return s.current;
    if (s.changing && is_weather(s.next) && amount(s, s.next) > 0.f) return s.next;
    return kClear;
}

// nextWeather the scene has to head for: a different non-clear weather fades out through Clear first.
inline int hop(int present_weather, int want) {
    return present_weather == kClear || present_weather == want ? want : kClear;
}

namespace detail {

inline float restore_target(const Sky& s, Memory& m, int w, const Roll& roll) {
    float t = m.target[w] > 0.f ? m.target[w] : amount(s, w) > 0.f ? amount(s, w) : roll.intensity(w);
    m.target[w] = t;
    return t;
}

// The Clear fade divides by weatherIntensity; keep it a positive scale of the weather being faded.
inline void fade_scale(Sky& s, const Memory& m, int w) {
    if (s.target > 0.f) return;
    s.target = m.target[w] > 0.f ? m.target[w] : amount(s, w) > 0.f ? amount(s, w) : 1.f;
}

inline void fresh(Sky& s, Memory& m, int w, const Roll& roll) {
    s.target = m.target[w] = roll.intensity(w);
    s.speed = roll.speed(w);
}

}  // namespace detail

// Steer `s` one step toward `want` (kClear..kFoggy). Idempotent: once the scene is on the route a
// repeated call returns kNone and leaves `s` untouched, so per-tick evaluation never re-arms timers.
inline Step steer(Sky& s, Memory& m, int want, const Roll& roll) {
    if (want == kRainy && s.stopRain) want = kClear;  // the game stops rain inside StopRain areas
    const int p = present(s);
    const int h = hop(p, want);

    for (int w = kRainy; w <= kFoggy; ++w)
        if (w != p && w != s.next) m.target[w] = 0.f;  // gone for good: a later request rolls afresh
    if (is_weather(s.next) && s.target > 0.f) m.target[s.next] = s.target;  // the target being ramped to

    if (s.changing) {
        // Drying finishes rain (rain items off) unless rain itself is wanted back.
        if (s.next == kAfterRain ? !(p == kRainy && h == kRainy) : s.next == h) return kNone;
        if (h == kClear) {
            s.next = kClear;
            s.speed = roll.speed(kClear);
            if (!is_weather(p)) return kRetarget;  // ramp-up had not raised anything yet
            s.current = p;  // the Clear fade fades currentWeather's intensity
            detail::fade_scale(s, m, p);
            return kFade;
        }
        s.next = h;
        if (p == h) {
            s.target = detail::restore_target(s, m, h, roll);
            s.speed = roll.speed(h);
            return kRestore;
        }
        detail::fresh(s, m, h, roll);
        return kRetarget;
    }

    if (h == s.current) {
        Step st = kNone;
        if (s.next != h) {
            s.next = h;
            s.timer = kSteadyTicks;
            if (is_weather(h)) {
                s.target = detail::restore_target(s, m, h, roll);
                s.speed = roll.speed(h);
            }
            st = kHold;
        } else if (s.timer > kSteadyTicks) {
            s.timer = kSteadyTicks;
            st = kHold;
        }
        // Sleep::Update zeroes rainIntensity: ramp back now instead of after the steady period.
        if (is_weather(h) && amount(s, h) + kSleepGap < 0.5f * s.target && s.timer > kChangeTicks) {
            s.timer = kChangeTicks;
            st = kHold;
        }
        return st;
    }

    if (s.next != h) {
        s.next = h;
        s.timer = kChangeTicks;
        if (h == kClear) {
            s.speed = roll.speed(kClear);
            if (is_weather(s.current)) detail::fade_scale(s, m, s.current);
        } else {
            detail::fresh(s, m, h, roll);
        }
        return kSchedule;
    }
    if (s.timer > kChangeTicks) {
        s.timer = kChangeTicks;
        return kSchedule;
    }
    return kNone;
}

}  // namespace weather
