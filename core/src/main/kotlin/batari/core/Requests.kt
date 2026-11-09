package batari.core

/**
 * طلب قراءة فقط. لا يمكن إنشاؤه إلا من [ReadRequests]، وطبقة البلوتوث لا تقبل غيره.
 * لا يوجد في التطبيق أي كود يبني أمر كتابة/تعديل (0x79 أو DD 5A).
 */
class ReadRequest internal constructor(val bytes: ByteArray, val label: String)

object ReadRequests {
    const val UP_FC_READ = 0x78
    const val UP_PACK_STATUS_START = 0x1000
    const val UP_PACK_STATUS_END = 0x10A0
    const val CLASSIC_BASIC_INFO = 0x03
    const val CLASSIC_CELL_VOLTAGES = 0x04

    /** قراءة حالة المجموعة في سلسلة UP: ‎addr 78 10 00 10 A0 00 00 crcL crcH‎. */
    fun upPackStatus(address: Int): ReadRequest {
        require(address in 0..247) { "address out of range" }
        val f = ByteArray(10)
        f[0] = address.toByte()
        f[1] = UP_FC_READ.toByte()
        f[2] = (UP_PACK_STATUS_START shr 8).toByte(); f[3] = UP_PACK_STATUS_START.toByte()
        f[4] = (UP_PACK_STATUS_END shr 8).toByte(); f[5] = UP_PACK_STATUS_END.toByte()
        f[6] = 0; f[7] = 0
        val crc = crc16Modbus(f, 8)
        f[8] = crc.toByte(); f[9] = (crc shr 8).toByte()
        return checked(f, "UP: قراءة حالة المجموعة (0x78 @0x1000)")
    }

    fun classicBasicInfo(): ReadRequest = classic(CLASSIC_BASIC_INFO, "كلاسيكي: المعلومات الأساسية (0x03)")
    fun classicCellVoltages(): ReadRequest = classic(CLASSIC_CELL_VOLTAGES, "كلاسيكي: جهود الخلايا (0x04)")

    private fun classic(cmd: Int, label: String): ReadRequest {
        val f = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), cmd.toByte(), 0, 0, 0, 0x77)
        val chk = classicChecksum(f, 2, 2)
        f[4] = (chk shr 8).toByte(); f[5] = chk.toByte()
        return checked(f, label)
    }

    private fun checked(bytes: ByteArray, label: String): ReadRequest {
        check(isAllowedRead(bytes)) { "طلب غير مسموح" }
        return ReadRequest(bytes, label)
    }

    /**
     * حارس مستقل (دفاع ثانٍ): يتحقق من أن البايتات بالضبط أحد طلبات القراءة المسموحة.
     * يُستدعى قبل كل كتابة على خاصية البلوتوث.
     */
    fun isAllowedRead(b: ByteArray): Boolean {
        // UP: دالة القراءة 0x78 فقط، سجل حالة المجموعة فقط، بلا حمولة.
        if (b.size == 10 && b.u8(1) == UP_FC_READ) {
            val crc = crc16Modbus(b, 8)
            return b.u16(2) == UP_PACK_STATUS_START && b.u16(4) == UP_PACK_STATUS_END &&
                b.u16(6) == 0 && b.u8(8) == (crc and 0xFF) && b.u8(9) == (crc shr 8)
        }
        // كلاسيكي: DD A5 (قراءة) فقط، الأمران 03 و04 فقط، بلا حمولة.
        if (b.size == 7 && b.u8(0) == 0xDD && b.u8(1) == 0xA5 && b.u8(6) == 0x77) {
            val cmd = b.u8(2)
            return (cmd == CLASSIC_BASIC_INFO || cmd == CLASSIC_CELL_VOLTAGES) && b.u8(3) == 0 &&
                b.u16(4) == classicChecksum(b, 2, 2)
        }
        return false
    }
}
