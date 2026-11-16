import Foundation

/// 485 上的 ECU 地址
enum ECU: String, CaseIterable, Identifiable {
    case mcu = "MCU 电机控制器"
    case labms = "LABMS 低压BMS"

    var id: String { rawValue }

    var tx: UInt8 {
        switch self {
        case .mcu: return 0x21
        case .labms: return 0x22
        }
    }
    var rx: UInt8 {
        switch self {
        case .mcu: return 0xA1
        case .labms: return 0xA2
        }
    }
}

/// UDS 命令
struct UDSCommand: Identifiable {
    let id = UUID()
    let name: String
    let hex: String
    let category: Category
    var bytes: [UInt8] { hex.hexBytes ?? [] }

    enum Category: String, CaseIterable, Identifiable {
        case info = "读信息"
        case diag = "诊断"
        case routine = "例程/维修"
        var id: String { rawValue }
    }

    static let all: [UDSCommand] = [
        // 读信息
        UDSCommand(name: "读零件号", hex: "22 F1 87", category: .info),
        UDSCommand(name: "读供应商", hex: "22 F1 8A", category: .info),
        UDSCommand(name: "读仪表UUID", hex: "22 F1 8D", category: .info),
        UDSCommand(name: "读VIN车架号", hex: "22 F1 90", category: .info),
        UDSCommand(name: "读软件版本", hex: "22 F1 95", category: .info),
        UDSCommand(name: "读总里程", hex: "22 F2 01", category: .info),
        UDSCommand(name: "读NFC卡数量", hex: "22 06 03", category: .info),
        UDSCommand(name: "读IMEI", hex: "22 07 00", category: .info),
        UDSCommand(name: "读功能配置", hex: "22 F0 10", category: .info),
        UDSCommand(name: "读配置(0xF101)", hex: "22 F1 01", category: .info),

        // 诊断
        UDSCommand(name: "扩展会话", hex: "10 03", category: .diag),
        UDSCommand(name: "保持链接", hex: "3E 80", category: .diag),
        UDSCommand(name: "安全访问-取种子", hex: "27 01", category: .diag),
        UDSCommand(name: "硬复位", hex: "11 01", category: .diag),
        UDSCommand(name: "读故障码", hex: "19 02 09", category: .diag),
        UDSCommand(name: "清故障码", hex: "14 FF FF FF", category: .diag),

        // 例程/维修
        UDSCommand(name: "胎压匹配-前轮", hex: "31 01 53 90 00", category: .routine),
        UDSCommand(name: "胎压匹配-后轮", hex: "31 01 53 91 00", category: .routine),
        UDSCommand(name: "胎压匹配-前查询", hex: "31 03 53 90", category: .routine),
        UDSCommand(name: "胎压匹配-后查询", hex: "31 03 53 91", category: .routine),
        UDSCommand(name: "NFC学卡", hex: "31 01 52 00", category: .routine),
        UDSCommand(name: "NFC删卡", hex: "31 01 52 01", category: .routine),
        UDSCommand(name: "NFC查询", hex: "31 03 52 00", category: .routine),
        UDSCommand(name: "节点在线检查", hex: "31 01 51 00 05", category: .routine),
        UDSCommand(name: "在线检查结束", hex: "31 02 51 00", category: .routine),
        UDSCommand(name: "换件", hex: "31 01 51 01", category: .routine),
        UDSCommand(name: "换件查询", hex: "31 03 51 01", category: .routine),
    ]

    static func inCategory(_ c: Category) -> [UDSCommand] {
        all.filter { $0.category == c }
    }
}

/// 写操作定义（需要参数）
struct WriteAction: Identifiable {
    let id = UUID()
    let name: String
    let service: String
    let did: String          // DID 高字节(hex) + 低字节(hex) 用空格分隔
    let dataLen: Int
    let format: Format

    enum Format {
        case ascii      // ASCII 字符串（如 VIN）
        case bigEndian  // 大端整数（如里程）
        case hex        // 原始 HEX
    }

    static let all: [WriteAction] = [
        WriteAction(name: "写VIN车架号", service: "2E", did: "F1 90", dataLen: 17, format: .ascii),
        WriteAction(name: "写总里程", service: "2E", did: "F2 01", dataLen: 3, format: .bigEndian),
        WriteAction(name: "写功能配置", service: "2E", did: "F0 10", dataLen: 16, format: .hex),
        WriteAction(name: "写网络配置", service: "2E", did: "F1 01", dataLen: 8, format: .hex),
        WriteAction(name: "写SK安全密钥", service: "2E", did: "02 03", dataLen: 16, format: .hex),
    ]
}
