package com.ms1535.scalelog;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

final class Perms {
    private Perms() {}

    private static boolean has(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean bluetooth(Context c) {
        if (Build.VERSION.SDK_INT >= 31) return has(c, Manifest.permission.BLUETOOTH_SCAN) && has(c, Manifest.permission.BLUETOOTH_CONNECT);
        return has(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    static boolean notifications(Context c) {
        return Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS);
    }

    static String[] missing(Context c) {
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            want.add(Manifest.permission.BLUETOOTH_SCAN);
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> out = new ArrayList<>();
        for (String p : want) if (!has(c, p)) out.add(p);
        return out.toArray(new String[0]);
    }
}
