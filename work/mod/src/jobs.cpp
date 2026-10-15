// Job payout / penalties / XP multipliers and rewarded-ad short-circuit.
//
// Payout (JobComplete::SetUI @0x1b8fe60, called once from JobComplete::Completed):
//   income   = Job.Income(0x78) * clamp(Job.BonusMultiplier(0x7C), 1, 10)   -> _income (0x48)
//   left     = Job.Time(0x80) - TotalHours(now - Job.StartTime)
//   dmgPen   = 0.3 * income * clamp01(Prefs.TrailerDamage)
//   timePen  = left > 0 ? 0 : 0.3 * income * min(0.5 * |left|, 1)
//   _totalPenalties (0x4C) = dmgPen + timePen
//   Prefs.Balance += income - _totalPenalties; Prefs.MoneyEarnt += same; then SetXP().
// XP (JobComplete::SetXP @0x1b908f0): fixed +800 (xpToAdd 0x70), Prefs.Xp = Xp + 800.
// Rewarded ads: every flow calls ServiceManager.CanShowRewardedAd() and then
// ServiceManager.ShowRewardedAd(VideoAdType name, data). The latter already has a
// "config missing / enableAds off" path that sets rewardedAdSuccess=true, which the next
// ServiceManager.Update dispatches to OnRewardedAdSuccess(tag,data) -> TSAdManager reward.
#include "mod.h"

static void no_pen_changed(Feature* f);
static void bonus_changed(Feature* f);

FEATURE(f_no_penalties, "no_penalties", "Jobs", "No delivery penalties", F_TOGGLE, false, 0, 0, 0, nullptr,
        no_pen_changed);
FEATURE(f_pay_mult, "pay_mult", "Jobs", "Job income multiplier", F_TOGGLE_FLOAT, false, 2, 1, 20, nullptr, nullptr);
FEATURE(f_xp_mult, "xp_mult", "Jobs", "Job XP multiplier", F_TOGGLE_FLOAT, false, 2, 1, 20, nullptr, nullptr);
FEATURE(f_bonus_no_ad, "bonus_no_ad", "Jobs", "Ad rewards without ads", F_TOGGLE, false, 0, 0, 0, nullptr,
        bonus_changed);

static float clamp_mult(float v) { return v < 1.f ? 1.f : (v > 20.f ? 20.f : v); }

// ---------------------------------------------------------------- no_penalties
// Both penalty terms in SetUI are zeroed at their source, so the displayed values,
// _totalPenalties, Balance and MoneyEarnt stay consistent (UI shows "-" for both).
static const BytePatch kNoPenalties[] = {
    {0x1b90130, "2b39201e", "0b102e1e"},  // fsub s11,s9,s0 -> fmov s11,#1.0 (time left > 0: on time)
    {0x1b90174, "6908211e", "e903271e"},  // fmul s9,s3,s1  -> fmov s9,wzr   (damage penalty 0)
};

static void no_pen_changed(Feature* f) {
    for (const auto& p : kNoPenalties) patch_apply(p, f->on);
}

// ---------------------------------------------------------------- pay_mult
// Job object comes fresh from Prefs.get_CurrentJob (JSON) and is dropped right after
// (set_CurrentJob(null)), so scaling Income for the duration of SetUI never reaches the save.
static void (*o_SetUI)(void* self, void* job, const MethodInfo* mi);
static void h_SetUI(void* self, void* job, const MethodInfo* mi) {
    if (!f_pay_mult.on || !job) {
        o_SetUI(self, job, mi);
        return;
    }
    float& income = FIELD(float, job, 0x78);
    const float saved = income;
    income = saved * clamp_mult(f_pay_mult.value);
    o_SetUI(self, job, mi);
    income = saved;
}

// ---------------------------------------------------------------- xp_mult
static void (*o_SetXP)(void* self, const MethodInfo* mi);
static void h_SetXP(void* self, const MethodInfo* mi) {
    o_SetXP(self, mi);
    if (!f_xp_mult.on || !self) return;
    const float m = clamp_mult(f_xp_mult.value);
    if (m == 1.f) return;
    const float add = FIELD(float, self, 0x70);    // xpToAdd (800)
    const float start = FIELD(float, self, 0x74);  // startXp (Prefs.Xp before this job)
    const float gain = add * m;
    double total = (double)start + (double)gain + 0.5;
    if (total > 2147483647.0) total = 2147483647.0;
    const int new_xp = (int)total;
    FIELD(float, self, 0x70) = gain;           // xpToAdd: bar sound curve normalisation
    FIELD(float, self, 0x68) = (float)new_xp;  // newXp: Update() animates currentXp toward it, LevelUp() per threshold
    ((void (*)(int, const MethodInfo*))VA(0x1b603f0))(new_xp, nullptr);  // Prefs::set_Xp
}

// ---------------------------------------------------------------- bonus_no_ad
static bool (*o_CanShowRewardedAd)(void* self, const MethodInfo* mi);
static bool h_CanShowRewardedAd(void* self, const MethodInfo* mi) {
    return f_bonus_no_ad.on ? true : o_CanShowRewardedAd(self, mi);
}

// ShowRewardedAd: after storing rewardTag(0x1C8)/rewardData(0x1D0),
//   tbz w0,#0,0x38716a8  (if !config -> rewardedAdSuccess = true; return)
// becomes an unconditional branch to that grant path.
static const BytePatch kGrantReward = {0x38715e8, "00060036", "30000014"};

static void bonus_changed(Feature* f) { patch_apply(kGrantReward, f->on); }

// ---------------------------------------------------------------- install
INSTALL(install_jobs) {
    HOOK(0x1b8fe60, h_SetUI, o_SetUI);                          // JobComplete::SetUI(Job)
    HOOK(0x1b908f0, h_SetXP, o_SetXP);                          // JobComplete::SetXP()
    HOOK(0x38712d4, h_CanShowRewardedAd, o_CanShowRewardedAd);  // ServiceManager::CanShowRewardedAd()
    no_pen_changed(&f_no_penalties);
    bonus_changed(&f_bonus_no_ad);
}
