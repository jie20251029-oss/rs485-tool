package com.cfmoto.rs485

/** 485 上的 ECU */
enum class Ecu(val label: String, val tx: Int, val rx: Int) {
    MCU("MCU 电机控制器", 0x21, 0xA1),
    LABMS("LABMS 低压BMS", 0x22, 0xA2)
}

/** UDS 命令 */
data class UdsCommand(
    val name: String,
    val hex: String,
    val category: Category
) {
    enum class Category(val label: String) {
        INFO("读信息"), DIAG("诊断"), ROUTINE("例程/维修")
    }

    val bytes: ByteArray
        get() = hex.trim().split(" ", ",").filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        val ALL = listOf(
            // 读信息
            UdsCommand("读零件号", "22 F1 87", Category.INFO),
            UdsCommand("读供应商", "22 F1 8A", Category.INFO),
            UdsCommand("读仪表UUID", "22 F1 8D", Category.INFO),
            UdsCommand("读VIN车架号", "22 F1 90", Category.INFO),
            UdsCommand("读软件版本", "22 F1 95", Category.INFO),
            UdsCommand("读总里程", "22 F2 01", Category.INFO),
            UdsCommand("读NFC卡数量", "22 06 03", Category.INFO),
            UdsCommand("读IMEI", "22 07 00", Category.INFO),
            UdsCommand("读功能配置", "22 F0 10", Category.INFO),
            UdsCommand("读配置(0xF101)", "22 F1 01", Category.INFO),
            // 诊断
            UdsCommand("扩展会话", "10 03", Category.DIAG),
            UdsCommand("保持链接", "3E 80", Category.DIAG),
            UdsCommand("安全访问-取种子", "27 01", Category.DIAG),
            UdsCommand("硬复位", "11 01", Category.DIAG),
            UdsCommand("读故障码", "19 02 09", Category.DIAG),
            UdsCommand("清故障码", "14 FF FF FF", Category.DIAG),
            // 例程/维修
            UdsCommand("胎压匹配-前轮", "31 01 53 90 00", Category.ROUTINE),
            UdsCommand("胎压匹配-后轮", "31 01 53 91 00", Category.ROUTINE),
            UdsCommand("胎压匹配-前查询", "31 03 53 90", Category.ROUTINE),
            UdsCommand("胎压匹配-后查询", "31 03 53 91", Category.ROUTINE),
            UdsCommand("NFC学卡", "31 01 52 00", Category.ROUTINE),
            UdsCommand("NFC删卡", "31 01 52 01", Category.ROUTINE),
            UdsCommand("NFC查询", "31 03 52 00", Category.ROUTINE),
            UdsCommand("节点在线检查", "31 01 51 00 05", Category.ROUTINE),
            UdsCommand("在线检查结束", "31 02 51 00", Category.ROUTINE),
            UdsCommand("换件", "31 01 51 01", Category.ROUTINE),
            UdsCommand("换件查询", "31 03 51 01", Category.ROUTINE)
        )

        fun inCategory(c: Category) = ALL.filter { it.category == c }
    }
}

/** 写操作 */
data class WriteAction(
    val name: String,
    val service: String,   // "2E"
    val did: String,       // "F1 90"
    val dataLen: Int,
    val format: Format
) {
    enum class Format { ASCII, BIG_ENDIAN, HEX }

    companion object {
        val ALL = listOf(
            WriteAction("写VIN车架号", "2E", "F1 90", 17, Format.ASCII),
            WriteAction("写总里程", "2E", "F2 01", 3, Format.BIG_ENDIAN),
            WriteAction("写功能配置", "2E", "F0 10", 16, Format.HEX),
            WriteAction("写网络配置", "2E", "F1 01", 8, Format.HEX),
            WriteAction("写SK安全密钥", "2E", "02 03", 16, Format.HEX)
        )
    }
}
