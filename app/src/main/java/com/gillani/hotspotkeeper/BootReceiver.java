package com.gillani.hotspotkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the keeper after a reboot if it was on before. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && Log.prefs(c).getBoolean("enabled", false)) {
            try {
                c.startForegroundService(new Intent(c, KeeperService.class));
            } catch (Exception e) {
                Log.add(c, "Couldn't start after reboot: " + e.getMessage());
            }
        }
    }
}
