package jp.androplus.vivohz;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The app in front, as reported by whichever watcher the user picked, and the
 * vote that goes with it. All watchers run in this one process.
 */
final class FrontApp {
    static final String KEY_WATCH = "watch";
    /** Accessibility service: event-driven, kept alive by the system. */
    static final String WATCH_ACCESSIBILITY = "accessibility";
    /** Usage access: polled from {@link WatchService}. */
    static final String WATCH_USAGE = "usage";
    /** Shizuku: an activity-manager process observer, registered as shell. */
    static final String WATCH_SHIZUKU = "shizuku";

    private static String current;
    /** What we voted last; -1 until the first switch. */
    private static int voted = -1;

    private FrontApp() {
    }

    static String watch(Context context) {
        return prefs(context).getString(KEY_WATCH, WATCH_ACCESSIBILITY);
    }

    static void setWatch(Context context, String watch) {
        prefs(context).edit().putString(KEY_WATCH, watch).apply();
        reset();
    }

    /** Forget what was voted, so the next switch votes again. */
    static synchronized void reset() {
        current = null;
        voted = -1;
    }

    /** Whether {@code pkg} is already the app in front, so a watcher can skip looking further. */
    static synchronized boolean isCurrent(String pkg) {
        return pkg.equals(current);
    }

    /**
     * {@code pkg} came to the front: vote its rate unless that is already
     * voted. Votes made from MainActivity bypass {@link #voted}, but this app
     * has no rule of its own, so they always match what it would vote here.
     */
    static synchronized void moved(Context context, String pkg) {
        if (pkg.equals(current)) return;
        current = pkg;
        int fps = AppRates.target(context, pkg);
        if (fps == voted) return;
        if (fps > 0) Rms.vote(context, fps);
        else Rms.release();
        voted = fps;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }
}
