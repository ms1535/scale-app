package com.ms1535.scalelog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android 8-11: the background scan saw the scale. */
public class ScanReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        ScaleService.start(c, Store.mac(c));
    }
}
