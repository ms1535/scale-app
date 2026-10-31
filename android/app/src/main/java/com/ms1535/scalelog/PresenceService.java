package com.ms1535.scalelog;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.net.MacAddress;

import java.util.Locale;

/**
 * Android 12+: the system watches for the paired scale and calls this when it wakes up --
 * with the app closed, even after a reboot. Being a companion app is also what allows
 * starting the foreground service from the background.
 */
public class PresenceService extends CompanionDeviceService {
    // Android 13+ calls this; its default would call the String version below, so handle it
    // here and don't call super, or each wake-up would start the service twice.
    @Override public void onDeviceAppeared(AssociationInfo info) {
        MacAddress m = info.getDeviceMacAddress();
        if (m != null) ScaleService.start(this, m.toString().toUpperCase(Locale.ROOT));
    }

    // Android 12 calls this one.
    @SuppressWarnings("deprecation")
    @Override public void onDeviceAppeared(String address) {
        ScaleService.start(this, address.toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings("deprecation")
    @Override public void onDeviceDisappeared(String address) { }
}
