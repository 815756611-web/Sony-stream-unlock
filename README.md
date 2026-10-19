# Xperia Stream 风扇控制器

独立控制 Sony **Xperia Stream (XQZ-GG01)** 的风扇与接口状态，**不依赖 Sony 的 GamingFanService / ThermalFanService**，适用于第三方 ROM（如在 HyperOS 移植的 Android 17 上验证）。

App 直接通过 USB CDC-ACM 与配件的 MCU 通信（VID `054C` / PID `0E6D`），因此不需要 Sony 的平台签名应用，也不需要 root。

## 功能

- **手动转速**：关闭 / 低 4800 / 中 5700 / 高 6600 / 最大 7500 / 查询
- **自动调速**：按**电池温度**分档（34 / 36 / 38 / 40 / 42 °C → 3900 / 4800 / 5700 / 6600 / 7500），2 °C 迟滞，避免风扇反复升降
- **语音输入时暂停风扇**：检测到麦克风被占用或通话时风扇归零，语音结束后自动恢复（仅自动模式）
- **四合一接口检测**：充电功率（W）、3.5mm 音频、HDMI 分辨率@刷新率/等效 Gbps、网卡链路与实时上下行网速
- **USB 保活心跳**：每 2 秒一次查询，避免 MCU 空闲复位导致整机掉线
- **前台服务**：自动模式在后台持续运行
- **导出检测报告**：一键写入 `Download/stream_diag_*.txt`

## 兼容性

| 项目 | 说明 |
| --- | --- |
| 设备 | Xperia 1 V（XQ-DQ* / pdx234） |
| 配件 | Xperia Stream（XQZ-GG01） |
| 系统 | Android 13+；在 HyperOS 4 / Android 17 移植版上验证 |
| Root | App 本身不需要；模块需要 KernelSU / SukiSU |

## 安装

### APK

```sh
adb install -r StreamFanCtl.apk
```

### KernelSU / SukiSU 模块

刷入 `StreamFanCtl-v1.3.6.zip` 后重启。模块会安装 APK，并附带：

- `tools/audio-diag.sh` —— 3.5mm / USB 音频链路诊断
- `tools/usb-monitor.sh` —— USB 掉线瞬间抓 dmesg 与供电信息
- `tools/usb-keepawake.sh` —— 关闭 Stream 的 USB 运行时自动挂起
- `service.sh` —— 开机拉起 keepawake

## 使用

1. 插上 Stream，打开 App，点“连接 / 重新扫描”；
2. 手动挡位直接点按钮；
3. 自动调速开关打开后，按电池温度自动升降档；
4. 「接口检测」四排每 3 秒刷新，点“导出检测报告”可保存完整日志。

## 构建

需要：

- JDK 21
- Android build-tools 35（`aapt2` / `d8` / `zipalign` / `apksigner`）
- `android-35` 的 `android.jar`

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
powershell -ExecutionPolicy Bypass -File fanctl/build.ps1 `
  -BuildTools "C:\path\to\build-tools\35.0.0" `
  -AndroidJar "C:\path\to\platforms\android-35\android.jar"
```

`-JavaHome` 默认取 `JAVA_HOME`；源码目录默认是本脚本所在目录，APK 默认输出到上一级 `StreamFanCtl.apk`。

## 协议

见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## 已知问题

- 部分 ROM 上 3.5mm（USB 音频）会在 20~60 秒后随整机掉线，疑似供电或 MCU 看门狗；可用模块里的 `usb-monitor.sh` 抓 dmesg 定位。
- 充电功率优先读 `/sys/class/power_supply`；被 SELinux 拦截时退回电池侧估算（报告里会注明）。
- HDMI 的 Gbps 是 `宽×高×刷新率×24bit` 的等效数据率，不是 DP 链路速率。

## 免责声明

仅供学习与研究使用。刷机、改机有风险，请备好救砖模块。

## License

MIT
