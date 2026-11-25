# Hotspot Keeper

Personal app for the Redmi Note 13 hotspot.

## Build
Open this folder in Android Studio → Build → Build App Bundle(s) / APK(s) → Build APK(s).
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install + one-time setup (phone plugged into Mac)
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.gillani.hotspotkeeper android.permission.WRITE_SECURE_SETTINGS
adb shell settings put global tether_offload_disabled 1
adb shell settings put global mobile_data_always_on 1
```
On Xiaomi, `adb install` needs Developer options → "Install via USB" ON.

Then open the app, tap "Allow running in background", and in
Settings → Apps → Hotspot Keeper → Battery saver → No restrictions; also enable Autostart.
