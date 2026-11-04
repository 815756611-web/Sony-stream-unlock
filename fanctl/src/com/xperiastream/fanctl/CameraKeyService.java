package com.xperiastream.fanctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Set;

/**
 * Camera-key screenshot: while the foreground app is one of the selected
 * packages, a camera-key press triggers the system screenshot flow.
 */
public class CameraKeyService extends AccessibilityService {
    public static final String PREFS = "fanctl";
    public static final String KEY_ENABLED = "camShot";
    public static final String KEY_APPS = "camApps";

    private static final int KEY_CAMERA = 27;
    private static final int KEY_FOCUS = 80;

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

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return false;
        }
        int code = event.getKeyCode();
        if (code != KEY_CAMERA && code != KEY_FOCUS) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            return false;
        }
        Set<String> apps = prefs.getStringSet(KEY_APPS, null);
        if (apps == null || apps.isEmpty()) {
            return false;
        }
        String foreground = foregroundPackage();
        if (foreground == null || !apps.contains(foreground)) {
            return false;
        }
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
    }

    private String foregroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
