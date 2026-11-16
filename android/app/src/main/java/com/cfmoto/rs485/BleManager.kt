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
    val mileage: String = "",
    val version: String = "",
    val partNo: String = "",
    val supplier: String = "",
    val uuid: String = ""
) {
    val isEmpty: Boolean
        get() = vin.isEmpty() && mileage.isEmpty() && version.isEmpty()
                && partNo.isEmpty() && supplier.isEmpty() && uuid.isEmpty()
}

/** 扫描到的设备 */
data class DiscoveredDevice(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int
)

/** BLE + 485 帧 + 车辆信息解析 + 手动选设备 */
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
    private val _vehicleInfo = MutableStateFlow(VehicleInfo())
    val vehicleInfo: StateFlow<VehicleInfo> = _vehicleInfo
    private val _reading = MutableStateFlow("")
    val reading: StateFlow<String> = _reading

    /** 扫描到的设备列表（用户自己选） */
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices

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

    /** 扫描所有有名字的设备，列在列表里让用户选 */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) return
        _devices.value = emptyList()
        _state.value = State.SCANNING
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = try { device.name } catch (e: SecurityException) { null } ?: return
                if (name.isBlank()) return
                val list = _devices.value.toMutableList()
                if (list.none { it.device.address == device.address }) {
                    list.add(DiscoveredDevice(device, name, result.rssi))
                    list.sortByDescending { it.rssi }
                    _devices.value = list
                }
            }
        }
        scanCallback = cb
        scanner?.startScan(cb)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback
        if (cb != null) {
            try { scanner?.stopScan(cb) } catch (e: SecurityException) { }
        }
        scanCallback = null
        if (_state.value == State.SCANNING) _state.value = State.IDLE
    }

    /** 用户手动选择设备连接 */
    @SuppressLint("MissingPermission")
    fun connect(scanned: DiscoveredDevice) {
        stopScan()
        _deviceName.value = scanned.name
        _state.value = State.CONNECTING
        connectGatt(scanned.device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect(); gatt?.close(); gatt = null
        writeChar = null
        _state.value = State.IDLE
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.value = State.IDLE; writeChar = null
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    addLog(LogEntry.Dir.SYS, "", "发现服务失败: $status")
                    _state.value = State.IDLE
                    return
                }
                // 优先 FFF0，否则遍历所有服务，按 properties 找 write+notify
                val preferred = g.getService(SERVICE_UUID)
                val services = if (preferred != null) listOf(preferred) else g.services
                for (svc in services) {
                    var n: BluetoothGattCharacteristic? = null
                    var w: BluetoothGattCharacteristic? = null
                    for (ch in svc.characteristics) {
                        val p = ch.properties
                        if (n == null && ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                                          (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)) n = ch
                        if (w == null && ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                          (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)) w = ch
                    }
                    if (n != null && w != null) {
                        g.setCharacteristicNotification(n, true)
                        writeChar = w
                        _state.value = State.CONNECTED
                        addLog(LogEntry.Dir.SYS, "",
                            "服务 ${svc.uuid}\n  通知 ${n.uuid}\n  写入 ${w.uuid}")
                        return
                    }
                }
                addLog(LogEntry.Dir.SYS, "", "未找到可用的 write/notify 特征")
                _state.value = State.IDLE
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                ch.value?.let { feed(it) }
            }
        })
    }

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

    /** 一键读全部车辆信息 */
    @SuppressLint("MissingPermission")
    fun readAllVehicleInfo(addr: Int) {
        _reading.value = "读取中…"
        val items = listOf(
            byteArrayOf(0x27, 0x01),
            byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()),
            byteArrayOf(0x22, 0xF2.toByte(), 0x01),
            byteArrayOf(0x22, 0xF1.toByte(), 0x95.toByte()),
            byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte()),
            byteArrayOf(0x22, 0xF1.toByte(), 0x8A.toByte()),
        )
        val ch = writeChar ?: return
        val g = gatt ?: return
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

    /** 写总里程: 2E F2 01 + 3 字节大端 */
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

    private fun parseUds(p: ByteArray) {
        if (p.size < 3) return
        val sid = p[0].toInt() and 0xFF
        if (sid != 0x62) return
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
