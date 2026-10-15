// Driver fatigue: keeps the driver fully rested without touching the saved
// last-sleep time.
//
// Fatigue is computed only in UIHandler.<UpdateContitions>d__54::MoveNext
// (vaddr 0x195a77c), a 1 s coroutine loop:
//   awakeTime(0x148) = sky(0xF0).Cycle.Ticks - lastSleep(0x138);
//   if (!sleeping(0x154)) {
//     fatigue(0x140) = 1 - FatigueLevel(0x78).Evaluate(awakeTime / 5.8248e11f);  // 1 = rested
//     fatigueImage(0xB0).fillAmount = fatigue;
//     if (!isEditor && fatigue < 0.1f) {                    // blackout + yawn
//       if (lastBlackOut == nextBlackOut) BlackOut();
//       lastBlackOut++;
//     }
//   }
//   if (!sleepMessageShown && fatigue < 0.3f && UIManager.AllowMessagesAfterMapLoad())
//     { sleepMessageShown = true; ShowSleepMessage(); }
// Patching the `fsub` that produces fatigue to `fmov s0, s1` (s1 == 1.0) pins
// fatigue at 1.0, so the bar stays full and the blackout/sleep-message
// branches never fire. lastSleep is never modified, so Sleep still uses the
// game's real awake time, and turning the feature off resumes vanilla fatigue.
#include "mod.h"

static void no_fatigue_changed(Feature* f);
FEATURE(f_no_fatigue, "no_fatigue", "Driver", "No driver fatigue", F_TOGGLE, false, 0, 0, 0, nullptr,
        no_fatigue_changed);

static const BytePatch kFatigue{0x195a82c, "2038201e", "2040201e"};  // fsub s0,s1,s0 -> fmov s0,s1

static void no_fatigue_changed(Feature* f) {
    if (!patch_apply(kFatigue, f->on)) mlog("no_fatigue: patch %s failed", f->on ? "apply" : "restore");
}

INSTALL(install_fatigue) { no_fatigue_changed(&f_no_fatigue); }
