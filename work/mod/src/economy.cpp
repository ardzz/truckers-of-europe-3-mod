// Currency/progress overrides (Prefs getters), paid-feature flags, and the
// "no rain dirt" byte patches in Truck/TrailerController::Update.
#include "mod.h"

// ---------------------------------------------------------------- features
FEATURE(f_money, "money", "Economy", "Money", F_TOGGLE_INT, true, 50000000, 0, 100000000, nullptr, nullptr);
FEATURE(f_gold, "gold", "Economy", "Gold", F_TOGGLE_INT, true, 999999, 0, 9999999, nullptr, nullptr);
FEATURE(f_tickets, "wash_tickets", "Economy", "Wash tickets", F_TOGGLE_INT, true, 999, 0, 9999, nullptr, nullptr);
FEATURE(f_level, "level", "Economy", "Level", F_TOGGLE_INT, true, 100, 1, 200, nullptr, nullptr);
FEATURE(f_xp, "xp", "Economy", "XP", F_TOGGLE_INT, false, 0, 0, 1000000, nullptr, nullptr);
FEATURE(f_adfree, "ad_free", "Economy", "Remove ads", F_TOGGLE, false, 0, 0, 0, nullptr, nullptr);
FEATURE(f_unlock_trailers, "unlock_trailers", "Economy", "Unlock all trailers", F_TOGGLE, false, 0, 0, 0, nullptr,
        nullptr);

static void dirt_changed(Feature* f);
FEATURE(f_no_rain_dirt, "no_rain_dirt", "Damage", "No dirt from rain", F_TOGGLE, true, 0, 0, 0, nullptr,
        dirt_changed);

// ---------------------------------------------------------------- Prefs getters
// All are static: Ret get_X(const MethodInfo*)
static float (*o_get_Balance)(const MethodInfo*);
static int (*o_get_Gold)(const MethodInfo*);
static int (*o_get_WashTickets)(const MethodInfo*);
static int (*o_get_Level)(const MethodInfo*);
static int (*o_get_Xp)(const MethodInfo*);
static bool (*o_get_AdFree)(const MethodInfo*);
static bool (*o_get_UnlockTrailers)(const MethodInfo*);

static float h_get_Balance(const MethodInfo* mi) { return f_money.on ? (float)(int)f_money.value : o_get_Balance(mi); }
static int h_get_Gold(const MethodInfo* mi) { return f_gold.on ? (int)f_gold.value : o_get_Gold(mi); }
static int h_get_WashTickets(const MethodInfo* mi) {
    return f_tickets.on ? (int)f_tickets.value : o_get_WashTickets(mi);
}
static int h_get_Level(const MethodInfo* mi) { return f_level.on ? (int)f_level.value : o_get_Level(mi); }
static int h_get_Xp(const MethodInfo* mi) { return f_xp.on ? (int)f_xp.value : o_get_Xp(mi); }
static bool h_get_AdFree(const MethodInfo* mi) { return f_adfree.on ? true : o_get_AdFree(mi); }
static bool h_get_UnlockTrailers(const MethodInfo* mi) {
    return f_unlock_trailers.on ? true : o_get_UnlockTrailers(mi);
}

// ---------------------------------------------------------------- rain dirt
// Truck (rained flag @0x278, dirtiness @0x268) and trailer (rained @0xE1, dirtiness @0xE4):
//  - skip the post-rain "drying" path that raises dirtiness to 1.0
//  - drop the dirtiness increment while driving in rain (dirtiness <= 0.5 branch)
static const BytePatch kDirt[] = {
    {0x19b44ec, "e8090034", "4f000014"},  // TruckController: cbz w8,#.. -> b 0x19b4628
    {0x19b45a8, "0028211e", "1f2003d5"},  // TruckController: fadd s0,s0,s1 -> nop
    {0x199ccf4, "e8090034", "4f000014"},  // TrailerController: cbz w8,#.. -> b 0x199ce30
    {0x199cdb0, "2028201e", "2040201e"},  // TrailerController: fadd s0,s1,s0 -> fmov s0,s1
};

static void dirt_changed(Feature* f) {
    for (const auto& p : kDirt) patch_apply(p, f->on);
}

INSTALL(install_economy) {
    HOOK(0x1b60978, h_get_Balance, o_get_Balance);
    HOOK(0x1b60ab8, h_get_Gold, o_get_Gold);
    HOOK(0x1b61de4, h_get_WashTickets, o_get_WashTickets);
    HOOK(0x1b60228, h_get_Level, o_get_Level);
    HOOK(0x1b6035c, h_get_Xp, o_get_Xp);
    HOOK(0x1b60d34, h_get_AdFree, o_get_AdFree);
    HOOK(0x1b60e70, h_get_UnlockTrailers, o_get_UnlockTrailers);
    dirt_changed(&f_no_rain_dirt);
}
