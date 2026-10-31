package com.ms1535.scalelog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Android 8-11 forget the background scan on reboot; set it up again. (12+ keeps it.) */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Build.VERSION.SDK_INT < 31) {
            Background.watch(c, Store.mac(c));
        }
    }
}
