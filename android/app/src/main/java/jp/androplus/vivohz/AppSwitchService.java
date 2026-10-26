package jp.androplus.vivohz;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * Follows the app in front for {@link FrontApp}, when the user picked the
 * accessibility watcher. A pinned 165 Hz halves apps that cap themselves at 120
 * fps (they land on 82.5), and outranks vivo's own per-app setting, so those
 * apps get a rate of their own or no vote at all.
 *
 * Only window changes that belong to an activity count: the notification
 * shade, keyboards and toasts also raise TYPE_WINDOW_STATE_CHANGED, and the
 * app below them is still in front.
 */
public class AppSwitchService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        FrontApp.reset();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // Left on while another watcher was picked: stay out of its way.
        if (!FrontApp.WATCH_ACCESSIBILITY.equals(FrontApp.watch(this))) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || !isActivity(pkg.toString(), cls.toString())) return;
        FrontApp.moved(this, pkg.toString());
    }

    @Override
    public void onInterrupt() {
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
