package com.ms1535.scalelog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentSender;
import android.net.MacAddress;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shows the web app (index.html, copied into the APK) and lends it the phone's Bluetooth
 * through the NativeScale bridge. The page is served from an https address inside the app
 * rather than file://, so its storage behaves exactly as it does in Chrome.
 */
@SuppressLint("MissingPermission")  // Bluetooth calls are behind Perms.bluetooth()
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int REQ_PAIR = 1, REQ_FILE = 2, REQ_PERMS = 3;

    private static volatile MainActivity current;
    private static volatile boolean visible;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private ScanCallback quickScan;

    // ----- called from ScaleService; safe when the screen is closed -----

    static boolean isVisible() { return visible; }

    /** Hands a frame to the page. False when nobody is looking, so the service saves it itself. */
    static boolean frame(String svc, String chr, String hex) {
        MainActivity a = current;
        if (a == null || !visible) return false;
        a.js("window.onNativeFrame && onNativeFrame(" + JSONObject.quote(svc) + "," + JSONObject.quote(chr) + "," + JSONObject.quote(hex) + ")");
        return true;
    }

    static void state(String state, String text) {
        MainActivity a = current;
        if (a != null) a.js("window.onNativeState && onNativeState(" + JSONObject.quote(state) + "," + JSONObject.quote(text) + ")");
    }

    static void log(String s) {
        MainActivity a = current;
        if (a != null) a.js("window.onNativeLog && onNativeLog(" + JSONObject.quote(s) + ")");
    }

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ----- lifecycle -----

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serve(r.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Links out of the app (none today) open in the browser, not in here.
                if (HOST.equals(r.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (RuntimeException ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // "Restore from backup file" needs a file picker; WebView has none of its own.
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                    return true;
                } catch (RuntimeException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "NativeScale");
        web.loadUrl(HOME);
        askPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        // Pick up anything weighed while the app was closed.
        js("window.nativeResumed && nativeResumed()");
        startQuickScan();
    }

    @Override protected void onPause() {
        visible = false;
        stopQuickScan();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        // Leave the app rather than navigating the page back to nothing.
        moveTaskToBack(true);
    }

    private void askPermissions() {
        String[] need = Perms.missing(this);
        if (need.length > 0) requestPermissions(need, REQ_PERMS);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_PERMS) return;
        if (Perms.bluetooth(this)) {
            Background.watch(this, Store.mac(this));
            startQuickScan();
        } else {
            state("idle", "Scale Log needs the Nearby devices permission to reach the scale. Allow it in Settings > Apps > Scale Log > Permissions.");
        }
    }

    // ----- serving the page -----

    private WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return null;
        String path = u.getPath() == null || u.getPath().equals("/") ? "index.html" : u.getPath().substring(1);
        String mime = path.endsWith(".html") ? "text/html"
                : path.endsWith(".png") ? "image/png"
                : path.endsWith(".webmanifest") ? "application/manifest+json"
                : path.endsWith(".js") ? "text/javascript"
                : "application/octet-stream";
        try {
            InputStream in = getAssets().open("web/" + path);
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(),
                    new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    // ----- finding the scale -----

    /**
     * Pairing goes through Android's companion-device screen. That one step is what lets
     * Android watch for the scale and wake the app when it appears, with the app closed.
     */
    @SuppressWarnings("deprecation")
    private void pair() {
        if (!Perms.bluetooth(this)) {
            askPermissions();
            state("idle", "Allow Nearby devices, then press Connect to scale again.");
            return;
        }
        CompanionDeviceManager cdm = getSystemService(CompanionDeviceManager.class);
        if (cdm == null) { state("idle", "This phone cannot pair companion devices."); return; }
        // The MF-BS2 calls itself QN-Scale. Allow for close variants rather than miss it.
        BluetoothLeDeviceFilter f = new BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("(?i).*(qn|scale).*"))
                .build();
        AssociationRequest req = new AssociationRequest.Builder().addDeviceFilter(f).setSingleDevice(false).build();
        state("pairing", "Step on the scale to wake it, then choose QN-Scale from the list.");
        cdm.associate(req, new CompanionDeviceManager.Callback() {
            @Override public void onDeviceFound(IntentSender sender) {
                try {
                    startIntentSenderForResult(sender, REQ_PAIR, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    state("idle", "Could not open the pairing screen: " + e.getMessage());
                }
            }

            @Override public void onFailure(CharSequence error) {
                state("idle", "Scale not found. Step on it to wake it, then press Connect to scale again.");
                log("Pairing: " + error);
            }
        }, null);
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_FILE) {
            if (fileCallback != null) fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
            return;
        }
        if (req != REQ_PAIR) return;
        String address = result == RESULT_OK && data != null ? pairedAddress(data) : null;
        if (address == null) { state("idle", "Not paired. Press Connect to scale to try again."); return; }
        Store.setMac(this, address);
        log("Paired with " + address);
        Background.watch(this, address);
        ScaleService.start(this, address);
    }

    @SuppressWarnings("deprecation")
    private static String pairedAddress(Intent data) {
        Object x = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
        if (x instanceof ScanResult) return ((ScanResult) x).getDevice().getAddress();
        if (x instanceof BluetoothDevice) return ((BluetoothDevice) x).getAddress();
        if (Build.VERSION.SDK_INT >= 33) {
            AssociationInfo ai = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo.class);
            MacAddress m = ai == null ? null : ai.getDeviceMacAddress();
            if (m != null) return m.toString().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * While the app is on screen, also scan hard for the scale ourselves. Android's background
     * watch is tuned to save battery and can take several seconds; on screen, connecting the
     * moment someone steps on matters more.
     */
    private void startQuickScan() {
        String address = Store.mac(this);
        if (address == null || quickScan != null || !Perms.bluetooth(this)) return;
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        BluetoothLeScanner sc = ad == null || !ad.isEnabled() ? null : ad.getBluetoothLeScanner();
        if (sc == null) return;
        quickScan = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                if (!ScaleService.active()) ScaleService.start(MainActivity.this, address);
            }
        };
        ScanFilter f = new ScanFilter.Builder().setDeviceAddress(address).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try { sc.startScan(Collections.singletonList(f), s, quickScan); } catch (RuntimeException e) { quickScan = null; }
    }

    private void stopQuickScan() {
        if (quickScan == null) return;
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        BluetoothLeScanner sc = ad == null || !ad.isEnabled() ? null : ad.getBluetoothLeScanner();
        try { if (sc != null) sc.stopScan(quickScan); } catch (RuntimeException ignored) { }
        quickScan = null;
    }

    private void shareFile(String name, String mime, String text) {
        try {
            File dir = new File(getCacheDir(), "share");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no share folder");
            File f = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            Uri u = FileProvider.getUriForFile(this, "com.ms1535.scalelog.files", f);
            Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
                    .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Send " + name));
        } catch (IOException | RuntimeException e) {
            js("say(" + JSONObject.quote("Could not send the file: " + e.getMessage()) + ", true)");
        }
    }

    /** What the page can call, as window.NativeScale. Runs on a WebView thread, not the UI one. */
    private class Bridge {
        @JavascriptInterface public boolean isPaired() { return Store.mac(MainActivity.this) != null; }

        @JavascriptInterface public void pair() { runOnUiThread(MainActivity.this::pair); }

        @JavascriptInterface public void connect() {
            runOnUiThread(() -> {
                if (Store.mac(MainActivity.this) == null) { MainActivity.this.pair(); return; }
                if (!Perms.bluetooth(MainActivity.this)) { askPermissions(); return; }
                state("waiting", "Waiting for the scale. Step on it to wake it; it connects on its own.");
                stopQuickScan();
                startQuickScan();
            });
        }

        @JavascriptInterface public void disconnect() { ScaleService.stop(); }

        @JavascriptInterface public boolean sendUnit(String unit) { return ScaleService.sendUnit(unit); }

        @JavascriptInterface public void sync(String json) { Store.setSnapshot(MainActivity.this, json); }

        @JavascriptInterface public String pending() { return Store.pending(MainActivity.this); }

        @JavascriptInterface public void clearPending(String times) { Store.clearPending(MainActivity.this, times); }

        @JavascriptInterface public void share(String name, String mime, String text) {
            runOnUiThread(() -> shareFile(name, mime, text));
        }

        @JavascriptInterface public void copy(String text) {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Scale Log", text));
        }
    }
}
