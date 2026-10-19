package com.xperiastream.fanctl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Four subsystem checks for the Xperia Stream:
 *   charge  - USB input voltage/current -> watts
 *   audio   - wired / USB headset devices (3.5 mm jack goes through UAC)
 *   hdmi    - external display resolution + refresh rate -> effective data rate
 *   eth     - Ethernet link + current throughput
 *
 * All checks are read-only and prefer public Android APIs; sysfs is used when
 * it is readable, with a fallback when SELinux denies it.
 */
public final class Detectors {

    public static final class Det {
        public final String status;   // OK / WARN / FAIL
        public final String text;     // one-line summary for the UI
        public final String detail;   // multi-line detail for the report

        Det(String status, String text, String detail) {
            this.status = status;
            this.text = text;
            this.detail = detail;
        }
    }

    private Detectors() {
    }

    // ------------------------------------------------------------- charge

    public static Det charge(Context ctx) {
        StringBuilder d = new StringBuilder();
        double usbW = -1;
        String usbVI = null;

        File ps = new File("/sys/class/power_supply");
        File usbDir = null;
        File battDir = new File(ps, "battery");
        File[] subs = ps.listFiles();
        if (subs != null) {
            for (File f : subs) {
                String t = readFile(new File(f, "type")).trim();
                if ("USB".equalsIgnoreCase(t)) {
                    usbDir = f;
                    break;
                }
            }
        }
        if (usbDir != null) {
            long uv = parseLong(readFile(new File(usbDir, "voltage_now")));
            long ua = parseLong(readFile(new File(usbDir, "current_now")));
            d.append("供电节点: ").append(usbDir.getName()).append('\n');
            if (uv > 0 && ua > 0) {
                usbW = uv * (double) ua / 1e12;
                usbVI = String.format(Locale.US, "%.2fV / %.2fA", uv / 1e6, ua / 1e6);
                d.append("输入: ").append(usbVI).append(" = ")
                        .append(String.format(Locale.US, "%.2f W", usbW)).append('\n');
            }
        } else {
            d.append("没有可读的 USB 供电节点\n");
        }

        long bv = parseLong(readFile(new File(battDir, "voltage_now")));
        long bi = parseLong(readFile(new File(battDir, "current_now")));
        String bStatus = readFile(new File(battDir, "status")).trim();
        String bCap = readFile(new File(battDir, "capacity")).trim();
        if (bv > 0 && bi != 0) {
            double w = bv * (double) bi / 1e12;
            d.append("电池: ").append(bStatus).append(' ').append(bCap).append("%  ")
                    .append(String.format(Locale.US, "%.2f V × %.2f A = %.2f W%n",
                            bv / 1e6, bi / 1e6, w));
        }

        Intent batt = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
        int status = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
        int level = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int mv = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) : -1;

        if (usbW < 0 && plugged != 0) {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int ua = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
            if (mv > 0 && ua != 0 && ua != Integer.MIN_VALUE) {
                usbW = (mv / 1000.0) * (ua / 1e6);
                usbVI = String.format(Locale.US, "%.2fV / %.2fA (电池侧)", mv / 1000.0, ua / 1e6);
                d.append("电池侧功率: ").append(String.format(Locale.US, "%.2f W", usbW)).append('\n');
            }
        }

        boolean charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING;
        d.append("plugged=").append(plugged).append(" status=").append(status)
                .append(" level=").append(level).append("%\n");

        String text;
        String st;
        if (charging && usbW >= 1) {
            st = "OK";
            text = String.format(Locale.US, "%.2f W · %d%%", usbW, level);
            if (usbVI != null) {
                text += " · " + usbVI;
            }
        } else if (charging) {
            st = "WARN";
            text = "已连接，功率 " + (usbW >= 0 ? String.format(Locale.US, "%.2f W", usbW) : "未知")
                    + " · " + level + "%";
        } else {
            st = "FAIL";
            text = "未充电 · " + level + "%";
        }
        return new Det(st, text, d.toString());
    }

    // -------------------------------------------------------------- audio

    public static Det audio(Context ctx) {
        StringBuilder d = new StringBuilder();
        String found = null;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo info : outs) {
                int t = info.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    found = info.getProductName() + " (type " + t + ")";
                    break;
                }
            }
            d.append("输出设备数: ").append(outs.length).append('\n');
        }
        String cards = readFile(new File("/proc/asound/cards"));
        boolean usbCard = cards.toLowerCase(Locale.US).contains("usb-audio");
        d.append("USB 声卡: ").append(usbCard ? "已识别" : "未识别").append('\n');
        if (cards.length() > 0) {
            d.append(cards).append('\n');
        }
        if (found == null && usbCard) {
            found = "USB-Audio 声卡";
        }
        d.append("headset: ").append(found == null ? "无" : found).append('\n');

        // USB presence helps telling "audio unplugged" from "whole device gone".
        StringBuilder usb = new StringBuilder();
        boolean stream = false;
        File[] devs = new File("/sys/bus/usb/devices").listFiles();
        if (devs != null) {
            for (File f : devs) {
                String vid = readFile(new File(f, "idVendor")).trim();
                String pid = readFile(new File(f, "idProduct")).trim();
                if (vid.length() == 0) {
                    continue;
                }
                usb.append(vid).append(':').append(pid).append(' ');
                if ("054c".equalsIgnoreCase(vid)) {
                    stream = true;
                }
            }
        }
        d.append("USB 设备: ").append(usb.length() == 0 ? "无" : usb.toString()).append('\n');

        if (found != null) {
            return new Det("OK", found, d.toString());
        }
        if (stream) {
            return new Det("WARN", "Stream 已枚举，但音频设备不在", d.toString());
        }
        return new Det("FAIL", "未检测到 3.5mm/USB 音频", d.toString());
    }

    // --------------------------------------------------------------- hdmi

    public static Det hdmi(Context ctx) {
        StringBuilder d = new StringBuilder();
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display external = null;
        if (dm != null) {
            Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            for (Display display : displays) {
                if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    continue;
                }
                d.append("外接显示: ").append(display.getName())
                        .append(" id=").append(display.getDisplayId()).append('\n');
                external = display;
                break;
            }
        }

        String text;
        String st;
        if (external != null) {
            Display.Mode mode = external.getMode();
            if (mode == null) {
                d.append("外接显示没有可用 mode\n");
                return new Det("WARN", "外接显示无 mode", d.toString());
            }
            int w = mode.getPhysicalWidth();
            int h = mode.getPhysicalHeight();
            float rate = mode.getRefreshRate();
            double gbps = w * (double) h * rate * 24 / 1e9;
            text = String.format(Locale.US, "%d×%d@%.0f ≈%.2f Gbps", w, h, rate, gbps);
            st = "OK";
            d.append(String.format(Locale.US, "当前模式: %d×%d @ %.1f Hz%n", w, h, rate));
            d.append(String.format(Locale.US, "等效数据率(24bpp): %.2f Gbps%n", gbps));
        } else {
            st = "FAIL";
            text = "无 HDMI/DP 外接显示";
        }

        File[] drm = new File("/sys/class/drm").listFiles();
        if (drm != null) {
            for (File c : drm) {
                File stFile = new File(c, "status");
                if (!stFile.isFile()) {
                    continue;
                }
                String name = c.getName();
                if (name.contains("DSI")) {
                    continue;
                }
                d.append(name).append(": ").append(readFile(stFile).trim()).append('\n');
            }
        }
        return new Det(st, text, d.toString());
    }

    // ---------------------------------------------------------------- eth

    public static Det eth(Context ctx, long[] prev) {
        StringBuilder d = new StringBuilder();
        String ifName = null;
        String ip = null;

        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName();
                if (name.startsWith("wlan") || name.startsWith("rmnet")
                        || name.startsWith("dummy") || name.startsWith("p2p")
                        || name.startsWith("tun") || name.startsWith("sit")) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        ip = ia.getAddress().getHostAddress();
                        ifName = name;
                        break;
                    }
                }
                if (ifName != null) {
                    break;
                }
            }
        } catch (Exception e) {
            d.append("枚举网卡失败: ").append(e).append('\n');
        }

        boolean ethTransport = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
            ethTransport = caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable t) {
            d.append("ConnectivityManager 不可用: ").append(t.getClass().getSimpleName()).append('\n');
        }
        d.append("以太网 transport: ").append(ethTransport ? "是" : "否").append('\n');

        if (ifName == null) {
            return new Det("FAIL", ethTransport ? "以太网已连接但无 IPv4" : "网卡未连接", d.toString());
        }

        long rx = -1;
        long tx = -1;
        File rxFile = new File("/sys/class/net/" + ifName + "/statistics/rx_bytes");
        File txFile = new File("/sys/class/net/" + ifName + "/statistics/tx_bytes");
        if (rxFile.isFile()) {
            rx = parseLong(readFile(rxFile));
            tx = parseLong(readFile(txFile));
        }
        if (rx < 0) {
            rx = TrafficStats.getTotalRxBytes();
            tx = TrafficStats.getTotalTxBytes();
            d.append("sysfs 统计不可读，使用 TrafficStats 总量\n");
        }

        String speed = readFile(new File("/sys/class/net/" + ifName + "/speed")).trim();
        long now = SystemClock.elapsedRealtime();
        double down = 0;
        double up = 0;
        if (prev[2] > 0 && now > prev[2] && rx >= prev[0]) {
            double sec = (now - prev[2]) / 1000.0;
            down = (rx - prev[0]) * 8.0 / sec / 1e6;
            up = (tx - prev[1]) * 8.0 / sec / 1e6;
        }
        prev[0] = rx;
        prev[1] = tx;
        prev[2] = now;

        String link = speed;
        if (link.length() == 0 || "-1".equals(link) || "0".equals(link)) {
            link = "usbnet";
        }
        d.append("接口: ").append(ifName).append("  IP: ").append(ip).append('\n');
        d.append("链路: ").append(link).append("  Mbps\n");
        d.append(String.format(Locale.US, "当前: ↓%.2f Mbps ↑%.2f Mbps%n", down, up));

        String text = link + " · ↓" + String.format(Locale.US, "%.1f", down)
                + " ↑" + String.format(Locale.US, "%.1f", up) + " Mbps · " + ip;
        return new Det("OK", text, d.toString());
    }

    // ------------------------------------------------------------- utils

    private static String readFile(File f) {
        if (f == null || !f.exists()) {
            return "";
        }
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

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
