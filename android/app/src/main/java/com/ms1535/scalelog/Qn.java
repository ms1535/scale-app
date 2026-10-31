package com.ms1535.scalelog;

import java.util.Locale;
import java.util.UUID;

/**
 * The Yolanda / QingNiu protocol as the MF-BS2 speaks it. Same decoding as index.html
 * section 1, confirmed against a real capture on 2026-10-30:
 *   10 0b 15 29 86 01 01 d5 01 8b 42
 *            weight kg x100 (106.30) / steady flag / impedance 469 / impedance 395 / checksum
 */
final class Qn {
    private Qn() {}

    static UUID uuid16(int n) {
        return UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", n));
    }

    static final UUID SVC_E0 = uuid16(0xFFE0), SVC_F0 = uuid16(0xFFF0);
    static final UUID WRITE_E3 = uuid16(0xFFE3), WRITE_F2 = uuid16(0xFFF2);
    static final UUID CCCD = uuid16(0x2902);

    static final double KG_PER_LB = 0.45359237;
    /** The scale counts its clock from 2000-01-01 UTC. */
    static final long EPOCH_2000 = 946684800L;

    static byte[] withSum(int... a) {
        byte[] b = new byte[a.length + 1];
        int sum = 0;
        for (int i = 0; i < a.length; i++) { b[i] = (byte) a[i]; sum += a[i]; }
        b[a.length] = (byte) sum;
        return b;
    }

    /** The scale's screen unit is set by the app: 1 = kg, 2 = lb. */
    static byte[] unitFrame(String unit) {
        return withSum(0x13, 0x09, 0x15, "kg".equals(unit) ? 1 : 2, 0x10, 0, 0, 0);
    }

    static byte[] timeFrame() {
        long s = System.currentTimeMillis() / 1000 - EPOCH_2000;
        return withSum(0x20, 0x08, 0x15, (int) (s & 255), (int) ((s >> 8) & 255), (int) ((s >> 16) & 255), (int) ((s >> 24) & 255));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    static final class Reading {
        double kg;
        boolean stable;
        int ohms, ohms2;
    }

    static Reading decode(byte[] b) {
        if (b == null || b.length < 10 || (b[0] & 0xFF) != 0x10) return null;
        Reading r = new Reading();
        r.kg = (((b[3] & 0xFF) << 8) | (b[4] & 0xFF)) / 100.0;
        r.stable = (b[5] & 0xFF) == 1;
        if (r.stable) {
            r.ohms = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
            r.ohms2 = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
        }
        return r;
    }
}
