import SwiftUI

@main
struct RS485DiagnosticApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}

struct ContentView: View {
    @StateObject private var ble = BLEManager()
    @State private var ecu: ECU = .mcu
    @State private var category: UDSCommand.Category = .info
    @State private var customHex = ""
    @State private var logPulse = false

    // 配色
    private let bgTop = Color(red: 0.04, green: 0.06, blue: 0.12)
    private let bgBottom = Color(red: 0.09, green: 0.11, blue: 0.22)
    private let accent = Color(red: 0.0, green: 0.85, blue: 0.75)
    private let accent2 = Color(red: 0.55, green: 0.45, blue: 1.0)
    private let txColor = Color(red: 0.35, green: 0.75, blue: 1.0)
    private let rxColor = Color(red: 0.35, green: 0.95, blue: 0.6)

    var body: some View {
        ZStack {
            // 背景渐变
            LinearGradient(colors: [bgTop, bgBottom], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            // 光晕
            Circle()
                .fill(accent.opacity(0.18))
                .frame(width: 320)
                .blur(radius: 80)
                .offset(x: -120, y: -260)
            Circle()
                .fill(accent2.opacity(0.16))
                .frame(width: 300)
                .blur(radius: 90)
                .offset(x: 140, y: -180)

            ScrollView {
                VStack(spacing: 16) {
                    header
                    connectionCard
                    ecuPicker
                    categoryPicker
                    commandGrid
                    customInput
                    logTerminal
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 30)
            }
        }
        .onAppear { logPulse = true }
    }

    // MARK: - 顶部标题
    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("485 诊断")
                    .font(.system(size: 28, weight: .heavy, design: .rounded))
                    .foregroundStyle(
                        LinearGradient(colors: [.white, accent], startPoint: .leading, endPoint: .trailing)
                    )
                Text("CFMOTO AE4/AE5 · I6328A 透传")
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.45))
            }
            Spacer()
            // 状态圆点
            HStack(spacing: 6) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 8, height: 8)
                    .shadow(color: statusColor, radius: 6)
                    .scaleEffect(ble.state == .connected ? (logPulse ? 1.2 : 1.0) : 1.0)
                    .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                               value: logPulse)
                Text(ble.state.label)
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.7))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
        }
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
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(LinearGradient(colors: [accent.opacity(0.35), accent2.opacity(0.25)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 44, height: 44)
                Image(systemName: "bolt.horizontal.circle.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(accent)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(ble.deviceName.isEmpty ? "I6328A 透传模块" : ble.deviceName)
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                Text(ble.state == .connected ? "已建立 485 通道" :
                        (ble.scanning ? "正在搜索设备…" : "点击连接开始"))
                    .font(.system(size: 12, design: .rounded))
                    .foregroundStyle(.white.opacity(0.45))
            }
            Spacer()
            Button {
                switch ble.state {
                case .connected: ble.disconnect()
                case .scanning: ble.stopScan()
                default: ble.startScan()
                }
            } label: {
                Text(ble.state == .connected ? "断开" : (ble.scanning ? "取消" : "连接"))
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundStyle(ble.state == .connected ? .white : bgTop)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(
                        Capsule().fill(ble.state == .connected
                                       ? AnyShapeStyle(Color.white.opacity(0.12))
                                       : AnyShapeStyle(LinearGradient(colors: [accent, accent2],
                                                                      startPoint: .leading, endPoint: .trailing)))
                    )
                    .overlay(Capsule().stroke(.white.opacity(0.15), lineWidth: 1))
            }
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(.white.opacity(0.08), lineWidth: 1))
    }

    // MARK: - ECU 选择
    private var ecuPicker: some View {
        HStack(spacing: 10) {
            ForEach(ECU.allCases) { e in
                Button {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { ecu = e }
                } label: {
                    VStack(spacing: 3) {
                        Text(e.rawValue.components(separatedBy: " ").first ?? "")
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                        Text("tx 0x\(String(format: "%02X", e.tx))")
                            .font(.system(size: 10, design: .monospaced))
                            .opacity(0.6)
                    }
                    .foregroundStyle(ecu == e ? bgTop : .white.opacity(0.7))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(ecu == e
                                  ? AnyShapeStyle(LinearGradient(colors: [accent, accent.opacity(0.8)],
                                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                                  : AnyShapeStyle(Color.white.opacity(0.05)))
                    )
                }
            }
        }
    }

    // MARK: - 命令分类
    private var categoryPicker: some View {
        HStack(spacing: 4) {
            ForEach(UDSCommand.Category.allCases) { c in
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { category = c }
                } label: {
                    Text(c.rawValue)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundStyle(category == c ? .white : .white.opacity(0.45))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(
                            RoundedRectangle(cornerRadius: 11, style: .continuous)
                                .fill(category == c ? Color.white.opacity(0.12) : .clear)
                        )
                }
            }
        }
        .padding(4)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: - 命令网格
    private var commandGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10),
                            GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(UDSCommand.inCategory(category)) { cmd in
                Button {
                    ble.sendUDS(cmd.bytes, addr: ecu.tx, note: "\(ecu.rawValue) · \(cmd.name)")
                } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(cmd.name)
                            .font(.system(size: 14, weight: .semibold, design: .rounded))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Text(cmd.hex)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(accent.opacity(0.85))
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(Color.white.opacity(0.05))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(accent.opacity(0.18), lineWidth: 1)
                    )
                }
                .disabled(ble.state != .connected)
                .opacity(ble.state == .connected ? 1 : 0.45)
            }
        }
    }

    // MARK: - 自定义命令
    private var customInput: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "terminal")
                    .font(.system(size: 13))
                    .foregroundStyle(accent.opacity(0.8))
                TextField("自定义 HEX  如 22 F1 90", text: $customHex)
                    .font(.system(size: 14, design: .monospaced))
                    .foregroundStyle(.white)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            Button {
                guard let bytes = customHex.hexBytes, !bytes.isEmpty else { return }
                ble.sendUDS(bytes, addr: ecu.tx, note: "自定义")
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(bgTop)
                    .padding(13)
                    .background(
                        Circle().fill(LinearGradient(colors: [accent, accent2],
                                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                    )
            }
            .disabled(ble.state != .connected || (customHex.hexBytes?.isEmpty ?? true))
            .opacity(ble.state == .connected ? 1 : 0.45)
        }
    }

    // MARK: - 日志终端
    private var logTerminal: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("日志", systemImage: "text.alignleft")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.7))
                Spacer()
                Button {
                    withAnimation { ble.clearLogs() }
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.5))
                }
            }
            .padding(.horizontal, 4)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        if ble.logs.isEmpty {
                            Text("等待收发数据…")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(.white.opacity(0.25))
                                .padding(.vertical, 10)
                        }
                        ForEach(ble.logs) { log in
                            logLine(log).id(log.id)
                        }
                    }
                    .padding(12)
                }
                .frame(height: 200)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color.black.opacity(0.35))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(.white.opacity(0.06), lineWidth: 1)
                )
                .onChange(of: ble.logs.count) { _ in
                    if let last = ble.logs.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }
        }
    }

    private func logLine(_ log: BLEManager.LogEntry) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(timeString(log.time))
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(.white.opacity(0.3))
            Text(directionLabel(log.dir))
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(log.dir == .tx ? txColor : (log.dir == .rx ? rxColor : .gray))
                .frame(width: 24, alignment: .leading)
            VStack(alignment: .leading, spacing: 1) {
                if !log.hex.isEmpty {
                    Text(log.hex)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(log.dir == .tx ? txColor.opacity(0.9) : rxColor.opacity(0.9))
                }
                if !log.note.isEmpty {
                    Text(log.note)
                        .font(.system(size: 10, design: .rounded))
                        .foregroundStyle(.white.opacity(0.35))
                }
            }
            Spacer(minLength: 0)
        }
    }

    private func timeString(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f.string(from: d)
    }

    private func directionLabel(_ dir: BLEManager.Dir) -> String {
        switch dir {
        case .tx: return "TX"
        case .rx: return "RX"
        case .system: return "SYS"
        }
    }
}
