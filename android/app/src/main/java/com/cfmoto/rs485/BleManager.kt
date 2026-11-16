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

/** BLE + 485 帧 */
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val NOTIFY_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED }

    data class LogEntry(
        val time: Long,
        val dir: Dir,
        val hex: String,
        val note: String
    ) { enum class Dir { TX, RX, SYS } }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _liveFrames = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val liveFrames: StateFlow<Map<Int, ByteArray>> = _liveFrames

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

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.value = State.IDLE
                    writeChar = null
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
        out.add(0x43); out.add(0x46)
        out.add(addr.toByte())
        out.add(data.size.toByte())
        out.addAll(data.toList())
        var sum = addr + data.size
        data.forEach { sum += it.toInt() and 0xFF }
        out.add((sum and 0xFF).toByte())
        out.add(0x45); out.add(0x44)
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    fun sendUds(data: ByteArray, addr: Int, note: String = "") {
        val frame = buildFrame(addr, data)
        val ch = writeChar
        if (ch == null || gatt == null) {
            addLog(LogEntry.Dir.SYS, "", "未连接，无法发送")
            return
        }
        ch.value = frame
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(ch)
        addLog(LogEntry.Dir.TX, frame.toHex(), note)
    }

    @SuppressLint("MissingPermission")
    fun sendRaw(frame: ByteArray, note: String = "") {
        val ch = writeChar ?: return
        ch.value = frame
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(ch)
        addLog(LogEntry.Dir.TX, frame.toHex(), note)
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
            addLog(LogEntry.Dir.RX, frame.toHex(),
                "addr=0x%02X  数据=%s".format(addr, payload.toHex()))
            val m = _liveFrames.value.toMutableMap()
            m[addr] = payload
            _liveFrames.value = m
        }
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
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
