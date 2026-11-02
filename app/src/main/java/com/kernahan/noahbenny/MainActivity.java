package com.kernahan.noahbenny;

import android.app.Activity;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Fullscreen WebView that runs the game and forwards Fire TV remote / Bluetooth controller input to it. */
public class MainActivity extends Activity {
    private WebView web;

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
        web.loadUrl("file:///android_asset/game/index.html");
    }

    private void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    private static String nameFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: return "jump";
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_R2: return "roll";
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_BUTTON_L1:
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
        return true;   // consumed (B no longer closes the app; use the Home button to exit)
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
