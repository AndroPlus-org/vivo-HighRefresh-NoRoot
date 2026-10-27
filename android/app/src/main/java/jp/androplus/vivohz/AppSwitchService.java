package jp.androplus.vivohz;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * Follows the app in front for {@link FrontApp}, when the user picked the
 * accessibility watcher. A pinned 165 Hz halves apps that cap themselves at 120
 * fps (they land on 82.5), and outranks vivo's own per-app setting, so those
 * apps get a rate of their own or no vote at all.
 *
 * Only window changes that belong to an activity count: the notification
 * shade, keyboards and toasts also raise TYPE_WINDOW_STATE_CHANGED, and the
 * app below them is still in front.
 *
 * Closing a floating window hands focus back to the app below without a state
 * change, so window-list changes also count: the focused application window
 * is then the app in front.
 */
public class AppSwitchService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        FrontApp.reset();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        // Left on while another watcher was picked: stay out of its way.
        if (!FrontApp.WATCH_ACCESSIBILITY.equals(FrontApp.watch(this))) return;
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if ((event.getWindowChanges() & WINDOW_MOVES) == 0) return;
            String pkg = focusedApp();
            if (pkg != null) FrontApp.moved(this, pkg);
            return;
        }
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || FrontApp.isCurrent(pkg.toString())
                || !isActivity(pkg.toString(), cls.toString())) return;
        FrontApp.moved(this, pkg.toString());
    }

    private static final int WINDOW_MOVES = AccessibilityEvent.WINDOWS_CHANGE_ADDED
            | AccessibilityEvent.WINDOWS_CHANGE_REMOVED
            | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
            | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE;

    /**
     * The focused application window last looked up, and its package. Reading
     * a window's root asks that app's UI thread, so a game would stall a frame
     * each time an overlay, toast or notification comes and goes over it; a
     * window keeps its id, so it is asked once.
     */
    private int focusedId = -1;
    private String focusedPkg;

    /** The package of the focused application window, or null while none has focus. */
    private String focusedApp() {
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isFocused()) continue;
            if (w.getId() == focusedId) return focusedPkg;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            if (pkg == null) return null;
            focusedId = w.getId();
            focusedPkg = pkg.toString();
            return focusedPkg;
        }
        return null;
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
