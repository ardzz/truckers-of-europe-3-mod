// Shopping: free truck/trailer/interior/garage purchases and free ferry/toll/tow travel costs.
#include "mod.h"

static void free_shop_changed(Feature* f);
static void free_travel_changed(Feature* f);
FEATURE(f_free_shop, "free_shop", "Shopping", "Free shopping (trucks, trailers, garages)", F_TOGGLE, false, 0, 0, 0,
        nullptr, free_shop_changed);
FEATURE(f_free_travel, "free_travel", "Shopping", "Free ferry, tolls & towing", F_TOGGLE, false, 0, 0, 0, nullptr,
        free_travel_changed);

// Zeroes a price field for the duration of one original call. The field is restored afterwards unless the
// game itself wrote a new value meanwhile, so the real price stays in the object (it is also saved as the
// truck/trailer value used for resale/trade-in).
template <typename T>
struct TempZero {
    T* p;
    T old;
    explicit TempZero(void* obj, uintptr_t off) : p((T*)((uintptr_t)obj + off)), old(*p) { *p = T(0); }
    ~TempZero() {
        if (*p == T(0)) *p = old;
    }
    TempZero(const TempZero&) = delete;
    TempZero& operator=(const TempZero&) = delete;
};

// ================================================================ free shop
// Configure (truck dealer / truck customisation, also carries InteriorCustomizer costs):
//   totalPrice 0x170 (new truck), additionalPrice 0x178 (edit; UpdateSummary adds
//   InteriorCustomizer.totalMoneyCosts 0x200), additionalGold 0x17C (= additionalAccessoriesGold 0x180 +
//   InteriorCustomizer.totalGoldCosts 0x204).
// AskPurchaseSure: "not enough money/gold" checks (Balance < totalPrice, additionalPrice > Balance,
//   Gold < additionalGold) and the confirm dialog text. Zeroed during the call -> check passes, dialog shows 0.
static void (*o_Configure_AskPurchaseSure)(void*, bool, const MethodInfo*);
static void h_Configure_AskPurchaseSure(void* self, bool direct, const MethodInfo* mi) {
    if (!f_free_shop.on || !self) return o_Configure_AskPurchaseSure(self, direct, mi);
    TempZero<float> tp(self, 0x170), ap(self, 0x178);
    TempZero<int32_t> ag(self, 0x17C);
    o_Configure_AskPurchaseSure(self, direct, mi);
}

// Configure.<_Save>d__160 (this @0x20, priceMultiplier @0x28): the only place truck purchases are charged:
//   Balance -= totalPrice * priceMultiplier            (new truck)
//   Balance -= additionalPrice(*0.5 if <0) * mult      (edit)
//   if (additionalGold >= 1) Gold -= additionalGold
// Every route (confirm button, ad-discount Save/SaveAndAssign via TSAdManager) ends here.
static bool (*o_ConfigureSave_MoveNext)(void*, const MethodInfo*);
static bool h_ConfigureSave_MoveNext(void* co, const MethodInfo* mi) {
    void* cfg = co ? FIELD(void*, co, 0x20) : nullptr;
    if (!f_free_shop.on || !cfg) return o_ConfigureSave_MoveNext(co, mi);
    TempZero<float> mult(co, 0x28);
    TempZero<int32_t> gold(cfg, 0x17C);
    return o_ConfigureSave_MoveNext(co, mi);
}

// TrailerConfigure: totalPrice 0xD0 (new trailer), additionalPrice 0xD4 (edit). AskPurchaseSure checks
// Balance < totalPrice / additionalPrice > Balance and prints the price.
static void (*o_TrailerConfigure_AskPurchaseSure)(void*, const MethodInfo*);
static void h_TrailerConfigure_AskPurchaseSure(void* self, const MethodInfo* mi) {
    if (!f_free_shop.on || !self) return o_TrailerConfigure_AskPurchaseSure(self, mi);
    TempZero<float> tp(self, 0xD0), ap(self, 0xD4);
    o_TrailerConfigure_AskPurchaseSure(self, mi);
}

// TrailerConfigure.<_Save>d__96 (this @0x20, priceMultiplier @0x28): Balance -= price * priceMultiplier.
static bool (*o_TrailerSave_MoveNext)(void*, const MethodInfo*);
static bool h_TrailerSave_MoveNext(void* co, const MethodInfo* mi) {
    if (!f_free_shop.on || !co) return o_TrailerSave_MoveNext(co, mi);
    TempZero<float> mult(co, 0x28);
    return o_TrailerSave_MoveNext(co, mi);
}

// GarageManager (TSE.Garage.Price @0x24):
static const BytePatch kFreeGarage[] = {
    // UpgradeGarage: closure.upgradePrice(0x10) = Price * (level*k + c) -> 0. Used by the balance check,
    // the price label, <UpgradeGarage>b__2 (Balance -= upgradePrice) and b__3 (ad-discount price string
    // that TSAdManager.OnRewardedAdSuccess parses and subtracts).
    {0x1b89088, "801200bd", "9f1200b9"},  // str s0,[x20,#0x10] -> str wzr,[x20,#0x10]
    // AskBuyNewGarage: "Price > Balance -> not enough money" test.
    {0x1b8b128, "082440bd", "e803271e"},  // ldr s8,[x0,#0x24] -> fmov s8,wzr
    // BuyNewGarage(priceMultiplier): Balance -= Price*mult; MoneySpent += Price*mult.
    {0x1b8b4ac, "812640bd", "e103271e"},  // ldr s1,[x20,#0x24] -> fmov s1,wzr
    {0x1b8b4d8, "812640bd", "e103271e"},  // ldr s1,[x20,#0x24] -> fmov s1,wzr
    // NewGarageSelected: the two MoneyStr(Price) labels of the new-garage panel.
    {0x1b8aa4c, "802640bd", "e003271e"},  // ldr s0,[x20,#0x24] -> fmov s0,wzr
    {0x1b8ab7c, "802640bd", "e003271e"},  // ldr s0,[x20,#0x24] -> fmov s0,wzr
};

static void free_shop_changed(Feature* f) {
    for (const auto& p : kFreeGarage) patch_apply(p, f->on);
}

// ================================================================ free travel
// Ferry.ferryPrice (int @0x20). OnEnable prints it via Int32.ToString(&ferryPrice) -> zero it for the call.
static void (*o_Ferry_OnEnable)(void*, const MethodInfo*);
static void h_Ferry_OnEnable(void* self, const MethodInfo* mi) {
    if (!f_free_travel.on || !self) return o_Ferry_OnEnable(self, mi);
    TempZero<int32_t> price(self, 0x20);
    o_Ferry_OnEnable(self, mi);
}

static const BytePatch kFreeTravel[] = {
    // Ferry.SetFerryAction: Balance -= (float)ferryPrice; MoneySpent += (float)ferryPrice.
    // ldr s1,[x19,#0x20] -> fmov s1,wzr (following scvtf s1,s1 turns 0 into 0.0f).
    {0x1928914, "612240bd", "e103271e"},
    {0x1928930, "612240bd", "e103271e"},
    // UIButtons.TowTruck(false): Balance -= (float)towPrice(0xBC); MoneySpent += (float)towPrice.
    {0x193b7f0, "61be40bd", "e103271e"},  // ldr s1,[x19,#0xbc] -> fmov s1,wzr
    {0x193b80c, "61be40bd", "e103271e"},  // ldr s1,[x19,#0xbc] -> fmov s1,wzr
    // DoorAnimiation.OnTriggerEnter (toll gate): s8 = (float)(toll ?: 70); message "MoneyStr(s8)";
    // tail-call Prefs.set_Balance(get_Balance() - s8). tollPaid (0x66) is still set, gate still opens.
    {0x1b21098, "0801221e", "e803271e"},  // scvtf s8,w8 -> fmov s8,wzr (message shows 0)
    {0x1b21104, "43fe0014", "c0035fd6"},  // b Prefs::set_Balance -> ret (epilogue already done)
};

static void free_travel_changed(Feature* f) {
    for (const auto& p : kFreeTravel) patch_apply(p, f->on);
}

INSTALL(install_shop) {
    HOOK(0x18e797c, h_Configure_AskPurchaseSure, o_Configure_AskPurchaseSure);
    HOOK(0x1906e60, h_ConfigureSave_MoveNext, o_ConfigureSave_MoveNext);
    HOOK(0x1913358, h_TrailerConfigure_AskPurchaseSure, o_TrailerConfigure_AskPurchaseSure);
    HOOK(0x19d0280, h_TrailerSave_MoveNext, o_TrailerSave_MoveNext);
    HOOK(0x19283d0, h_Ferry_OnEnable, o_Ferry_OnEnable);
    free_shop_changed(&f_free_shop);
    free_travel_changed(&f_free_travel);
}
