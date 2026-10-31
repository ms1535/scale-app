package com.ms1535.scalelog;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * One visit to the scale: connect, switch its screen to the right unit, take the steady
 * reading, disconnect. Started by Android when the paired scale wakes up (PresenceService,
 * ScanReceiver), or by the app's own screen.
 *
 * With the screen open, every frame goes to the page, which saves exactly as the web app
 * does. With it closed, this saves the reading itself and posts a notification.
 */
@SuppressLint("MissingPermission")  // every entry point checks Perms.bluetooth() first
public class ScaleService extends Service {
    static final String EXTRA_ADDRESS = "address";
    private static volatile ScaleService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Runnable> ops = new ArrayDeque<>();
    private boolean opBusy;
    private int opSeq;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean saved;

    static boolean active() { return instance != null && instance.gatt != null; }

    static void start(Context c, String address) {
        if (address == null || active()) return;
        if (!Perms.bluetooth(c)) { MainActivity.log("No Bluetooth permission; cannot read the scale."); return; }
        Intent i = new Intent(c, ScaleService.class).putExtra(EXTRA_ADDRESS, address);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            // Android 12+ refuses a background start without the companion exemption.
            MainActivity.log("Could not start the scale service: " + e);
        }
    }

    static void stop() {
        ScaleService s = instance;
        if (s != null) s.h.post(s::finish);
    }

    static boolean sendUnit(String unit) {
        ScaleService s = instance;
        if (s == null || s.writeChar == null) return false;
        s.h.post(() -> s.write(Qn.unitFrame(unit)));
        return true;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        closeGatt();
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Must come first: Android kills a foreground-started service that doesn't promote itself.
        if (!promote()) { stopSelf(); return START_NOT_STICKY; }
        String address = intent == null ? null : intent.getStringExtra(EXTRA_ADDRESS);
        if (gatt != null) return START_NOT_STICKY;       // already with the scale
        if (address == null) { finish(); return START_NOT_STICKY; }

        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled() || !Perms.bluetooth(this)) {
            MainActivity.state("idle", "Bluetooth is off. Turn it on, then step on the scale.");
            finish();
            return START_NOT_STICKY;
        }
        saved = false;
        MainActivity.state("connecting", "Connecting to the scale...");
        MainActivity.log("Connecting to " + address);
        BluetoothDevice d = ad.getRemoteDevice(address);
        gatt = d.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        // The scale sleeps by itself within a minute; never hold the connection longer than this.
        h.postDelayed(this::finish, 90_000);
        return START_NOT_STICKY;
    }

    private boolean promote() {
        Notification n = Notes.reading(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notes.ID_READING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(Notes.ID_READING, n);
            return true;
        } catch (RuntimeException e) {
            MainActivity.log("Android would not let the scale service run: " + e);
            return false;
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            h.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    MainActivity.log("Connected; finding services");
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    MainActivity.log("Disconnected (status " + status + ")");
                    finish();
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> { if (g == gatt) setup(g); });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(ScaleService.this::opDone);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.post(ScaleService.this::opDone);
        }

        // Android 13+ calls this one, with the value handed over safely.
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            byte[] v = value.clone();
            h.post(() -> onFrame(c, v));
        }

        // Android 12 and older call this one instead.
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            byte[] copy = v.clone();
            h.post(() -> onFrame(c, copy));
        }
    };

    private void setup(BluetoothGatt g) {
        BluetoothGattService s = g.getService(Qn.SVC_E0);
        boolean e0 = s != null;
        if (s == null) s = g.getService(Qn.SVC_F0);
        if (s == null) {
            MainActivity.log("This device has no scale service (FFE0/FFF0).");
            finish();
            return;
        }
        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
            int p = c.getProperties();
            MainActivity.log(" " + c.getUuid().toString().substring(4, 8) + " props 0x" + Integer.toHexString(p));
            if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                boolean notify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                enqueue(() -> enableNotify(g, c, notify));
            }
        }
        writeChar = s.getCharacteristic(e0 ? Qn.WRITE_E3 : Qn.WRITE_F2);
        // On the MF-BS2 this is what starts the conversation: it answers with 0x14, then weighs.
        write(Qn.unitFrame(Store.unit(this)));
        MainActivity.state("connected", "Connected. Step on barefoot and stand still.");
    }

    @SuppressWarnings("deprecation")
    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic c, boolean notify) {
        g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(Qn.CCCD);
        if (d == null) { opDone(); return; }
        byte[] v = notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) started = g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS;
        else { d.setValue(v); started = g.writeDescriptor(d); }
        if (!started) opDone();
    }

    private void write(byte[] b) {
        enqueue(() -> {
            BluetoothGatt g = gatt;
            BluetoothGattCharacteristic c = writeChar;
            if (g == null || c == null) { opDone(); return; }
            boolean started = doWrite(g, c, b);
            MainActivity.log((started ? "TX: " : "TX failed: ") + Qn.hex(b));
            if (!started) opDone();
        });
    }

    @SuppressWarnings("deprecation")
    private static boolean doWrite(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] b) {
        int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        if (Build.VERSION.SDK_INT >= 33) return g.writeCharacteristic(c, b, type) == BluetoothStatusCodes.SUCCESS;
        c.setWriteType(type);
        c.setValue(b);
        return g.writeCharacteristic(c);
    }

    // Android allows one GATT operation at a time; a second one started early is silently dropped.
    private void enqueue(Runnable r) {
        ops.add(r);
        if (!opBusy) nextOp();
    }

    private void nextOp() {
        Runnable r = ops.poll();
        if (r == null) { opBusy = false; return; }
        opBusy = true;
        int seq = ++opSeq;
        // A callback that never arrives must not stall the queue for good.
        h.postDelayed(() -> { if (opBusy && opSeq == seq) opDone(); }, 3000);
        r.run();
    }

    private void opDone() {
        opBusy = false;
        nextOp();
    }

    private void onFrame(BluetoothGattCharacteristic c, byte[] v) {
        String svc = c.getService().getUuid().toString(), chr = c.getUuid().toString();
        boolean onScreen = MainActivity.frame(svc, chr, Qn.hex(v));
        if (!onScreen) MainActivity.log("RX " + Qn.hex(v));
        int type = v.length > 0 ? v[0] & 0xFF : -1;
        if (type == 0x12) write(Qn.unitFrame(Store.unit(this)));
        else if (type == 0x14) write(Qn.timeFrame());

        Qn.Reading r = Qn.decode(v);
        if (r == null) return;
        if (!r.stable && r.kg < 5) { saved = false; return; }     // stepped off
        if (!r.stable || saved) return;
        saved = true;
        if (onScreen) return;                                     // the page saves it
        saveInBackground(r);
        // Got what we came for. Let go so the scale can sleep and the next person can wake it.
        h.postDelayed(this::finish, 1500);
    }

    private void saveInBackground(Qn.Reading r) {
        String uid = Store.guess(this, r.kg);
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("kg", Math.round(r.kg * 1000) / 1000.0);
            o.put("ohms", r.ohms > 0 ? r.ohms : JSONObject.NULL);
            o.put("ohms2", r.ohms2 > 0 ? r.ohms2 : JSONObject.NULL);
            o.put("uid", uid == null ? JSONObject.NULL : uid);
            o.put("src", "scale");
            Store.addPending(this, o);
        } catch (JSONException e) {
            MainActivity.log("Could not save: " + e);
            return;
        }
        if (uid != null) Store.noteWeight(this, uid, r.kg);
        String w = Store.weight(this, r.kg);
        Notes.saved(this, uid != null
                ? "Saved " + w + " for " + Store.nameOf(this, uid)
                : w + " weighed. Open Scale Log to say who it was.");
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        writeChar = null;
        if (g != null) {
            try { g.disconnect(); } catch (RuntimeException ignored) { }
            try { g.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void finish() {
        h.removeCallbacksAndMessages(null);
        ops.clear();
        opBusy = false;
        boolean was = gatt != null;
        closeGatt();
        if (was) MainActivity.state("idle", "Scale went to sleep. Step on it again whenever you like.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
