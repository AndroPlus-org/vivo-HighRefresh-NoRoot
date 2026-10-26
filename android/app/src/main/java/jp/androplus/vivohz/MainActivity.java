package jp.androplus.vivohz;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.TreeSet;

public class MainActivity extends Activity {
    static final String PREFS = "vote";
    static final String KEY_FPS = "fps";
    static final String KEY_ON_BOOT = "on_boot";

    /** How often the live rate is read while the screen is in front. */
    private static final long LIVE_INTERVAL_MS = 500;

    /**
     * vivo / iQOO "auto-start" managers, tried in order. Without auto-start the
     * system may withhold BOOT_COMPLETED, and the pin is not put back.
     */
    private static final ComponentName[] AUTOSTART_SCREENS = {
            new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            new ComponentName("com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            new ComponentName("com.iqoo.secure",
                    "com.iqoo.secure.safeguard.PurviewTabActivity"),
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable live = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, LIVE_INTERVAL_MS);
        }
    };
    private TextView liveRate;
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Our own title instead of the action bar: edge-to-edge (targetSdk 35+)
        // lets content slide under the bar, notably in landscape.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        liveRate = new TextView(this);
        liveRate.setTextSize(40);
        liveRate.setPadding(0, pad / 2, 0, 0);
        root.addView(liveRate);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 0, 0, pad / 2);
        root.addView(status);

        boolean reachable = Rms.binder() != null;
        if (!reachable) {
            TextView err = new TextView(this);
            err.setText(R.string.unreachable);
            root.addView(err);
        }

        for (int fps : panelRates(getDisplay())) {
            Button b = button(getString(R.string.rate_button, fps));
            b.setEnabled(reachable);
            b.setOnClickListener(v -> {
                Rms.vote(fps);
                prefs.edit().putInt(KEY_FPS, fps).apply();
                refresh();
            });
            root.addView(b);
        }

        Button release = button(getString(R.string.release));
        release.setEnabled(reachable);
        release.setOnClickListener(v -> {
            Rms.release();
            prefs.edit().remove(KEY_FPS).apply();
            refresh();
        });
        root.addView(release);

        Button perApp = button(getString(R.string.app_rates));
        perApp.setEnabled(reachable);
        perApp.setOnClickListener(v -> startActivity(new Intent(this, AppRatesActivity.class)));
        root.addView(perApp);

        CheckBox onBoot = new CheckBox(this);
        onBoot.setText(R.string.apply_on_boot);
        onBoot.setChecked(prefs.getBoolean(KEY_ON_BOOT, true));
        onBoot.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(KEY_ON_BOOT, checked).apply());
        root.addView(onBoot);

        TextView hint = new TextView(this);
        hint.setText(R.string.autostart_hint);
        root.addView(hint);

        Button autostart = button(getString(R.string.open_autostart));
        autostart.setOnClickListener(v -> openAutostart());
        root.addView(autostart);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(live);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(live);
    }

    private Button button(String text) {
        Button b = new Button(this);
        // The device theme upper-cases button text, which turns "Hz" into "HZ".
        b.setAllCaps(false);
        b.setText(text);
        return b;
    }

    /** The panel's own modes, highest first; only those can be pinned. */
    static TreeSet<Integer> panelRates(Display display) {
        TreeSet<Integer> rates = new TreeSet<>((a, b) -> b - a);
        if (display != null) {
            for (Display.Mode m : display.getSupportedModes()) {
                rates.add(Math.round(m.getRefreshRate()));
            }
        }
        return rates;
    }

    private void refresh() {
        liveRate.setText(getString(R.string.live_rate, Rms.rate()));
        StringBuilder sb = new StringBuilder();
        int fps = prefs.getInt(KEY_FPS, 0);
        sb.append(fps > 0 ? getString(R.string.status_pinned, fps) : getString(R.string.status_not_pinned));
        String reason = activeReason();
        if (reason != null) sb.append('\n').append(getString(R.string.status_reason, reason));
        status.setText(sb);
    }

    /** What the vendor says it applies, e.g. "VivoHz|144" or "reqFps:<activity>|120". */
    private String activeReason() {
        try {
            return Settings.Secure.getString(getContentResolver(), "vivo_rms_active_request_reason");
        } catch (Throwable t) {
            // Non-public keys can be refused to apps on newer targetSdk.
            return null;
        }
    }

    private void openAutostart() {
        for (ComponentName screen : AUTOSTART_SCREENS) {
            try {
                startActivity(new Intent().setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Throwable ignored) {
                // Not on this build, or not exported: try the next one.
            }
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }
}
