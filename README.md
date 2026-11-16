# CFMOTO 485 诊断工具（iOS）

iPhone + I6328A 透传模块，通过 485 总线调试 CFMOTO AE4/AE5。

## 功能

- **BLE 连接**：自动识别 I6328A（FFF0 透传服务）
- **双 ECU 切换**：MCU 电机控制器（tx 0x21）/ LABMS 低压BMS（tx 0x22）
- **20 条内置命令**：读信息 / 诊断 / 例程维修，一键发送
- **自定义 HEX**：发任意 UDS 命令
- **实时日志**：TX/RX 彩色分色，自动滚动
- **自动 485 成帧**：`43 46 [地址] [长度] [数据] [校验] 45 44`

## 文件

| 文件 | 作用 |
|---|---|
| `RS485DiagnosticApp` (ContentView.swift 内) | App 入口 |
| `BLEManager.swift` | BLE 连接 + 485 帧构造/解析 |
| `UDSCommand.swift` | ECU 地址 + 全部命令定义 |
| `ContentView.swift` | SwiftUI 界面 |

## Xcode 搭建

1. Xcode → New Project → **iOS App**（SwiftUI，Swift）。
2. 把 3 个 `.swift`（BLEManager / UDSCommand / ContentView）拖进工程。
3. `Info.plist` 加蓝牙权限：
   - `Privacy - Bluetooth Always Usage Description` → `需要蓝牙连接 485 透传模块`
4. 真机运行（模拟器无 BLE）。
5. 签名：Signing & Capabilities → 你的 Apple ID（或用 AltStore/SideStore 自签）。

## 485 协议

```
帧: 43 46 [地址] [长度] [UDS数据...] [校验] 45 44
校验 = (地址 + 长度 + 数据各字节和) & 0xFF
```

| ECU | tx（发送地址） | rx（接收地址） |
|---|---|---|
| MCU 电机控制器 | 0x21 | 0xA1 |
| LABMS 低压BMS | 0x22 | 0xA2 |

## 内置命令一览

**读信息**
`22 F1 87` 零件号 · `22 F1 8A` 供应商 · `22 F1 8D` 仪表UUID · `22 F1 90` VIN
`22 F1 95` 版本 · `22 F2 01` 总里程 · `22 06 03` NFC卡数 · `22 07 00` IMEI · `22 F0 10` 功能配置

**诊断**
`10 03` 扩展会话 · `3E 80` 保持链接 · `27 01` 安全访问 · `11 01` 硬复位
`19 02 09` 读故障码 · `14 FF FF FF` 清故障码

**例程/维修**
`31 01 53 90 00` 前轮胎压匹配 · `31 01 53 91 00` 后轮胎压匹配
`31 01 52 00` NFC学卡 · `31 01 52 01` NFC删卡
`31 01 51 00 05` 节点在线检查 · `31 01 51 01` 换件

## 注意

- 写操作（VIN/里程/配置）需先 `27 01` 安全访问，工具里可手动发。
- ABS 不在 485 上（走 CAN 0x7E1），本工具不涉及。
- 先空载测通 BLE 与 485 收发，再上车操作。
