package jp.androplus.vivohz;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * vivo's "rms" binder (com.vivo.rms.IRM), spoken the way the framework's own
 * com.vivo.rms.common.RefreshRateRequester does: raw Parcels, no interface
 * token. Same layout as the command-line RmsVote in the repo root.
 *
 * The server (vivo-services.jar, RefreshRateAdjuster) checks no uid. It looks
 * `caller` up among AMS processes and treats caller <= 0 as itself, so the
 * vote below is owned by system_server and outlives this app's process.
 */
final class Rms {
    private static final String TAG = "VivoHz";
    private static final int TX_RELEASE = 24, TX_GET = 26, TX_ACQUIRE_V2 = 31;

    /**
     * system_server numbers its own votes from 1 under caller 0; stay clear.
     * Votes use this handle or the next one, see {@link #vote}.
     */
    static final long HANDLE = 900001;
    /** The handle holding our vote, in {@link MainActivity#PREFS}. */
    private static final String KEY_HANDLE = "handle";
    /**
     * FLAG_USE_PRIVATE_FPS | USE_VSYNC_DFPS | IGNORE_BRIGHTNESS. Unlike the
     * vendor's adb path, no FLAG_USE_OWN_CONFIG_BITS: with it and configBits 0
     * the server skips ConfigBitsManager and picks a panel mode with no bits,
     * which on the X300 is the full-PWM one whatever the user set.
     */
    private static final int FLAGS = 14;
    private static final int PRIORITY = 100;

    private Rms() {
    }

    static IBinder binder() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "rms");
        } catch (Throwable t) {
            Log.w(TAG, "getService(rms) failed", t);
            return null;
        }
    }

    /**
     * Votes {@code fps} in the FixRate scene. The server ignores an acquire
     * for a handle that already holds a vote, and a release sent just before
     * an acquire can land after it, so votes alternate between two handles:
     * the new one goes in first, then the old one is dropped.
     */
    static boolean vote(Context context, int fps) {
        IBinder b = binder();
        if (b == null) return false;
        SharedPreferences prefs = prefs(context);
        long old = prefs.getLong(KEY_HANDLE, HANDLE);
        long handle = old == HANDLE ? HANDLE + 1 : HANDLE;
        Parcel d = Parcel.obtain();
        try {
            d.writeLong(handle);
            d.writeString("FixRate");
            d.writeString("VivoHz");
            d.writeInt(-1);        // reqDisplay: all
            d.writeInt(0);         // reqResolution
            d.writeInt(fps);
            d.writeInt(0);         // reqDFps
            d.writeInt(0);         // reqConfigBits
            d.writeInt(0);         // reqStates
            d.writeInt(PRIORITY);
            d.writeInt(0);         // duration
            d.writeInt(0);         // caller: system_server
            d.writeInt(0);         // client
            d.writeInt(FLAGS);
            d.writeInt(0);         // arg1
            d.writeInt(0);         // arg2
            if (!b.transact(TX_ACQUIRE_V2, d, null, IBinder.FLAG_ONEWAY)) return false;
        } catch (Throwable t) {
            Log.w(TAG, "vote failed", t);
            return false;
        } finally {
            d.recycle();
        }
        prefs.edit().putLong(KEY_HANDLE, handle).apply();
        release(b, old);
        return true;
    }

    /** Drops our vote, whichever handle holds it. */
    static boolean release() {
        IBinder b = binder();
        if (b == null) return false;
        return release(b, HANDLE) & release(b, HANDLE + 1);
    }

    private static boolean release(IBinder b, long handle) {
        Parcel d = Parcel.obtain();
        try {
            d.writeInt(0);
            d.writeLong(handle);
            return b.transact(TX_RELEASE, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            Log.w(TAG, "release failed", t);
            return false;
        } finally {
            d.recycle();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** The rate the vendor has applied, or 0 when the service can't be reached. */
    static float rate() {
        IBinder b = binder();
        if (b == null) return 0;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            b.transact(TX_GET, d, r, 0);
            r.readException();
            return r.readFloat();
        } catch (Throwable t) {
            Log.w(TAG, "rate failed", t);
            return 0;
        } finally {
            d.recycle();
            r.recycle();
        }
    }
}
