package jp.androplus.vivohz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** The vendor keeps votes in memory only, so put the pinned rate back after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        int fps = prefs.getInt(MainActivity.KEY_FPS, 0);
        if (fps > 0 && prefs.getBoolean(MainActivity.KEY_ON_BOOT, true)) {
            Rms.vote(fps);
        }
        // The accessibility watcher comes back on its own; the others need their service.
        WatchService.sync(context);
    }
}
