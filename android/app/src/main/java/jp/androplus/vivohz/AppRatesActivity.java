package jp.androplus.vivohz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/** Lists launchable apps; tapping one picks the rate it gets while in front. */
public class AppRatesActivity extends Activity {
    private static final class App {
        final String pkg;
        final String label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private int[] rates;
    private TextView serviceState;
    private Button serviceButton;
    private EditText filter;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Our own title, as in MainActivity.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        rates = MainActivity.panelRates(getDisplay()).stream().mapToInt(Integer::intValue).toArray();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_rates);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.app_rates_hint);
        root.addView(hint);

        serviceState = new TextView(this);
        serviceState.setPadding(0, pad / 2, 0, 0);
        root.addView(serviceState);

        serviceButton = new Button(this);
        serviceButton.setAllCaps(false);
        serviceButton.setText(R.string.open_accessibility);
        serviceButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(serviceButton);

        filter = new EditText(this);
        filter.setHint(R.string.search_apps);
        filter.setSingleLine(true);
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                show();
            }
        });
        root.addView(filter);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> pick(shown.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);

        // Labels take a while to load with many apps installed.
        new Thread(() -> {
            List<App> apps = loadApps();
            runOnUiThread(() -> {
                all.addAll(apps);
                show();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = AppSwitchService.enabled(this);
        serviceState.setText(on ? R.string.service_on : R.string.service_off);
        serviceButton.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private List<App> loadApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            // This app follows the pin, which keeps MainActivity's votes in step with the service.
            if (pkg.equals(getPackageName())) continue;
            boolean dup = false;
            for (App a : apps) dup |= a.pkg.equals(pkg);
            if (!dup) apps.add(new App(pkg, ri.loadLabel(pm).toString()));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    /** Apps with a rule first, then the rest; both narrowed by the search box. */
    private void show() {
        String q = filter.getText().toString().trim().toLowerCase();
        shown.clear();
        for (int pass = 0; pass < 2; pass++) {
            for (App a : all) {
                boolean ruled = AppRates.get(this, a.pkg) != AppRates.DEFAULT;
                if (ruled != (pass == 0)) continue;
                if (!q.isEmpty() && !a.label.toLowerCase().contains(q) && !a.pkg.contains(q)) continue;
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private String describe(int rule) {
        if (rule == AppRates.DEFAULT) return getString(R.string.rule_default);
        if (rule == AppRates.AUTO) return getString(R.string.rule_auto);
        return getString(R.string.rate_button, rule);
    }

    private void pick(App app) {
        int[] values = new int[rates.length + 2];
        String[] labels = new String[values.length];
        values[0] = AppRates.DEFAULT;
        values[1] = AppRates.AUTO;
        for (int i = 0; i < rates.length; i++) values[i + 2] = rates[i];
        int checked = 0;
        int rule = AppRates.get(this, app.pkg);
        for (int i = 0; i < values.length; i++) {
            labels[i] = describe(values[i]);
            if (values[i] == rule) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppRates.set(this, app.pkg, values[which]);
                    dialog.dismiss();
                    show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public App getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            App app = getItem(position);
            ((TextView) v.findViewById(android.R.id.text1)).setText(app.label);
            ((TextView) v.findViewById(android.R.id.text2)).setText(describe(AppRates.get(AppRatesActivity.this, app.pkg)));
            return v;
        }
    }
}
