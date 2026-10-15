// Collision damage (truck health / trailer+cargo damage) and repair cost/time.
//
// Damage model (from libil2cpp + shipped scene/prefab data):
//  - TruckDamage::OnCollisionEnter is the only collision damage source. The
//    truck prefabs carry TruckDamage(isTrailer=false); TrailerController::Start
//    adds TruckDamage at runtime with isTrailer=true.
//      truck  : TruckController.instance.health (0x260, 0..1) += |relVel| / -800
//      trailer: Prefs.TrailerDamage += |relVel| / 500 (cargo damage; drives the
//               JobComplete penalty, reset to 0 on job start/complete)
//  - No mesh deformation exists for player vehicles: ImpactDeformable / VPDamage /
//    RepairArea are asset-demo scripts with zero instances in the game data.
#include "mod.h"

// ---------------------------------------------------------------- features
static void free_repair_changed(Feature* f);
static void instant_repair_changed(Feature* f);

FEATURE(f_no_truck_damage, "no_truck_damage", "Damage", "No truck damage", F_TOGGLE, false, 0, 0, 0, nullptr,
        nullptr);
FEATURE(f_no_trailer_damage, "no_trailer_damage", "Damage", "No trailer/cargo damage", F_TOGGLE, false, 0, 0, 0,
        nullptr, nullptr);
FEATURE(f_free_repair, "free_repair", "Damage", "Free repair", F_TOGGLE, false, 0, 0, 0, nullptr,
        free_repair_changed);
FEATURE(f_instant_repair, "instant_repair", "Damage", "Instant repair", F_TOGGLE, false, 0, 0, 0, nullptr,
        instant_repair_changed);

// ---------------------------------------------------------------- collision damage
// TruckDamage: bool isTrailer @0x20
static void (*o_TruckDamage_OnCollisionEnter)(void* self, void* collision, const MethodInfo* mi);

static void h_TruckDamage_OnCollisionEnter(void* self, void* collision, const MethodInfo* mi) {
    if (self) {
        bool is_trailer = FIELD(bool, self, 0x20);
        if (is_trailer ? f_no_trailer_damage.on : f_no_truck_damage.on) return;
    }
    o_TruckDamage_OnCollisionEnter(self, collision, mi);
}

// ---------------------------------------------------------------- free repair
// Garage repair (Repair): repairPrice @0x30 = (100 - health*100) * 15, charged in Pay.
// Roadside wreck repair (FuelRepairUI, serviceType "Repair"): totalToPay @0x28 = 2000,
// towFee @0x2c = 1000 (ctor); FuelRepairUI::Pay charges totalToPay + towFee.
static const BytePatch kFreeRepair[] = {
    {0x192b428, "0008211e", "e003271e"},  // Repair::OnEnable: fmul s0,s0,s1 -> fmov s0,wzr (price 0, label "0")
    {0x192b760, "34020037", "11000014"},  // Repair::Pay: tbnz w20(payWithAd),#0x192b7a4 -> b (skip charge)
    {0x192ac40, "489fa852", "e8031f2a"},  // FuelRepairUI::ShowWindow: mov w8,#2000f -> mov w8,wzr (non-Fuel)
};

static void free_repair_changed(Feature* f) {
    for (const auto& p : kFreeRepair) patch_apply(p, f->on);
}

// Il2CppString: int32 length @0x10, UTF-16 chars @0x14.
static bool il2cpp_str_eq(void* s, const char* lit) {
    if (!s) return false;
    int len = FIELD(int32_t, s, 0x10);
    const uint16_t* chars = (const uint16_t*)((uintptr_t)s + 0x14);
    int i = 0;
    for (; lit[i]; ++i)
        if (i >= len || chars[i] != (uint16_t)(unsigned char)lit[i]) return false;
    return i == len;
}

// towFee is only initialised by the ctor, so a zeroed value must be put back
// before the window is shown for fuel or with the feature off.
static void* g_tow_owner = nullptr;
static float g_tow_saved = 0.0f;

static void (*o_FuelRepairUI_ShowWindow)(void* self, void* type, const MethodInfo* mi);

static void h_FuelRepairUI_ShowWindow(void* self, void* type, const MethodInfo* mi) {
    bool free_rep = self && f_free_repair.on && !il2cpp_str_eq(type, "Fuel");
    if (free_rep) {
        if (g_tow_owner != self) {
            g_tow_saved = FIELD(float, self, 0x2c);
            g_tow_owner = self;
        }
        FIELD(float, self, 0x2c) = 0.0f;
    } else if (self && g_tow_owner == self) {
        FIELD(float, self, 0x2c) = g_tow_saved;
        g_tow_owner = nullptr;
    }
    o_FuelRepairUI_ShowWindow(self, type, mi);
    if (free_rep) FIELD(float, self, 0x28) = 0.0f;
}

// ---------------------------------------------------------------- instant repair
// Repair.<Repairing>d__13::MoveNext: progress bar loop, scn @0x40 += 1 per
// WaitForSeconds(0.1) until scn >= 70. Truck health is already 1.0 (set in Pay).
static const BytePatch kInstantRepair = {0x192bfac, "cb060054", "1f2003d5"};  // b.lt (scn<70) -> nop

static void instant_repair_changed(Feature* f) { patch_apply(kInstantRepair, f->on); }

// ---------------------------------------------------------------- install
INSTALL(install_damage) {
    HOOK(0x19bbe8c, h_TruckDamage_OnCollisionEnter, o_TruckDamage_OnCollisionEnter);
    HOOK(0x192aa18, h_FuelRepairUI_ShowWindow, o_FuelRepairUI_ShowWindow);
    free_repair_changed(&f_free_repair);
    instant_repair_changed(&f_instant_repair);
}
