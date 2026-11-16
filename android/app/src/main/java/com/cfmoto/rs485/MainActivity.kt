package com.cfmoto.rs485

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RS485App(this) }
    }
}

private val BgTop = Color(0xFF0A0F1E)
private val BgBottom = Color(0xFF171B33)
private val Accent = Color(0xFF00D9C0)
private val Accent2 = Color(0xFF8B72FF)
private val TxColor = Color(0xFF5ABFFF)
private val RxColor = Color(0xFF5AF2A0)

@Composable
fun RS485App(activity: ComponentActivity) {
    val ble = remember { BleManager(activity) }
    val state by ble.state.collectAsState()
    val deviceName by ble.deviceName.collectAsState()
    val info by ble.vehicleInfo.collectAsState()
    val reading by ble.reading.collectAsState()
    val logs by ble.logs.collectAsState()
    val devices by ble.devices.collectAsState()

    var showAdvanced by remember { mutableStateOf(false) }
    var writeVinText by remember { mutableStateOf("") }
    var writeKmText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permLauncher.launch(perms)
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp)
        ) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("极核 485 诊断", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("读车架号 / 里程 / 写 VIN / 写里程", fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f))
                }
                StatePill(state)
            }
            Spacer(Modifier.height(12.dp))

            // 连接卡片
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.35f), Accent2.copy(alpha = 0.25f)))),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.BluetoothSearching, null, tint = Accent, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (deviceName.isBlank()) "I6328A 模块" else deviceName,
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            when (state) {
                                BleManager.State.CONNECTED -> "已连接"
                                BleManager.State.SCANNING -> "搜索中…"
                                BleManager.State.CONNECTING -> "连接中…"
                                else -> "点击连接"
                            },
                            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = {
                            when (state) {
                                BleManager.State.CONNECTED -> ble.disconnect()
                                BleManager.State.SCANNING -> ble.stopScan()
                                else -> ble.startScan()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state == BleManager.State.CONNECTED) Color.White.copy(alpha = 0.12f) else Accent
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            when (state) {
                                BleManager.State.CONNECTED -> "断开"
                                BleManager.State.SCANNING -> "取消"
                                else -> "扫描设备"
                            },
                            color = if (state == BleManager.State.CONNECTED) Color.White else BgTop,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // 设备列表（手动选择）
            if (state == BleManager.State.SCANNING || devices.isNotEmpty()) {
                SectionTitle(if (state == BleManager.State.SCANNING) "搜索到的设备" else "设备列表",
                    Icons.Default.BluetoothSearching)
                GlassCard {
                    Column {
                        if (devices.isEmpty()) {
                            Text(
                                if (state == BleManager.State.SCANNING) "正在搜索…" else "未发现设备",
                                color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp
                            )
                        } else {
                            devices.forEach { dev ->
                                Surface(
                                    onClick = { ble.connect(dev) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.05f),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(34.dp).clip(CircleShape)
                                                .background(Accent.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Bluetooth, null, tint = Accent, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(dev.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text("信号 ${dev.rssi} dBm", color = Color.White.copy(alpha = 0.35f),
                                                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        Text("连接", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (state == BleManager.State.CONNECTED) {
                // ===== 车辆信息 =====
                SectionTitle("车辆信息", Icons.Default.Info)
                GlassCard {
                    Column {
                        InfoRow("车架号 VIN", info.vin, Icons.Default.ConfirmationNumber)
                        Divider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.06f))
                        InfoRow("总里程", info.mileage, Icons.Default.Speed)
                        Divider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.06f))
                        InfoRow("软件版本", info.version, Icons.Default.SystemUpdate)
                        Divider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.06f))
                        InfoRow("零件号", info.partNo, Icons.Default.QrCode)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { ble.readAllVehicleInfo(0x21) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = reading.isEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent2.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, tint = Accent2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (reading.isEmpty()) "一键读取车辆信息" else reading,
                                color = Color.White, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ===== 写 VIN =====
                SectionTitle("修改车架号", Icons.Default.Edit)
                GlassCard {
                    Column {
                        Text("输入 17 位新车架号（VIN）", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = writeVinText,
                            onValueChange = { if (it.length <= 17) writeVinText = it.uppercase() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("17位VIN", color = Color.White.copy(alpha = 0.25f), fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent.copy(alpha = 0.6f),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                            ),
                            trailingIcon = {
                                Text("${writeVinText.length}/17", fontSize = 11.sp,
                                    color = if (writeVinText.length == 17) RxColor else Color.White.copy(alpha = 0.3f))
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { confirm = ConfirmAction.WriteVin(writeVinText) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = writeVinText.length == 17,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Save, null, tint = BgTop, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("写入车架号", color = BgTop, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ===== 写里程 =====
                SectionTitle("修改总里程", Icons.Default.Speed)
                GlassCard {
                    Column {
                        Text("输入目标公里数（写入后立即生效）", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = writeKmText,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 7) writeKmText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("例如 5000", color = Color.White.copy(alpha = 0.25f), fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent2.copy(alpha = 0.6f),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                            ),
                            trailingIcon = {
                                if (writeKmText.isNotEmpty())
                                    Text("km", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                writeKmText.toLongOrNull()?.let { confirm = ConfirmAction.WriteKm(it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = writeKmText.toLongOrNull()?.let { it in 0..16777215 } == true,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent2),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Save, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("写入总里程", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ===== 高级（折叠） =====
                Surface(
                    onClick = { showAdvanced = !showAdvanced },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("高级功能（其他命令 / 日志）", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, tint = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
                AnimatedVisibility(visible = showAdvanced) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        AdvancedCommands(ble)
                        Spacer(Modifier.height(10.dp))
                        LogTerminal(logs) { ble.clearLogs() }
                    }
                }
            } else {
                Spacer(Modifier.height(40.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.BluetoothDisabled, null, tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("请先连接 I6328A 模块", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                    }
                }
            }
        }

        // 确认弹窗
        confirm?.let { act ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(if (act is ConfirmAction.WriteVin) "确认写车架号" else "确认写总里程", color = Color.White) },
                text = {
                    Text(
                        when (act) {
                            is ConfirmAction.WriteVin -> "即将把车架号改为：\n\n${act.vin}\n\n此操作不可撤销！"
                            is ConfirmAction.WriteKm -> "即将把总里程改为：\n\n${act.km} km\n\n此操作不可撤销！"
                        },
                        color = Color.White.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        when (act) {
                            is ConfirmAction.WriteVin -> ble.writeVin(act.vin, 0x21)
                            is ConfirmAction.WriteKm -> ble.writeMileage(act.km, 0x21)
                        }
                        confirm = null
                    }) { Text("确认写入", color = Accent, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = null }) { Text("取消", color = Color.White.copy(alpha = 0.5f)) }
                },
                containerColor = Color(0xFF1A1F35)
            )
        }
    }
}

sealed class ConfirmAction {
    data class WriteVin(val vin: String) : ConfirmAction()
    data class WriteKm(val km: Long) : ConfirmAction()
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(Modifier.padding(bottom = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
            Spacer(Modifier.height(2.dp))
            Text(
                value.ifBlank { "—" },
                fontSize = if (value.isBlank()) 14.sp else 16.sp,
                fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                fontFamily = if (label.contains("VIN") || label.contains("零件")) FontFamily.Monospace else FontFamily.Default,
                color = if (value.isBlank()) Color.White.copy(alpha = 0.25f) else Color.White
            )
        }
    }
}

@Composable
private fun StatePill(state: BleManager.State) {
    val color = when (state) {
        BleManager.State.CONNECTED -> Color(0xFF2ECC71)
        BleManager.State.SCANNING, BleManager.State.CONNECTING -> Color(0xFFF5B942)
        else -> Color.Gray
    }
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            when (state) {
                BleManager.State.CONNECTED -> "已连接"
                BleManager.State.SCANNING -> "扫描中"
                BleManager.State.CONNECTING -> "连接中"
                else -> "未连接"
            },
            fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
    ) { Box(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun AdvancedCommands(ble: BleManager) {
    var ecu by remember { mutableStateOf(Ecu.MCU) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ecu.entries.forEach { e ->
                val sel = ecu == e
                Surface(
                    onClick = { ecu = e },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = if (sel) Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f)
                ) {
                    Text(
                        e.label.substringBefore(" "),
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (sel) Accent else Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        UdsCommand.ALL.forEach { cmd ->
            Surface(
                onClick = { ble.sendUds(cmd.bytes, ecu.tx, cmd.name) },
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.04f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(cmd.name, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(cmd.hex, color = Accent.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun LogTerminal(logs: List<BleManager.LogEntry>, onClear: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("日志", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
        Surface(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.35f)
        ) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无数据", color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    items(logs) { log ->
                        Row(Modifier.padding(vertical = 1.dp)) {
                            Text(
                                when (log.dir) {
                                    BleManager.LogEntry.Dir.TX -> "TX"
                                    BleManager.LogEntry.Dir.RX -> "RX"
                                    else -> "SYS"
                                },
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                color = when (log.dir) {
                                    BleManager.LogEntry.Dir.TX -> TxColor
                                    BleManager.LogEntry.Dir.RX -> RxColor
                                    else -> Color.Gray
                                },
                                modifier = Modifier.width(26.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                if (log.hex.isNotEmpty())
                                    Text(log.hex, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                        color = if (log.dir == BleManager.LogEntry.Dir.TX) TxColor else RxColor)
                                if (log.note.isNotEmpty())
                                    Text(log.note, fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }
    }
}
