import Foundation
import CoreBluetooth
import Combine

/// 扫描到的设备
struct DiscoveredDevice: Identifiable {
    let id: UUID
    let name: String
    let rssi: Int
    let peripheral: CBPeripheral
}

/// 车辆信息（解析后的可读结果）
struct VehicleInfo {
    var vin = ""
    var mileage = ""
    var version = ""
    var partNo = ""
    var supplier = ""
    var uuid = ""

    var isEmpty: Bool {
        vin.isEmpty && mileage.isEmpty && version.isEmpty
            && partNo.isEmpty && supplier.isEmpty && uuid.isEmpty
    }
}

/// BLE 连接 + 485 帧 + 车辆信息解析 + 手动选设备
final class BLEManager: NSObject, ObservableObject {
    private let serviceUUID = CBUUID(string: "0000FFF0-0000-1000-8000-00805F9B34FB")
    private let notifyUUID  = CBUUID(string: "0000FFF1-0000-1000-8000-00805F9B34FB")
    private let writeUUID   = CBUUID(string: "0000FFF2-0000-1000-8000-00805F9B34FB")

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?

    @Published var state: BLEState = .idle
    @Published var deviceName: String = ""
    @Published var logs: [LogEntry] = []
    @Published var scanning = false
    @Published var devices: [DiscoveredDevice] = []
    @Published var liveFrames: [UInt8: LiveFrame] = [:]

    /// 车辆信息
    @Published var vehicleInfo = VehicleInfo()
    /// 读取状态提示
    @Published var reading = ""

    private var rxBuffer = Data()
    private var knownPeripherals: Set<UUID> = []

    enum BLEState: Equatable {
        case idle, scanning, connecting, connected
        var label: String {
            switch self {
            case .idle: return "未连接"
            case .scanning: return "扫描中…"
            case .connecting: return "连接中…"
            case .connected: return "已连接"
            }
        }
    }

    struct LiveFrame: Identifiable {
        var id: UInt8 { addr }
        let addr: UInt8
        var data: [UInt8]
        var count: Int
        var lastUpdate: Date
        var changed: Set<Int> = []
    }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: - 扫描 / 连接
    func startScan() {
        guard central.state == .poweredOn else { return }
        devices.removeAll()
        knownPeripherals.removeAll()
        state = .scanning
        scanning = true
        central.scanForPeripherals(withServices: nil, options: nil)
    }

    func stopScan() {
        central.stopScan()
        scanning = false
        if state == .scanning { state = .idle }
    }

    func connect(_ device: DiscoveredDevice) {
        central.stopScan()
        scanning = false
        deviceName = device.name
        peripheral = device.peripheral
        state = .connecting
        central.connect(device.peripheral, options: nil)
    }

    func disconnect() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        writeChar = nil
        peripheral = nil
        state = .idle
    }

    // MARK: - 485 帧
    func buildFrame(addr: UInt8, data: [UInt8]) -> [UInt8] {
        var frame: [UInt8] = [0x43, 0x46, addr, UInt8(data.count & 0xFF)]
        frame += data
        var sum = Int(addr) + data.count
        for b in data { sum += Int(b) }
        frame.append(UInt8(sum & 0xFF))
        frame += [0x45, 0x44]
        return frame
    }

    func sendUDS(_ data: [UInt8], addr: UInt8, note: String = "") {
        sendRaw(buildFrame(addr: addr, data: data), note: note)
    }

    func sendRaw(_ bytes: [UInt8], note: String = "") {
        guard let char = writeChar, let p = peripheral else {
            appendLog(.system, hex: "", note: "未连接，无法发送")
            return
        }
        p.writeValue(Data(bytes), for: char, type: .withoutResponse)
        appendLog(.tx, hex: bytes.hexString, note: note)
    }

    // MARK: - 一键读车辆信息
    func readAllVehicleInfo(addr: UInt8) {
        reading = "读取中…"
        // 读 VIN / 里程 / 版本 / 零件号 / 供应商（依次发，间隔 150ms）
        let items: [[UInt8]] = [
            [0x22, 0xF1, 0x90],   // VIN
            [0x22, 0xF2, 0x01],   // 里程
            [0x22, 0xF1, 0x95],   // 版本
            [0x22, 0xF1, 0x87],   // 零件号
            [0x22, 0xF1, 0x8A],   // 供应商
        ]
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            for (i, cmd) in items.enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.15) {
                    self.sendUDS(cmd, addr: addr, note: "读取信息 \(i + 1)/\(items.count)")
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(items.count) * 0.15 + 0.8) {
                self.reading = ""
            }
        }
    }

    // MARK: - 写操作
    func writeVin(_ vin: String, addr: UInt8) {
        let b = Array(vin.trimmingCharacters(in: .whitespaces).uppercased().utf8)
        guard b.count == 17 else {
            appendLog(.system, hex: "", note: "VIN 必须 17 位")
            return
        }
        sendUDS([0x2E, 0xF1, 0x90] + b, addr: addr, note: "写VIN: \(vin)")
    }

    func writeMileage(_ km: Int, addr: UInt8) {
        guard km >= 0 && km <= 0xFFFFFF else {
            appendLog(.system, hex: "", note: "里程超出范围")
            return
        }
        let data: [UInt8] = [
            UInt8((km >> 16) & 0xFF),
            UInt8((km >> 8) & 0xFF),
            UInt8(km & 0xFF)
        ]
        sendUDS([0x2E, 0xF2, 0x01] + data, addr: addr, note: "写总里程: \(km) km")
    }

    // MARK: - 接收解析
    private func feed(_ data: Data) {
        rxBuffer.append(data)
        while rxBuffer.count >= 6 {
            let b = [UInt8](rxBuffer)
            if b[0] != 0x43 || b[1] != 0x46 {
                rxBuffer.removeFirst()
                continue
            }
            let len = Int(b[3])
            let total = 4 + len + 2
            if rxBuffer.count < total { break }
            let frame = Array(b[0..<total])
            let addr = frame[2]
            let payload = Array(frame[4..<(4 + len)])
            rxBuffer.removeFirst(total)
            appendLog(.rx, hex: frame.hexString,
                      note: "addr=0x\(String(format: "%02X", addr))  数据=\(payload.hexString)")
            updateLiveFrame(addr: addr, data: payload)
            parseUds(payload)
        }
    }

    private func parseUds(_ p: [UInt8]) {
        guard p.count >= 3, p[0] == 0x62 else { return }
        let did = (Int(p[1]) << 8) | Int(p[2])
        let body = Array(p[3...])
        var info = vehicleInfo
        switch did {
        case 0xF190:
            let vin = asciiOf(body)
            if vin.count >= 17 { info.vin = String(vin.prefix(17)) }
        case 0xF201:
            if body.count >= 3 {
                let raw = (Int(body[0]) << 16) | (Int(body[1]) << 8) | Int(body[2])
                info.mileage = fmtMileage(raw)
            }
        case 0xF195:
            info.version = asciiOf(body)
        case 0xF187:
            info.partNo = asciiOf(body)
        case 0xF18A:
            info.supplier = asciiOf(body)
        case 0xF18D:
            info.uuid = asciiOf(body)
        default:
            return
        }
        DispatchQueue.main.async { self.vehicleInfo = info }
    }

    private func fmtMileage(_ raw: Int) -> String {
        let km = Double(raw) / 10.0
        if km == km.rounded() { return "\(Int(km)).0 km" }
        return String(format: "%.1f km", km)
    }

    private func asciiOf(_ b: [UInt8]) -> String {
        var s = ""
        for x in b {
            if x == 0xFF || x == 0x00 { break }
            if x >= 32 && x < 127 { s.append(Character(UnicodeScalar(x))) }
        }
        return s.trimmingCharacters(in: .whitespaces)
    }

    private func updateLiveFrame(addr: UInt8, data: [UInt8]) {
        DispatchQueue.main.async {
            var changed = Set<Int>()
            if let prev = self.liveFrames[addr] {
                for i in 0..<min(prev.data.count, data.count) where prev.data[i] != data[i] {
                    changed.insert(i)
                }
            }
            self.liveFrames[addr] = LiveFrame(
                addr: addr, data: data,
                count: (self.liveFrames[addr]?.count ?? 0) + 1,
                lastUpdate: Date(), changed: changed
            )
        }
    }

    // MARK: - 日志
    enum Dir { case tx, rx, system }

    struct LogEntry: Identifiable {
        let id = UUID()
        let time: Date
        let dir: Dir
        let hex: String
        let note: String
    }

    private func appendLog(_ dir: Dir, hex: String, note: String) {
        DispatchQueue.main.async {
            self.logs.append(LogEntry(time: Date(), dir: dir, hex: hex, note: note))
            if self.logs.count > 500 { self.logs.removeFirst(100) }
        }
    }

    func clearLogs() { logs.removeAll() }
}

// MARK: - CBCentralManagerDelegate
extension BLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn { state = .idle }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""
        guard !name.isEmpty else { return }
        if !knownPeripherals.contains(peripheral.identifier) {
            knownPeripherals.insert(peripheral.identifier)
            devices.append(DiscoveredDevice(
                id: peripheral.identifier, name: name,
                rssi: RSSI.intValue, peripheral: peripheral
            ))
            devices.sort { $0.rssi > $1.rssi }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        state = .idle
        writeChar = nil
    }
}

// MARK: - CBPeripheralDelegate
extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else { return }
        peripheral.discoverCharacteristics([notifyUUID, writeUUID], for: svc)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for ch in service.characteristics ?? [] {
            if ch.uuid == notifyUUID {
                peripheral.setNotifyValue(true, for: ch)
            } else if ch.uuid == writeUUID {
                writeChar = ch
            }
        }
        state = .connected
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value, characteristic.uuid == notifyUUID else { return }
        feed(data)
    }
}

// MARK: - 工具
extension Array where Element == UInt8 {
    var hexString: String { map { String(format: "%02X", $0) }.joined(separator: " ") }
}

extension String {
    var hexBytes: [UInt8]? {
        let clean = replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "0x", with: "")
            .replacingOccurrences(of: ",", with: "")
            .uppercased()
        guard clean.count % 2 == 0, !clean.isEmpty else { return nil }
        var out: [UInt8] = []
        var idx = clean.startIndex
        while idx < clean.endIndex {
            let next = clean.index(idx, offsetBy: 2)
            guard let b = UInt8(clean[idx..<next], radix: 16) else { return nil }
            out.append(b)
            idx = next
        }
        return out
    }
}
