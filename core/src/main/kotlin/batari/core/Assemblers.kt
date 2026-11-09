package batari.core

sealed interface FrameResult {
    /** إطار مكتمل ومتحقق منه. [payload] هو البيانات فقط دون الترويسة ومجموع التحقق. */
    class Frame(val function: Int, val payload: ByteArray, val raw: ByteArray) : FrameResult
    data class Error(val reason: String) : FrameResult
}

/** يجمّع الإشعارات المتجزئة إلى إطارات كاملة. يُستدعى reset قبل كل طلب وبعد كل مهلة. */
interface FrameAssembler {
    fun feed(chunk: ByteArray): List<FrameResult>
    fun reset()
    val bufferedBytes: Int
}

/**
 * إطار سلسلة UP:
 * ‎[العنوان 1][الدالة 1][بداية 2][نهاية 2][طول البيانات 2][بيانات…][CRC16 بايت أدنى أولًا]‎
 */
class UpFrameAssembler(private val expectedAddress: Int? = null) : FrameAssembler {
    private var buf = ByteArray(0)
    override val bufferedBytes: Int get() = buf.size
    override fun reset() { buf = ByteArray(0) }

    override fun feed(chunk: ByteArray): List<FrameResult> {
        buf += chunk
        val out = ArrayList<FrameResult>()
        while (true) {
            // إعادة المزامنة: تجاهل ما قبل بداية إطار محتملة.
            var start = 0
            while (start < buf.size && !plausibleStart(start)) start++
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 8) return out
            val dataLen = buf.u16(6)
            if (dataLen > MAX_DATA) {
                out += FrameResult.Error("طول بيانات غير معقول: $dataLen")
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            val total = 8 + dataLen + 2
            if (buf.size < total) return out
            val raw = buf.copyOfRange(0, total)
            val computed = crc16Modbus(raw, 8 + dataLen)
            val remote = raw.u8(8 + dataLen) or (raw.u8(9 + dataLen) shl 8)
            if (computed != remote) {
                out += FrameResult.Error("CRC غير مطابق: المحسوب %04X والمستلم %04X".format(computed, remote))
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            out += FrameResult.Frame(raw.u8(1), raw.copyOfRange(8, 8 + dataLen), raw)
            buf = buf.copyOfRange(total, buf.size)
        }
    }

    private fun plausibleStart(i: Int): Boolean {
        val addr = buf.u8(i)
        if (expectedAddress != null && addr != expectedAddress) return false
        if (addr > 247) return false
        return i + 1 >= buf.size || buf.u8(i + 1) == ReadRequests.UP_FC_READ
    }

    companion object { const val MAX_DATA = 512 }
}

/**
 * إطار JBD الكلاسيكي:
 * ‎DD [الأمر][الحالة][الطول][بيانات…][تحقق 2] 77‎ — مجموع التحقق على (الحالة، الطول، البيانات).
 */
class ClassicFrameAssembler : FrameAssembler {
    private var buf = ByteArray(0)
    override val bufferedBytes: Int get() = buf.size
    override fun reset() { buf = ByteArray(0) }

    override fun feed(chunk: ByteArray): List<FrameResult> {
        buf += chunk
        val out = ArrayList<FrameResult>()
        while (true) {
            val start = buf.indexOfFirst { it == 0xDD.toByte() }
            if (start < 0) { buf = ByteArray(0); return out }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 4) return out
            val len = buf.u8(3)
            val total = 4 + len + 3
            if (buf.size < total) return out
            val raw = buf.copyOfRange(0, total)
            if (raw.u8(total - 1) != 0x77) {
                out += FrameResult.Error("نهاية إطار غير صحيحة")
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            val computed = classicChecksum(raw, 2, len + 2)
            val remote = raw.u16(4 + len)
            if (computed != remote) {
                out += FrameResult.Error("مجموع التحقق غير مطابق: المحسوب %04X والمستلم %04X".format(computed, remote))
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            buf = buf.copyOfRange(total, buf.size)
            if (raw.u8(2) != 0x00) {
                out += FrameResult.Error("الجهاز ردّ بحالة خطأ 0x%02X للأمر 0x%02X".format(raw.u8(2), raw.u8(1)))
                continue
            }
            out += FrameResult.Frame(raw.u8(1), raw.copyOfRange(4, 4 + len), raw)
        }
    }
}
