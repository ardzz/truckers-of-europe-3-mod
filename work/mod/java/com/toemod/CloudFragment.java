package com.toemod;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Headless fragment that receives the document picker's and the storage permission's results inside
 * UnityPlayerActivity, so the mod needs no Activity or manifest entry of its own. Results go to the
 * process-wide {@link CloudController}, which also works when Android recreated this fragment.
 */
@SuppressWarnings("deprecation") // the platform Fragment: the injected code has no AndroidX
public final class CloudFragment extends android.app.Fragment {
    private static final String FRAGMENT_TAG = "toemod-cloud";
    private static final int REQ_OPEN = 0x7031;
    private static final int REQ_STORAGE = 0x7032;

    /** Public no-argument constructor: Android recreates fragments by class name. */
    public CloudFragment() {}

    /** Opens the system document picker; false when the device has none. */
    static boolean pick(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            attached(a).startActivityForResult(i, REQ_OPEN);
            return true;
        } catch (ActivityNotFoundException | IllegalStateException e) {
            Log.w(Menu.TAG, "cloud: no document picker", e);
            return false;
        }
    }

    /** Asks for the Android 6-9 storage permission that puts backups in the shared Download folder. */
    static void requestStorage(Activity a) {
        attached(a).requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    private static CloudFragment attached(Activity a) {
        android.app.FragmentManager fm = a.getFragmentManager();
        android.app.Fragment f = fm.findFragmentByTag(FRAGMENT_TAG);
        if (f instanceof CloudFragment) return (CloudFragment) f;
        CloudFragment created = new CloudFragment();
        fm.beginTransaction().add(created, FRAGMENT_TAG).commitAllowingStateLoss();
        fm.executePendingTransactions();
        return created;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_OPEN || getActivity() == null) return;
        CloudController.get(getActivity()).onPicked(resultCode == Activity.RESULT_OK && data != null ? data.getData() : null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_STORAGE || getActivity() == null) return;
        CloudController.get(getActivity()).onStorageResult(
                grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
    }
}
