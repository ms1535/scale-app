package com.ms1535.scalelog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notes {
    private Notes() {}

    static final int ID_READING = 1, ID_SAVED = 2;
    private static final String CH_READING = "reading", CH_SAVED = "saved";

    private static NotificationManager nm(Context c) {
        NotificationManager m = c.getSystemService(NotificationManager.class);
        if (m.getNotificationChannel(CH_READING) == null) {
            // Low: the "reading the scale" note shows for a few seconds and should not buzz.
            m.createNotificationChannel(new NotificationChannel(CH_READING, "Reading the scale", NotificationManager.IMPORTANCE_LOW));
            m.createNotificationChannel(new NotificationChannel(CH_SAVED, "Weigh-ins", NotificationManager.IMPORTANCE_DEFAULT));
        }
        return m;
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification reading(Context c) {
        nm(c);
        return new Notification.Builder(c, CH_READING)
                .setSmallIcon(R.drawable.ic_stat_scale)
                .setContentTitle("Reading the scale")
                .setContentText("Stand still, barefoot.")
                .setContentIntent(open(c))
                .setOngoing(true)
                .build();
    }

    static void saved(Context c, String text) {
        if (!Perms.notifications(c)) return;
        nm(c).notify(ID_SAVED, new Notification.Builder(c, CH_SAVED)
                .setSmallIcon(R.drawable.ic_stat_scale)
                .setContentTitle("Scale Log")
                .setContentText(text)
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build());
    }
}
