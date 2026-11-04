package com.xperiastream.fanctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
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
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private int C_BG;
    private int C_CARD;
    private int C_TEXT;
    private int C_MUTED;
    private int C_PRIMARY;
    private int C_PRIMARY_SOFT;
    private int C_GREEN;
    private int C_GREEN_BG;
    private int C_RED;
    private int C_RED_BG;
    private int C_GRAY_BTN;
    private int C_OUTLINE;
    private int C_AMBER;
    private int C_AMBER_BG;

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
    private volatile int lastSentRpm = 0;
    private Thread autoThread;

    // four-in-one detection panel
    private final TextView[] detValue = new TextView[4];
    private final TextView[] detStatus = new TextView[4];
    private final TextView[] secChip = new TextView[4];
    private final TextView[] secDetail = new TextView[4];
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
    private volatile boolean callWatchdog = false;
    private static final int REQ_PHONE_STATE = 2;
    private Switch voiceSwitch;
    private int heartbeatLogs = 0;

    // ---- v2.0 UI state ----
    private SharedPreferences prefs;
    private boolean darkMode = false;
    private boolean ledEnabled = true;
    private boolean hdmiEnabled = true;
    private boolean autoConnect = true;
    private String profileId = "balanced";
    private TextView rpmView;
    private TextView modeView;
    private TextView chargeWatts;
    private TextView chargeDetail;
    private TextView chargeTemp;
    private SparkView spark;
    private Switch ledSwitch;
    private Switch hdmiSwitch;
    private Switch autoConnectSwitch;
    private final Button[] profileBtns = new Button[4];
    private TextView profileHint;
    private final List<Float> chargeHist = new ArrayList<Float>();

    // v2.1 controls
    private Switch fanOnSwitch;
    private boolean fanOn = true;
    private TextView speedValueView;
    private TextView hdmiModeValueView;
    private TextView fwView;
    private SeekBar mediaBar;
    private SeekBar callBar;
    private TextView camAppsView;
    private Switch camSwitch;
    private TextView camStatusView;
    private Button customBtn;
    private int[] customTemps = {34, 36, 38, 40, 42};
    private int[] customSpeeds = {3900, 4800, 5700, 6600, 7500};
    private int hdmiModeId = -1;
    private volatile String lastHdmiFw = null;
    private static final int[] ALL_SPEEDS = {0, 4800, 5700, 6600, 7500, 8400, 9300, 10200};

    private static final String[] PROFILE_IDS = {"balanced", "performance"};
    private static final String[] PROFILE_NAMES = {"均衡", "性能"};
    private static final float[][] PROFILE_THRESHOLDS = {
            {34f, 36f, 38f, 40f, 42f},
            {32f, 34f, 36f, 38f, 40f}
    };
    private static final int[][] PROFILE_SPEEDS = {
            {3900, 4800, 5700, 6600, 7500},
            {5700, 6600, 7500, 8400, 10200}
    };

    private final Runnable detTick = new Runnable() {
        public void run() {
            refreshDetection();
            refreshCharge();
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
                if (!autoConnect) {
                    return;
                }
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
        prefs = getSharedPreferences("fanctl", MODE_PRIVATE);
        darkMode = prefs.getBoolean("dark", true);
        ledEnabled = prefs.getBoolean("led", true);
        hdmiEnabled = prefs.getBoolean("hdmi", true);
        autoConnect = prefs.getBoolean("autoConnect", true);
        voicePauseEnabled = prefs.getBoolean("voicePause", true);
        fanOn = prefs.getBoolean("fanOn", true);
        profileId = prefs.getString("profile", "balanced");
        if (!"balanced".equals(profileId) && !"performance".equals(profileId)
                && !"custom".equals(profileId)) {
            profileId = "balanced";
        }
        customTemps = splitInts(prefs.getString("customTemps", null), customTemps);
        customSpeeds = splitInts(prefs.getString("customSpeeds", null), customSpeeds);
        applyPalette(darkMode);
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
        String cmd = getIntent() != null ? getIntent().getStringExtra("cmd") : null;
        if (cmd != null) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    scan();
                }
            }, 400);
        }
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
        boolean active = state == TelephonyManager.CALL_STATE_RINGING
                || state == TelephonyManager.CALL_STATE_OFFHOOK;
        callActive = active;
        if (!active) {
            // The call may have left a stale "recording" config behind, and its
            // callback may never fire again.  Clear it and resume immediately.
            micActive = false;
            onVoiceState(false);
            return;
        }
        onVoiceState(true);
        startCallWatchdog();
    }

    /**
     * While a call is active, re-check the real call state every 2 s.  This is
     * not a general poll: it only runs between RINGING/OFFHOOK and hang-up, and
     * exists because some ROMs do not deliver a reliable CALL_STATE_IDLE event.
     */
    private void startCallWatchdog() {
        if (callWatchdog) {
            return;
        }
        callWatchdog = true;
        new Thread(new Runnable() {
            public void run() {
                while (callWatchdog && autoEnabled) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (!callWatchdog) {
                        break;
                    }
                    int st = callStateNow();
                    boolean inCall = st == TelephonyManager.CALL_STATE_RINGING
                            || st == TelephonyManager.CALL_STATE_OFFHOOK;
                    if (!inCall && !micActive) {
                        onVoiceState(false);
                        break;
                    }
                }
                callWatchdog = false;
            }
        }, "call-watchdog").start();
    }

    private int callStateNow() {
        if (telephony != null && hasPhonePermission()) {
            try {
                return telephony.getCallState();
            } catch (Throwable ignored) {
            }
        }
        return TelephonyManager.CALL_STATE_IDLE;
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

    @Deprecated
    private View buildUiLegacy() {
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
                if (prefs != null) {
                    prefs.edit().putBoolean("voicePause", isChecked).apply();
                }
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

    // ------------------------------------------------------------ UI v2

    @Deprecated
    private View buildUiV2() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("Stream 控制台", 22f, C_TEXT, true));
        TextView sub = text("v2.0 · Xperia Stream · 免 Sony 服务", 11f, C_MUTED, false);
        sub.setPadding(0, dp(3), 0, 0);
        titleBox.addView(sub);
        header.addView(titleBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button themeBtn = action(darkMode ? "☀" : "☾", false, new View.OnClickListener() {
            public void onClick(View v) {
                darkMode = !darkMode;
                if (prefs != null) {
                    prefs.edit().putBoolean("dark", darkMode).apply();
                }
                applyPalette(darkMode);
                try {
                    setContentView(buildUi());
                } catch (Throwable t) {
                    Log.e(TAG, "rebuild ui failed", t);
                }
            }
        });
        themeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        header.addView(themeBtn, new LinearLayout.LayoutParams(dp(46), dp(40)));
        root.addView(header, rowLp());

        // status + connection
        statusChip = text("未连接", 12f, C_MUTED, true);
        statusChip.setPadding(dp(12), dp(5), dp(12), dp(5));
        statusChip.setBackground(bg(C_GRAY_BTN, 20));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp.bottomMargin = dp(10);
        root.addView(statusChip, chipLp);

        LinearLayout connRow = new LinearLayout(this);
        connRow.setOrientation(LinearLayout.HORIZONTAL);
        connRow.addView(action("连接 / 重新扫描", true, new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        }), speedLp());
        connRow.addView(action("断开", false, new View.OnClickListener() {
            public void onClick(View v) {
                disconnect();
                setChip("已断开", C_MUTED, C_GRAY_BTN);
            }
        }), speedLp());
        root.addView(connRow, rowLp());

        autoConnectSwitch = new Switch(this);
        autoConnectSwitch.setChecked(autoConnect);
        autoConnectSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                autoConnect = v;
                if (prefs != null) {
                    prefs.edit().putBoolean("autoConnect", v).apply();
                }
            }
        });
        root.addView(toggleRow("插入 Stream 时自动连接", autoConnectSwitch), rowLp());

        // fan card
        LinearLayout fanCard = card();
        fanCard.addView(text("风扇", 15f, C_TEXT, true));
        rpmView = text("--", 42f, C_TEXT, true);
        rpmView.setPadding(0, dp(2), 0, 0);
        fanCard.addView(rpmView);
        modeView = text("未连接", 12f, C_MUTED, false);
        modeView.setPadding(0, 0, 0, dp(8));
        fanCard.addView(modeView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(speedButton("关闭", 0), speedLp());
        row1.addView(speedButton("低 4800", 4800), speedLp());
        row1.addView(speedButton("中 5700", 5700), speedLp());
        fanCard.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(speedButton("高 6600", 6600), speedLp());
        row2.addView(speedButton("最大 7500", 7500), speedLp());
        Button query = action("查询", false, new View.OnClickListener() {
            public void onClick(View v) {
                sendRaw(";0,03,00\n");
            }
        });
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        row2.addView(query, speedLp());
        fanCard.addView(row2);

        ledSwitch = new Switch(this);
        ledSwitch.setChecked(ledEnabled);
        ledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setLed(v);
            }
        });
        fanCard.addView(toggleRow("配件 LED", ledSwitch));

        hdmiSwitch = new Switch(this);
        hdmiSwitch.setChecked(hdmiEnabled);
        hdmiSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setHdmi(v);
            }
        });
        fanCard.addView(toggleRow("HDMI 输出", hdmiSwitch));
        root.addView(fanCard);

        // auto card
        LinearLayout autoCard = card();
        LinearLayout autoHead = new LinearLayout(this);
        autoHead.setOrientation(LinearLayout.HORIZONTAL);
        autoHead.setGravity(Gravity.CENTER_VERTICAL);
        autoHead.addView(text("自动调速", 15f, C_TEXT, true), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        autoSwitch = new Switch(this);
        autoSwitch.setChecked(autoEnabled);
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

        tempView = text("温度 --", 26f, C_TEXT, true);
        tempView.setPadding(0, dp(10), 0, dp(2));
        autoCard.addView(tempView);

        targetView = text("目标转速 --", 12f, C_MUTED, false);
        targetView.setPadding(0, 0, 0, dp(8));
        autoCard.addView(targetView);

        tempBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        tempBar.setMax(9000);
        tempBar.setProgress(0);
        tempBar.setProgressTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY));
        tempBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(C_GRAY_BTN));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        autoCard.addView(tempBar, barLp);

        LinearLayout profRow = new LinearLayout(this);
        profRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            final int idx = i;
            Button b = action(PROFILE_NAMES[i], false, new View.OnClickListener() {
                public void onClick(View v) {
                    setProfile(PROFILE_IDS[idx]);
                }
            });
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            profileBtns[i] = b;
            profRow.addView(b, speedLp());
        }
        Button cb = action("自定义", false, new View.OnClickListener() {
            public void onClick(View v) {
                setProfile("custom");
                showTemplateEditor();
            }
        });
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        customBtn = cb;
        profRow.addView(cb, speedLp());
        autoCard.addView(profRow);

        profileHint = text("", 11f, C_MUTED, false);
        profileHint.setPadding(0, dp(6), 0, dp(4));
        autoCard.addView(profileHint);

        voiceSwitch = new Switch(this);
        voiceSwitch.setChecked(voicePauseEnabled);
        voiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                voicePauseEnabled = isChecked;
                if (prefs != null) {
                    prefs.edit().putBoolean("voicePause", isChecked).apply();
                }
                if (!isChecked && voicePaused) {
                    voicePaused = false;
                    sendSpeed(autoTarget, true);
                    log("语音暂停已关闭，恢复 " + autoTarget + " rpm");
                }
            }
        });
        autoCard.addView(toggleRow("语音 / 通话时暂停风扇", voiceSwitch));
        root.addView(autoCard);

        // charge monitor card
        LinearLayout chargeCard = card();
        chargeCard.addView(text("充电监控", 15f, C_TEXT, true));
        chargeWatts = text("-- W", 34f, C_TEXT, true);
        chargeWatts.setPadding(0, dp(2), 0, 0);
        chargeCard.addView(chargeWatts);
        chargeDetail = text("等待数据…", 12f, C_MUTED, false);
        chargeDetail.setPadding(0, 0, 0, dp(6));
        chargeCard.addView(chargeDetail);

        spark = new SparkView(this, C_PRIMARY, C_GRAY_BTN);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        chargeCard.addView(spark, spLp);

        chargeTemp = text("电池温度 --", 12f, C_MUTED, false);
        chargeTemp.setPadding(0, dp(6), 0, 0);
        chargeCard.addView(chargeTemp);
        root.addView(chargeCard);

        // interface detection card
        LinearLayout detCard = card();
        detCard.addView(text("接口检测", 15f, C_TEXT, true));

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
            chip.setBackground(bg(C_GRAY_BTN, 12));
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

        // log card
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

        updateProfileUi();
        refreshRpmUi();
        return scroll;
    }

    private View toggleRow(String label, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        row.addView(text(label, 13f, C_TEXT, false), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        styleSwitch(sw);
        row.addView(sw);
        return row;
    }

    private void updateProfileUi() {
        int active = profileIndex();
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            if (profileBtns[i] == null) {
                continue;
            }
            boolean on = i == active;
            profileBtns[i].setTextColor(on ? Color.WHITE : C_TEXT);
            profileBtns[i].setBackground(bg(on ? C_PRIMARY : C_GRAY_BTN, 12));
        }
        if (customBtn != null) {
            boolean on = active < 0;
            customBtn.setTextColor(on ? Color.WHITE : C_TEXT);
            customBtn.setBackground(bg(on ? C_PRIMARY : C_GRAY_BTN, 12));
        }
        if (profileHint != null) {
            float[] th = activeThresholds();
            int[] sp = activeSpeeds();
            String name = active < 0 ? "自定义" : PROFILE_NAMES[active];
            profileHint.setText(String.format(Locale.US,
                    "%s：%.0f / %.0f / %.0f / %.0f / %.0f °C → %d / %d / %d / %d / %d rpm",
                    name, th[0], th[1], th[2], th[3], th[4],
                    sp[0], sp[1], sp[2], sp[3], sp[4]));
        }
    }

    private float[] activeThresholds() {
        int idx = profileIndex();
        if (idx < 0) {
            float[] t = new float[5];
            for (int i = 0; i < 5; i++) {
                t[i] = customTemps[i];
            }
            return t;
        }
        return PROFILE_THRESHOLDS[idx];
    }

    private int[] activeSpeeds() {
        int idx = profileIndex();
        return idx < 0 ? customSpeeds : PROFILE_SPEEDS[idx];
    }

    private void setProfile(String id) {
        profileId = id;
        if (prefs != null) {
            prefs.edit().putString("profile", id).apply();
        }
        updateProfileUi();
        log("自动档位: " + id);
    }

    private int profileIndex() {
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            if (PROFILE_IDS[i].equals(profileId)) {
                return i;
            }
        }
        return -1;
    }

    private void setLed(boolean on) {
        ledEnabled = on;
        if (prefs != null) {
            prefs.edit().putBoolean("led", on).apply();
        }
        sendRaw(on ? ";0,42,00\n" : ";0,42,01\n");
    }

    private void setHdmi(boolean on) {
        hdmiEnabled = on;
        if (prefs != null) {
            prefs.edit().putBoolean("hdmi", on).apply();
        }
        // 0x43 SET_FORCE_HDMI_OFF: 1 = force off, 0 = normal
        sendRaw(on ? ";0,43,00\n" : ";0,43,01\n");
    }

    private void refreshRpmUi() {
        if (rpmView == null || modeView == null) {
            return;
        }
        if (conn == null) {
            rpmView.setText("--");
            modeView.setText("未连接");
            return;
        }
        rpmView.setText(autoEnabled ? String.valueOf(autoTarget) : String.valueOf(lastSentRpm));
        if (autoEnabled) {
            int pi = profileIndex();
            String pname = pi < 0 ? "自定义" : PROFILE_NAMES[pi];
            modeView.setText(voicePaused
                    ? "自动 · 语音/通话中，风扇暂停"
                    : "自动 · " + pname);
        } else {
            modeView.setText("手动");
        }
    }

    private void refreshCharge() {
        new Thread(new Runnable() {
            public void run() {
                File batt = new File("/sys/class/power_supply/battery");
                long uv = toLong(readAttr(new File(batt, "voltage_now")));
                long ua = toLong(readAttr(new File(batt, "current_now")));
                long cap = toLong(readAttr(new File(batt, "capacity")));
                long t10 = toLong(readAttr(new File(batt, "temp")));
                Intent it = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int plugged = it != null ? it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
                if (cap < 0 && it != null) {
                    cap = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                }
                final double w = (uv > 0 && ua != 0) ? uv * Math.abs(ua) / 1e12 : -1;
                final double v = uv > 0 ? uv / 1e6 : -1;
                final double a = ua != 0 ? Math.abs(ua) / 1e6 : -1;
                final int level = (int) cap;
                final float temp = t10 > 0 ? t10 / 10f : -1f;
                final int plug = plugged;
                ui.post(new Runnable() {
                    public void run() {
                        if (chargeWatts == null) {
                            return;
                        }
                        if (w >= 0) {
                            chargeWatts.setText(String.format(Locale.US, "%.2f W", w));
                            chargeWatts.setTextColor(plug != 0 ? C_GREEN : C_TEXT);
                        } else {
                            chargeWatts.setText("-- W");
                            chargeWatts.setTextColor(C_MUTED);
                        }
                        if (v > 0 && a > 0) {
                            chargeDetail.setText(String.format(Locale.US,
                                    "%.2f V · %.2f A · 电池 %d%%", v, a, level));
                        } else {
                            chargeDetail.setText("电池 " + level + "%");
                        }
                        if (temp > 0) {
                            chargeTemp.setText(String.format(Locale.US, "电池温度 %.1f °C", temp));
                        }
                        chargeHist.add(w >= 0 ? (float) w : 0f);
                        while (chargeHist.size() > 60) {
                            chargeHist.remove(0);
                        }
                        if (spark != null) {
                            spark.setData(chargeHist);
                        }
                    }
                });
            }
        }, "charge").start();
    }

    private String readAttr(File f) {
        if (f == null || !f.exists()) {
            return "";
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line = r.readLine();
            return line == null ? "" : line.trim();
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

    private long toLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Minimal filled line chart for the charging power history. */
    static class SparkView extends View {
        private final List<Float> data = new ArrayList<Float>();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path area = new Path();

        SparkView(Context c, int accent, int gridColor) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2.5f);
            line.setColor(accent);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(accent);
            fill.setAlpha(40);
            grid.setColor(gridColor);
            grid.setStrokeWidth(1f);
        }

        void setData(List<Float> d) {
            data.clear();
            data.addAll(d);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            canvas.drawLine(0, h - 1, w, h - 1, grid);
            if (data.size() < 2) {
                return;
            }
            float max = 5f;
            for (float v : data) {
                if (v > max) {
                    max = v;
                }
            }
            path.reset();
            area.reset();
            int n = data.size();
            for (int i = 0; i < n; i++) {
                float x = (float) i / (n - 1) * w;
                float y = h - 4 - (data.get(i) / max) * (h - 10);
                if (i == 0) {
                    path.moveTo(x, y);
                    area.moveTo(x, h);
                    area.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    area.lineTo(x, y);
                }
            }
            area.lineTo(w, h);
            area.close();
            canvas.drawPath(area, fill);
            canvas.drawPath(path, line);
        }
    }

    // ------------------------------------------------------------ UI v2.1

    @Deprecated
    private View buildUiV21() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Xperia Stream", 30f, C_TEXT, true));
        TextView sub = text("风扇 · 显示 · 音频 · 接口", 12f, C_MUTED, false);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button themeBtn = outlineButton(darkMode ? "☀" : "☾", new View.OnClickListener() {
            public void onClick(View v) {
                darkMode = !darkMode;
                prefs.edit().putBoolean("dark", darkMode).apply();
                applyPalette(darkMode);
                try {
                    setContentView(buildUi());
                } catch (Throwable t) {
                    Log.e(TAG, "rebuild ui failed", t);
                }
            }
        });
        header.addView(themeBtn, new LinearLayout.LayoutParams(dp(46), dp(42)));
        root.addView(header, rowLp());

        // connection card
        LinearLayout conn = new LinearLayout(this);
        conn.setOrientation(LinearLayout.VERTICAL);
        conn.setBackground(bg(C_PRIMARY_SOFT, 22));
        conn.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams connLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        connLp.bottomMargin = dp(14);
        conn.setLayoutParams(connLp);

        statusChip = text("正在扫描…", 13f, C_TEXT, true);
        statusChip.setPadding(dp(12), dp(5), dp(12), dp(5));
        statusChip.setBackground(bg(C_CARD, 16));
        conn.addView(statusChip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView devLine = text("连接 Xperia Stream 后可调整以下设置", 12f, C_TEXT, false);
        devLine.setPadding(0, dp(8), 0, dp(10));
        conn.addView(devLine);

        LinearLayout connRow = new LinearLayout(this);
        connRow.setOrientation(LinearLayout.HORIZONTAL);
        connRow.addView(action("刷新", false, new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        }), rowBtnLp());
        connRow.addView(action("连接", true, new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        }), rowBtnLp());
        conn.addView(connRow);
        root.addView(conn);

        // ---- cooling ----
        root.addView(section("散热"));
        LinearLayout cool = card();

        fanOnSwitch = new Switch(this);
        fanOnSwitch.setChecked(fanOn);
        fanOnSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                fanOn = v;
                prefs.edit().putBoolean("fanOn", v).apply();
                sendRaw(v ? ";0,0C,00\n" : ";0,0C,01\n");
            }
        });
        cool.addView(toggleRow("启用风扇", fanOnSwitch));

        ledSwitch = new Switch(this);
        ledSwitch.setChecked(ledEnabled);
        ledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setLed(v);
            }
        });
        cool.addView(toggleRow("启用风扇灯效", ledSwitch));

        speedValueView = text("5700 RPM", 16f, C_TEXT, false);
        cool.addView(selectorRow("风扇转速", speedValueView, new View.OnClickListener() {
            public void onClick(View v) {
                showSpeedDialog();
            }
        }));

        Button readSpeed = outlineButton("读取转速", new View.OnClickListener() {
            public void onClick(View v) {
                sendRaw(";0,03,00\n");
            }
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rbLp.topMargin = dp(10);
        cool.addView(readSpeed, rbLp);
        root.addView(cool);

        // ---- auto ----
        root.addView(section("自动调速"));
        LinearLayout autoCard = card();

        LinearLayout autoHead = new LinearLayout(this);
        autoHead.setOrientation(LinearLayout.HORIZONTAL);
        autoHead.setGravity(Gravity.CENTER_VERTICAL);
        autoHead.addView(text("自动调速", 14f, C_TEXT, true), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        autoSwitch = new Switch(this);
        autoSwitch.setChecked(autoEnabled);
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

        tempView = text("温度 --", 24f, C_TEXT, true);
        tempView.setPadding(0, dp(8), 0, dp(2));
        autoCard.addView(tempView);

        targetView = text("目标转速 --", 12f, C_MUTED, false);
        targetView.setPadding(0, 0, 0, dp(8));
        autoCard.addView(targetView);

        tempBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        tempBar.setMax(10200);
        tempBar.setProgress(0);
        tempBar.setProgressTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY));
        tempBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(C_GRAY_BTN));
        autoCard.addView(tempBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));

        LinearLayout profRow = new LinearLayout(this);
        profRow.setOrientation(LinearLayout.HORIZONTAL);
        profRow.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            final int idx = i;
            Button b = action(PROFILE_NAMES[i], false, new View.OnClickListener() {
                public void onClick(View v) {
                    setProfile(PROFILE_IDS[idx]);
                }
            });
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            profileBtns[i] = b;
            profRow.addView(b, speedLp());
        }
        Button cb = action("自定义", false, new View.OnClickListener() {
            public void onClick(View v) {
                setProfile("custom");
                showTemplateEditor();
            }
        });
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        customBtn = cb;
        profRow.addView(cb, speedLp());
        autoCard.addView(profRow);

        profileHint = text("", 11f, C_MUTED, false);
        profileHint.setPadding(0, dp(8), 0, 0);
        autoCard.addView(profileHint);

        Button editTpl = outlineButton("编辑模板（温度 ↔ 转速）", new View.OnClickListener() {
            public void onClick(View v) {
                showTemplateEditor();
            }
        });
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        etLp.topMargin = dp(8);
        autoCard.addView(editTpl, etLp);

        voiceSwitch = new Switch(this);
        voiceSwitch.setChecked(voicePauseEnabled);
        voiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                voicePauseEnabled = isChecked;
                prefs.edit().putBoolean("voicePause", isChecked).apply();
                if (!isChecked && voicePaused) {
                    voicePaused = false;
                    sendSpeed(autoTarget, true);
                    log("语音暂停已关闭，恢复 " + autoTarget + " rpm");
                }
            }
        });
        autoCard.addView(toggleRow("语音 / 通话时暂停风扇", voiceSwitch));
        root.addView(autoCard);

        // ---- display & audio ----
        root.addView(section("显示与音频"));
        LinearLayout av = card();

        hdmiSwitch = new Switch(this);
        hdmiSwitch.setChecked(hdmiEnabled);
        hdmiSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setHdmi(v);
            }
        });
        av.addView(toggleRow("启用 HDMI 视频和音频输出", hdmiSwitch));

        hdmiModeValueView = text("自动", 16f, C_TEXT, false);
        av.addView(selectorRow("显示模式", hdmiModeValueView, new View.OnClickListener() {
            public void onClick(View v) {
                showDisplayModeDialog();
            }
        }));

        fwView = text("固件版本 --", 12f, C_MUTED, false);
        fwView.setPadding(0, dp(8), 0, 0);
        av.addView(fwView);

        Button fwBtn = outlineButton("读取 HDMI 固件版本", new View.OnClickListener() {
            public void onClick(View v) {
                queryHdmiFw();
            }
        });
        LinearLayout.LayoutParams fwLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        fwLp.topMargin = dp(8);
        av.addView(fwBtn, fwLp);

        av.addView(sliderRow("媒体音量", true));
        av.addView(sliderRow("通话音量", false));
        root.addView(av);

        // ---- camera key ----
        root.addView(section("相机键截图"));
        LinearLayout cam = card();
        camSwitch = new Switch(this);
        camSwitch.setChecked(prefs.getBoolean("camShot", false));
        camSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.edit().putBoolean("camShot", v).apply();
                updateCamStatus();
            }
        });
        cam.addView(toggleRow("启用相机键截图", camSwitch));

        camAppsView = text("已选应用：无", 12f, C_MUTED, false);
        camAppsView.setPadding(0, dp(6), 0, 0);
        cam.addView(camAppsView);

        camStatusView = text("", 12f, C_MUTED, false);
        camStatusView.setPadding(0, dp(4), 0, 0);
        cam.addView(camStatusView);

        Button pickApps = outlineButton("选择应用（可多选）", new View.OnClickListener() {
            public void onClick(View v) {
                showAppPicker();
            }
        });
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        paLp.topMargin = dp(8);
        cam.addView(pickApps, paLp);

        Button openA11y = outlineButton("打开无障碍设置", new View.OnClickListener() {
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        LinearLayout.LayoutParams oaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        oaLp.topMargin = dp(8);
        cam.addView(openA11y, oaLp);
        root.addView(cam);

        // ---- detection ----
        root.addView(section("接口检测"));
        LinearLayout detCard = card();
        String[] names = {"充电", "3.5mm", "HDMI", "网卡"};
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
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
            chip.setBackground(bg(C_GRAY_BTN, 12));
            row.addView(chip);
            detValue[i] = value;
            detStatus[i] = chip;
            detCard.addView(row);
        }
        Button export = outlineButton("导出检测报告", new View.OnClickListener() {
            public void onClick(View v) {
                exportReport();
            }
        });
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        exLp.topMargin = dp(10);
        detCard.addView(export, exLp);
        root.addView(detCard);

        // ---- charge ----
        root.addView(section("充电监控"));
        LinearLayout chargeCard = card();
        chargeWatts = text("-- W", 30f, C_TEXT, true);
        chargeCard.addView(chargeWatts);
        chargeDetail = text("等待数据…", 12f, C_MUTED, false);
        chargeCard.addView(chargeDetail);
        spark = new SparkView(this, C_PRIMARY, C_GRAY_BTN);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        spLp.topMargin = dp(6);
        chargeCard.addView(spark, spLp);
        chargeTemp = text("电池温度 --", 12f, C_MUTED, false);
        chargeTemp.setPadding(0, dp(6), 0, 0);
        chargeCard.addView(chargeTemp);
        root.addView(chargeCard);

        // ---- log ----
        root.addView(section("日志"));
        LinearLayout logCard = card();
        logView = text("", 10f, C_MUTED, false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        logCard.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));
        root.addView(logCard);

        updateProfileUi();
        refreshRpmUi();
        updateVolumeUi();
        updateCamStatus();
        return scroll;
    }

    private void updateCamStatus() {
        if (camAppsView == null) {
            return;
        }
        Set<String> apps = prefs.getStringSet("camApps", null);
        camAppsView.setText("已选应用：" + (apps == null || apps.isEmpty() ? "无" : apps.size() + " 个"));
        if (camStatusView != null) {
            boolean on = isAccessibilityEnabled();
            camStatusView.setText(on ? "无障碍服务：已启用" : "无障碍服务：未启用，点下方按钮开启");
            camStatusView.setTextColor(on ? C_GREEN : C_RED);
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/")
                && enabled.contains("CameraKeyService");
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            log("打开无障碍设置失败: " + t);
        }
    }

    private void showAppPicker() {
        final PackageManager pm = getPackageManager();
        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        final List<String> pkgs = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        for (ApplicationInfo ai : infos) {
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) {
                continue;
            }
            pkgs.add(ai.packageName);
            labels.add(pm.getApplicationLabel(ai).toString());
        }
        Integer[] order = new Integer[pkgs.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return labels.get(a).compareToIgnoreCase(labels.get(b));
            }
        });
        final String[] names = new String[order.length];
        final String[] pkgArr = new String[order.length];
        for (int i = 0; i < order.length; i++) {
            names[i] = labels.get(order[i]) + "  (" + pkgs.get(order[i]) + ")";
            pkgArr[i] = pkgs.get(order[i]);
        }
        Set<String> saved = prefs.getStringSet("camApps", null);
        final boolean[] checked = new boolean[order.length];
        for (int i = 0; i < order.length; i++) {
            checked[i] = saved != null && saved.contains(pkgArr[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择应用（按相机键截屏）")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Set<String> out = new HashSet<String>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) {
                                out.add(pkgArr[i]);
                            }
                        }
                        prefs.edit().putStringSet("camApps", out).apply();
                        updateCamStatus();
                        log("相机键截图应用: " + out.size() + " 个");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ UI v2.2

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(28));
        scroll.addView(root);

        // ---- hero ----
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(C_PRIMARY_SOFT);
        hb.setCornerRadius(dp(26));
        hero.setBackground(hb);
        hero.setClipToOutline(true);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(196));
        heroLp.bottomMargin = dp(14);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int heroId = getResources().getIdentifier("hero", "drawable", getPackageName());
        if (heroId != 0) {
            img.setImageResource(heroId);
        }
        hero.addView(img, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xD9000000, 0x00000000}));
        hero.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(100), Gravity.BOTTOM));

        LinearLayout heroTextBox = new LinearLayout(this);
        heroTextBox.setOrientation(LinearLayout.VERTICAL);
        TextView heroTitle = text("Xperia Stream", 26f, Color.WHITE, true);
        heroTitle.setShadowLayer(8f, 0f, 2f, 0x99000000);
        heroTextBox.addView(heroTitle);
        heroTextBox.addView(text("风扇 · 显示 · 音频 · 接口", 12f, 0xFFE5E7EB, false));
        FrameLayout.LayoutParams htLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        htLp.leftMargin = dp(18);
        htLp.bottomMargin = dp(14);
        hero.addView(heroTextBox, htLp);
        root.addView(hero, heroLp);

        // ---- connection ----
        LinearLayout conn = card();
        conn.addView(headerRow("连接", null));
        statusChip = chip("未连接");
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = dp(8);
        conn.addView(statusChip, chipLp);
        TextView devLine = text("插入 Stream 后自动扫描；首次连接需要允许 USB 权限", 11f, C_MUTED, false);
        devLine.setPadding(0, dp(8), 0, dp(10));
        conn.addView(devLine);

        LinearLayout connRow = new LinearLayout(this);
        connRow.setOrientation(LinearLayout.HORIZONTAL);
        connRow.addView(action("重新扫描", false, new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        }), rowBtnLp());
        connRow.addView(action("连接", true, new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        }), rowBtnLp());
        conn.addView(connRow);

        autoConnectSwitch = new Switch(this);
        autoConnectSwitch.setChecked(autoConnect);
        autoConnectSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                autoConnect = v;
                prefs.edit().putBoolean("autoConnect", v).apply();
            }
        });
        conn.addView(toggleRow("插入时自动连接", autoConnectSwitch));
        root.addView(conn);

        // ---- fan + auto ----
        LinearLayout fan = card();
        fan.addView(headerRow("风扇调节", null));

        fanOnSwitch = new Switch(this);
        fanOnSwitch.setChecked(fanOn);
        fanOnSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                fanOn = v;
                prefs.edit().putBoolean("fanOn", v).apply();
                sendRaw(v ? ";0,0C,00\n" : ";0,0C,01\n");
            }
        });
        fan.addView(toggleRow("启用风扇", fanOnSwitch));

        ledSwitch = new Switch(this);
        ledSwitch.setChecked(ledEnabled);
        ledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setLed(v);
            }
        });
        fan.addView(toggleRow("风扇灯效", ledSwitch));

        speedValueView = text("5700 RPM", 16f, C_TEXT, false);
        fan.addView(selectorRow("风扇转速", speedValueView, new View.OnClickListener() {
            public void onClick(View v) {
                showSpeedDialog();
            }
        }));

        Button readSpeed = outlineButton("读取转速", new View.OnClickListener() {
            public void onClick(View v) {
                sendRaw(";0,03,00\n");
            }
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rbLp.topMargin = dp(10);
        fan.addView(readSpeed, rbLp);

        fan.addView(divider());

        autoSwitch = new Switch(this);
        autoSwitch.setChecked(autoEnabled);
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startAuto();
                } else {
                    stopAuto(true);
                }
            }
        });
        fan.addView(headerRow("自动调速", autoSwitch));

        tempView = text("温度 --", 24f, C_TEXT, true);
        tempView.setPadding(0, dp(8), 0, dp(2));
        fan.addView(tempView);

        targetView = text("目标转速 --", 12f, C_MUTED, false);
        targetView.setPadding(0, 0, 0, dp(8));
        fan.addView(targetView);

        tempBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        tempBar.setMax(10200);
        tempBar.setProgress(0);
        tempBar.setProgressTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY));
        tempBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(C_GRAY_BTN));
        fan.addView(tempBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));

        LinearLayout profRow = new LinearLayout(this);
        profRow.setOrientation(LinearLayout.HORIZONTAL);
        profRow.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            final int idx = i;
            Button b = action(PROFILE_NAMES[i], false, new View.OnClickListener() {
                public void onClick(View v) {
                    setProfile(PROFILE_IDS[idx]);
                }
            });
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            profileBtns[i] = b;
            profRow.addView(b, speedLp());
        }
        fan.addView(profRow);

        profileHint = text("", 11f, C_MUTED, false);
        profileHint.setPadding(0, dp(8), 0, 0);
        fan.addView(profileHint);

        LinearLayout tplRow = new LinearLayout(this);
        tplRow.setOrientation(LinearLayout.HORIZONTAL);
        tplRow.setPadding(0, dp(8), 0, 0);
        Button editTpl = outlineButton("编辑模板", new View.OnClickListener() {
            public void onClick(View v) {
                setProfile("custom");
                showTemplateEditor();
            }
        });
        Button importTpl = outlineButton("导入模板", new View.OnClickListener() {
            public void onClick(View v) {
                showTemplateImport();
            }
        });
        tplRow.addView(editTpl, rowBtnLp());
        tplRow.addView(importTpl, rowBtnLp());
        fan.addView(tplRow);

        voiceSwitch = new Switch(this);
        voiceSwitch.setChecked(voicePauseEnabled);
        voiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                voicePauseEnabled = isChecked;
                prefs.edit().putBoolean("voicePause", isChecked).apply();
                if (!isChecked && voicePaused) {
                    voicePaused = false;
                    sendSpeed(autoTarget, true);
                    log("语音暂停已关闭，恢复 " + autoTarget + " rpm");
                }
            }
        });
        fan.addView(toggleRow("语音 / 通话时暂停风扇", voiceSwitch));
        root.addView(fan);

        // ---- charge ----
        LinearLayout ch = card();
        secChip[0] = chip("--");
        ch.addView(headerRow("充电", secChip[0]));
        chargeWatts = text("-- W", 30f, C_TEXT, true);
        chargeWatts.setPadding(0, dp(6), 0, 0);
        ch.addView(chargeWatts);
        chargeDetail = text("等待数据…", 12f, C_MUTED, false);
        ch.addView(chargeDetail);
        secDetail[0] = text("", 11f, C_MUTED, false);
        secDetail[0].setPadding(0, dp(6), 0, 0);
        ch.addView(secDetail[0]);
        chargeTemp = text("电池温度 --", 12f, C_MUTED, false);
        chargeTemp.setPadding(0, dp(4), 0, 0);
        ch.addView(chargeTemp);
        root.addView(ch);

        // ---- ethernet ----
        LinearLayout ethCard = card();
        secChip[3] = chip("--");
        ethCard.addView(headerRow("网卡", secChip[3]));
        secDetail[3] = text("未检测", 12f, C_MUTED, false);
        secDetail[3].setPadding(0, dp(8), 0, 0);
        ethCard.addView(secDetail[3]);
        root.addView(ethCard);

        // ---- hdmi ----
        LinearLayout hdmiCard = card();
        secChip[2] = chip("--");
        hdmiCard.addView(headerRow("HDMI", secChip[2]));

        hdmiSwitch = new Switch(this);
        hdmiSwitch.setChecked(hdmiEnabled);
        hdmiSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setHdmi(v);
            }
        });
        hdmiCard.addView(toggleRow("启用 HDMI 视频和音频输出", hdmiSwitch));

        hdmiModeValueView = text("自动", 16f, C_TEXT, false);
        hdmiCard.addView(selectorRow("显示模式", hdmiModeValueView, new View.OnClickListener() {
            public void onClick(View v) {
                showDisplayModeDialog();
            }
        }));

        fwView = text("固件版本 --", 12f, C_MUTED, false);
        fwView.setPadding(0, dp(8), 0, 0);
        hdmiCard.addView(fwView);

        Button fwBtn = outlineButton("读取 HDMI 固件版本", new View.OnClickListener() {
            public void onClick(View v) {
                queryHdmiFw();
            }
        });
        LinearLayout.LayoutParams fwLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        fwLp.topMargin = dp(8);
        hdmiCard.addView(fwBtn, fwLp);

        hdmiCard.addView(sliderRow("HDMI 媒体音量", true));
        hdmiCard.addView(sliderRow("HDMI 通话音量", false));
        TextView avHint = text("音量作用于 HDMI 输出的音频", 11f, C_MUTED, false);
        avHint.setPadding(0, dp(4), 0, 0);
        hdmiCard.addView(avHint);

        secDetail[2] = text("", 11f, C_MUTED, false);
        secDetail[2].setPadding(0, dp(8), 0, 0);
        hdmiCard.addView(secDetail[2]);
        root.addView(hdmiCard);

        // ---- 3.5mm audio ----
        LinearLayout audioCard = card();
        secChip[1] = chip("--");
        audioCard.addView(headerRow("3.5mm 音频", secChip[1]));
        secDetail[1] = text("未检测", 12f, C_MUTED, false);
        secDetail[1].setPadding(0, dp(8), 0, 0);
        audioCard.addView(secDetail[1]);
        root.addView(audioCard);

        // ---- camera key ----
        LinearLayout cam = card();
        cam.addView(headerRow("相机键截图", null));
        camSwitch = new Switch(this);
        camSwitch.setChecked(prefs.getBoolean("camShot", false));
        camSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.edit().putBoolean("camShot", v).apply();
                updateCamStatus();
            }
        });
        cam.addView(toggleRow("启用相机键截图", camSwitch));

        camAppsView = text("已选应用：无", 12f, C_MUTED, false);
        camAppsView.setPadding(0, dp(6), 0, 0);
        cam.addView(camAppsView);

        camStatusView = text("", 12f, C_MUTED, false);
        camStatusView.setPadding(0, dp(4), 0, 0);
        cam.addView(camStatusView);

        Button pickApps = outlineButton("选择应用（可多选）", new View.OnClickListener() {
            public void onClick(View v) {
                showAppPicker();
            }
        });
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        paLp.topMargin = dp(8);
        cam.addView(pickApps, paLp);

        Button openA11y = outlineButton("打开无障碍设置", new View.OnClickListener() {
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        LinearLayout.LayoutParams oaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        oaLp.topMargin = dp(8);
        cam.addView(openA11y, oaLp);
        root.addView(cam);

        Button export = outlineButton("导出检测报告", new View.OnClickListener() {
            public void onClick(View v) {
                exportReport();
            }
        });
        root.addView(export, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        updateProfileUi();
        refreshRpmUi();
        updateVolumeUi();
        updateCamStatus();
        return scroll;
    }

    private LinearLayout headerRow(String title, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 15f, C_TEXT, true), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) {
            row.addView(right);
        }
        return row;
    }

    private TextView section(String s) {
        TextView t = text(s, 13f, C_MUTED, false);
        t.setPadding(dp(2), dp(12), 0, dp(6));
        return t;
    }

    private TextView chip(String s) {
        TextView t = text(s, 11f, C_MUTED, true);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        t.setBackground(bg(C_GRAY_BTN, 12));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_OUTLINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        p.topMargin = dp(12);
        p.bottomMargin = dp(4);
        v.setLayoutParams(p);
        return v;
    }

    private void applySectionDet(int i, Detectors.Det d) {
        if (secChip[i] == null) {
            return;
        }
        int fg;
        int bgc;
        if ("OK".equals(d.status)) {
            fg = C_GREEN;
            bgc = C_GREEN_BG;
        } else if ("WARN".equals(d.status)) {
            fg = C_AMBER;
            bgc = C_AMBER_BG;
        } else {
            fg = C_RED;
            bgc = C_RED_BG;
        }
        secChip[i].setText(d.status);
        secChip[i].setTextColor(fg);
        secChip[i].setBackground(bg(bgc, 12));
        if (secDetail[i] != null) {
            secDetail[i].setText(d.text);
            secDetail[i].setTextColor(fg);
        }
    }

    private Button outlineButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTextColor(C_TEXT);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), C_OUTLINE);
        b.setBackground(d);
        b.setOnClickListener(l);
        b.setMinHeight(dp(44));
        return b;
    }

    private View selectorRow(String label, TextView value, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), C_OUTLINE);
        box.setBackground(d);
        box.setPadding(dp(14), dp(8), dp(14), dp(8));
        box.setOnClickListener(l);
        TextView lab = text(label, 11f, C_MUTED, false);
        box.addView(lab);
        box.addView(value);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        box.setLayoutParams(p);
        return box;
    }

    private View sliderRow(String label, final boolean music) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, 0);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView val = text("", 11f, C_MUTED, false);
        head.addView(text(label, 13f, C_TEXT, false), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(val);
        box.addView(head);
        SeekBar bar = new SeekBar(this);
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int stream = music ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_VOICE_CALL;
            bar.setMax(am.getStreamMaxVolume(stream));
            bar.setProgress(am.getStreamVolume(stream));
            val.setText(bar.getProgress() + " / " + bar.getMax());
        }
        if (music) {
            mediaBar = bar;
        } else {
            callBar = bar;
        }
        final TextView fv = val;
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                fv.setText(progress + " / " + sb.getMax());
                if (fromUser) {
                    AudioManager am2 = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am2 != null) {
                        am2.setStreamVolume(music ? AudioManager.STREAM_MUSIC
                                : AudioManager.STREAM_VOICE_CALL, progress, 0);
                    }
                }
            }
            public void onStartTrackingTouch(SeekBar sb) {
            }
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        box.addView(bar);
        return box;
    }

    private void updateVolumeUi() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        if (mediaBar != null) {
            mediaBar.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            mediaBar.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        if (callBar != null) {
            callBar.setMax(am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
            callBar.setProgress(am.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
        }
    }

    private LinearLayout.LayoutParams rowBtnLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void showSpeedDialog() {
        String[] labels = new String[ALL_SPEEDS.length];
        for (int i = 0; i < ALL_SPEEDS.length; i++) {
            labels[i] = ALL_SPEEDS[i] == 0 ? "关闭 (0 RPM)" : ALL_SPEEDS[i] + " RPM";
        }
        new AlertDialog.Builder(this)
                .setTitle("风扇转速")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int rpm = ALL_SPEEDS[which];
                        if (autoEnabled) {
                            stopAuto(true);
                        }
                        sendSpeed(rpm, false);
                        if (speedValueView != null) {
                            speedValueView.setText(rpm == 0 ? "关闭" : rpm + " RPM");
                        }
                        d.dismiss();
                    }
                })
                .show();
    }

    private void showDisplayModeDialog() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display ext = null;
        if (dm != null) {
            for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    ext = d;
                    break;
                }
            }
        }
        if (ext == null) {
            log("没有外接显示，先连接 HDMI");
            return;
        }
        final Display display = ext;
        final Display.Mode[] modes = display.getSupportedModes();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modes[i].getPhysicalWidth() + "×" + modes[i].getPhysicalHeight()
                    + " @ " + Math.round(modes[i].getRefreshRate()) + "Hz";
        }
        new AlertDialog.Builder(this)
                .setTitle("显示模式")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        applyDisplayMode(display, modes[which]);
                        d.dismiss();
                    }
                })
                .show();
    }

    private void applyDisplayMode(Display display, Display.Mode mode) {
        try {
            java.lang.reflect.Method m = Display.class.getMethod(
                    "setUserPreferredDisplayMode", Display.Mode.class);
            m.invoke(display, mode);
            hdmiModeId = mode.getModeId();
            if (hdmiModeValueView != null) {
                hdmiModeValueView.setText(mode.getPhysicalWidth() + "×"
                        + mode.getPhysicalHeight() + " @ " + Math.round(mode.getRefreshRate()) + "Hz");
            }
            log("显示模式: " + mode.getPhysicalWidth() + "×" + mode.getPhysicalHeight());
        } catch (Throwable t) {
            log("显示模式设置失败: " + t.getClass().getSimpleName());
        }
    }

    private void queryHdmiFw() {
        lastHdmiFw = null;
        sendRaw(";0,22\n");
        ui.postDelayed(new Runnable() {
            public void run() {
                if (fwView != null) {
                    fwView.setText(lastHdmiFw != null ? "固件版本 " + lastHdmiFw : "固件版本读取失败");
                }
            }
        }, 800);
    }

    private void showTemplateEditor() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(4));
        final Spinner[] temps = new Spinner[5];
        final Spinner[] speeds = new Spinner[5];
        String[] tempLabels = new String[19];
        for (int i = 0; i < 19; i++) {
            tempLabels[i] = (28 + i) + " °C";
        }
        String[] speedLabels = new String[ALL_SPEEDS.length];
        for (int i = 0; i < ALL_SPEEDS.length; i++) {
            speedLabels[i] = ALL_SPEEDS[i] + " rpm";
        }
        for (int i = 0; i < 5; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(2), 0, dp(2));
            temps[i] = new Spinner(this);
            temps[i].setAdapter(new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_dropdown_item, tempLabels));
            temps[i].setSelection(Math.max(0, Math.min(18, customTemps[i] - 28)));
            row.addView(temps[i], new LinearLayout.LayoutParams(0, dp(48), 1f));
            row.addView(text("→", 14f, C_MUTED, false));
            speeds[i] = new Spinner(this);
            speeds[i].setAdapter(new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_dropdown_item, speedLabels));
            int sel = 0;
            for (int k = 0; k < ALL_SPEEDS.length; k++) {
                if (ALL_SPEEDS[k] == customSpeeds[i]) {
                    sel = k;
                }
            }
            speeds[i].setSelection(sel);
            row.addView(speeds[i], new LinearLayout.LayoutParams(0, dp(48), 1f));
            box.addView(row);
        }
        new AlertDialog.Builder(this)
                .setTitle("自定义模板")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < 5; i++) {
                            customTemps[i] = 28 + temps[i].getSelectedItemPosition();
                            customSpeeds[i] = ALL_SPEEDS[speeds[i].getSelectedItemPosition()];
                        }
                        profileId = "custom";
                        prefs.edit().putString("profile", "custom").apply();
                        prefs.edit().putString("customTemps", joinInts(customTemps)).apply();
                        prefs.edit().putString("customSpeeds", joinInts(customSpeeds)).apply();
                        updateProfileUi();
                        log("自定义模板已保存");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTemplateImport() {
        final EditText et = new EditText(this);
        et.setHint("34,36,38,40,42 → 3900,4800,5700,6600,7500");
        et.setText(joinInts(customTemps) + " → " + joinInts(customSpeeds));
        et.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("导入自定义模板")
                .setMessage("5 个温度(°C) → 5 个转速(rpm)。可用转速：0 / 4800 / 5700 / 6600 / 7500 / 8400 / 9300 / 10200")
                .setView(et)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (parseTemplate(et.getText().toString())) {
                            profileId = "custom";
                            prefs.edit().putString("profile", "custom")
                                    .putString("customTemps", joinInts(customTemps))
                                    .putString("customSpeeds", joinInts(customSpeeds)).apply();
                            updateProfileUi();
                            log("模板已导入: " + joinInts(customTemps) + " -> " + joinInts(customSpeeds));
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "格式不对：需要 5 个温度、5 个受支持的转速",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean parseTemplate(String s) {
        if (s == null) {
            return false;
        }
        String t = s.replace('：', ':').replace("->", "→").replace('→', '|');
        String[] parts = t.split("\\|");
        if (parts.length != 2) {
            return false;
        }
        String[] a = parts[0].replace("°C", "").replace("C", "").trim().split("[,\\s]+");
        String[] b = parts[1].replace("rpm", "").trim().split("[,\\s]+");
        if (a.length != 5 || b.length != 5) {
            return false;
        }
        int[] temps = new int[5];
        int[] speeds = new int[5];
        try {
            for (int i = 0; i < 5; i++) {
                temps[i] = Integer.parseInt(a[i].trim());
                speeds[i] = Integer.parseInt(b[i].trim());
            }
        } catch (Exception e) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            if (temps[i] < 20 || temps[i] > 60) {
                return false;
            }
            if (i > 0 && temps[i] <= temps[i - 1]) {
                return false;
            }
            boolean ok = false;
            for (int sp : ALL_SPEEDS) {
                if (sp == speeds[i]) {
                    ok = true;
                }
            }
            if (!ok) {
                return false;
            }
        }
        System.arraycopy(temps, 0, customTemps, 0, 5);
        System.arraycopy(speeds, 0, customSpeeds, 0, 5);
        return true;
    }

    private String joinInts(int[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private int[] splitInts(String s, int[] def) {
        if (s == null || s.length() == 0) {
            return def;
        }
        String[] parts = s.split(",");
        if (parts.length != def.length) {
            return def;
        }
        int[] out = new int[def.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            return out;
        } catch (Exception e) {
            return def;
        }
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
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_CARD);
        d.setCornerRadius(dp(22));
        d.setStroke(dp(1), C_OUTLINE);
        c.setBackground(d);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        if (!darkMode) {
            c.setElevation(dp(2));
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(14);
        c.setLayoutParams(p);
        return c;
    }

    private void styleSwitch(Switch sw) {
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(C_PRIMARY_SOFT));
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

    /** Light / dark palette used by the whole screen. */
    private void applyPalette(boolean dark) {
        if (!dark) {
            C_BG = 0xFFF2F4F7;
            C_CARD = 0xFFFFFFFF;
            C_TEXT = 0xFF111827;
            C_MUTED = 0xFF6B7280;
            C_PRIMARY = 0xFF2563EB;
            C_PRIMARY_SOFT = 0xFFDBEAFE;
            C_GREEN = 0xFF15803D;
            C_GREEN_BG = 0xFFDCFCE7;
            C_RED = 0xFFB91C1C;
            C_RED_BG = 0xFFFEE2E2;
            C_GRAY_BTN = 0xFFE5E7EB;
            C_OUTLINE = 0xFFCBD5E1;
            C_AMBER = 0xFFB45309;
            C_AMBER_BG = 0xFFFEF3C7;
        } else {
            C_BG = 0xFF0B0F14;
            C_CARD = 0xFF161B22;
            C_TEXT = 0xFFE6EDF3;
            C_MUTED = 0xFF8B949E;
            C_PRIMARY = 0xFF3B82F6;
            C_PRIMARY_SOFT = 0xFF172554;
            C_GREEN = 0xFF3FB950;
            C_GREEN_BG = 0xFF12331C;
            C_RED = 0xFFF85149;
            C_RED_BG = 0xFF3D1519;
            C_GRAY_BTN = 0xFF21262D;
            C_OUTLINE = 0xFF30363D;
            C_AMBER = 0xFFD29922;
            C_AMBER_BG = 0xFF3B2F0B;
        }
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
                    } else if (s.startsWith(";1,22,")) {
                        lastHdmiFw = s.substring(6).trim();
                        ui.post(new Runnable() {
                            public void run() {
                                if (fwView != null) {
                                    fwView.setText("固件版本 " + lastHdmiFw);
                                }
                            }
                        });
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
        lastSentRpm = rpm;
        ui.post(new Runnable() {
            public void run() {
                refreshRpmUi();
            }
        });
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
        callWatchdog = false;
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
        float[] th = activeThresholds();
        int[] sp = activeSpeeds();
        int hit = -1;
        for (int i = 0; i < th.length; i++) {
            if (t >= th[i]) {
                hit = i;
            } else {
                break;
            }
        }
        return hit < 0 ? 0 : sp[hit];
    }

    /** 2 °C of hysteresis per step, so the fan does not hunt. */
    private float lowerBound(int speed) {
        float[] th = activeThresholds();
        int[] sp = activeSpeeds();
        for (int i = 0; i < sp.length; i++) {
            if (sp[i] == speed) {
                return th[i] - 2f;
            }
        }
        return 100f;
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
                refreshRpmUi();
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
        applySectionDet(i, d);
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
            fg = C_AMBER;
            bgc = C_AMBER_BG;
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
