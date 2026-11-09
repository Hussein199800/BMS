package batari.core

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
fun ByteArray.s16(i: Int): Int = u16(i).toShort().toInt()
fun ByteArray.u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { it.isLetterOrDigit() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** CRC16/Modbus: القيمة الابتدائية 0xFFFF، كثير الحدود 0xA001، يُرسل البايت الأدنى أولًا. */
fun crc16Modbus(data: ByteArray, len: Int = data.size): Int {
    var crc = 0xFFFF
    for (i in 0 until len) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
    }
    return crc and 0xFFFF
}

/** مجموع التحقق في بروتوكول JBD الكلاسيكي: 0x10000 ناقص مجموع البايتات. */
fun classicChecksum(data: ByteArray, from: Int, len: Int): Int {
    var sum = 0
    for (i in from until from + len) sum += data[i].toInt() and 0xFF
    return (0x10000 - sum) and 0xFFFF
}
