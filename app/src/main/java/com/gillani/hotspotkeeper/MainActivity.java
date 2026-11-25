package com.gillani.hotspotkeeper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One-screen UI: status light, on/off switch, setup checks and the event log. */
public class MainActivity extends Activity {

    private TextView light, status, checks, log;
    private Button toggle;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Hotspot Keeper");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad, 0, pad / 2);
        light = new TextView(this);
        light.setText("●");
        light.setTextSize(28);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(dp(10), 0, 0, 0);
        row.addView(light);
        row.addView(status);
        root.addView(row);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (KeeperService.running) KeeperService.stop(this);
            else KeeperService.start(this);
            ui.postDelayed(this::refresh, 400);
        });
        root.addView(toggle);

        Button battery = new Button(this);
        battery.setText("Allow running in background (battery)");
        battery.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        root.addView(battery);

        Button hs = new Button(this);
        hs.setText("Open hotspot settings");
        hs.setOnClickListener(v -> {
            try { startActivity(KeeperService.hotspotSettingsIntent()); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
        });
        root.addView(hs);

        checks = new TextView(this);
        checks.setPadding(0, pad, 0, pad);
        checks.setTextSize(14);
        root.addView(checks);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = new TextView(this);
        lt.setText("Event log");
        lt.setTypeface(Typeface.DEFAULT_BOLD);
        lt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> { Log.clear(this); refresh(); });
        logHead.addView(lt);
        logHead.addView(clear);
        root.addView(logHead);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextIsSelectable(true);
        root.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (Log.prefs(this).getBoolean("enabled", true) && !KeeperService.running) {
            KeeperService.start(this); // on by default
        }
    }

    @Override protected void onResume() { super.onResume(); loop.run(); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(loop); }

    private final Runnable loop = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 2000); }
    };

    private void refresh() {
        boolean run = KeeperService.running;
        boolean hs = KeeperService.hotspotIface != null;
        Boolean net = KeeperService.internetOk;

        toggle.setText(run ? "Turn keeper OFF" : "Turn keeper ON");
        if (!run) { light.setTextColor(Color.GRAY); status.setText("Keeper is off"); }
        else if (!hs) { light.setTextColor(Color.rgb(90, 140, 255)); status.setText("Waiting for hotspot"); }
        else if (net == null) { light.setTextColor(Color.rgb(255, 190, 0)); status.setText("Hotspot on • checking…"); }
        else if (net) { light.setTextColor(Color.rgb(40, 200, 90)); status.setText("Hotspot on • internet OK"); }
        else { light.setTextColor(Color.rgb(240, 60, 60)); status.setText("Hotspot on • NO internet"); }

        boolean perm = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        boolean batt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        int always = Settings.Global.getInt(getContentResolver(), "mobile_data_always_on", 0);
        int offload = Settings.Global.getInt(getContentResolver(), "tether_offload_disabled", 0);

        StringBuilder s = new StringBuilder("Setup\n");
        s.append(mark(perm)).append(" Settings permission (adb grant)\n");
        s.append(mark(batt)).append(" Background running allowed\n");
        s.append(mark(always == 1)).append(" Mobile data always active\n");
        s.append(mark(offload == 1)).append(" Hardware acceleration off\n");
        if (hs) s.append("\nHotspot: ").append(KeeperService.hotspotIface).append("  ").append(KeeperService.hotspotAddr);
        checks.setText(s.toString());

        String l = Log.read(this);
        log.setText(l.isEmpty() ? "(nothing yet)" : l);
    }

    private static String mark(boolean ok) { return ok ? "✅" : "⚠️"; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
