# Xperia Stream 风扇控制器

独立控制 Sony **Xperia Stream (XQZ-GG01)** 的风扇与接口状态，**不依赖 Sony 的 GamingFanService / ThermalFanService**，适用于第三方 ROM（如在 HyperOS 移植的 Android 17 上验证）。

App 直接通过 USB CDC-ACM 与配件的 MCU 通信（VID `054C` / PID `0E6D`），因此不需要 Sony 的平台签名应用，也不需要 root。配套的 **SukiSU** 模块只用来安装 APK 和提供几个 root 诊断脚本。

## 功能

- **顶部头图**：直接使用索尼官方设置页的 Xperia Stream 渲染原图（`settings_global_gamingfan_xperia_stream_settings_main_icon`），无边框通栏显示
- **风扇调节**：启用/停用（`0x0C`）、灯效开关（`0x42`）、转速下拉（0 / 4800 / 5700 / 6600 / 7500 / 8400 / 9300 / 10200）、读取转速
- **自动调速 + 模板**：按电池温度升降档（2 °C 迟滞），预设精简为 **均衡 / 性能**，性能档最高 10200 rpm；另可自定义或**导入** 5 段「温度 ↔ 转速」模板
- **语音 / 通话时暂停风扇**：麦克风或通话期间风扇归零，结束自动恢复；**手动、自动模式均生效**（手动模式恢复原转速）
- **充电板块**：实时功率 W、电压 / 电流、电量、电池温度，状态检测并入本板块
- **网卡板块**：链路速率、实时上下行、IP，状态检测并入本板块
- **HDMI 板块**：视频/音频开关（`0x43`）、显示模式、固件版本（`0x22`）、外接显示检测，以及 **HDMI 媒体 / 通话音量**（作用于 HDMI 音频输出）
- **3.5mm 音频板块**：USB 声卡 / 耳机检测
- **相机键截图**：无障碍服务 + 应用多选（批量勾选），在选中应用内按相机键截屏
- **插入自动连接**：插入 Stream 后自动扫描并连接（可关闭）
- **USB 保活心跳**：每 2 秒一次查询，避免 MCU 空闲复位导致整机掉线
- **默认深色主题**：官方头图与背景融合；右上角可切换浅色
- **导出检测报告**：一键写入 `Download/stream_diag_*.txt`

## 兼容性

| 项目 | 说明 |
| --- | --- |
| 设备 | Xperia 1 V（XQ-DQ* / pdx234） |
| 配件 | Xperia Stream（XQZ-GG01） |
| 系统 | Android 13+；在 HyperOS 4 / Android 17 移植版上验证 |
| Root | App 本身不需要；模块用于 **SukiSU**（KernelSU 分支） |

## 下载

- [最新 Release](https://github.com/815756611-web/Sony-stream-unlock/releases/latest)
- [v2.4.0](https://github.com/815756611-web/Sony-stream-unlock/releases/tag/v2.4.0)
- [v2.3.0](https://github.com/815756611-web/Sony-stream-unlock/releases/tag/v2.3.0)
- [v2.2.0](https://github.com/815756611-web/Sony-stream-unlock/releases/tag/v2.2.0)
- [v2.1.0](https://github.com/815756611-web/Sony-stream-unlock/releases/tag/v2.1.0)
- [v2.0.0](https://github.com/815756611-web/Sony-stream-unlock/releases/tag/v2.0.0)
- [v1.4.0（旧版）](https://github.com/815756611-web/Sony-stream-unlock/releases/tag/v1.4.0)

## 安装

### APK

```sh
adb install -r StreamFanCtl.apk
```

### SukiSU 模块

刷入 `StreamFanCtl-v2.4.0.zip` 后重启。模块会安装 APK，并附带：

- `tools/audio-diag.sh` —— 3.5mm / USB 音频链路诊断
- `tools/usb-monitor.sh` —— USB 掉线瞬间抓 dmesg 与供电信息
- `tools/usb-keepawake.sh` —— 关闭 Stream 的 USB 运行时自动挂起
- `service.sh` —— 开机拉起 keepawake

## 使用

1. 插上 Stream，App 会自动扫描并连接（也可手动点“连接 / 重新扫描”）；
2. 手动挡位直接点按钮，或打开自动调速并选择「静音 / 均衡 / 性能 / 极速」；
3. 「充电监控」实时显示功率与曲线；
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
