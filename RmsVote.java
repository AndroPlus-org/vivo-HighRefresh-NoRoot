import java.lang.reflect.Method;

/**
 * Talks to vivo's "rms" binder (com.vivo.rms.IRM) the way the framework's own
 * com.vivo.rms.common.RefreshRateRequester does: raw Parcels with NO interface
 * token. `service call` always prepends one, which shifts every argument, so
 * this has to run through app_process instead.
 *
 *   get                     current refresh rate (tx 26)
 *   bits <fps>              config bits the vendor maps <fps> to (tx 32)
 *   hold <fps> [opts]       vote, print the rate as it changes, release on exit
 *   set  <fps> [opts]       vote and exit; the vote stays until released
 *   release <caller> <handle>  withdraw a vote left by `set` (tx 24)
 *
 * The server (vivo-services.jar, com.vivo.services.rrm.RefreshRateAdjuster)
 * checks no uid, but looks `caller` up among the processes AMS tracks and drops
 * the vote when it isn't one. app_process isn't, so its own pid is refused.
 * caller <= 0 makes the server stand in for itself (system_server), which is
 * what the vendor's own `dumpsys rms --display --set-fps` does.
 *
 * opts: --caller <pid>  (default 0: system_server; the vote then outlives us)
 *       --scene <name>  (default FixRate, the scene the vendor's adb path uses)
 *       --prio <n>      priority inside the scene, -100..100 (default 100)
 *       --dur <n>       duration as the vendor reads it (default 0)
 *       --flags <n>     RefreshRateRequester FLAG_* bits (default 14: as adb
 *                       minus USE_OWN_CONFIG_BITS, so the server picks the
 *                       panel mode bits, e.g. honouring the PWM setting)
 *       --display <n>   display id (default -1: all)
 *       --handle <n>    vote handle (default 900001; system_server numbers its
 *                       own from 1 under the same caller, so stay clear)
 *       --v1            use tx 23 (acquireRefreshRate) instead of tx 31 (V2)
 */
public class RmsVote {
    static final int TX_ACQUIRE = 23, TX_RELEASE = 24, TX_GET = 26, TX_ACQUIRE_V2 = 31, TX_BITS = 32;
    static final int FLAG_ONEWAY = 1;
    static final int FLAG_IGNORE_CLIENT_DEATH = 16;

    static Object rms;
    static Class<?> parcelCls;
    static Method obtain, recycle, writeInt, writeLong, writeString, readException, readInt, readFloat, transact;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        init();
        switch (args[0]) {
            case "get":
                System.out.println("rate=" + getRate());
                break;
            case "bits":
                System.out.println("bits=0x" + Integer.toHexString(getBits(Integer.parseInt(args[1]))));
                break;
            case "hold":
            case "set":
                vote(args);
                break;
            case "release":
                release(Integer.parseInt(args[1]), Long.parseLong(args[2]));
                System.out.println("released pid=" + args[1] + " handle=" + args[2]);
                break;
            default:
                usage();
        }
    }

    static void usage() {
        System.out.println("usage: RmsVote get | bits <fps> | hold <fps> [opts] | set <fps> [opts] | release <pid> <handle>");
    }

    static void init() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        rms = sm.getMethod("checkService", String.class).invoke(null, "rms");
        if (rms == null) throw new IllegalStateException("rms service not found");
        parcelCls = Class.forName("android.os.Parcel");
        obtain = parcelCls.getMethod("obtain");
        recycle = parcelCls.getMethod("recycle");
        writeInt = parcelCls.getMethod("writeInt", int.class);
        writeLong = parcelCls.getMethod("writeLong", long.class);
        writeString = parcelCls.getMethod("writeString", String.class);
        readException = parcelCls.getMethod("readException");
        readInt = parcelCls.getMethod("readInt");
        readFloat = parcelCls.getMethod("readFloat");
        transact = Class.forName("android.os.IBinder")
            .getMethod("transact", int.class, parcelCls, parcelCls, int.class);
    }

    static void vote(String[] args) throws Exception {
        boolean hold = args[0].equals("hold");
        int fps = Integer.parseInt(args[1]);
        String scene = "FixRate";
        int prio = 100, dur = 0, flags = 14, display = -1, caller = 0;
        long handle = 900001;
        boolean v1 = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--scene": scene = args[++i]; break;
                case "--prio": prio = Integer.parseInt(args[++i]); break;
                case "--dur": dur = Integer.parseInt(args[++i]); break;
                case "--flags": flags = Integer.parseInt(args[++i]); break;
                case "--handle": handle = Long.parseLong(args[++i]); break;
                case "--caller": caller = Integer.parseInt(args[++i]); break;
                case "--display": display = Integer.parseInt(args[++i]); break;
                case "--v1": v1 = true; break;
                default: throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        // With our own pid as caller, `set` exits right away, so the vote
        // would have to outlive this process.
        if (!hold && caller > 0) flags |= FLAG_IGNORE_CLIENT_DEATH;
        final int pid = caller;

        System.out.println("before=" + getRate());
        if (v1) acquireV1(handle, scene, fps, prio, dur, pid);
        else acquireV2(handle, scene, display, fps, prio, dur, pid, flags);
        System.out.println("voted fps=" + fps + " scene=" + scene + " prio=" + prio
            + " flags=" + flags + " tx=" + (v1 ? TX_ACQUIRE : TX_ACQUIRE_V2)
            + " caller=" + pid + " handle=" + handle);
        Thread.sleep(500);
        System.out.println("after=" + getRate());

        if (!hold) {
            System.out.println("release with: RmsVote release " + pid + " " + handle);
            return;
        }
        final long h = handle;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { release(pid, h); System.out.println("released"); } catch (Exception ignored) { }
        }));
        float last = -1;
        while (true) {
            float r = getRate();
            if (r != last) { System.out.println("rate=" + r); last = r; }
            Thread.sleep(1000);
        }
    }

    // Field order mirrors RefreshRateRequester.RmsManager.acquireRefreshRate.
    static void acquireV1(long handle, String scene, int fps, int prio, int dur, int pid) throws Exception {
        Object d = obtain.invoke(null);
        try {
            writeLong.invoke(d, handle);
            writeString.invoke(d, scene);
            writeString.invoke(d, "RmsVote");
            writeInt.invoke(d, fps);
            writeInt.invoke(d, prio);
            writeInt.invoke(d, dur);
            writeInt.invoke(d, pid);   // caller
            writeInt.invoke(d, 0);     // client
            writeInt.invoke(d, 0);     // states
            writeInt.invoke(d, 0);     // dfps
            writeInt.invoke(d, 0);     // extra
            transact.invoke(rms, TX_ACQUIRE, d, null, FLAG_ONEWAY);
        } finally {
            recycle.invoke(d);
        }
    }

    // Field order mirrors RefreshRateRequester.RmsManager.acquireRefreshRateV2.
    static void acquireV2(long handle, String scene, int display, int fps, int prio, int dur, int pid, int flags) throws Exception {
        Object d = obtain.invoke(null);
        try {
            writeLong.invoke(d, handle);
            writeString.invoke(d, scene);
            writeString.invoke(d, "RmsVote");
            writeInt.invoke(d, display);
            writeInt.invoke(d, 0);     // reqResolution
            writeInt.invoke(d, fps);
            writeInt.invoke(d, 0);     // reqDFps
            writeInt.invoke(d, 0);     // reqConfigBits
            writeInt.invoke(d, 0);     // reqStates
            writeInt.invoke(d, prio);
            writeInt.invoke(d, dur);
            writeInt.invoke(d, pid);   // caller
            writeInt.invoke(d, 0);     // client
            writeInt.invoke(d, flags);
            writeInt.invoke(d, 0);     // arg1
            writeInt.invoke(d, 0);     // arg2
            transact.invoke(rms, TX_ACQUIRE_V2, d, null, FLAG_ONEWAY);
        } finally {
            recycle.invoke(d);
        }
    }

    static void release(int pid, long handle) throws Exception {
        Object d = obtain.invoke(null);
        try {
            writeInt.invoke(d, pid);
            writeLong.invoke(d, handle);
            transact.invoke(rms, TX_RELEASE, d, null, FLAG_ONEWAY);
        } finally {
            recycle.invoke(d);
        }
    }

    static float getRate() throws Exception {
        Object d = obtain.invoke(null), r = obtain.invoke(null);
        try {
            transact.invoke(rms, TX_GET, d, r, 0);
            readException.invoke(r);
            return (Float) readFloat.invoke(r);
        } finally {
            recycle.invoke(d); recycle.invoke(r);
        }
    }

    static int getBits(int fps) throws Exception {
        Object d = obtain.invoke(null), r = obtain.invoke(null);
        try {
            writeInt.invoke(d, fps);
            transact.invoke(rms, TX_BITS, d, r, 0);
            readException.invoke(r);
            return (Integer) readInt.invoke(r);
        } finally {
            recycle.invoke(d); recycle.invoke(r);
        }
    }
}
