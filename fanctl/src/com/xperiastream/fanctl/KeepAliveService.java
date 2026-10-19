package com.xperiastream.fanctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Minimal foreground service that keeps the app process alive while automatic
 * fan control is running, so the 5 s control loop is not frozen after the
 * activity goes to the background (e.g. while gaming).
 *
 * It does not own the USB connection; the activity does. It only raises the
 * process priority and shows an ongoing notification.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "fan_auto";
    private static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "自动调速",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Stream 风扇自动调速")
                .setContentText("保持后台运行中")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true);
        startForeground(NOTIF_ID, b.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
