package jp.androplus.vivohz;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * Follows the app in front and re-votes when its per-app rate differs from
 * what is voted now. A pinned 165 Hz halves apps that cap themselves at 120
 * fps (they land on 82.5), and outranks vivo's own per-app setting, so those
 * apps get a rate of their own or no vote at all.
 *
 * Only window changes that belong to an activity count: the notification
 * shade, keyboards and toasts also raise TYPE_WINDOW_STATE_CHANGED, and the
 * app below them is still in front.
 */
public class AppSwitchService extends AccessibilityService {
    private String current;
    /** What we voted last; -1 until the first switch. */
    private int voted = -1;

    @Override
    protected void onServiceConnected() {
        current = null;
        voted = -1;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || !isActivity(pkg.toString(), cls.toString())) return;
        current = pkg.toString();
        apply();
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Votes for the app in front unless that is already voted. Votes made from
     * MainActivity bypass {@link #voted}, but this app has no rule of its own,
     * so they always match what it would vote here.
     */
    private void apply() {
        if (current == null) return;
        int fps = AppRates.target(this, current);
        if (fps == voted) return;
        if (fps > 0) Rms.vote(fps);
        else Rms.release();
        voted = fps;
    }

    private boolean isActivity(String pkg, String cls) {
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether the user has turned this service on in the accessibility settings. */
    static boolean enabled(Context context) {
        String on = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        ComponentName me = new ComponentName(context, AppSwitchService.class);
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(on);
        for (String s : split) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }
}
