package com.toemod;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.widget.Toast;

/**
 * Target of the PackageInstaller commit callback created by {@link Updater} (declared
 * non-exported, translucent). Opens Android's install confirmation or reports the final result,
 * then finishes without showing UI of its own.
 */
public final class UpdateInstallActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        boolean fresh = savedInstanceState == null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
        if (fresh && Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            try {
                handle(intent);
            } catch (RuntimeException e) {
                Log.e(Menu.TAG, "install callback failed", e);
            }
        }
        finish();
    }

    private void handle(Intent intent) {
        Updater updater = Updater.get(this);
        int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = confirmation(intent);
            if (confirm == null) {
                detail = "the installer sent no confirmation request";
            } else {
                try {
                    startActivity(confirm);
                    updater.onInstallStatus(session, status, null);
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.e(Menu.TAG, "cannot open the install confirmation", e);
                    detail = "cannot open the Android install prompt";
                }
            }
            updater.abandonSession(session);
            status = PackageInstaller.STATUS_FAILURE;
        }
        updater.onInstallStatus(session, status, detail);
        Toast.makeText(getApplicationContext(), "toemod: " + Updater.statusText(status, detail), Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation") // getParcelableExtra(String, Class) needs API 33
    private static Intent confirmation(Intent intent) {
        Parcelable p = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        return p instanceof Intent ? (Intent) p : null;
    }
}
