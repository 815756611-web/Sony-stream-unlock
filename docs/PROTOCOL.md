# Xperia Stream USB 协议笔记

## 设备

| 项目 | 值 |
| --- | --- |
| 厂商 ID | `0x054C`（Sony） |
| 产品 ID | `0x0E6D` |
| 复合设备 | Hub `0424:9514`、千兆网卡 `0424:EC00`、MCU `054C:0E6D`、USB 音频 `XQZ-GG01`（UAC） |
| 接口 | CDC-ACM：控制接口 class 2，数据接口 class 10 |
| 端点 | bulk IN `0x80`、bulk OUT `0x00` |
| 串口参数 | 115200 8N1，只需 `SET_LINE_CODING`（0x20），无需 `SET_CONTROL_LINE_STATE` |

Android 侧用 `UsbManager` 打开设备，`claimInterface(ctrl, true)` + `claimInterface(data, true)`，然后对控制接口发 `SET_LINE_CODING`。

## 帧格式

ASCII，一行一帧，以 `\n` 结尾：

```
;<type>,<cmd>,<param>\n
```

| 字段 | 含义 |
| --- | --- |
| type | `0` = 请求，`1` = 应答，`2` = 巡检 |
| cmd | 两位十六进制命令号 |
| param | 参数字符串（通常两位十六进制） |

例：设 6600 rpm → `;0,02,66\n`

## 命令

| cmd | 名称 | 参数 | 说明 |
| --- | --- | --- | --- |
| `02` | SET_FAN_SPEED | rpm / 100 的十六进制 | `66` = 6600 rpm，`00` = 停转 |
| `03` | GET_FAN_SPEED | 无 | 应答 `;1,03,xx` |
| `01` | SET_BATTERY_LEVEL | 电量百分比十六进制 | 连接时发 `;0,01,64` |
| `06` | SET_MODE | 模式位 | LED / 模式控制 |
| `0A` | GET_POWER_SUPPLY | 无 | 查询供电 |
| `43` | SET_FORCE_HDMI_OFF | `00` 关闭 / `01` 开启 | HDMI 强制关闭 |

## 转速档位（Sony 原厂）

`0, 4800, 5700, 6600, 7500, 8400, 9300, 10200`

ThermalFanService 的自动档位是 `3900 / 4800 / 5700 / 6600 / 7500`，自动模式上限 7500。

原厂阈值（SoC/热敏电阻）：40 / 45 / 50 °C。

本 App 用**电池温度**控制，阈值相应下移：34 / 36 / 38 / 40 / 42 °C，带 2 °C 迟滞。

## 保活

原厂 ThermalFanService 大约每秒都会与配件通信。本 App 在连接后：

1. 发送 `;0,01,64`（电量）与 `;0,0A,00`（查询供电）；
2. 每 2 秒发送 `;0,03,00`（查询转速）作为心跳。

目的是避免 MCU 在空闲一段时间后复位 USB 链路（表现为整机在 20~60 秒后掉线）。

## USB 音频

Stream 的 3.5mm 耳机口是一个 USB 音频设备（`USB-Audio - XQZ-GG01`，`AudioDeviceInfo.TYPE_USB_HEADSET`）。
它不是模拟直通，所以 USB 链路掉线时音频会一起消失。
