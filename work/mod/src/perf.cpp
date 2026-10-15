// Truck performance: engine power multiplier and removal of the game's speed caps.
//
// Engine data flow: truck JSON (Engine/GearBox entries -> TruckDetail.Torque@0x30, TruckGearBox.MaxRpm@0x40)
// -> LoadTruck::Load writes VPVehicleController.engine (Engine.Settings @0x248):
//      peakRpmTorque@0x20 = Torque, idleRpmTorque@0x1C = Torque * 0.57, maxRpm@0x18 = MaxRpm
//    (TruckController::Update later forces maxRpm = 2500 on gear changes; SetPowerMode/SetHillMode
//     rewrite idleRpmTorque from peakRpmTorque).
// -> VPVehicleController::OnInitialize creates the Engine block (vehicle+0x2B0) holding the Settings ref @0x50.
// -> every physics step Engine::EvaluateTorqueDownstream / CalculateTorque compute
//      torque = ClampPowerTorque(GetMaxPowerTorqueRaw(rpm)) * throttle - GetFrictionTorque(rpm)
//    reading the Settings live.
#include "mod.h"

static void no_speed_limit_changed(Feature* f);
FEATURE(f_engine_mult, "engine_mult", "Truck", "Engine power multiplier", F_TOGGLE_FLOAT, false, 2.0f, 1.0f, 10.0f,
        nullptr, nullptr);
FEATURE(f_no_speed_limit, "no_speed_limit", "Truck", "No speed limit", F_TOGGLE, false, 0, 0, 0, nullptr,
        no_speed_limit_changed);

// ---------------------------------------------------------------- engine power
// GetMaxPowerTorqueRaw(rpm) is the full-throttle combustion torque curve; its knots are
// (idleRpm, idleRpmTorque + F(idleRpm)), (peakRpm, peakRpmTorque + F(peakRpm)), (maxRpm, F(maxRpm)),
// i.e. the configured (net) torque plus friction F. Scaling only the part above friction multiplies the
// net torque/power at every rpm by `mult` while keeping zero net torque at maxRpm, so the VPP rev limit
// (net <= 0 beyond maxRpm) is unchanged. Engine blocks only exist on VPVehicleControllers with driven
// axles, i.e. the player truck (trailers have none, traffic is TrafficCar, not VPP).
typedef float (*EngineRpmFn)(void* engine, float rpm, const MethodInfo* mi);
static const uintptr_t kGetMaxPowerTorqueRaw = 0x37c66c0;
static const uintptr_t kGetFrictionTorque = 0x37c6488;

static EngineRpmFn o_GetMaxPowerTorqueRaw;
static float h_GetMaxPowerTorqueRaw(void* self, float rpm, const MethodInfo* mi) {
    float raw = o_GetMaxPowerTorqueRaw(self, rpm, mi);
    if (!f_engine_mult.on) return raw;
    float mult = f_engine_mult.value;
    if (!(mult > 1.0f)) return raw;  // also rejects NaN
    if (mult > 10.0f) mult = 10.0f;
    float fric = ((EngineRpmFn)VA(kGetFrictionTorque))(self, rpm, nullptr);
    if (!(raw > fric)) return raw;
    return fric + (raw - fric) * mult;
}

// ---------------------------------------------------------------- speed limits
// No VehiclePhysics limiter is active: all 139 VPVehicleController prefabs ship with
// speedControl.speedLimiter/limitReverseSpeed, engine.rpmLimiter/torqueCap/powerCap = false, no game code
// enables them, and no SpeedLimitZone/AutoBrakeZone exists in any scene or bundle. The game's own caps:
static const BytePatch kNoSpeedLimit[] = {
    // TruckController::Update, every 60th frame: speedInKph(0x2D8) > 200 or < -100 -> UIButtons::_TowTruck
    // (truck is towed away). b.gt #0x19b358c -> b #0x19b3620 skips both tests; the following
    // position.y < -1000 fall-through check at 0x19b3620 is kept.
    {0x19b3578, "ac000054", "2a000014"},
    // UIButtons::CruiseControlIncrease: cruise set speed (ccSpeed@0x22C, +5 per press) capped at 120 km/h.
    {0x193a610, "3fe50171", "3fed0371"},  // cmp w9,#0x79 -> cmp w9,#0xfb  (+button greys out at 251)
    {0x193a64c, "1fe10171", "1fe90371"},  // cmp w8,#0x78 -> cmp w8,#0xfa  (no increase above 250)
};

static void no_speed_limit_changed(Feature* f) {
    for (const auto& p : kNoSpeedLimit) patch_apply(p, f->on);
}

INSTALL(install_perf) {
    HOOK(kGetMaxPowerTorqueRaw, h_GetMaxPowerTorqueRaw, o_GetMaxPowerTorqueRaw);
    no_speed_limit_changed(&f_no_speed_limit);
}
