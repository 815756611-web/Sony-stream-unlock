# Xperia Stream 四合一检测

一个只做检测的 KernelSU / SukiSU 模块，检查 Xperia Stream 的四条链路是否正常工作。

## 检测项

| 项目 | 看什么 | 输出 |
| --- | --- | --- |
| 充电 | `usb` 供电节点的电压/电流、电池状态 | **输入功率 W**、电池百分比、充电状态 |
| 3.5mm | `/proc/asound/cards`、`snd-usb-audio` 绑定、AudioPolicy 路由 | 是否识别到 USB 声卡/耳机路由 |
| HDMI | `/sys/class/drm` 连接器状态、`dumpsys display` 外接显示 | **分辨率@刷新率、等效数据率 Gbps** |
| 网卡 | `/sys/class/net` carrier/speed/IP、2 秒收发字节差 | **链路速率、当前下行/上行 Mbps**、IP |

每项给出 `OK / WARN / FAIL` 判定。

## 用法

```sh
# 完整报告（每项细节 + 汇总）
su -c 'sh /data/adb/modules/stream-diag/tools/stream-diag.sh'

# 只看汇总
su -c 'sh /data/adb/modules/stream-diag/tools/stream-diag.sh --brief'

# 只测某一项
su -c 'sh /data/adb/modules/stream-diag/tools/stream-diag.sh --only charge'
su -c 'sh /data/adb/modules/stream-diag/tools/stream-diag.sh --only audio'
su -c 'sh /data/adb/modules/stream-diag/tools/stream-diag.sh --only hdmi'
su -c 'sh /data/adb/modules/stream-diag/tools/stream-diag.sh --only eth'
```

- 报告文件：`/sdcard/Download/stream_diag_<时间>.txt`
- logcat：`logcat -s XperiaStreamDiag`
- 管理器里的「执行」按钮 = `--brief`

## 判读

| 结果 | 含义 |
| --- | --- |
| `充电 [OK]` | 检测到 USB 输入且功率 ≥ 1 W |
| `充电 [WARN]` | 已连接但功率很低（PD 未协商 / 线材问题） |
| `充电 [FAIL]` | 没有充电输入 |
| `3.5mm [OK]` | 有 USB 声卡或 headset 路由 |
| `3.5mm [FAIL]` | 没有识别到音频设备（耳机没插好 / UAC 未枚举） |
| `HDMI [OK]` | 有 connected 的连接器且有活动外接显示 |
| `HDMI [WARN]` | 接口连着但没有活动显示（DP 未协商成功） |
| `网卡 [OK]` | carrier=1 且有 IPv4 |
| `网卡 [WARN]` | 链路起来了但没拿到 IP（DHCP 问题） |

## 说明

- 只读检测，不改任何系统设置；
- 网速是采样 2 秒的平均值，单位 Mbps；
- HDMI 的 Gbps 是 `宽×高×刷新率×24bit` 的**等效数据率**，不是 DP 链路速率（链路速率由内核/PHY 决定，用户空间读不到精确值）。
