// Fuel: infinite fuel (TruckController::Update consumption) and free refuelling
// (gas-station FillFuel window + roadside FuelRepairUI "Fuel" service).
#include "mod.h"

static void inf_fuel_changed(Feature* f);
static void free_fuel_changed(Feature* f);
FEATURE(f_inf_fuel, "inf_fuel", "Fuel", "Infinite fuel", F_TOGGLE, false, 0, 0, 0, nullptr, inf_fuel_changed);
FEATURE(f_free_fuel, "free_fuel", "Fuel", "Free refuelling", F_TOGGLE, false, 0, 0, 0, nullptr, free_fuel_changed);

// ---------------------------------------------------------------- infinite fuel
// TruckController::Update, every 0.5 s while fuelRate (0x2CC) > 0:
//   fuelLeft(0x264) += fuelRate / -1e6 * 1.3;  fuel(0x258) = fuelLeft / fuelTankCapacity(0x40)
// Replacing the fadd keeps fuelLeft at its current level; totalJobFuel (job stat) still accumulates.
static const BytePatch kInfFuel = {0x19b40ac, "6028201e", "6040201e"};  // fadd s0,s3,s0 -> fmov s0,s3

static void inf_fuel_changed(Feature* f) { patch_apply(kInfFuel, f->on); }

// ---------------------------------------------------------------- free refuelling
// FillFuel: totalToPay(0x6C) = fAmount(0x70) * literPrice(0x50) * tankCapacity(0x54) in the Filling coroutine.
// Forcing it to 0 makes every price text show 0; Fill()'s "nothing filled -> no pay button" test is
// switched from totalToPay to fAmount so the pay button (which applies the fuel) still appears.
static const BytePatch kFreeFuel[] = {
    {0x192a65c, "0008211e", "e003271e"},  // <Filling>d__23::MoveNext: fmul s0,s0,s1 -> fmov s0,wzr
    {0x1929cb4, "686e40bd", "687240bd"},  // Fill: ldr s8,[x19,#0x6c] -> ldr s8,[x19,#0x70]
    {0x19293dc, "685240bd", "e803271e"},  // Start: ldr s8,[x19,#0x50] (price/L label) -> fmov s8,wzr
};

static void free_fuel_changed(Feature* f) {
    for (const auto& p : kFreeFuel) patch_apply(p, f->on);
}

// Pay(false) deducts totalToPay from Prefs.Balance and adds it to MoneySpent; Pay(true) (ad reward path)
// skips exactly that block and then applies the fuel identically.
static void (*o_FillFuel_Pay)(void*, bool, const MethodInfo*);
static void h_FillFuel_Pay(void* self, bool payWithAd, const MethodInfo* mi) {
    o_FillFuel_Pay(self, payWithAd || f_free_fuel.on, mi);
}

// Il2CppString: int32 length @0x10, UTF-16 chars @0x14. Game compares with String.op_Equality (ordinal).
static bool is_fuel_service(const void* s) {
    if (!s || FIELD(int32_t, s, 0x10) != 4) return false;
    const uint16_t* c = (const uint16_t*)((uintptr_t)s + 0x14);
    return c[0] == 'F' && c[1] == 'u' && c[2] == 'e' && c[3] == 'l';
}

// FuelRepairUI.serviceType (0x20) is "Fuel" or "Repair"; only the fuel delivery is made free.
static void (*o_FuelRepairUI_Pay)(void*, bool, const MethodInfo*);
static void h_FuelRepairUI_Pay(void* self, bool payWithAd, const MethodInfo* mi) {
    if (f_free_fuel.on && self && is_fuel_service(FIELD(void*, self, 0x20))) payWithAd = true;
    o_FuelRepairUI_Pay(self, payWithAd, mi);
}

INSTALL(install_fuel) {
    HOOK(0x192a024, h_FillFuel_Pay, o_FillFuel_Pay);
    HOOK(0x192af7c, h_FuelRepairUI_Pay, o_FuelRepairUI_Pay);
    inf_fuel_changed(&f_inf_fuel);
    free_fuel_changed(&f_free_fuel);
}
