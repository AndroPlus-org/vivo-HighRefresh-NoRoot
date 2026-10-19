package jp.androplus.vivohz;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.TreeSet;

public class MainActivity extends Activity {
    static final String PREFS = "vote";
    static final String KEY_FPS = "fps";
    static final String KEY_ON_BOOT = "on_boot";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 3, pad, pad);

        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);

        boolean reachable = Rms.binder() != null;
        if (!reachable) {
            TextView err = new TextView(this);
            err.setText(R.string.unreachable);
            root.addView(err);
        }

        for (int fps : panelRates()) {
            Button b = new Button(this);
            b.setText(getString(R.string.rate_button, fps));
            b.setEnabled(reachable);
            b.setOnClickListener(v -> {
                Rms.vote(fps);
                prefs.edit().putInt(KEY_FPS, fps).apply();
                refreshSoon();
            });
            root.addView(b);
        }

        Button release = new Button(this);
        release.setText(R.string.release);
        release.setEnabled(reachable);
        release.setOnClickListener(v -> {
            Rms.release();
            prefs.edit().remove(KEY_FPS).apply();
            refreshSoon();
        });
        root.addView(release);

        CheckBox onBoot = new CheckBox(this);
        onBoot.setText(R.string.apply_on_boot);
        onBoot.setChecked(prefs.getBoolean(KEY_ON_BOOT, true));
        onBoot.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(KEY_ON_BOOT, checked).apply());
        root.addView(onBoot);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** The panel's own modes, highest first; only those can be pinned. */
    private TreeSet<Integer> panelRates() {
        TreeSet<Integer> rates = new TreeSet<>((a, b) -> b - a);
        Display display = getDisplay();
        if (display != null) {
            for (Display.Mode m : display.getSupportedModes()) {
                rates.add(Math.round(m.getRefreshRate()));
            }
        }
        return rates;
    }

    private void refreshSoon() {
        // The vote is oneway; give the vendor a moment to switch modes.
        handler.postDelayed(this::refresh, 600);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.status_current, Rms.rate())).append('\n');
        int fps = prefs.getInt(KEY_FPS, 0);
        sb.append(fps > 0 ? getString(R.string.status_pinned, fps) : getString(R.string.status_not_pinned)).append('\n');
        String reason = activeReason();
        if (reason != null) sb.append(getString(R.string.status_reason, reason)).append('\n');
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
}
