package com.cfmoto.rs485

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 车辆信息（解析后的可读结果） */
data class VehicleInfo(
    val vin: String = "",
    val mileage: String = "",      // 已格式化，如 "12345.6 km"
    val version: String = "",
    val partNo: String = "",
    val supplier: String = "",
    val uuid: String = ""
) {
    val isEmpty: Boolean
        get() = vin.isEmpty() && mileage.isEmpty() && version.isEmpty()
                && partNo.isEmpty() && supplier.isEmpty() && uuid.isEmpty()
}

/** BLE + 485 帧 + 车辆信息解析 */
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val NOTIFY_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED }

    data class LogEntry(val time: Long, val dir: Dir, val hex: String, val note: String) {
        enum class Dir { TX, RX, SYS }
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs
    private val _liveFrames = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val liveFrames: StateFlow<Map<Int, ByteArray>> = _liveFrames

    /** 车辆信息（自动解析） */
    private val _vehicleInfo = MutableStateFlow(VehicleInfo())
    val vehicleInfo: StateFlow<VehicleInfo> = _vehicleInfo

    /** 正在读取的提示 */
    private val _reading = MutableStateFlow("")
    val reading: StateFlow<String> = _reading

    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var adapter: BluetoothAdapter? = null
    private var scanCallback: ScanCallback? = null
    private val rxBuf = ArrayList<Byte>()

    init {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = mgr.adapter
        scanner = adapter?.bluetoothLeScanner
    }

    fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) return
        _state.value = State.SCANNING
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.uppercase().contains("6328")) {
                    stopScan()
                    _deviceName.value = name
                    _state.value = State.CONNECTING
                    connect(result.device)
                }
            }
        }
        scanCallback = cb
        scanner?.startScan(cb)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback
        if (cb != null) scanner?.stopScan(cb)
        scanCallback = null
        if (_state.value == State.SCANNING) _state.value = State.IDLE
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect(); gatt?.close(); gatt = null
        writeChar = null
        _state.value = State.IDLE
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.value = State.IDLE; writeChar = null
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SERVICE_UUID) ?: return
                val notify = svc.getCharacteristic(NOTIFY_UUID)
                val write = svc.getCharacteristic(WRITE_UUID)
                if (notify != null) g.setCharacteristicNotification(notify, true)
                writeChar = write
                _state.value = State.CONNECTED
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                ch.value?.let { feed(it) }
            }
        })
    }

    /** 构造 485 帧 43 46 [addr] [len] [data] [ck] 45 44 */
    fun buildFrame(addr: Int, data: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x43); out.add(0x46); out.add(addr.toByte()); out.add(data.size.toByte())
        out.addAll(data.toList())
        var sum = addr + data.size
        data.forEach { sum += it.toInt() and 0xFF }
        out.add((sum and 0xFF).toByte()); out.add(0x45); out.add(0x44)
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    fun sendUds(data: ByteArray, addr: Int, note: String = "") {
        val frame = buildFrame(addr, data)
        val ch = writeChar
        if (ch == null || gatt == null) { addLog(LogEntry.Dir.SYS, "", "未连接"); return }
        ch.value = frame
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(ch)
        addLog(LogEntry.Dir.TX, frame.toHex(), note)
    }

    /** 读 DID: 22 XX YY */
    fun readDid(didHigh: Int, didLow: Int, addr: Int, label: String) {
        sendUds(byteArrayOf(0x22, didHigh.toByte(), didLow.toByte()), addr, "读$label")
    }

    /** 一键读全部车辆信息 */
    fun readAllVehicleInfo(addr: Int) {
        _reading.value = "读取中…"
        val items = listOf(
            byteArrayOf(0x27, 0x01),               // 安全访问（部分车需要）
            byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), // VIN
            byteArrayOf(0x22, 0xF2.toByte(), 0x01), // 里程
            byteArrayOf(0x22, 0xF1.toByte(), 0x95.toByte()), // 版本
            byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte()), // 零件号
            byteArrayOf(0x22, 0xF1.toByte(), 0x8A.toByte()), // 供应商
        )
        val ch = writeChar ?: return
        val g = gatt ?: return
        // 逐个发送（间隔 120ms，避免总线冲突）
        var i = 0
        val sender = object : Runnable {
            override fun run() {
                if (i < items.size) {
                    val frame = buildFrame(addr, items[i])
                    ch.value = frame
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    g.writeCharacteristic(ch)
                    addLog(LogEntry.Dir.TX, frame.toHex(), "读取信息 ${i + 1}/${items.size}")
                    i++
                    main.postDelayed(this, 150)
                } else {
                    main.postDelayed({ _reading.value = "" }, 800)
                }
            }
        }
        main.post(sender)
    }

    /** 写 VIN: 2E F1 90 + 17 字节 ASCII */
    fun writeVin(vin: String, addr: Int) {
        val b = vin.trim().uppercase().toByteArray(Charsets.US_ASCII)
        if (b.size != 17) { addLog(LogEntry.Dir.SYS, "", "VIN 必须 17 位"); return }
        sendUds(byteArrayOf(0x2E, 0xF1.toByte(), 0x90.toByte()) + b, addr, "写VIN: $vin")
    }

    /** 写总里程: 2E F2 01 + 3 字节大端（输入 km） */
    fun writeMileage(km: Long, addr: Int) {
        if (km < 0 || km > 0xFFFFFF) { addLog(LogEntry.Dir.SYS, "", "里程超出范围"); return }
        val data = byteArrayOf(
            (km shr 16 and 0xFF).toByte(),
            (km shr 8 and 0xFF).toByte(),
            (km and 0xFF).toByte()
        )
        sendUds(byteArrayOf(0x2E, 0xF2.toByte(), 0x01) + data, addr, "写总里程: $km km")
    }

    private fun feed(data: ByteArray) {
        rxBuf.addAll(data.toList())
        while (rxBuf.size >= 6) {
            if ((rxBuf[0].toInt() and 0xFF) != 0x43 || (rxBuf[1].toInt() and 0xFF) != 0x46) {
                rxBuf.removeAt(0); continue
            }
            val len = rxBuf[3].toInt() and 0xFF
            val total = 4 + len + 2
            if (rxBuf.size < total) break
            val frame = rxBuf.subList(0, total).toByteArray()
            val addr = frame[2].toInt() and 0xFF
            val payload = frame.copyOfRange(4, 4 + len)
            repeat(total) { rxBuf.removeAt(0) }
            addLog(LogEntry.Dir.RX, frame.toHex(), "")
            updateLive(addr, payload)
            parseUds(payload)
        }
    }

    private fun updateLive(addr: Int, payload: ByteArray) {
        val m = _liveFrames.value.toMutableMap()
        m[addr] = payload
        _liveFrames.value = m
    }

    /** 解析 UDS 响应，更新车辆信息 */
    private fun parseUds(p: ByteArray) {
        if (p.size < 3) return
        val sid = p[0].toInt() and 0xFF
        if (sid != 0x62) return  // 只处理 ReadDataByIdentifier 正响应
        val did = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
        val body = p.copyOfRange(3, p.size)
        val info = _vehicleInfo.value
        when (did) {
            0xF190 -> {
                val vin = asciiOf(body)
                if (vin.length >= 17) _vehicleInfo.value = info.copy(vin = vin.take(17))
            }
            0xF201 -> {
                if (body.size >= 3) {
                    val raw = ((body[0].toInt() and 0xFF) shl 16) or
                              ((body[1].toInt() and 0xFF) shl 8) or
                              (body[2].toInt() and 0xFF)
                    _vehicleInfo.value = info.copy(mileage = fmtMileage(raw))
                }
            }
            0xF195 -> _vehicleInfo.value = info.copy(version = asciiOf(body))
            0xF187 -> _vehicleInfo.value = info.copy(partNo = asciiOf(body))
            0xF18A -> _vehicleInfo.value = info.copy(supplier = asciiOf(body))
            0xF18D -> _vehicleInfo.value = info.copy(uuid = asciiOf(body))
        }
    }

    private fun fmtMileage(raw: Int): String {
        // App 里总里程是 0.1km 单位
        val km = raw / 10.0
        return if (km == km.toLong().toDouble()) "${km.toLong()}.0 km" else "%.1f km".format(km)
    }

    private fun asciiOf(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c == 0xFF || c == 0x00) break
            if (c in 32..126) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun addLog(dir: LogEntry.Dir, hex: String, note: String) {
        main.post {
            val list = _logs.value.toMutableList()
            list.add(LogEntry(System.currentTimeMillis(), dir, hex, note))
            if (list.size > 500) list.subList(0, 100).clear()
            _logs.value = list
        }
    }

    fun clearLogs() { _logs.value = emptyList() }
    fun clearInfo() { _vehicleInfo.value = VehicleInfo() }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
