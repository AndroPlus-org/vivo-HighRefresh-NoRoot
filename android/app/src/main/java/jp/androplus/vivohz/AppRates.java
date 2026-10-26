package jp.androplus.vivohz;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-app overrides of the pinned rate. A package with no entry follows the
 * pin; {@link #AUTO} withdraws our vote while it is in front, so the app and
 * vivo's own per-app setting decide; any other value is pinned instead.
 */
final class AppRates {
    static final String PREFS = "apps";
    static final int AUTO = 0;
    /** No entry: follow the pinned rate. */
    static final int DEFAULT = -1;

    private AppRates() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int get(Context context, String pkg) {
        return prefs(context).getInt(pkg, DEFAULT);
    }

    static void set(Context context, String pkg, int fps) {
        SharedPreferences.Editor e = prefs(context).edit();
        if (fps == DEFAULT) e.remove(pkg);
        else e.putInt(pkg, fps);
        e.apply();
    }

    /** The rate to vote while {@code pkg} is in front, or {@link #AUTO} to vote none. */
    static int target(Context context, String pkg) {
        int rule = get(context, pkg);
        if (rule != DEFAULT) return rule;
        return context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getInt(MainActivity.KEY_FPS, AUTO);
    }
}
