package jp.androplus.vivohz;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Follows the app in front without the accessibility service, for users who
 * would rather not turn one on. Both ways live in this app's process, so it
 * runs as a foreground service to keep vivo from killing it.
 *
 * Usage access polls UsageStatsManager while the screen is on. Shizuku
 * registers a process observer with the activity manager as shell (which
 * holds SET_ACTIVITY_WATCHER); the observer is our own binder, so it keeps
 * working after Shizuku stops, until this process dies.
 *
 * Both keep every app still in front, not just the last to arrive: closing a
 * floating window brings no new arrival, only the floating app leaving, and
 * the app below is then the latest one left.
 */
public class WatchService extends Service {
    private static final String TAG = "VivoHz";
    private static final String CHANNEL = "watch";
    private static final int NOTIFICATION_ID = 1;
    private static final long POLL_MS = 1000;
    /**
     * Events are stamped when they happen but can be recorded a little later,
     * so one stamped just before a query may only show up after it. Each poll
     * reaches back this far; replaying an event already seen changes nothing.
     */
    private static final long OVERLAP_MS = 5000;
    private static final String OBSERVER = "android.app.IProcessObserver";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String watch;

    // Usage access.
    private long since;
    /** Resumed activities ("pkg/cls"), latest last. */
    private final ArrayList<String> resumed = new ArrayList<>();
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollUsage();
            handler.postDelayed(this, POLL_MS);
        }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(poll);
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) handler.post(poll);
        }
    };

    // Shizuku.
    private Object observerProxy;
    /**
     * IProcessObserver.onForegroundActivitiesChanged(int pid, int uid, boolean):
     * 1 until onProcessStarted was put ahead of it (2 on Android 15), so read
     * from the framework's own stub.
     */
    private volatile int txForegroundActivities = -1;
    /** IProcessObserver.onProcessDied(int pid, int uid). */
    private volatile int txProcessDied = -1;
    private final Binder observer = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != txForegroundActivities && code != txProcessDied) return true;
            data.enforceInterface(OBSERVER);
            int pid = data.readInt();
            int uid = data.readInt();
            boolean foreground = code == txForegroundActivities && data.readInt() != 0;
            handler.post(() -> foregroundChanged(pid, uid, foreground));
            return true;
        }
    };
    /** Processes with activities in front, pid to uid, latest last. Main thread only. */
    private final LinkedHashMap<Integer, Integer> foregroundPids = new LinkedHashMap<>();
    private final Shizuku.OnBinderReceivedListener shizukuUp = this::registerObserver;

    /** Starts or stops this service to match the watcher the user picked. */
    static void sync(Context context) {
        String watch = FrontApp.watch(context);
        Intent intent = new Intent(context, WatchService.class);
        if (FrontApp.WATCH_USAGE.equals(watch) || FrontApp.WATCH_SHIZUKU.equals(watch)) {
            context.startForegroundService(intent);
        } else {
            context.stopService(intent);
        }
    }

    static boolean usageGranted(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static boolean shizukuGranted() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        String want = FrontApp.watch(this);
        if (!want.equals(watch)) {
            stopWatching();
            watch = want;
            FrontApp.reset();
            if (FrontApp.WATCH_USAGE.equals(watch)) startUsage();
            else if (FrontApp.WATCH_SHIZUKU.equals(watch)) Shizuku.addBinderReceivedListenerSticky(shizukuUp, handler);
            else stopSelf();
        } else if (FrontApp.WATCH_SHIZUKU.equals(watch)) {
            // Started again, e.g. after the Shizuku permission was granted.
            registerObserver();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopWatching();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopWatching() {
        if (FrontApp.WATCH_USAGE.equals(watch)) {
            handler.removeCallbacks(poll);
            unregisterReceiver(screen);
        } else if (FrontApp.WATCH_SHIZUKU.equals(watch)) {
            Shizuku.removeBinderReceivedListener(shizukuUp);
            unregisterObserver();
        }
        watch = null;
    }

    private void startUsage() {
        since = System.currentTimeMillis() - 10_000;
        resumed.clear();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        if (getSystemService(PowerManager.class).isInteractive()) handler.post(poll);
    }

    private void pollUsage() {
        long now = System.currentTimeMillis();
        UsageEvents events = getSystemService(UsageStatsManager.class)
                .queryEvents(since - OVERLAP_MS, now);
        if (events == null) return;
        boolean changed = false;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.getNextEvent(e)) {
            int type = e.getEventType();
            String key = e.getPackageName() + "/" + e.getClassName();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                resumed.remove(key);
                resumed.add(key);
                changed = true;
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                changed |= resumed.remove(key);
            }
        }
        since = now;
        if (!changed || resumed.isEmpty()) return;
        String front = resumed.get(resumed.size() - 1);
        FrontApp.moved(this, front.substring(0, front.indexOf('/')));
    }

    private void registerObserver() {
        if (observerProxy != null || !shizukuGranted()) return;
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
            Class<?> stub = Class.forName(OBSERVER + "$Stub");
            Field tx = stub.getDeclaredField("TRANSACTION_onForegroundActivitiesChanged");
            tx.setAccessible(true);
            txForegroundActivities = tx.getInt(null);
            Field died = stub.getDeclaredField("TRANSACTION_onProcessDied");
            died.setAccessible(true);
            txProcessDied = died.getInt(null);
            Object proxy = stub.getMethod("asInterface", IBinder.class).invoke(null, observer);
            foregroundPids.clear();
            invokeAm("registerProcessObserver", proxy);
            observerProxy = proxy;
        } catch (Throwable t) {
            Log.w(TAG, "registerProcessObserver failed", t);
        }
    }

    private void unregisterObserver() {
        if (observerProxy == null) return;
        try {
            invokeAm("unregisterProcessObserver", observerProxy);
        } catch (Throwable t) {
            // Shizuku is gone; the observer goes with this process.
            Log.w(TAG, "unregisterProcessObserver failed", t);
        }
        observerProxy = null;
    }

    /** Calls IActivityManager.{@code name}(IProcessObserver) as shell, through Shizuku. */
    private static void invokeAm(String name, Object proxy) throws Exception {
        IBinder am = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity"));
        Object iam = Class.forName("android.app.IActivityManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, am);
        Method m = iam.getClass().getMethod(name, Class.forName(OBSERVER));
        m.invoke(iam, proxy);
    }

    /**
     * An app leaving the front is usually followed by the next one arriving,
     * which then wins. When nothing arrives, as when a floating window closes,
     * the latest process still in front is the app in front.
     */
    private void foregroundChanged(int pid, int uid, boolean foreground) {
        boolean was = foregroundPids.remove(pid) != null;
        if (foreground) {
            foregroundPids.put(pid, uid);
            foregroundUid(uid);
            return;
        }
        if (!was) return;
        Integer latest = null;
        for (Integer u : foregroundPids.values()) latest = u;
        if (latest != null) foregroundUid(latest);
    }

    /** Shared uids map to several packages; prefer one with a launcher entry. */
    private void foregroundUid(int uid) {
        if (!FrontApp.WATCH_SHIZUKU.equals(watch)) return;
        PackageManager pm = getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return;
        String pick = pkgs[0];
        for (String p : pkgs) {
            if (pm.getLaunchIntentForPackage(p) != null) {
                pick = p;
                break;
            }
        }
        FrontApp.moved(this, pick);
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.watch_channel), NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, AppRatesActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentTitle(getString(R.string.watch_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
