package com.xperiastream.fanctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Camera-key bindings: single / double / long press.
 *
 * Only active while the Stream is connected (pref streamConnected), so the
 * system default camera long-press is untouched when the accessory is absent.
 */
public class CameraKeyService extends AccessibilityService {
    public static final String PREFS = "fanctl";

    private static final int KEY_CAMERA = 27;
    private static final int KEY_FOCUS = 80;
    private static final long LONG_PRESS_MS = 600;
    private static final long DOUBLE_MS = 320;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long downAt;
    private boolean longFired;
    private int clickCount;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private boolean active() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getBoolean("camBind", false) && p.getBoolean("streamConnected", false);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KEY_CAMERA && code != KEY_FOCUS) {
            return false;
        }
        if (!active()) {
            // No Stream attached: let the system handle the camera key.
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                downAt = SystemClock.elapsedRealtime();
                longFired = false;
                handler.postDelayed(longRunnable, LONG_PRESS_MS);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longRunnable);
            long dur = SystemClock.elapsedRealtime() - downAt;
            if (longFired) {
                longFired = false;
                return true;
            }
            if (dur >= LONG_PRESS_MS) {
                act("camLong");
                return true;
            }
            clickCount++;
            if (clickCount == 1) {
                handler.postDelayed(singleRunnable, DOUBLE_MS);
            } else {
                handler.removeCallbacks(singleRunnable);
                clickCount = 0;
                act("camDouble");
            }
            return true;
        }
        return false;
    }

    private final Runnable longRunnable = new Runnable() {
        public void run() {
            longFired = true;
            act("camLong");
        }
    };

    private final Runnable singleRunnable = new Runnable() {
        public void run() {
            clickCount = 0;
            act("camSingle");
        }
    };

    private void act(String key) {
        String action = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "none");
        if ("shot".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        } else if ("open".equals(action)) {
            openSmallWindow();
        } else if ("close".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private void openSmallWindow() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = (int) (dm.widthPixels * 0.78f);
            int h = (int) (dm.heightPixels * 0.62f);
            int left = dm.widthPixels - w - dp(16);
            int top = (int) (dm.heightPixels * 0.18f);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchBounds(new Rect(left, top, left + w, top + h));
            startActivity(i, o.toBundle());
        } catch (Throwable t) {
            startActivity(i);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
