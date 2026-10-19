package com.xperiastream.fanctl;

import android.app.Activity;
import android.app.PendingIntent;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
/**
 * Standalone controller for the Sony Xperia Stream (USB 054C:0E6D).
 *
 * Protocol (same as Sony's GamingFanService, CDC-ACM 115200 8N1):
 *   ;<type>,<cmd>,<param>\n     type 0=request, 1=response
 *   SET_FAN_SPEED (0x02)        param = rpm / 100, hex
 *   e.g. 6600 rpm -> ";0,02,66\n"
 */
public class MainActivity extends Activity {
    private static final String TAG = "StreamFanCtl";
    private static final String ACTION_PERM = "com.xperiastream.fanctl.USB_PERMISSION";
    private static final int VID = 0x054C;
    private static final int PID = 0x0E6D;

    private static final int C_BG = 0xFFF2F4F7;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_MUTED = 0xFF6B7280;
    private static final int C_PRIMARY = 0xFF2563EB;
    private static final int C_PRIMARY_SOFT = 0xFFDBEAFE;
    private static final int C_GREEN = 0xFF15803D;
    private static final int C_GREEN_BG = 0xFFDCFCE7;
    private static final int C_RED = 0xFFB91C1C;
    private static final int C_RED_BG = 0xFFFEE2E2;
    private static final int C_GRAY_BTN = 0xFFE5E7EB;

    private UsbManager usb;
    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface ifCtrl;
    private UsbInterface ifData;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;
    private int ctrlIndex = -1;

    private volatile boolean reading = false;
    private Thread readThread;
    private volatile boolean keepAlive = false;
    private Thread keepAliveThread;
    private final Object txLock = new Object();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusChip;
    private TextView tempView;
    private TextView targetView;
    private ProgressBar tempBar;
    private TextView logView;
    private ScrollView logScroll;
    private Switch autoSwitch;
    private final List<Button> manualButtons = new ArrayList<Button>();
    private final StringBuilder logBuf = new StringBuilder();
    private boolean receiversRegistered = false;

    private volatile boolean autoEnabled = false;
    private volatile int autoTarget = 0;
    private Thread autoThread;

    // four-in-one detection panel
    private final TextView[] detValue = new TextView[4];
    private final TextView[] detStatus = new TextView[4];
    private final Detectors.Det[] detCache = new Detectors.Det[4];
    private final long[] ethPrev = new long[3];
    private boolean detBusy = false;

    // voice-input pause (auto mode only)
    private AudioManager audio;
    private AudioManager.AudioRecordingCallback recCb;
    private volatile boolean micActive = false;
    private volatile boolean voicePaused = false;
    private boolean voicePauseEnabled = true;
    private volatile boolean voicePoll = false;
    private Thread voicePollThread;
    private TelephonyManager telephony;
    private Object callListener;
    private volatile boolean callActive = false;
    private static final int REQ_PHONE_STATE = 2;
    private Switch voiceSwitch;
    private int heartbeatLogs = 0;

    private final Runnable detTick = new Runnable() {
        public void run() {
            refreshDetection();
            ui.postDelayed(this, 3000);
        }
    };

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_PERM.equals(intent.getAction())) {
                return;
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            log("USB 权限: " + (granted ? "已授权" : "被拒绝"));
            if (granted) {
                connect();
            } else {
                setChip("USB 权限被拒绝", C_RED, C_RED_BG);
            }
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("配件已拔出");
                disconnect();
                setChip("配件已拔出", C_MUTED, 0xFFE5E7EB);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("检测到 USB 设备插入");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        scan();
                    }
                }, 800);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashHandler();
        try {
            setContentView(buildUi());
        } catch (Throwable t) {
            Log.e(TAG, "buildUi failed", t);
            TextView tv = new TextView(this);
            tv.setTextSize(12f);
            tv.setPadding(24, 24, 24, 24);
            tv.setText("界面初始化失败:\n\n" + Log.getStackTraceString(t));
            setContentView(tv);
        }
        initVoiceDetection();
    }

    /** Pauses the fan while any app is using the microphone or a call is active. */
    private void initVoiceDetection() {
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        recCb = new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                micActive = configs != null && !configs.isEmpty();
                onVoiceState(micActive || isCallMode());
            }
        };
        try {
            audio.registerAudioRecordingCallback(recCb, ui);
            log("语音检测: 已启用（麦克风回调，无需录音权限）");
        } catch (Throwable t) {
            recCb = null;
            log("语音检测不可用: " + t.getClass().getSimpleName());
        }
        initCallDetection();
    }

    /** Call state is event-driven when READ_PHONE_STATE is granted. */
    private void initCallDetection() {
        telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (telephony == null) {
            return;
        }
        if (hasPhonePermission()) {
            registerCallListener();
        } else {
            log("通话检测: 未授权，开自动调速时会请求电话权限");
        }
    }

    private boolean hasPhonePermission() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void registerCallListener() {
        if (telephony == null || callListener != null || !hasPhonePermission()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                CallStateCb cb = new CallStateCb();
                telephony.registerTelephonyCallback(getMainExecutor(), cb);
                callListener = cb;
            } else {
                PhoneStateListener psl = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        onCallState(state);
                    }
                };
                telephony.listen(psl, PhoneStateListener.LISTEN_CALL_STATE);
                callListener = psl;
            }
            log("通话检测: 事件驱动已启用");
        } catch (Throwable t) {
            callListener = null;
            log("通话检测注册失败: " + t.getClass().getSimpleName());
        }
    }

    private void unregisterCallListener() {
        Object cl = callListener;
        callListener = null;
        if (cl == null || telephony == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && cl instanceof TelephonyCallback) {
                telephony.unregisterTelephonyCallback((TelephonyCallback) cl);
            } else if (cl instanceof PhoneStateListener) {
                telephony.listen((PhoneStateListener) cl, PhoneStateListener.LISTEN_NONE);
            }
        } catch (Throwable ignored) {
        }
    }

    private void onCallState(int state) {
        callActive = state == TelephonyManager.CALL_STATE_RINGING
                || state == TelephonyManager.CALL_STATE_OFFHOOK;
        onVoiceState(micActive || callActive || isCallMode());
    }

    private void syncCallState() {
        if (telephony == null || !hasPhonePermission()) {
            return;
        }
        try {
            onCallState(telephony.getCallState());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PHONE_STATE) {
            return;
        }
        if (hasPhonePermission()) {
            stopVoiceWatch();
            registerCallListener();
            syncCallState();
            log("通话检测: 已启用（事件驱动，0 轮询）");
        } else {
            log("未授予电话权限，通话检测退回 1 秒轮询");
            startVoiceWatch();
        }
    }

    /** Fallback poll, used only when READ_PHONE_STATE is denied. */
    private void startVoiceWatch() {
        if (voicePoll || !autoEnabled || !voicePauseEnabled || hasPhonePermission()) {
            return;
        }
        voicePoll = true;
        voicePollThread = new Thread(new Runnable() {
            public void run() {
                while (voicePoll) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (voicePoll) {
                        onVoiceState(micActive || isCallMode());
                    }
                }
            }
        }, "voice-poll");
        voicePollThread.start();
    }

    private void stopVoiceWatch() {
        voicePoll = false;
        Thread t = voicePollThread;
        voicePollThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    @android.annotation.TargetApi(31)
    private class CallStateCb extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            onCallState(state);
        }
    }

    private boolean isCallMode() {
        if (audio == null) {
            return false;
        }
        int m = audio.getMode();
        return m == AudioManager.MODE_IN_CALL
                || m == AudioManager.MODE_IN_COMMUNICATION
                || m == AudioManager.MODE_CALL_SCREENING;
    }

    private void onVoiceState(boolean active) {
        if (!autoEnabled || !voicePauseEnabled) {
            return;
        }
        if (active == voicePaused) {
            return;
        }
        voicePaused = active;
        final int target = active ? 0 : autoTarget;
        log(active ? "语音输入开始，风扇暂停" : "语音输入结束，恢复 " + autoTarget + " rpm");
        new Thread(new Runnable() {
            public void run() {
                sendSpeed(target, true);
            }
        }, "voice").start();
    }

    /**
     * Writes the next uncaught exception to
     * /sdcard/Android/data/com.xperiastream.fanctl/files/crash.txt so a crash
     * can be diagnosed without adb.
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        java.io.FileWriter w = new java.io.FileWriter(new File(dir, "crash.txt"), true);
                        w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                .format(new Date()) + " thread=" + t.getName() + "\n");
                        w.write(Log.getStackTraceString(e));
                        w.write("\n\n");
                        w.close();
                    }
                } catch (Throwable ignored) {
                }
                Log.e(TAG, "uncaught exception", e);
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceivers();
        ui.removeCallbacks(detTick);
        ui.post(detTick);
        // Do not reconnect (and therefore do not reset auto mode) when coming
        // back from the launcher: the USB connection is still owned by this
        // activity object and stays valid.
        if (conn != null) {
            if (isDevicePresent()) {
                setChip("已连接 054C:0E6D", C_GREEN, C_GREEN_BG);
                return;
            }
            disconnect();
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                try {
                    scan();
                } catch (Throwable t) {
                    Log.e(TAG, "scan failed", t);
                    setChip("扫描失败: " + t.getClass().getSimpleName(), C_RED, C_RED_BG);
                }
            }
        }, 300);
    }

    @Override
    protected void onPause() {
        unregisterReceivers();
        ui.removeCallbacks(detTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopVoiceWatch();
        unregisterCallListener();
        if (audio != null && recCb != null) {
            try {
                audio.unregisterAudioRecordingCallback(recCb);
            } catch (Throwable ignored) {
            }
            recCb = null;
        }
        stopAuto(false);
        disconnect();
        stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(root);

        TextView title = text("Stream 风扇控制", 23f, C_TEXT, true);
        root.addView(title);

        TextView sub = text("USB 054C:0E6D · CDC 直连 · 无需 Sony 服务", 12f, C_MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        statusChip = text("未连接", 13f, C_MUTED, true);
        statusChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusChip.setBackground(bg(0xFFE5E7EB, 20));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp.bottomMargin = dp(12);
        root.addView(statusChip, chipLp);

        LinearLayout connRow = new LinearLayout(this);
        connRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnConnect = action("连接 / 重新扫描", true, new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        });
        Button btnDisconnect = action("断开", false, new View.OnClickListener() {
            public void onClick(View v) {
                disconnect();
                setChip("已断开", C_MUTED, 0xFFE5E7EB);
            }
        });
        addWeighted(connRow, btnConnect);
        addWeighted(connRow, btnDisconnect);
        root.addView(connRow, rowLp());

        // ---- auto card ----
        LinearLayout autoCard = card();
        LinearLayout autoHead = new LinearLayout(this);
        autoHead.setOrientation(LinearLayout.HORIZONTAL);
        autoHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView autoTitle = text("自动调速", 16f, C_TEXT, true);
        autoHead.addView(autoTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        autoSwitch = new Switch(this);
        autoSwitch.setChecked(false);
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startAuto();
                } else {
                    stopAuto(true);
                }
            }
        });
        autoHead.addView(autoSwitch);
        autoCard.addView(autoHead);

        tempView = text("温度 --", 20f, C_TEXT, true);
        tempView.setPadding(0, dp(10), 0, dp(2));
        autoCard.addView(tempView);

        targetView = text("目标转速 --", 13f, C_MUTED, false);
        targetView.setPadding(0, 0, 0, dp(10));
        autoCard.addView(targetView);

        tempBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        tempBar.setMax(9000);
        tempBar.setProgress(0);
        tempBar.setProgressTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY));
        tempBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFE5E7EB));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        autoCard.addView(tempBar, barLp);

        TextView autoHint = text("每 5 秒按电池温度升降档：34 / 36 / 38 / 40 / 42 °C → 3900 / 4800 / 5700 / 6600 / 7500，带 2 °C 迟滞。",
                11f, C_MUTED, false);
        autoHint.setPadding(0, dp(10), 0, 0);
        autoCard.addView(autoHint);

        LinearLayout voiceRow = new LinearLayout(this);
        voiceRow.setOrientation(LinearLayout.HORIZONTAL);
        voiceRow.setGravity(Gravity.CENTER_VERTICAL);
        voiceRow.setPadding(0, dp(6), 0, 0);
        TextView voiceLabel = text("语音输入时暂停风扇", 13f, C_TEXT, false);
        voiceRow.addView(voiceLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        voiceSwitch = new Switch(this);
        voiceSwitch.setChecked(true);
        voiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                voicePauseEnabled = isChecked;
                if (!isChecked && voicePaused) {
                    voicePaused = false;
                    sendSpeed(autoTarget, true);
                    log("语音暂停已关闭，恢复 " + autoTarget + " rpm");
                }
            }
        });
        voiceRow.addView(voiceSwitch);
        autoCard.addView(voiceRow);
        root.addView(autoCard);

        // ---- manual card ----
        LinearLayout manualCard = card();
        manualCard.addView(text("手动转速", 16f, C_TEXT, true));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(10), 0, 0);
        row1.addView(speedButton("关闭", 0), speedLp());
        row1.addView(speedButton("低 4800", 4800), speedLp());
        row1.addView(speedButton("中 5700", 5700), speedLp());
        manualCard.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        row2.addView(speedButton("高 6600", 6600), speedLp());
        row2.addView(speedButton("最大 7500", 7500), speedLp());
        Button query = action("查询", false, new View.OnClickListener() {
            public void onClick(View v) {
                sendRaw(";0,03,00\n");
            }
        });
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        row2.addView(query, speedLp());
        manualCard.addView(row2);
        root.addView(manualCard);

        // ---- interface detection card ----
        LinearLayout detCard = card();
        detCard.addView(text("接口检测", 16f, C_TEXT, true));

        String[] names = {"充电", "3.5mm", "HDMI", "网卡"};
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);

            TextView label = text(names[i], 13f, C_TEXT, true);
            label.setWidth(dp(52));
            row.addView(label);

            TextView value = text("--", 11f, C_MUTED, false);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            vp.rightMargin = dp(6);
            row.addView(value, vp);

            TextView chip = text("--", 10f, C_MUTED, true);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackground(bg(0xFFE5E7EB, 12));
            row.addView(chip);

            detValue[i] = value;
            detStatus[i] = chip;
            detCard.addView(row);
        }

        Button export = action("导出检测报告", false, new View.OnClickListener() {
            public void onClick(View v) {
                exportReport();
            }
        });
        export.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams expLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        expLp.topMargin = dp(12);
        detCard.addView(export, expLp);
        root.addView(detCard);

        // ---- log card ----
        LinearLayout logCard = card();
        logCard.addView(text("日志", 14f, C_TEXT, true));

        logView = text("", 10f, C_MUTED, false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);

        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(130));
        logLp.topMargin = dp(8);
        logCard.addView(logScroll, logLp);
        root.addView(logCard);

        return scroll;
    }

    private Button speedButton(String label, final int rpm) {
        Button b = action(label, false, new View.OnClickListener() {
            public void onClick(View v) {
                if (autoEnabled) {
                    return;
                }
                sendSpeed(rpm, false);
            }
        });
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        manualButtons.add(b);
        return b;
    }

    private LinearLayout.LayoutParams speedLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void addWeighted(LinearLayout parent, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(child, p);
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        return p;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(bg(C_CARD, 18));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        c.setLayoutParams(p);
        return c;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private Button action(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTextColor(primary ? Color.WHITE : C_TEXT);
        b.setBackground(bg(primary ? C_PRIMARY : C_GRAY_BTN, 12));
        b.setOnClickListener(l);
        b.setMinHeight(dp(44));
        return b;
    }

    private GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void setChip(String s, int textColor, int bgColor) {
        if (statusChip == null) {
            return;
        }
        statusChip.setText(s);
        statusChip.setTextColor(textColor);
        statusChip.setBackground(bg(bgColor, 20));
    }

    // ------------------------------------------------------------- USB

    private void scan() {
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usb == null) {
            setChip("UsbManager 不可用", C_RED, C_RED_BG);
            return;
        }
        if (conn != null) {
            if (isDevicePresent()) {
                setChip("已连接 054C:0E6D", C_GREEN, C_GREEN_BG);
                return;
            }
            disconnect();
        }
        device = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) {
                device = d;
                break;
            }
        }
        if (device == null) {
            log("未找到 054C:0E6D（当前设备数: " + usb.getDeviceList().size() + "）");
            setChip("未检测到 Xperia Stream", C_MUTED, 0xFFE5E7EB);
            return;
        }
        log("找到设备: " + device.getDeviceName() + " ifaces=" + device.getInterfaceCount());
        if (usb.hasPermission(device)) {
            connect();
        } else {
            setChip("请求 USB 权限…", C_PRIMARY, C_PRIMARY_SOFT);
            int flags = (Build.VERSION.SDK_INT >= 31) ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(ACTION_PERM).setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
            usb.requestPermission(device, pi);
        }
    }

    private boolean isDevicePresent() {
        if (usb == null || device == null) {
            return false;
        }
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getDeviceId() == device.getDeviceId()) {
                return true;
            }
        }
        return false;
    }

    private void connect() {
        closeUsb();
        if (device == null) {
            scan();
            return;
        }

        UsbInterface ctrl = null;
        UsbInterface data = null;
        int ci = -1;
        UsbEndpoint in = null;
        UsbEndpoint out = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            if (itf.getInterfaceClass() == 2) {
                ctrl = itf;
                ci = itf.getId();
            } else if (itf.getInterfaceClass() == 10) {
                data = itf;
                for (int j = 0; j < itf.getEndpointCount(); j++) {
                    UsbEndpoint ep = itf.getEndpoint(j);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        continue;
                    }
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        in = ep;
                    } else {
                        out = ep;
                    }
                }
            }
        }

        if (ctrl == null || data == null || in == null || out == null) {
            setChip("接口不匹配", C_RED, C_RED_BG);
            log("接口不匹配: ctrl=" + (ctrl != null) + " data=" + (data != null)
                    + " in=" + (in != null) + " out=" + (out != null));
            return;
        }

        UsbDeviceConnection c = usb.openDevice(device);
        if (c == null) {
            setChip("openDevice 失败", C_RED, C_RED_BG);
            return;
        }
        if (!c.claimInterface(ctrl, true) || !c.claimInterface(data, true)) {
            c.close();
            setChip("接口被占用（Sony 服务？）", C_RED, C_RED_BG);
            log("claimInterface 失败，先禁用 GamingFanService");
            return;
        }

        byte[] lineCoding = new byte[] {0x00, (byte) 0xC2, 0x01, 0x00, 0x00, 0x00, 0x08};
        int rc = c.controlTransfer(0x21, 0x20, 0, ci, lineCoding, 7, 200);
        log("SET_LINE_CODING rc=" + rc);

        conn = c;
        ifCtrl = ctrl;
        ifData = data;
        epIn = in;
        epOut = out;
        ctrlIndex = ci;

        // Same opening sequence Sony's GamingFanService sends on connect, plus
        // a periodic query.  The Stream MCU appears to reset its USB link when
        // it receives no command for a while, which looked like "audio drops
        // after 20 s".
        sendRawQuiet(";0,01,64\n");
        sendRawQuiet(";0,0A,00\n");
        startKeepAlive();

        setChip("已连接 054C:0E6D", C_GREEN, C_GREEN_BG);
        reading = true;
        readThread = new Thread(new Runnable() {
            public void run() {
                readLoop();
            }
        }, "usb-read");
        readThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[1024];
        while (reading && conn != null) {
            try {
                int n = conn.bulkTransfer(epIn, buf, buf.length, 200);
                if (n > 0) {
                    String s = new String(buf, 0, n).trim();
                    if (s.startsWith(";1,03,")) {
                        heartbeatLogs++;
                        // one labelled line every 10 s instead of 30 lines/min
                        if (heartbeatLogs % 5 == 1) {
                            log("程序保活心跳 #" + heartbeatLogs + "  " + s);
                        }
                    } else {
                        log("RX " + s);
                    }
                }
            } catch (Exception e) {
                log("读线程异常: " + e);
                break;
            }
        }
    }

    private void disconnect() {
        stopAuto(true);
        closeUsb();
    }

    private void closeUsb() {
        keepAlive = false;
        Thread k = keepAliveThread;
        keepAliveThread = null;
        if (k != null) {
            try {
                k.join(200);
            } catch (InterruptedException ignored) {
            }
        }
        reading = false;
        Thread t = readThread;
        readThread = null;
        if (t != null) {
            try {
                t.join(300);
            } catch (InterruptedException ignored) {
            }
        }
        if (conn != null) {
            try {
                if (ifCtrl != null) {
                    conn.releaseInterface(ifCtrl);
                }
            } catch (Exception ignored) {
            }
            try {
                if (ifData != null) {
                    conn.releaseInterface(ifData);
                }
            } catch (Exception ignored) {
            }
            try {
                conn.close();
            } catch (Exception ignored) {
            }
            conn = null;
        }
        ifCtrl = null;
        ifData = null;
        epIn = null;
        epOut = null;
        ctrlIndex = -1;
    }

    // ------------------------------------------------------------ TX

    private void sendSpeed(int rpm, boolean fromAuto) {
        int param = rpm / 100;
        sendRawInternal(String.format(Locale.US, ";0,02,%02X\n", param), rpm, fromAuto);
    }

    private void sendRaw(String cmd) {
        sendRawInternal(cmd, -1, false);
    }

    private void sendRawQuiet(String cmd) {
        if (conn == null) {
            return;
        }
        try {
            byte[] b = cmd.getBytes("UTF-8");
            synchronized (txLock) {
                conn.bulkTransfer(epOut, b, b.length, 300);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Sony's ThermalFanService talks to the accessory about once per second.
     * A 2 s GET_FAN_SPEED query keeps the MCU from idling its USB link.
     */
    private void startKeepAlive() {
        keepAlive = true;
        if (keepAliveThread != null) {
            return;
        }
        keepAliveThread = new Thread(new Runnable() {
            public void run() {
                while (keepAlive && conn != null) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (keepAlive && conn != null) {
                        sendRawQuiet(";0,03,00\n");
                    }
                }
            }
        }, "keepalive");
        keepAliveThread.start();
    }

    private void sendRawInternal(String cmd, int rpm, boolean fromAuto) {
        if (conn == null) {
            log("未连接，先点「连接 / 重新扫描」");
            return;
        }
        try {
            byte[] b = cmd.getBytes("UTF-8");
            int n;
            synchronized (txLock) {
                n = conn.bulkTransfer(epOut, b, b.length, 300);
            }
            log("TX " + cmd.trim() + "  rc=" + n);
            if (fromAuto) {
                if (n > 0) {
                    updateTargetUi(rpm);
                }
            } else if (n > 0) {
                setChip("已发送 " + (rpm >= 0 ? rpm + " rpm" : cmd.trim()), C_GREEN, C_GREEN_BG);
            }
        } catch (Exception e) {
            log("发送异常: " + e);
        }
    }

    // ------------------------------------------------------------ auto

    private void startAuto() {
        if (conn == null) {
            log("未连接，无法开启自动调速");
            autoSwitch.setChecked(false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        autoEnabled = true;
        autoTarget = 0;
        if (hasPhonePermission()) {
            registerCallListener();
            syncCallState();
        } else {
            requestPermissions(new String[] {Manifest.permission.READ_PHONE_STATE},
                    REQ_PHONE_STATE);
            startVoiceWatch();
        }
        setManualEnabled(false);
        Intent svc = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        log("自动调速: 开启");
        if (autoThread != null) {
            return;
        }
        autoThread = new Thread(new Runnable() {
            public void run() {
                autoLoop();
            }
        }, "auto-fan");
        autoThread.start();
    }

    private void stopAuto(boolean updateSwitch) {
        boolean was = autoEnabled;
        autoEnabled = false;
        autoThread = null;
        voicePaused = false;
        stopVoiceWatch();
        setManualEnabled(true);
        stopService(new Intent(this, KeepAliveService.class));
        if (was) {
            log("自动调速: 关闭");
        }
        if (updateSwitch && autoSwitch != null && autoSwitch.isChecked()) {
            autoSwitch.setChecked(false);
        }
        if (tempBar != null) {
            ui.post(new Runnable() {
                public void run() {
                    tempBar.setProgress(0);
                    targetView.setText("目标转速 --");
                }
            });
        }
    }

    private void autoLoop() {
        int lastSent = -1;
        float smooth = -1f;
        while (autoEnabled && conn != null) {
            float soc = readSocTemp();
            float batt = readBatteryTemp();
            // Battery temperature is the control source: it changes slowly and
            // does not jitter like the SoC thermistors.  A light EMA removes
            // the remaining 0.1 °C steps.
            if (batt > 0) {
                smooth = (smooth < 0) ? batt : (smooth * 0.5f + batt * 0.5f);
            }
            float control = smooth > 0 ? smooth : soc;
            if (voicePaused) {
                // Microphone in use: keep the fan at 0 until voice input ends.
                if (lastSent != 0) {
                    sendSpeed(0, true);
                    lastSent = 0;
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (targetView != null) {
                            targetView.setText("语音输入中 · 风扇暂停");
                        }
                    }
                });
            } else if (control > 0) {
                int desired = desiredByTemp(control);
                if (desired > autoTarget) {
                    autoTarget = desired;
                } else if (desired < autoTarget && control <= lowerBound(autoTarget)) {
                    autoTarget = desired;
                }
                if (autoTarget != lastSent) {
                    sendSpeed(autoTarget, true);
                    lastSent = autoTarget;
                }
                updateTempUi(soc, smooth, autoTarget, smooth > 0);
            } else {
                updateTempUi(-1, -1, autoTarget, false);
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Fan steps follow Sony's ThermalFanService table
     * (3900 / 4800 / 5700 / 6600 / 7500), but the thresholds are expressed in
     * battery temperature, which runs lower and much steadier than the SoC.
     */
    private int desiredByTemp(float t) {
        if (t >= 42f) return 7500;
        if (t >= 40f) return 6600;
        if (t >= 38f) return 5700;
        if (t >= 36f) return 4800;
        if (t >= 34f) return 3900;
        return 0;
    }

    /** 2 °C of hysteresis per step, so the fan does not hunt. */
    private float lowerBound(int speed) {
        switch (speed) {
            case 7500: return 40f;
            case 6600: return 38f;
            case 5700: return 36f;
            case 4800: return 34f;
            case 3900: return 32f;
            default: return 100f;
        }
    }

    private void updateTempUi(final float soc, final float batt, final int target, final boolean battOk) {
        ui.post(new Runnable() {
            public void run() {
                if (battOk) {
                    tempView.setText(String.format(Locale.US, "电池 %.1f °C", batt));
                } else if (soc > 0) {
                    tempView.setText(String.format(Locale.US, "SoC %.1f °C", soc));
                } else {
                    tempView.setText("温度不可读");
                }
                StringBuilder sb = new StringBuilder();
                sb.append("目标 ").append(target).append(" rpm");
                if (battOk && soc > 0) {
                    sb.append(String.format(Locale.US, " · SoC %.1f °C", soc));
                } else if (!battOk) {
                    sb.append("（电池温度不可读，暂用 SoC）");
                }
                targetView.setText(sb.toString());
                tempBar.setProgress(target);
            }
        });
    }

    private void updateTargetUi(final int rpm) {
        ui.post(new Runnable() {
            public void run() {
                if (targetView != null && autoEnabled) {
                    targetView.setText(voicePaused
                            ? "语音输入中 · 风扇暂停"
                            : "目标 " + rpm + " rpm");
                }
            }
        });
    }

    /** Reads the hottest SoC/board thermal zone; returns -1 when unavailable. */
    private float readSocTemp() {
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) {
            return -1;
        }
        float best = -1;
        for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) {
                continue;
            }
            String type = readFile(new File(z, "type")).trim().toLowerCase(Locale.US);
            if (!isSocType(type)) {
                continue;
            }
            String raw = readFile(new File(z, "temp")).trim();
            if (raw.length() == 0) {
                continue;
            }
            try {
                float v = Float.parseFloat(raw) / 1000f;
                if (v > 0 && v < 150 && v > best) {
                    best = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private boolean isSocType(String t) {
        return t.contains("cpu") || t.contains("soc") || t.contains("sdm")
                || t.contains("tsens") || t.contains("mtk") || t.contains("gpu")
                || t.contains("skin") || t.contains("board") || t.contains("ap-")
                || t.contains("quiet") || t.contains("xo-therm");
    }

    private float readBatteryTemp() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) {
                return -1;
            }
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            return t > 0 ? t / 10f : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String readFile(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line = r.readLine();
            return line == null ? "" : line;
        } catch (Exception e) {
            return "";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void setManualEnabled(final boolean enabled) {
        ui.post(new Runnable() {
            public void run() {
                for (Button b : manualButtons) {
                    b.setEnabled(enabled);
                    b.setAlpha(enabled ? 1f : 0.4f);
                }
            }
        });
    }

    // ------------------------------------------------------ detection

    private void refreshDetection() {
        if (detBusy) {
            return;
        }
        detBusy = true;
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final Detectors.Det c = safeDetect(0, ctx);
                final Detectors.Det a = safeDetect(1, ctx);
                final Detectors.Det h = safeDetect(2, ctx);
                final Detectors.Det e = safeDetect(3, ctx);
                detCache[0] = c;
                detCache[1] = a;
                detCache[2] = h;
                detCache[3] = e;
                ui.post(new Runnable() {
                    public void run() {
                        applyDet(0, c);
                        applyDet(1, a);
                        applyDet(2, h);
                        applyDet(3, e);
                        detBusy = false;
                    }
                });
            }
        }, "detect").start();
    }

    private Detectors.Det safeDetect(int which, Context ctx) {
        try {
            switch (which) {
                case 0:
                    return Detectors.charge(ctx);
                case 1:
                    return Detectors.audio(ctx);
                case 2:
                    return Detectors.hdmi(ctx);
                default:
                    return Detectors.eth(ctx, ethPrev);
            }
        } catch (Throwable t) {
            Log.e(TAG, "detector " + which + " failed", t);
            return new Detectors.Det("FAIL", "检测异常: " + t.getClass().getSimpleName(),
                    Log.getStackTraceString(t));
        }
    }

    private void applyDet(int i, Detectors.Det d) {
        if (detValue[i] == null) {
            return;
        }
        detValue[i].setText(d.text);
        int fg;
        int bgc;
        if ("OK".equals(d.status)) {
            fg = C_GREEN;
            bgc = C_GREEN_BG;
        } else if ("WARN".equals(d.status)) {
            fg = 0xFF92400E;
            bgc = 0xFFFEF3C7;
        } else {
            fg = C_RED;
            bgc = C_RED_BG;
        }
        detStatus[i].setText(d.status);
        detStatus[i].setTextColor(fg);
        detStatus[i].setBackground(bg(bgc, 12));
    }

    private void exportReport() {
        final String content = buildDetReport();
        final String name = "stream_diag_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) {
                        throw new RuntimeException("MediaStore insert returned null");
                    }
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(content.getBytes("UTF-8"));
                    os.close();
                    msg = "已保存到 Download/" + name;
                    Log.i(TAG, msg);
                } catch (Exception ex) {
                    msg = "导出失败: " + ex;
                    Log.e(TAG, msg, ex);
                }
                final String fmsg = msg;
                ui.post(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, fmsg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "export").start();
    }

    private String buildDetReport() {
        String[] names = {"充电", "3.5mm", "HDMI", "网卡"};
        StringBuilder sb = new StringBuilder();
        sb.append("Xperia Stream 接口检测\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("\n\n");
        for (int i = 0; i < 4; i++) {
            Detectors.Det d = detCache[i];
            sb.append("[").append(names[i]).append("] ");
            if (d == null) {
                sb.append("尚未检测\n\n");
                continue;
            }
            sb.append(d.status).append("  ").append(d.text).append("\n");
            sb.append(d.detail).append("\n");
        }
        sb.append("---\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        return sb.toString();
    }

    // --------------------------------------------------------- helpers

    private void registerReceivers() {
        if (receiversRegistered) {
            return;
        }
        IntentFilter perm = new IntentFilter(ACTION_PERM);
        IntentFilter usbEvents = new IntentFilter();
        usbEvents.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbEvents.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(permReceiver, perm, Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(usbReceiver, usbEvents, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(permReceiver, perm);
                registerReceiver(usbReceiver, usbEvents);
            }
            receiversRegistered = true;
        } catch (Throwable t) {
            Log.e(TAG, "registerReceivers failed", t);
        }
    }

    private void unregisterReceivers() {
        if (!receiversRegistered) {
            return;
        }
        try {
            unregisterReceiver(permReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        receiversRegistered = false;
    }

    private void log(final String s) {
        Log.i(TAG, s);
        ui.post(new Runnable() {
            public void run() {
                logBuf.append(s).append('\n');
                if (logBuf.length() > 20000) {
                    logBuf.delete(0, logBuf.length() - 20000);
                }
                if (logView != null) {
                    logView.setText(logBuf);
                }
                if (logScroll != null) {
                    logScroll.post(new Runnable() {
                        public void run() {
                            logScroll.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
        });
    }
}
