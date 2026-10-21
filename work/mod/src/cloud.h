// Cloud-save bridge: the game's CloudSave buttons and the menu's backups reach com.toemod.CloudController.
//
// Unity keeps PlayerPrefs in memory and writes them to SharedPreferences only when it saves, so every
// backup taken while the game runs is preceded by UnityEngine.PlayerPrefs.Save() on the Unity thread.
#pragma once

// Native.onGameCloud(int) actions; CloudController.ACTION_* must match.
enum CloudAction : int {
    CLOUD_SAVE = 1,     // the game's Save button; PlayerPrefs were just saved
    CLOUD_LOAD = 2,     // the game's Load button
    CLOUD_FLUSHED = 3,  // PlayerPrefs saved for a cloud_request_flush()
};

// main.cpp: calls Native.onGameCloud(action) on the calling thread. True when Java took the action;
// false when the bridge is missing, Java is not ready or it threw (the game's own code should run).
bool cloud_upcall(int action);

// cloud.cpp, any thread: the next EventSystem.Update saves PlayerPrefs and reports CLOUD_FLUSHED.
void cloud_request_flush();
