package com.ms1535.scalelog;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Collections;

/** Arranges for the scale to be noticed with the app closed. */
@SuppressLint("MissingPermission")
final class Background {
    private Background() {}

    static void watch(Context c, String address) {
        if (address == null || !Perms.bluetooth(c)) return;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                c.getSystemService(CompanionDeviceManager.class).startObservingDevicePresence(address);
                MainActivity.log("Android is now watching for the scale in the background.");
            } catch (RuntimeException e) {
                MainActivity.log("Could not watch for the scale in the background: " + e);
            }
            return;
        }
        // Android 8-11: a scan the system runs on our behalf, filtered in the Bluetooth chip
        // to this one scale, that wakes ScanReceiver when the scale advertises.
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        BluetoothLeScanner sc = ad == null ? null : ad.getBluetoothLeScanner();
        if (sc == null) return;
        ScanFilter f = new ScanFilter.Builder().setDeviceAddress(address).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
        try {
            sc.startScan(Collections.singletonList(f), s, scanIntent(c));
            MainActivity.log("Watching for the scale in the background.");
        } catch (RuntimeException e) {
            MainActivity.log("Could not watch for the scale in the background: " + e);
        }
    }

    static PendingIntent scanIntent(Context c) {
        return PendingIntent.getBroadcast(c, 7, new Intent(c, ScanReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
