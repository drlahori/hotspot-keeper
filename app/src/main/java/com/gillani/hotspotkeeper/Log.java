package com.gillani.hotspotkeeper;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small persistent event log (last 150 lines) kept in SharedPreferences. */
final class Log {
    private static final String PREFS = "keeper";
    private static final String KEY = "log";
    private static final int MAX_LINES = 150;

    static synchronized void add(Context c, String msg) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stamp = new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(new Date());
        String line = stamp + "  " + msg;
        String old = p.getString(KEY, "");
        String all = old.isEmpty() ? line : line + "\n" + old;
        String[] lines = all.split("\n");
        if (lines.length > MAX_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LINES; i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            all = sb.toString();
        }
        p.edit().putString(KEY, all).apply();
        android.util.Log.i("HotspotKeeper", msg);
    }

    static String read(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
    }

    static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Log() {}
}
