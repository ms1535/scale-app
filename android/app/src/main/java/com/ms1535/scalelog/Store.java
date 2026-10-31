package com.ms1535.scalelog;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the background service needs to know, and what it found. The real history lives in
 * the web page's storage; this only holds:
 *   - the paired scale's address
 *   - a snapshot of the people and their last weights, sent by the page (so a reading taken
 *     with the app closed can still be filed to the right person)
 *   - weigh-ins taken with the app closed, waiting for the page to collect them
 */
final class Store {
    private Store() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("scale", Context.MODE_PRIVATE);
    }

    static String mac(Context c) { return p(c).getString("mac", null); }

    static void setMac(Context c, String mac) { p(c).edit().putString("mac", mac).apply(); }

    static void setSnapshot(Context c, String json) { p(c).edit().putString("snapshot", json).apply(); }

    private static JSONObject snapshot(Context c) {
        try { return new JSONObject(p(c).getString("snapshot", "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    static String unit(Context c) { return snapshot(c).optString("unit", "lb"); }

    static String weight(Context c, double kg) {
        boolean lb = !"kg".equals(unit(c));
        return String.format(java.util.Locale.US, "%.1f %s", lb ? kg / Qn.KG_PER_LB : kg, lb ? "lb" : "kg");
    }

    /** Same rule as guessPerson() in index.html: close to one person and nobody else, or ask. */
    static String guess(Context c, double kg) {
        JSONObject s = snapshot(c);
        if (!s.optBoolean("auto", true)) return null;
        JSONArray people = s.optJSONArray("people");
        if (people == null || people.length() == 0) return null;
        if (people.length() == 1) return people.optJSONObject(0).optString("id", null);
        String hit = null;
        int near = 0;
        double tol = Math.max(3, kg * 0.04);
        for (int i = 0; i < people.length(); i++) {
            JSONObject o = people.optJSONObject(i);
            if (o == null || o.isNull("lastKg")) continue;
            if (Math.abs(o.optDouble("lastKg") - kg) <= tol) { near++; hit = o.optString("id"); }
        }
        return near == 1 ? hit : null;
    }

    static String nameOf(Context c, String id) {
        JSONArray people = snapshot(c).optJSONArray("people");
        if (people != null) for (int i = 0; i < people.length(); i++) {
            JSONObject o = people.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o.optString("name", "");
        }
        return "";
    }

    /** Two weigh-ins before the app is opened: the second guess must see the first. */
    static synchronized void noteWeight(Context c, String id, double kg) {
        JSONObject s = snapshot(c);
        JSONArray people = s.optJSONArray("people");
        if (people == null) return;
        try {
            for (int i = 0; i < people.length(); i++) {
                JSONObject o = people.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) o.put("lastKg", kg);
            }
            setSnapshot(c, s.toString());
        } catch (JSONException ignored) { }
    }

    /**
     * The scale keeps advertising for a while after we let go of it, and that can wake the app
     * again while the same person is still standing there. The same weight within a minute is
     * the same weigh-in, not a new one.
     */
    static synchronized boolean isRepeat(Context c, double kg) {
        long t = p(c).getLong("lastSaveAt", 0);
        double w = Double.longBitsToDouble(p(c).getLong("lastSaveKg", 0));
        return System.currentTimeMillis() - t < 60_000 && Math.abs(w - kg) < 0.3;
    }

    static synchronized void noteSave(Context c, double kg) {
        p(c).edit().putLong("lastSaveAt", System.currentTimeMillis()).putLong("lastSaveKg", Double.doubleToLongBits(kg)).commit();
    }

    /** Just saved in the background: don't reconnect to the still-awake scale straight away. */
    static boolean coolingDown(Context c) {
        return System.currentTimeMillis() - p(c).getLong("lastSaveAt", 0) < 10_000;
    }

    static synchronized void addPending(Context c, JSONObject r) {
        JSONArray a;
        try { a = new JSONArray(p(c).getString("pending", "[]")); } catch (JSONException e) { a = new JSONArray(); }
        a.put(r);
        // commit(), not apply(): the service stops right after this and the reading must be on disk.
        p(c).edit().putString("pending", a.toString()).commit();
    }

    static synchronized String pending(Context c) { return p(c).getString("pending", "[]"); }

    /**
     * Drops only the weigh-ins the page says it has saved (by their times). Clearing on read
     * would lose them if the page failed between reading and saving.
     */
    static synchronized void clearPending(Context c, String timesJson) {
        try {
            JSONArray done = new JSONArray(timesJson), a = new JSONArray(pending(c)), keep = new JSONArray();
            java.util.HashSet<Long> gone = new java.util.HashSet<>();
            for (int i = 0; i < done.length(); i++) gone.add(done.optLong(i));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !gone.contains(o.optLong("t"))) keep.put(o);
            }
            p(c).edit().putString("pending", keep.toString()).commit();
        } catch (JSONException ignored) { }
    }
}
