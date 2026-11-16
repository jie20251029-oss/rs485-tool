import SwiftUI

@main
struct RS485DiagnosticApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView().preferredColorScheme(.dark)
        }
    }
}

struct ContentView: View {
    @StateObject private var ble = BLEManager()
    @State private var ecu: ECU = .mcu
    @State private var showAdvanced = false
    @State private var writeVin = ""
    @State private var writeKm = ""
    @State private var confirm: ConfirmAction?

    private let bgTop = Color(red: 0.04, green: 0.06, blue: 0.12)
    private let bgBottom = Color(red: 0.09, green: 0.11, blue: 0.22)
    private let accent = Color(red: 0.0, green: 0.85, blue: 0.75)
    private let accent2 = Color(red: 0.55, green: 0.45, blue: 1.0)
    private let txColor = Color(red: 0.35, green: 0.75, blue: 1.0)
    private let rxColor = Color(red: 0.35, green: 0.95, blue: 0.6)

    var body: some View {
        ZStack {
            LinearGradient(colors: [bgTop, bgBottom], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            Circle().fill(accent.opacity(0.18)).frame(width: 320).blur(radius: 80).offset(x: -120, y: -260)
            Circle().fill(accent2.opacity(0.16)).frame(width: 300).blur(radius: 90).offset(x: 140, y: -180)

            ScrollView {
                VStack(spacing: 14) {
                    header
                    connectionCard
                    if ble.state == .scanning || !ble.devices.isEmpty { deviceList }
                    if ble.state == .connected {
                        vehicleInfoCard
                        writeVinCard
                        writeKmCard
                        advancedToggle
                        if showAdvanced {
                            advancedCommands
                            logTerminal
                        }
                    } else {
                        notConnectedView
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 30)
            }
        }
        .alert(item: $confirm) { act in
            Alert(
                title: Text(act.title),
                message: Text(act.message),
                primaryButton: .destructive(Text("确认写入")) {
                    switch act {
                    case .vin(let v): ble.writeVin(v, addr: ecu.tx)
                    case .km(let k): ble.writeMileage(k, addr: ecu.tx)
                    }
                },
                secondaryButton: .cancel(Text("取消"))
            )
        }
    }

    // MARK: - 标题
    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("极核 485 诊断")
                    .font(.system(size: 26, weight: .heavy, design: .rounded))
                    .foregroundStyle(LinearGradient(colors: [.white, accent], startPoint: .leading, endPoint: .trailing))
                Text("读车架号 / 里程 · 写 VIN / 写里程")
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.45))
            }
            Spacer()
            statePill
        }
    }

    private var statePill: some View {
        HStack(spacing: 6) {
            Circle().fill(statusColor).frame(width: 8, height: 8).shadow(color: statusColor, radius: 6)
            Text(ble.state.label)
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundStyle(.white.opacity(0.7))
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private var statusColor: Color {
        switch ble.state {
        case .connected: return .green
        case .scanning, .connecting: return .yellow
        case .idle: return .gray
        }
    }

    // MARK: - 连接卡片
    private var connectionCard: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(LinearGradient(colors: [accent.opacity(0.35), accent2.opacity(0.25)],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 42, height: 42)
                Image(systemName: "bolt.horizontal.circle.fill").font(.system(size: 20)).foregroundStyle(accent)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(ble.deviceName.isEmpty ? "I6328A 模块" : ble.deviceName)
                    .font(.system(size: 15, weight: .semibold, design: .rounded)).foregroundStyle(.white)
                Text(ble.state == .connected ? "已连接" : (ble.scanning ? "搜索中…" : "点击扫描设备"))
                    .font(.system(size: 12, design: .rounded)).foregroundStyle(.white.opacity(0.45))
            }
            Spacer()
            Button {
                switch ble.state {
                case .connected: ble.disconnect()
                case .scanning: ble.stopScan()
                default: ble.startScan()
                }
            } label: {
                Text(ble.state == .connected ? "断开" : (ble.scanning ? "取消" : "扫描设备"))
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundStyle(ble.state == .connected ? .white : bgTop)
                    .padding(.horizontal, 18).padding(.vertical, 10)
                    .background(
                        Capsule().fill(ble.state == .connected
                                       ? AnyShapeStyle(Color.white.opacity(0.12))
                                       : AnyShapeStyle(LinearGradient(colors: [accent, accent2],
                                                                      startPoint: .leading, endPoint: .trailing)))
                    )
            }
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.08), lineWidth: 1))
    }

    // MARK: - 设备列表
    private var deviceList: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(ble.scanning ? "搜索到的设备" : "设备列表",
                      systemImage: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.6))
                Spacer()
                if ble.scanning { ProgressView().scaleEffect(0.7) }
            }
            if ble.devices.isEmpty {
                Text(ble.scanning ? "正在搜索…" : "未发现设备")
                    .font(.system(size: 12, design: .rounded)).foregroundStyle(.white.opacity(0.35))
                    .frame(maxWidth: .infinity).padding(.vertical, 20)
            } else {
                ForEach(ble.devices) { dev in
                    Button { ble.connect(dev) } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(Color.white.opacity(0.08)).frame(width: 34, height: 34)
                                Image(systemName: "bluetooth").font(.system(size: 14)).foregroundStyle(accent)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(dev.name).font(.system(size: 14, weight: .semibold, design: .rounded)).foregroundStyle(.white)
                                Text("信号 \(dev.rssi) dBm").font(.system(size: 10, design: .monospaced))
                                    .foregroundStyle(.white.opacity(0.35))
                            }
                            Spacer()
                            Text("连接").font(.system(size: 12, weight: .bold, design: .rounded)).foregroundStyle(accent)
                        }
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.white.opacity(0.05)))
                        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(accent.opacity(0.18), lineWidth: 1))
                    }
                }
            }
        }
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.08), lineWidth: 1))
    }

    // MARK: - 车辆信息
    private var vehicleInfoCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("车辆信息", icon: "info.circle.fill")
            VStack(spacing: 12) {
                infoRow("车架号 VIN", ble.vehicleInfo.vin, "number.square")
                Divider().background(Color.white.opacity(0.06))
                infoRow("总里程", ble.vehicleInfo.mileage, "speedometer")
                Divider().background(Color.white.opacity(0.06))
                infoRow("软件版本", ble.vehicleInfo.version, "arrow.triangle.2.circlepath")
                Divider().background(Color.white.opacity(0.06))
                infoRow("零件号", ble.vehicleInfo.partNo, "qrcode")
                Button {
                    ble.readAllVehicleInfo(addr: ecu.tx)
                } label: {
                    HStack {
                        Image(systemName: "arrow.clockwise").font(.system(size: 14, weight: .bold))
                        Text(ble.reading.isEmpty ? "一键读取车辆信息" : ble.reading)
                            .font(.system(size: 14, weight: .semibold, design: .rounded))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(accent2.opacity(0.25)))
                }
                .disabled(!ble.reading.isEmpty)
            }
            .padding(16)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.08), lineWidth: 1))
        }
    }

    // MARK: - 写 VIN
    private var writeVinCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("修改车架号", icon: "square.and.pencil")
            VStack(spacing: 10) {
                HStack {
                    Text("17 位新车架号").font(.system(size: 12)).foregroundStyle(.white.opacity(0.5))
                    Spacer()
                    Text("\(writeVin.count)/17").font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(writeVin.count == 17 ? rxColor : .white.opacity(0.3))
                }
                TextField("17位VIN", text: Binding(
                    get: { writeVin },
                    set: { writeVin = String($0.uppercased().prefix(17)) }
                ))
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(.white)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.06)))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(accent.opacity(0.25), lineWidth: 1))

                Button {
                    confirm = .vin(writeVin)
                } label: {
                    HStack {
                        Image(systemName: "square.and.arrow.down").font(.system(size: 14, weight: .bold))
                        Text("写入车架号").font(.system(size: 14, weight: .bold, design: .rounded))
                    }
                    .foregroundStyle(bgTop)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(accent))
                }
                .disabled(writeVin.count != 17)
                .opacity(writeVin.count == 17 ? 1 : 0.4)
            }
            .padding(16)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.08), lineWidth: 1))
        }
    }

    // MARK: - 写里程
    private var writeKmCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("修改总里程", icon: "speedometer")
            VStack(spacing: 10) {
                HStack {
                    Text("目标公里数").font(.system(size: 12)).foregroundStyle(.white.opacity(0.5))
                    Spacer()
                    if !writeKm.isEmpty {
                        Text("km").font(.system(size: 11, design: .monospaced)).foregroundStyle(.white.opacity(0.4))
                    }
                }
                TextField("例如 5000", text: Binding(
                    get: { writeKm },
                    set: { writeKm = String($0.filter { $0.isNumber }.prefix(7)) }
                ))
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(.white)
                .keyboardType(.numberPad)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.06)))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(accent2.opacity(0.25), lineWidth: 1))

                Button {
                    if let km = Int(writeKm) { confirm = .km(km) }
                } label: {
                    HStack {
                        Image(systemName: "square.and.arrow.down").font(.system(size: 14, weight: .bold))
                        Text("写入总里程").font(.system(size: 14, weight: .bold, design: .rounded))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(accent2))
                }
                .disabled(Int(writeKm).map { $0 >= 0 && $0 <= 16777215 } != true)
                .opacity(Int(writeKm).map { $0 >= 0 && $0 <= 16777215 } == true ? 1 : 0.4)
            }
            .padding(16)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.08), lineWidth: 1))
        }
    }

    // MARK: - 高级
    private var advancedToggle: some View {
        Button { withAnimation { showAdvanced.toggle() } } label: {
            HStack {
                Image(systemName: "wrench.and.screwdriver").font(.system(size: 14)).foregroundStyle(.white.opacity(0.5))
                Text("高级功能（其他命令 / 日志）").font(.system(size: 13)).foregroundStyle(.white.opacity(0.6))
                Spacer()
                Image(systemName: showAdvanced ? "chevron.up" : "chevron.down")
                    .font(.system(size: 12)).foregroundStyle(.white.opacity(0.4))
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.04)))
        }
    }

    private var advancedCommands: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                ForEach(ECU.allCases) { e in
                    Button { ecu = e } label: {
                        Text(e.rawValue.components(separatedBy: " ").first ?? "")
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .foregroundStyle(ecu == e ? accent : .white.opacity(0.6))
                            .frame(maxWidth: .infinity).padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: 10)
                                .fill(ecu == e ? accent.opacity(0.2) : Color.white.opacity(0.05)))
                    }
                }
            }
            ForEach(UDSCommand.all) { cmd in
                Button { ble.sendUDS(cmd.bytes, addr: ecu.tx, note: cmd.name) } label: {
                    HStack {
                        Text(cmd.name).font(.system(size: 13)).foregroundStyle(.white.opacity(0.85))
                        Spacer()
                        Text(cmd.hex).font(.system(size: 11, design: .monospaced)).foregroundStyle(accent.opacity(0.6))
                    }
                    .padding(.horizontal, 12).padding(.vertical, 9)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.04)))
                }
            }
        }
    }

    private var logTerminal: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("日志").font(.system(size: 13, weight: .semibold)).foregroundStyle(.white.opacity(0.6))
                Spacer()
                Button { ble.clearLogs() } label: {
                    Image(systemName: "trash").font(.system(size: 12)).foregroundStyle(.white.opacity(0.4))
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 3) {
                    if ble.logs.isEmpty {
                        Text("暂无数据").font(.system(size: 12, design: .monospaced)).foregroundStyle(.white.opacity(0.25))
                    }
                    ForEach(ble.logs) { log in
                        HStack(alignment: .top, spacing: 6) {
                            Text(dirLabel(log.dir))
                                .font(.system(size: 9, weight: .bold, design: .monospaced))
                                .foregroundStyle(log.dir == .tx ? txColor : (log.dir == .rx ? rxColor : .gray))
                                .frame(width: 26, alignment: .leading)
                            VStack(alignment: .leading, spacing: 1) {
                                if !log.hex.isEmpty {
                                    Text(log.hex).font(.system(size: 10, design: .monospaced))
                                        .foregroundStyle(log.dir == .tx ? txColor : rxColor)
                                }
                                if !log.note.isEmpty {
                                    Text(log.note).font(.system(size: 9)).foregroundStyle(.white.opacity(0.3))
                                }
                            }
                            Spacer(minLength: 0)
                        }
                    }
                }
                .padding(10)
            }
            .frame(height: 150)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.black.opacity(0.35)))
        }
    }

    private var notConnectedView: some View {
        VStack(spacing: 12) {
            Image(systemName: "bluetooth.disabled").font(.system(size: 48)).foregroundStyle(.white.opacity(0.2))
            Text("请先连接 I6328A 模块").font(.system(size: 14)).foregroundStyle(.white.opacity(0.4))
        }
        .frame(maxWidth: .infinity).padding(.top, 50)
    }

    // MARK: - 组件
    private func sectionTitle(_ title: String, icon: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 13)).foregroundStyle(accent.opacity(0.8))
            Text(title).font(.system(size: 13, weight: .semibold, design: .rounded)).foregroundStyle(.white.opacity(0.75))
        }
    }

    private func infoRow(_ label: String, _ value: String, _ icon: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 15)).foregroundStyle(.white.opacity(0.35)).frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.system(size: 11)).foregroundStyle(.white.opacity(0.45))
                Text(value.isEmpty ? "—" : value)
                    .font(.system(size: value.isEmpty ? 14 : 16, weight: value.isEmpty ? .regular : .semibold,
                                  design: label.contains("VIN") || label.contains("零件") ? .monospaced : .default))
                    .foregroundStyle(value.isEmpty ? .white.opacity(0.25) : .white)
            }
            Spacer()
        }
    }

    private func dirLabel(_ d: BLEManager.Dir) -> String {
        switch d {
        case .tx: return "TX"
        case .rx: return "RX"
        case .system: return "SYS"
        }
    }
}

enum ConfirmAction: Identifiable {
    case vin(String)
    case km(Int)

    var id: String {
        switch self {
        case .vin(let v): return "vin_\(v)"
        case .km(let k): return "km_\(k)"
        }
    }
    var title: String {
        switch self {
        case .vin: return "确认写车架号"
        case .km: return "确认写总里程"
        }
    }
    var message: String {
        switch self {
        case .vin(let v): return "即将把车架号改为：\n\n\(v)\n\n此操作不可撤销"
        case .km(let k): return "即将把总里程改为：\n\n\(k) km\n\n此操作不可撤销"
        }
    }
}
