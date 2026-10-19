# 排障手册

## 接口检测四排的含义

| 行 | OK | WARN | FAIL |
| --- | --- | --- | --- |
| 充电 | 已插电且功率 ≥ 1 W | 已连接但功率很低（PD 未协商/线材） | 没有充电输入 |
| 3.5mm | 检测到 USB 声卡或 headset 路由 | Stream 已枚举但音频设备不在 | 未检测到音频设备 |
| HDMI | 有 connected 连接器且有活动外接显示 | 接口连着但没有活动显示 | 没有 HDMI/DP 外接显示 |
| 网卡 | carrier=1 且有 IPv4 | 链路起来了但没拿到 IP | 网卡未连接 |

报告里 `USB 设备: 无` 可能是 App 读不到 `/sys/bus/usb/devices`（SELinux），不代表设备真的不存在；以 root 下的 `usb-monitor.sh` 为准。

## 整机在 20~60 秒后掉线

现象：3.5mm 先正常，随后“配件已拔出”，风扇也断开。

排查顺序：

1. **看 dmesg**：

   ```sh
   su -c 'sh /data/adb/modules/stream-fanctl/tools/usb-monitor.sh 300'
   ```

   掉线瞬间会记录 USB 设备列表、供电电压/电流/限流和 dmesg。

   | dmesg 关键字 | 结论 |
   | --- | --- |
   | `over-current` / `port disabled` | 供电不足或过流 |
   | 只有 `USB disconnect` / `reset` | 线材、接口接触或固件 |
   | 什么都没有 | 配件自己断电 |

2. **A/B 测试**：`am force-stop com.xperiastream.fanctl` 后只插 Stream，看是否还掉线。
3. **外接供电**：把充电器插到 Stream 的 USB-C 口（不是手机），看是否稳定。

## 3.5mm 音频掉线

Stream 的耳机口是 USB 音频设备，不是模拟直通。先确认整机是否掉线（见上一节），再区分：

```sh
su -c 'sh /data/adb/modules/stream-fanctl/tools/audio-diag.sh 180'
```

- `USB CHANGED`：整机 USB 掉线；
- 只有 `ALSA CARD COUNT` 变化：UAC 被卸载/挂起；
- 只有 `AUDIO ROUTE CHANGED`：Android 音频策略切换；
- 都没有：模拟部分或配件固件。

## 程序保活心跳

日志里的 `程序保活心跳 #N  ;1,03,00` 是每 2 秒一次的 `GET_FAN_SPEED` 查询应答，用来防止 MCU 空闲复位。每 10 秒记录一条，属于正常现象，不是错误。

## 充电功率显示“电池侧”

部分 ROM 的 SELinux 不允许 App 读 `/sys/class/power_supply/usb`，此时退回用电池电压 × 电流估算，报告里会标注“电池侧”。数值仍可用于判断是否在充电，但会略低于实际输入功率。

## HDMI 的 Gbps

`宽 × 高 × 刷新率 × 24bit` 的等效数据率，用于估算带宽占用，不是 DisplayPort 链路速率。

## 网卡显示 usbnet

Stream 的 LAN9500 驱动不通过 `/sys/class/net/*/speed` 报告链路速率时显示 `usbnet`，以实时 `↓/↑ Mbps` 为准。
