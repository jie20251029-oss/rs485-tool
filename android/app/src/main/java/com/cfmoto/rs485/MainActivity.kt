package com.cfmoto.rs485

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
    val logs by ble.logs.collectAsState()
    val liveFrames by ble.liveFrames.collectAsState()

    var ecu by remember { mutableStateOf(Ecu.MCU) }
    var category by remember { mutableStateOf(UdsCommand.Category.INFO) }
    var customHex by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) } // 0=命令 1=写操作 2=数据流
    var pendingWrite by remember { mutableStateOf<WriteAction?>(null) }
    var writeInput by remember { mutableStateOf("") }
    var writeMsg by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // 权限
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
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp)
        ) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "485 诊断",
                        fontSize = 28.sp, fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        "CFMOTO AE4/AE5 · I6328A 透传",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f)
                    )
                }
                StatePill(state)
            }
            Spacer(Modifier.height(12.dp))

            // 连接卡片
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.35f), Accent2.copy(alpha = 0.25f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.BluetoothSearching, null, tint = Accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (deviceName.isBlank()) "I6328A 透传模块" else deviceName,
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                        )
                        Text(
                            when (state) {
                                BleManager.State.CONNECTED -> "已建立 485 通道"
                                BleManager.State.SCANNING -> "正在搜索设备…"
                                BleManager.State.CONNECTING -> "连接中…"
                                else -> "点击连接开始"
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
                            containerColor = if (state == BleManager.State.CONNECTED)
                                Color.White.copy(alpha = 0.12f) else Accent
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            when (state) {
                                BleManager.State.CONNECTED -> "断开"
                                BleManager.State.SCANNING -> "取消"
                                else -> "连接"
                            },
                            color = if (state == BleManager.State.CONNECTED) Color.White else BgTop,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ECU 选择
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ecu.entries.forEach { e ->
                    val sel = ecu == e
                    Surface(
                        onClick = { ecu = e },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (sel) Accent else Color.White.copy(alpha = 0.05f)
                    ) {
                        Column(
                            Modifier.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                e.label.substringBefore(" "),
                                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                color = if (sel) BgTop else Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                "tx 0x%02X".format(e.tx),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = if (sel) BgTop.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 主 Tab
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(4.dp)
            ) {
                listOf("命令", "写操作", "数据流").forEachIndexed { i, label ->
                    val sel = tab == i
                    Surface(
                        onClick = { tab = i },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        color = if (sel) Color.White.copy(alpha = 0.12f) else Color.Transparent
                    ) {
                        Text(
                            label, modifier = Modifier.padding(vertical = 9.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (sel) Color.White else Color.White.copy(alpha = 0.45f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // 内容区
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> CommandTab(ble, ecu, category, { category = it })
                    1 -> WriteTab(ble, ecu, pendingWrite) { act, input ->
                        pendingWrite = act; writeInput = input
                    }
                    2 -> DataStreamTab(liveFrames)
                }
            }

            Spacer(Modifier.height(10.dp))

            // 自定义命令
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customHex,
                    onValueChange = { customHex = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("自定义 HEX  如 22 F1 90", fontSize = 13.sp, color = Color.White.copy(alpha = 0.3f)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                    )
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(
                    onClick = {
                        val bytes = parseHex(customHex)
                        if (bytes.isNotEmpty()) ble.sendUds(bytes, ecu.tx, "自定义")
                    },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent)
                ) {
                    Icon(Icons.Default.Send, null, tint = BgTop)
                }
            }
            Spacer(Modifier.height(10.dp))

            // 日志
            LogTerminal(logs) { ble.clearLogs() }
            Spacer(Modifier.height(12.dp))
        }

        // 写操作弹窗
        pendingWrite?.let { act ->
            AlertDialog(
                onDismissRequest = { pendingWrite = null },
                title = { Text(act.name, color = Color.White) },
                text = {
                    Column {
                        Text(
                            when (act.format) {
                                WriteAction.Format.ASCII -> "输入 ${act.dataLen} 位 ASCII（如 VIN）"
                                WriteAction.Format.BIG_ENDIAN -> "输入数值（自动转 ${act.dataLen} 字节大端）"
                                WriteAction.Format.HEX -> "输入 ${act.dataLen} 字节 HEX"
                            },
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = writeInput, onValueChange = { writeInput = it },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Color.White)
                        )
                        if (writeMsg.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(writeMsg, fontSize = 12.sp, color = Accent)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val data = buildWriteData(act, writeInput)
                        if (data == null) { writeMsg = "输入格式不对"; return@TextButton }
                        val did = parseHex(act.did)
                        val svc = act.service.toInt(16)
                        val cmd = byteArrayOf(svc.toByte()) + did + data
                        ble.sendUds(cmd, ecu.tx, "${ecu.label} · ${act.name}")
                        writeMsg = ""
                        pendingWrite = null
                    }) { Text("发送", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingWrite = null; writeMsg = "" }) {
                        Text("取消", color = Color.White.copy(alpha = 0.5f))
                    }
                },
                containerColor = Color(0xFF1A1F35)
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
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            when (state) {
                BleManager.State.CONNECTED -> "已连接"
                BleManager.State.SCANNING -> "扫描中…"
                BleManager.State.CONNECTING -> "连接中…"
                else -> "未连接"
            },
            fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun CommandTab(
    ble: BleManager, ecu: Ecu,
    category: UdsCommand.Category,
    onCategory: (UdsCommand.Category) -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(4.dp)
        ) {
            UdsCommand.Category.entries.forEach { c ->
                val sel = category == c
                Surface(
                    onClick = { onCategory(c) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(9.dp),
                    color = if (sel) Color.White.copy(alpha = 0.12f) else Color.Transparent
                ) {
                    Text(
                        c.label, modifier = Modifier.padding(vertical = 7.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (sel) Color.White else Color.White.copy(alpha = 0.45f)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val cmds = UdsCommand.inCategory(category)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cmds.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { cmd ->
                        CmdCard(cmd, Modifier.weight(1f)) {
                            ble.sendUds(cmd.bytes, ecu.tx, "${ecu.label} · ${cmd.name}")
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CmdCard(cmd: UdsCommand, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.18f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(cmd.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(cmd.hex, color = Accent.copy(alpha = 0.85f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
private fun WriteTab(
    ble: BleManager, ecu: Ecu,
    pending: WriteAction?,
    onPick: (WriteAction, String) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(WriteAction.ALL) { act ->
            Surface(
                onClick = { onPick(act, "") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.05f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Accent2.copy(alpha = 0.2f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(act.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            "2E ${act.did} + ${act.dataLen}字节",
                            color = Accent2.copy(alpha = 0.9f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@Composable
private fun DataStreamTab(frames: Map<Int, ByteArray>) {
    if (frames.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("等待 485 数据…", color = Color.White.copy(alpha = 0.25f), fontSize = 13.sp)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(frames.entries.toList()) { (addr, data) ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.3f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("addr 0x%02X".format(addr), color = RxColor, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(data.toHex(), color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun LogTerminal(logs: List<BleManager.LogEntry>, onClear: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.List, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("日志", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
        }
        Surface(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.35f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("等待收发数据…", color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.padding(10.dp)) {
                    items(logs) { log ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(
                                when (log.dir) {
                                    BleManager.LogEntry.Dir.TX -> "TX"
                                    BleManager.LogEntry.Dir.RX -> "RX"
                                    else -> "SYS"
                                },
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                color = when (log.dir) {
                                    BleManager.LogEntry.Dir.TX -> TxColor
                                    BleManager.LogEntry.Dir.RX -> RxColor
                                    else -> Color.Gray
                                },
                                modifier = Modifier.width(28.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                if (log.hex.isNotEmpty())
                                    Text(log.hex, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                        color = if (log.dir == BleManager.LogEntry.Dir.TX)
                                            TxColor.copy(alpha = 0.9f) else RxColor.copy(alpha = 0.9f))
                                if (log.note.isNotEmpty())
                                    Text(log.note, fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- 工具 ----
private fun parseHex(s: String): ByteArray {
    val clean = s.replace(" ", "").replace(",", "").replace("0x", "", ignoreCase = true)
    if (clean.isEmpty() || clean.length % 2 != 0) return ByteArray(0)
    return try {
        ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    } catch (e: Exception) { ByteArray(0) }
}

private fun buildWriteData(act: WriteAction, input: String): ByteArray? {
    return when (act.format) {
        WriteAction.Format.ASCII -> {
            val b = input.toByteArray(Charsets.US_ASCII)
            if (b.size != act.dataLen) null else b
        }
        WriteAction.Format.BIG_ENDIAN -> {
            val v = input.trim().toLongOrNull() ?: return null
            val out = ByteArray(act.dataLen)
            for (i in 0 until act.dataLen) out[act.dataLen - 1 - i] = ((v shr (8 * i)) and 0xFF).toByte()
            out
        }
        WriteAction.Format.HEX -> {
            val b = parseHex(input)
            if (b.size != act.dataLen) null else b
        }
    }
}
