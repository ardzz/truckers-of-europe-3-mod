// Host tests for weather::steer (work/mod/src/weather_policy.h) driving a model of the vanilla SkyValues
// weather machine: <UpdateValues>d__151::MoveNext timing, the UpdateSkyValues switch (ramps, Clear fade,
// Rainy -> AfterRain drying, rain items), SetNextWeather alternation and the NoRain StopRain trigger.
// Ramps use a fixed per-resume fraction instead of Time.deltaTime and the weatherChangeCurve.
#include "weather_policy.h"

#include <cmath>
#include <cstdint>
#include <cstdio>

using namespace weather;

static int g_failures = 0;
#define CHECK(cond)                                                                     \
    do {                                                                                \
        if (!(cond)) {                                                                  \
            std::fprintf(stderr, "%s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                               \
        }                                                                               \
    } while (0)

// Deterministic rolls; intensities differ per call so a restore is distinguishable from a re-roll.
static int g_intensity_rolls = 0;
static float test_intensity(int w) {
    ++g_intensity_rolls;
    float base = w == kRainy ? 0.6f : w == kCloudy ? 0.4f : 0.2f;
    return base + 0.03f * (float)(g_intensity_rolls % 10);
}
static float test_speed(int) { return 7.f; }
static const Roll kRoll{test_intensity, test_speed};

struct Game {
    Sky s{};
    float wetness = 0.f, wtns = 0.f, last_rain = 0.f;
    bool rain_items = false;
    int fast = 0;
    uint32_t rng = 12345u;
    bool on = true;
    int want = kClear;
    Memory m{};
    int transitions = 0;
    int max_present = 0;

    float& amt(int w) { return w == kRainy ? s.rain : w == kCloudy ? s.cloud : s.mist; }

    void control() {  // world.cpp weather_control
        if (!on) return;
        if (counts(steer(s, m, want, kRoll))) ++transitions;
    }

    int vanilla_pick() {
        rng = rng * 1103515245u + 12345u;
        return (int)((rng >> 16) % 4u);
    }
    static int timer_for(int current) { return current == kFoggy ? 90 : current == kCloudy ? 30 : 120; }

    void set_next_weather() {  // SkyValues::SetNextWeather + h_SetNextWeather
        if (s.next != kClear) {
            s.next = kClear;
            s.timer = timer_for(s.current);
        } else {
            s.next = vanilla_pick();
            s.timer = s.next == kClear ? timer_for(s.current) : 140;
        }
        switch (s.next) {
            case kCloudy: s.speed = 6; s.target = 0.5f; s.timer = 120; break;
            case kRainy: s.target = 0.9f; s.speed = 12; break;
            default: s.speed = 6; if (s.next != kClear) s.target = 0.3f; break;
        }
        s.changing = false;
        control();
    }

    static constexpr float kStep = 0.15f;

    void update_sky_values() {
        if (s.changing) {
            switch (s.next) {
                case kClear:
                    if (is_weather(s.current)) {
                        float& v = amt(s.current);
                        v += (0.f - v) * kStep;
                        if (v > 0.01f) break;
                        v = 0.f;
                        if (s.current == kRainy) {  // stays Rainy while drying
                            wtns = 0.45f;
                            wetness = 0.75f;
                            s.next = kAfterRain;
                            s.speed = 10;
                            s.timer = s.stopRain ? 30 : 60;
                            break;
                        }
                    }
                    s.current = kClear;
                    set_next_weather();
                    break;
                case kAfterRain:
                    if (s.timer > 30) break;
                    wtns += (0.f - wtns) * 0.1f;
                    if (wtns <= 0.03f) wtns = 0.f;
                    if (wetness == 0.f) {
                        s.timer = 0;
                        s.current = kClear;
                        rain_items = false;
                        set_next_weather();
                        break;
                    }
                    wetness = std::fmin(1.f, std::fmax(0.f, (wtns - 0.2f) * 3.f));
                    break;
                default: {
                    if (!is_weather(s.next)) break;
                    if (s.next == kRainy && s.target == 0.f) s.target = 0.1f;
                    float& v = amt(s.next);
                    v += (s.target - v) * kStep;
                    if (std::fabs(s.target - v) > 0.01f) break;
                    s.current = s.next;
                    v = s.target;
                    set_next_weather();
                    break;
                }
            }
        }
        if (s.rain != last_rain) {  // any rain change enables rain items until AfterRain clears them
            last_rain = s.rain;
            rain_items = true;
        }
    }

    void tick() {  // one MoveNext resume with the h_UpdateSkyValues hook
        control();
        update_sky_values();
        if (s.timer != 0) {
            if (!s.changing || ++fast == 20) {
                fast = 0;
                --s.timer;
            }
        } else if (!s.changing) {
            s.changing = true;
        }
        int n = (s.rain > 0.f) + (s.cloud > 0.f) + (s.mist > 0.f);
        if (n > max_present) max_present = n;
    }

    bool settled_at(int w) {
        if (s.changing || s.current != w) return false;
        for (int x = kRainy; x <= kFoggy; ++x)
            if (x != w && amt(x) != 0.f) return false;
        if (is_weather(w) && std::fabs(amt(w) - s.target) > 0.011f) return false;
        return w == kRainy || !rain_items;
    }

    int run_until_settled(int w, int max_ticks = 6000) {
        for (int t = 0; t < max_ticks; ++t) {
            if (settled_at(w)) return t;
            tick();
        }
        return -1;
    }

    template <class Pred>
    int run_until(Pred pred, int max_ticks = 6000) {
        for (int t = 0; t < max_ticks; ++t) {
            if (pred(*this)) return t;
            tick();
        }
        return -1;
    }
};

// Scene as SkyValues::Awake leaves it: currentWeather = Clear, then SetNextWeather.
static Game fresh_scene(int want) {
    Game g;
    g.want = want;
    g.set_next_weather();
    return g;
}

static Game settled_scene(int want) {
    Game g = fresh_scene(want);
    CHECK(g.run_until_settled(want) >= 0);
    g.transitions = 0;
    g.max_present = 0;
    return g;
}

static void test_steady_change_starts_without_waiting_for_recheck() {
    Game g = settled_scene(kClear);
    for (int i = 0; i < 20; ++i) g.tick();
    CHECK(g.s.next == kClear && !g.s.changing);
    g.want = kRainy;
    g.tick();
    CHECK(g.s.next == kRainy && g.s.timer <= kChangeTicks);
    CHECK(g.run_until([](Game& x) { return x.s.changing; }, 3) >= 0);
    CHECK(g.run_until_settled(kRainy) >= 0);
    CHECK(g.transitions == 1);
    CHECK(g.s.next == kRainy && g.s.timer <= kSteadyTicks);  // held with the steady re-check
}

static void test_request_during_rampup_fades_at_once_through_drying() {
    Game g = settled_scene(kClear);
    g.want = kRainy;
    CHECK(g.run_until([](Game& x) { return x.s.rain > 0.3f; }) >= 0);
    g.want = kClear;
    float rain = g.s.rain;
    g.tick();
    CHECK(g.s.current == kRainy && g.s.next == kClear);
    CHECK(g.s.rain < rain);
    bool rose = false, dried = false;
    for (int t = 0; t < 6000 && !g.settled_at(kClear); ++t) {
        rain = g.s.rain;
        g.tick();
        rose |= g.s.rain > rain;
        dried |= g.s.next == kAfterRain;
    }
    CHECK(!rose);
    CHECK(dried);
    CHECK(g.settled_at(kClear) && !g.rain_items);
    CHECK(g.max_present <= 1);
}

static void test_other_weather_route_fades_through_clear() {
    Game g = settled_scene(kFoggy);
    g.want = kCloudy;
    bool overlap = false;
    for (int t = 0; t < 6000 && !g.settled_at(kCloudy); ++t) {
        g.tick();
        overlap |= g.s.cloud > 0.f && g.s.mist > 0.f;
    }
    CHECK(!overlap);
    CHECK(g.settled_at(kCloudy));
    CHECK(g.transitions == 2);  // fade Foggy, ramp Cloudy

    Game r = settled_scene(kRainy);
    r.want = kFoggy;
    bool mist_while_wet = false;
    for (int t = 0; t < 6000 && !r.settled_at(kFoggy); ++t) {
        r.tick();
        mist_while_wet |= r.s.mist > 0.f && (r.rain_items || r.s.rain > 0.f);
    }
    CHECK(!mist_while_wet);  // fog only after the rain fade and AfterRain clean-up
    CHECK(r.settled_at(kFoggy));
}

static void test_rapid_changes_latest_wins() {
    Game g = settled_scene(kClear);
    uint32_t lcg = 7u;
    for (int change = 0; change < 400; ++change) {
        lcg = lcg * 1664525u + 1013904223u;
        g.want = (int)((lcg >> 8) % 4u);
        int hold = 1 + (int)((lcg >> 20) % 60u);
        for (int t = 0; t < hold; ++t) g.tick();
    }
    g.want = kFoggy;
    g.tick();
    g.want = kRainy;
    g.tick();
    g.want = kCloudy;
    CHECK(g.run_until_settled(kCloudy) >= 0);
    CHECK(g.max_present <= 1);
    CHECK(!g.rain_items);
}

static void test_same_weather_restores_its_intensity() {
    Game g = settled_scene(kClear);
    g.want = kRainy;
    g.tick();
    float target = g.s.target;
    int rolls = g_intensity_rolls;
    CHECK(g.run_until([target](Game& x) { return x.s.rain > 0.5f * target; }) >= 0);
    g.want = kClear;
    for (int t = 0; t < 5; ++t) g.tick();
    CHECK(g.s.next == kClear && g.s.rain < 0.5f * target);
    g.want = kRainy;
    g.tick();
    CHECK(g.s.next == kRainy && g.s.target == target);
    CHECK(g_intensity_rolls == rolls);
    CHECK(g.run_until_settled(kRainy) >= 0);
    CHECK(g.s.rain == target);

    // Sleep::Update zeroes rainIntensity: ramp back right away, to the same target.
    for (int i = 0; i < 30; ++i) g.tick();
    g.s.rain = 0.f;
    CHECK(g.run_until([](Game& x) { return x.s.changing; }, kChangeTicks + 3) >= 0);
    CHECK(g.run_until_settled(kRainy) >= 0);
    CHECK(g.s.rain == target);
}

static void test_drying_only_interrupted_to_return_to_rain() {
    Game g = settled_scene(kRainy);
    float target = g.s.target;
    g.want = kCloudy;
    CHECK(g.run_until([](Game& x) { return x.s.next == kAfterRain; }) >= 0);
    for (int t = 0; t < 50; ++t) {
        g.tick();
        CHECK(g.s.next == kAfterRain && g.s.cloud == 0.f);
    }
    g.want = kRainy;
    g.tick();
    CHECK(g.s.next == kRainy && g.s.target == target);
    CHECK(g.run_until_settled(kRainy) >= 0);

    g.want = kClear;
    CHECK(g.run_until([](Game& x) { return x.s.next == kAfterRain; }) >= 0);
    g.want = kCloudy;
    bool clouds_while_wet = false;
    for (int t = 0; t < 6000 && !g.settled_at(kCloudy); ++t) {
        g.tick();
        clouds_while_wet |= g.s.cloud > 0.f && g.rain_items;
    }
    CHECK(!clouds_while_wet);
    CHECK(g.settled_at(kCloudy));
}

static void test_stop_rain_area_suppresses_rain_until_left() {
    Game g = settled_scene(kRainy);
    // NoRain::OnTriggerEnter (StopRain) with currentWeather Rainy.
    g.s.stopRain = true;
    g.s.current = kRainy;
    g.s.next = kClear;
    g.s.timer = 10;
    CHECK(g.run_until_settled(kClear) >= 0);
    for (int t = 0; t < 400; ++t) {
        g.tick();
        CHECK(g.s.rain == 0.f);
    }
    g.s.stopRain = false;  // NoRain::OnTriggerExit
    CHECK(g.run_until_settled(kRainy) >= 0);
}

static void test_off_hands_a_coherent_state_to_vanilla() {
    Game g = settled_scene(kClear);
    g.want = kRainy;
    CHECK(g.run_until([](Game& x) { return x.s.rain > 0.2f; }) >= 0);
    g.want = kCloudy;
    g.tick();  // fade of the partial rain begins
    g.on = false;
    int writes = g.transitions;
    bool cleared = false;
    for (int t = 0; t < 20000; ++t) {
        g.tick();
        cleared |= g.s.current == kClear && g.s.rain == 0.f && !g.rain_items;
    }
    CHECK(cleared);
    CHECK(g.max_present <= 1);
    CHECK(g.transitions == writes);
    g.on = true;
    g.want = kFoggy;
    CHECK(g.run_until_settled(kFoggy, 20000) >= 0);
}

static void test_new_scene_ignores_stale_memory() {
    Game g;
    g.m.target[kRainy] = 0.33f;  // left over from another scene instance
    g.want = kRainy;
    g.set_next_weather();
    CHECK(g.run_until_settled(kRainy) >= 0);
    float target = g.s.target;
    CHECK(target != 0.33f && g.m.target[kRainy] == target);
    g.want = kClear;
    for (int t = 0; t < 3; ++t) g.tick();
    g.want = kRainy;
    g.tick();
    CHECK(g.s.target == target);
}

static void test_steer_is_idempotent() {
    Game g = settled_scene(kClear);
    g.want = kFoggy;
    CHECK(steer(g.s, g.m, g.want, kRoll) == kSchedule);
    Sky before = g.s;
    CHECK(steer(g.s, g.m, g.want, kRoll) == kNone);
    CHECK(g.s.timer == before.timer && g.s.next == before.next && g.s.target == before.target);

    CHECK(g.run_until([](Game& x) { return x.s.mist > 0.1f; }) >= 0);
    g.want = kRainy;
    CHECK(steer(g.s, g.m, g.want, kRoll) == kFade);
    CHECK(g.s.current == kFoggy && g.s.next == kClear);
    CHECK(steer(g.s, g.m, g.want, kRoll) == kNone);
    g.want = kFoggy;
    CHECK(steer(g.s, g.m, g.want, kRoll) == kRestore);
    CHECK(steer(g.s, g.m, g.want, kRoll) == kNone);
}

int main() {
    test_steady_change_starts_without_waiting_for_recheck();
    test_request_during_rampup_fades_at_once_through_drying();
    test_other_weather_route_fades_through_clear();
    test_rapid_changes_latest_wins();
    test_same_weather_restores_its_intensity();
    test_drying_only_interrupted_to_return_to_rain();
    test_stop_rain_area_suppresses_rain_until_left();
    test_off_hands_a_coherent_state_to_vanilla();
    test_new_scene_ignores_stale_memory();
    test_steer_is_idempotent();
    if (g_failures) {
        std::fprintf(stderr, "%d check(s) failed\n", g_failures);
        return 1;
    }
    std::puts("weather policy: all checks passed");
    return 0;
}
