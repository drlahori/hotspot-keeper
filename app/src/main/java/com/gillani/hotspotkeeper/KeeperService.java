package com.gillani.hotspotkeeper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;

/**
 * Foreground service that, while the hotspot is on:
 *  1. keeps the Wi-Fi link to connected devices busy so iPhones don't drop it when idle,
 *  2. keeps mobile data awake and checks that it really reaches the internet,
 *  3. re-applies the two system fixes if HyperOS resets them,
 *  4. logs every hotspot on/off and internet lost/restored event.
 */
public class KeeperService extends Service {

    static final String CH_STATUS = "status";
    static final String CH_ALERT = "alert";
    static final int ID_STATUS = 1;
    static final int ID_ALERT = 2;

    static final long TICK_MS = 20_000;     // keep-alive interval
    static final int FAILS_BEFORE_ALERT = 3; // ~1 minute without internet

    // Shared with the UI
    static volatile boolean running = false;
    static volatile String hotspotIface = null;
    static volatile String hotspotAddr = null;
    static volatile Boolean internetOk = null;
    static volatile long lastCheck = 0;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private int fails = 0;
    private boolean wasHotspotOn = false;
    private boolean alerted = false;

    static void start(Context c) {
        Log.prefs(c).edit().putBoolean("enabled", true).apply();
        c.startForegroundService(new Intent(c, KeeperService.class));
    }

    static void stop(Context c) {
        Log.prefs(c).edit().putBoolean("enabled", false).apply();
        c.stopService(new Intent(c, KeeperService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        Notification n = statusNotification("Starting…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_STATUS, n);
        }
        running = true;
        Log.add(this, "Keeper started");
        applyFixes(true);

        thread = new HandlerThread("keeper");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        releaseWake();
        Log.add(this, "Keeper stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { loop(); } catch (Throwable t) { Log.add(KeeperService.this, "Error: " + t); }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void loop() {
        applyFixes(false);
        findHotspot();
        boolean on = hotspotIface != null;

        if (on != wasHotspotOn) {
            Log.add(this, on ? "Hotspot ON (" + hotspotIface + " " + hotspotAddr + ")" : "Hotspot OFF");
            wasHotspotOn = on;
            fails = 0;
            alerted = false;
            internetOk = null;
            cancelAlert();
        }

        if (!on) {
            releaseWake();
            updateStatus("Waiting for hotspot to turn on");
            return;
        }

        holdWake();
        keepAlive();

        boolean ok = checkInternet();
        lastCheck = System.currentTimeMillis();
        if (ok) {
            if (Boolean.FALSE.equals(internetOk)) Log.add(this, "Internet restored");
            internetOk = true;
            fails = 0;
            if (alerted) { cancelAlert(); alerted = false; }
            updateStatus("Hotspot on • internet OK • keeping link awake");
        } else {
            fails++;
            if (fails == 1) Log.add(this, "Internet check failed");
            internetOk = false;
            updateStatus("Hotspot on • NO internet (" + fails + " failed checks)");
            if (fails >= FAILS_BEFORE_ALERT && !alerted) {
                alerted = true;
                Log.add(this, "No internet for ~1 min — asked you to restart hotspot");
                showAlert();
            }
        }
    }

    /** Re-apply: mobile data always active ON, tethering hardware offload OFF. */
    private void applyFixes(boolean logStatus) {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            if (logStatus) Log.add(this, "Settings permission not granted yet — run the adb grant command");
            return;
        }
        try {
            if (Settings.Global.getInt(getContentResolver(), "mobile_data_always_on", 0) != 1) {
                Settings.Global.putInt(getContentResolver(), "mobile_data_always_on", 1);
                Log.add(this, "Re-enabled 'Mobile data always active'");
            }
            if (Settings.Global.getInt(getContentResolver(), "tether_offload_disabled", 0) != 1) {
                Settings.Global.putInt(getContentResolver(), "tether_offload_disabled", 1);
                Log.add(this, "Turned off tethering hardware acceleration");
            }
        } catch (Exception e) {
            Log.add(this, "Couldn't apply settings: " + e.getMessage());
        }
    }

    /** The hotspot interface is the Wi-Fi/AP interface whose IPv4 address is the gateway (x.x.x.1). */
    private void findHotspot() {
        String iface = null, addr = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (!(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan"))) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress();
                    boolean gateway = (b[3] & 0xff) == 1;
                    if (gateway || !name.equals("wlan0")) {
                        iface = name;
                        addr = a.getHostAddress() + "/" + ia.getNetworkPrefixLength();
                    }
                }
                if (iface != null) break;
            }
        } catch (Exception ignored) { }
        hotspotIface = iface;
        hotspotAddr = addr;
    }

    /**
     * Sends one tiny UDP packet (discard port 9) to every address in the hotspot /24.
     * Each send makes the phone ARP for that address, so every connected device sees
     * traffic on the link and never treats it as idle.
     */
    private void keepAlive() {
        if (hotspotAddr == null) return;
        String ip = hotspotAddr.substring(0, hotspotAddr.indexOf('/'));
        String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
        byte[] data = new byte[]{0};
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            for (int i = 2; i < 255; i++) {
                try {
                    s.send(new DatagramPacket(data, 1, InetAddress.getByName(prefix + i), 9));
                } catch (Exception ignored) { }
            }
            s.send(new DatagramPacket(data, 1, InetAddress.getByName(prefix + "255"), 9));
        } catch (Exception ignored) { }
    }

    /** Checks the mobile-data network itself (what the hotspot shares) reaches the internet. */
    private boolean checkInternet() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network cell = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                cell = n;
                break;
            }
        }
        if (cell == null) return false;
        String[] urls = {
                "http://connectivitycheck.gstatic.com/generate_204",
                "http://cp.cloudflare.com/generate_204"
        };
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) cell.openConnection(new URL(u));
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                if (c.getResponseCode() == 204) return true;
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    private void holdWake() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotspotKeeper:link");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
        else wakeLock.acquire(10 * 60 * 1000L); // refresh timeout
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---- notifications ----

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel a = new NotificationChannel(CH_ALERT, "No internet alerts", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(a);
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Intent hotspotSettingsIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private Notification statusNotification(String text) {
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Hotspot Keeper")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
    }

    private void updateStatus(String text) {
        getSystemService(NotificationManager.class).notify(ID_STATUS, statusNotification(text));
    }

    private void showAlert() {
        Intent target = hotspotSettingsIntent();
        if (getPackageManager().resolveActivity(target, 0) == null) {
            target = new Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 1, target,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Hotspot has no internet")
                .setContentText("Tap to open hotspot settings — turn it off and on again")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_ALERT, n);
    }

    private void cancelAlert() {
        getSystemService(NotificationManager.class).cancel(ID_ALERT);
    }
}
