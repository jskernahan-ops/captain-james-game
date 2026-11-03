package com.kernahan.noahbenny;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fullscreen WebView that runs the game and forwards TV remote / Bluetooth controller input to it.
 *
 * Over-the-air updates (no reinstalling for normal game changes):
 *  - Every launch, the app checks the newest GitHub release of this game (version.json).
 *  - If there's a newer game (game.html), it downloads it quietly and plays it. Saved games are kept.
 *  - If the app itself has a newer version (only needed when this Java wrapper changes), it asks
 *    "Install the update?" and hands the new .apk to the TV's installer.
 *  - With no internet it simply plays the newest game it already has.
 * The GitHub repository and the bundled game number are written into assets/update.json by the build.
 */
public class MainActivity extends Activity {
    private static final String BASE = "file:///android_asset/game/";
    private static final String ACTION_INSTALL_STATUS = "com.kernahan.noahbenny.INSTALL_STATUS";
    private WebView web;
    private String repo = null;      // e.g. "yourname/captain-james-game"
    private int bundledGame = 0;     // game build number inside this .apk
    private int loadedGame = 0;      // game build number currently playing

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(0xFF0B1D2E);
        web.setFocusable(true);
        setContentView(web);
        hideBars();
        readUpdateConfig();
        loadGame();
        new Thread(new Runnable() { public void run() { checkForUpdates(); } }).start();
    }

    // ---------------- game content ----------------
    private File cachedHtml() { return new File(getFilesDir(), "game.html"); }
    private File cachedVersion() { return new File(getFilesDir(), "game_version.txt"); }

    private void readUpdateConfig() {
        try {
            String j = new String(readAll(getAssets().open("update.json")), "UTF-8");
            repo = jsonString(j, "repo");
            bundledGame = jsonInt(j, "game");
        } catch (Exception e) { repo = null; }
    }

    /** Plays the downloaded game if it's newer than the one built into the app, otherwise the built-in one. */
    private void loadGame() {
        int cached = 0;
        try { cached = Integer.parseInt(new String(readAll(new FileInputStream(cachedVersion())), "UTF-8").trim()); } catch (Exception e) { cached = 0; }
        if (cached > bundledGame && cachedHtml().exists()) {
            try {
                String html = new String(readAll(new FileInputStream(cachedHtml())), "UTF-8");
                // same base address as the built-in game, so saved games are shared
                web.loadDataWithBaseURL(BASE, html, "text/html", "UTF-8", null);
                loadedGame = cached;
                return;
            } catch (Exception e) { /* fall through to the built-in game */ }
        }
        web.loadUrl(BASE + "index.html");
        loadedGame = bundledGame;
    }

    private void checkForUpdates() {
        if (repo == null || repo.length() == 0) return;
        try {
            String base = "https://github.com/" + repo + "/releases/latest/download/";
            String j = new String(download(base + "version.json"), "UTF-8");
            int remoteGame = jsonInt(j, "game");
            int remoteApk = jsonInt(j, "apk");
            if (remoteGame > loadedGame) {
                byte[] html = download(base + "game.html");
                if (html.length > 1000) {
                    writeFile(cachedHtml(), html);
                    writeFile(cachedVersion(), String.valueOf(remoteGame).getBytes("UTF-8"));
                    runOnUiThread(new Runnable() { public void run() { applyNewGameIfOnTitle(); } });
                }
            }
            int installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            if (remoteApk > installed) {
                runOnUiThread(new Runnable() { public void run() { offerAppUpdate(); } });
            }
        } catch (Exception e) { /* offline or no release yet: keep playing what we have */ }
    }

    /** Switch to the new game straight away if the kids are still on the title screen; otherwise next launch. */
    private void applyNewGameIfOnTitle() {
        web.evaluateJavascript("(typeof STATE!=='undefined')?STATE:''", new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if ("\"title\"".equals(v)) { loadGame(); Toast.makeText(MainActivity.this, "Game updated!", Toast.LENGTH_SHORT).show(); }
            }
        });
    }

    // ---------------- app (.apk) updates ----------------
    private void offerAppUpdate() {
        new AlertDialog.Builder(this)
            .setTitle("Game update")
            .setMessage("A new version of the game app is ready. Install it now?")
            .setPositiveButton("Install", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { startAppUpdate(); }
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void startAppUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow this game to install updates, then press Back and choose Install again.", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "Turn on 'Install unknown apps' for this game in Settings.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Toast.makeText(this, "Downloading the update...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { public void run() {
            try {
                byte[] apk = download("https://github.com/" + repo + "/releases/latest/download/CaptainJames.apk");
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int id = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(id);
                OutputStream out = session.openWrite("CaptainJames.apk", 0, apk.length);
                out.write(apk);
                session.fsync(out);
                out.close();
                Intent status = new Intent(MainActivity.this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getActivity(MainActivity.this, 7, status, flags);
                session.commit(pending.getIntentSender());
                session.close();
            } catch (final Exception e) {
                runOnUiThread(new Runnable() { public void run() {
                    Toast.makeText(MainActivity.this, "Update didn't download. Try again later.", Toast.LENGTH_LONG).show();
                } });
            }
        } }).start();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && ACTION_INSTALL_STATUS.equals(i.getAction())) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm);   // the TV's own "Install update?" screen
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(this, "The update wasn't installed.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------- small helpers ----------------
    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CaptainJamesGame");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally { c.disconnect(); }
    }
    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }
    private static void writeFile(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try { o.write(data); } finally { o.close(); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }
    private static String jsonString(String j, String key) {
        int k = j.indexOf("\"" + key + "\""); if (k < 0) return null;
        int a = j.indexOf('"', j.indexOf(':', k) + 1), z = j.indexOf('"', a + 1);
        return (a < 0 || z < 0) ? null : j.substring(a + 1, z);
    }
    private static int jsonInt(String j, String key) {
        int k = j.indexOf("\"" + key + "\""); if (k < 0) return 0;
        int i = j.indexOf(':', k) + 1; StringBuilder d = new StringBuilder();
        while (i < j.length() && (j.charAt(i) == ' ' || j.charAt(i) == '"')) i++;
        while (i < j.length() && Character.isDigit(j.charAt(i))) d.append(j.charAt(i++));
        return d.length() == 0 ? 0 : Integer.parseInt(d.toString());
    }

    private void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    // ---------------- controller input ----------------
    private static String nameFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            // SNES / Donkey Kong Country layout for the 8BitDo SN30 Pro (Android mode reports buttons by position):
            // bottom button (labelled B) = jump, left (Y) or top (X) = roll, right (A) = swap
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: return "jump";
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_R2: return "roll";
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2: case KeyEvent.KEYCODE_MENU: return "swap";
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_BACK: return "pause";
            default: return null;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        String n = nameFor(e.getKeyCode());
        if (n == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) send(n, true);
        else if (e.getAction() == KeyEvent.ACTION_UP) send(n, false);
        return true;   // consumed (Back opens the pause menu; use the Home button to exit)
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hx) > 0.5f) x = hx;
            if (Math.abs(hy) > 0.5f) y = hy;
            web.evaluateJavascript("window.nativeAxis&&nativeAxis(" + x + "," + y + ")", null);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void send(String n, boolean down) {
        web.evaluateJavascript("window.nativeInput&&nativeInput('" + n + "'," + (down ? 1 : 0) + ")", null);
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); send("pause", true); send("pause", false); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideBars(); }
}
