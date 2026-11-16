import Foundation
import CoreBluetooth
import Combine

/// BLE 连接 I6328A 透传模块 + 485 帧构造/解析 + 数据流
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
    /// 数据流: 地址 -> (数据, 更新计数)
    @Published var liveFrames: [UInt8: LiveFrame] = [:]

    private var rxBuffer = Data()

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
        state = .scanning
        scanning = true
        central.scanForPeripherals(withServices: nil, options: nil)
    }

    func stopScan() {
        central.stopScan()
        scanning = false
        if state == .scanning { state = .idle }
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

    // MARK: - 接收
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
        }
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
        if name.uppercased().contains("6328") {
            central.stopScan()
            scanning = false
            self.peripheral = peripheral
            deviceName = name
            state = .connecting
            central.connect(peripheral, options: nil)
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
