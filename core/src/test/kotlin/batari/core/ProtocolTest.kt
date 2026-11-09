package batari.core

import org.junit.Assert.*
import org.junit.Test

/** إطارات حقيقية ملتقطة من أجهزة (مصدرها اختبارات syssi/esphome-jbd-bms) — ليست من بطارية المستخدم. */
object Fixtures {
    // JBD-UP16S020، العنوان 1، 16 خلية، 100 أمبير·ساعة.
    val UP_STATUS = hexToBytes(
        "01 78 10 00 10 a0 00 9e 14 c5 00 00 00 04 93 e0 17 cf 17 cf 27 10 27 10 02 c6 02 d6 00 00 00 64 " +
            "00 00 00 00 00 00 00 00 00 03 00 04 00 0c 00 03 0d 03 00 08 0c f5 0c fb 00 04 02 c8 00 02 02 c0 " +
            "02 c4 02 48 07 d0 01 c0 07 d0 00 10 0c fe 0c f6 0d 03 0c f6 0c f8 0c fb 0d 00 0c f5 0c fc 0c fb " +
            "0c fe 0c fb 0c fc 0c fd 0c fd 0c fa 00 04 02 c5 02 c0 02 c6 02 c8 00 00 00 00 0c 04 4a 42 44 34 " +
            "38 31 30 30 30 30 30 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 00 03 5a a6 " +
            "00 00 00 00 00 00 6a 2e"
    )
    // JBD-SP04S034، 4 خلايا.
    val CLASSIC_BASIC = hexToBytes(
        "DD 03 00 1D 06 18 00 00 01 F2 01 F4 00 00 2C 7C 00 00 00 00 00 00 80 64 03 04 03 0B 8B 0B 8A 0B 84 FA 8D 77"
    )
    val CLASSIC_CELLS = hexToBytes("DD 04 00 08 0F 45 0F 3D 0F 37 0F 3D FE C6 77")

    /** يبني إطار UP صحيح الـCRC من حمولة (للاختبارات فقط). */
    fun upFrame(payload: ByteArray, address: Int = 1): ByteArray {
        val head = byteArrayOf(address.toByte(), 0x78, 0x10, 0x00, 0x10, 0xA0.toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
        val body = head + payload
        val crc = crc16Modbus(body)
        return body + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }

    fun payloadOf(frame: ByteArray) = frame.copyOfRange(8, frame.size - 2)
}

class RequestsTest {
    @Test fun upRequestMatchesDocumentedBytes() {
        // من وصف PhracturedBlue: 01 78 10 00 10 a0 00 00 7f b2
        assertEquals("01 78 10 00 10 A0 00 00 7F B2", ReadRequests.upPackStatus(1).bytes.toHex())
    }

    @Test fun classicRequestsMatchDocumentedBytes() {
        assertEquals("DD A5 03 00 FF FD 77", ReadRequests.classicBasicInfo().bytes.toHex())
        assertEquals("DD A5 04 00 FF FC 77", ReadRequests.classicCellVoltages().bytes.toHex())
    }

    @Test fun guardRejectsEveryWriteOrUnknownFrame() {
        fun up(fc: Int, start: Int, end: Int, data: ByteArray = ByteArray(0)): ByteArray {
            val f = byteArrayOf(1, fc.toByte(), (start shr 8).toByte(), start.toByte(), (end shr 8).toByte(), end.toByte(),
                0, data.size.toByte()) + data
            val crc = crc16Modbus(f)
            return f + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        }
        // أمر التحكم بمفاتيح MOS (0x79 @0x2902) — يجب أن يُرفض.
        assertFalse(ReadRequests.isAllowedRead(up(0x79, 0x2902, 0x2904, hexToBytes("11 4A 42 44 00 03"))))
        // كتابة بلا حمولة، إعادة ضبط المصنع، إعادة التشغيل.
        assertFalse(ReadRequests.isAllowedRead(up(0x79, 0x1000, 0x10A0)))
        assertFalse(ReadRequests.isAllowedRead(up(0x79, 0x290E, 0x2910)))
        // قراءة سجلات غير حالة المجموعة (مثل الإعدادات) غير مدرجة في القائمة المسموحة.
        assertFalse(ReadRequests.isAllowedRead(up(0x78, 0x1C00, 0x1CA0)))
        // كلاسيكي: كتابة (5A)، وأوامر قراءة غير مدرجة، ووضع المصنع.
        assertFalse(ReadRequests.isAllowedRead(hexToBytes("DD 5A E1 02 00 02 FF 1B 77")))
        assertFalse(ReadRequests.isAllowedRead(hexToBytes("DD 5A 00 02 56 78 FF 30 77")))
        assertFalse(ReadRequests.isAllowedRead(hexToBytes("DD A5 05 00 FF FB 77")))
        // إطار المصادقة FF AA.
        assertFalse(ReadRequests.isAllowedRead(hexToBytes("FF AA 15 06 30 30 30 30 30 30 3B")))
        // CRC خاطئ.
        assertFalse(ReadRequests.isAllowedRead(hexToBytes("01 78 10 00 10 A0 00 00 7F B3")))
        assertFalse(ReadRequests.isAllowedRead(ByteArray(0)))
        // والمسموح يمرّ.
        assertTrue(ReadRequests.isAllowedRead(ReadRequests.upPackStatus(2).bytes))
        assertTrue(ReadRequests.isAllowedRead(ReadRequests.classicBasicInfo().bytes))
    }
}

class ChecksumTest {
    @Test fun crc16OfRealFrame() {
        val f = Fixtures.UP_STATUS
        assertEquals(0x2E6A, crc16Modbus(f, f.size - 2))
    }

    @Test fun classicChecksumOfRealFrames() {
        val b = Fixtures.CLASSIC_BASIC
        assertEquals(0xFA8D, classicChecksum(b, 2, b.u8(3) + 2))
        val c = Fixtures.CLASSIC_CELLS
        assertEquals(0xFEC6, classicChecksum(c, 2, c.u8(3) + 2))
    }
}

class UpAssemblerTest {
    private fun frames(r: List<FrameResult>) = r.filterIsInstance<FrameResult.Frame>()

    @Test fun wholeFrameAtOnce() {
        val r = UpFrameAssembler(1).feed(Fixtures.UP_STATUS)
        assertEquals(1, frames(r).size)
        assertEquals(158, frames(r)[0].payload.size)
    }

    @Test fun fragmentedIn20ByteNotifications() {
        val a = UpFrameAssembler(1)
        val chunks = Fixtures.UP_STATUS.toList().chunked(20).map { it.toByteArray() }
        val results = chunks.flatMap { a.feed(it) }
        assertEquals(9, chunks.size)
        assertEquals(1, frames(results).size)
        assertTrue(results.none { it is FrameResult.Error })
        assertEquals(0, a.bufferedBytes)
    }

    @Test fun byteByByte() {
        val a = UpFrameAssembler(1)
        val results = Fixtures.UP_STATUS.flatMap { a.feed(byteArrayOf(it)) }
        assertEquals(1, frames(results).size)
    }

    @Test fun incompleteFrameYieldsNothingUntilResetOrCompletion() {
        val a = UpFrameAssembler(1)
        assertTrue(a.feed(Fixtures.UP_STATUS.copyOfRange(0, 100)).isEmpty())
        assertEquals(100, a.bufferedBytes)
        a.reset() // انقطاع/مهلة: نرمي الجزء الناقص
        assertEquals(0, a.bufferedBytes)
        assertEquals(1, frames(a.feed(Fixtures.UP_STATUS)).size)
    }

    @Test fun corruptedByteFailsCrcAndIsNotDelivered() {
        val bad = Fixtures.UP_STATUS.copyOf().also { it[20] = (it[20] + 1).toByte() }
        val r = UpFrameAssembler(1).feed(bad)
        assertTrue(frames(r).isEmpty())
        assertTrue(r.any { it is FrameResult.Error && it.reason.contains("CRC") })
    }

    @Test fun truncatedFrameIsNeverDelivered() {
        val r = UpFrameAssembler(1).feed(Fixtures.UP_STATUS.copyOfRange(0, Fixtures.UP_STATUS.size - 1))
        assertTrue(frames(r).isEmpty())
    }

    @Test fun leadingGarbageIsSkipped() {
        val r = UpFrameAssembler(1).feed(hexToBytes("FF 00 AA") + Fixtures.UP_STATUS)
        assertEquals(1, frames(r).size)
    }

    @Test fun twoFramesBackToBack() {
        val r = UpFrameAssembler(1).feed(Fixtures.UP_STATUS + Fixtures.UP_STATUS)
        assertEquals(2, frames(r).size)
    }

    @Test fun frameFromOtherAddressIgnored() {
        val other = Fixtures.upFrame(Fixtures.payloadOf(Fixtures.UP_STATUS), address = 2)
        assertTrue(frames(UpFrameAssembler(1).feed(other)).isEmpty())
        assertEquals(1, frames(UpFrameAssembler(2).feed(other)).size)
    }

    @Test fun implausibleLengthRejected() {
        val r = UpFrameAssembler(1).feed(hexToBytes("01 78 10 00 10 A0 FF FF 00 00"))
        assertTrue(frames(r).isEmpty())
    }
}

class UpParserTest {
    private val snap = UpPackStatusParser.parse(Fixtures.payloadOf(Fixtures.UP_STATUS), 1000)

    @Test fun decodesRealCapture() {
        assertEquals(53.17, snap.packVoltage!!, 1e-9)
        assertEquals(0.0, snap.currentA!!, 1e-9)
        assertEquals(60.95, snap.socPercent!!, 1e-9)
        assertEquals(60.95, snap.remainingAh!!, 1e-9)
        assertEquals(100.0, snap.fullAh!!, 1e-9)
        assertEquals(100.0, snap.ratedAh!!, 1e-9)
        assertEquals(21.0, snap.mosTempC!!, 1e-9)
        assertEquals(22.6, snap.ambientTempC!!, 1e-9)
        assertEquals(BmsOperation.IDLE, snap.bmsOperation)
        assertEquals(100, snap.sohPercent)
        assertEquals(12, snap.cycles)
        assertEquals(true, snap.chargeMosOn)
        assertEquals(true, snap.dischargeMosOn)
        assertEquals(16, snap.cellCount)
        assertEquals(3326, snap.cellMilliVolts!![0])
        assertEquals(3331, snap.maxCellMv)
        assertEquals(3317, snap.minCellMv)
        assertEquals(14, snap.cellDeltaMv)
        assertEquals(listOf(20.9, 20.4, 21.0, 21.2), snap.cellTempsC)
        assertEquals("JBD48100000", snap.model)
        assertEquals("12.4", snap.firmware)
        assertEquals(2, snap.parallelCount)
        assertEquals(emptySet<Int>(), snap.balancingCells)
        assertTrue(snap.protections!!.isEmpty())
        assertTrue(snap.alarms!!.isEmpty())
        assertFalse(snap.hasBmsWarning)
        assertEquals(58.4, snap.chargeVoltageLimitV!!, 1e-9)
    }

    /** قيم لقطة شاشة المستخدم: 52.52 فولت، ‎-58.44 أمبير، 171.75 أمبير·ساعة. */
    private fun screenshotPayload(): ByteArray {
        val p = Fixtures.payloadOf(Fixtures.UP_STATUS).copyOf()
        fun put16(i: Int, v: Int) { p[i] = (v shr 8).toByte(); p[i + 1] = v.toByte() }
        put16(0, 5252)
        val cur = 300_000 - 5844
        put16(4, cur shr 16); put16(6, cur and 0xFFFF)
        put16(8, 8588); put16(10, 17175); put16(12, 20000); put16(14, 20000)
        put16(16, 869); put16(20, 2); put16(36, 78)
        return p
    }

    @Test fun signedCurrentDischargeIsNegative() {
        val s = UpPackStatusParser.parse(screenshotPayload(), 0)
        assertEquals(-58.44, s.currentA!!, 1e-9)
        assertEquals(BmsOperation.DISCHARGING, s.bmsOperation)
        assertEquals(FlowDirection.DISCHARGING, Estimates.direction(s.currentA))
        assertEquals(52.52, s.packVoltage!!, 1e-9)
        assertEquals(171.75, s.remainingAh!!, 1e-9)
        assertEquals(36.9, s.mosTempC!!, 1e-9)
        assertEquals(78, s.cycles)
        assertEquals(-3069.27, Estimates.powerW(s.packVoltage, s.currentA)!!, 0.01)
    }

    @Test fun positiveCurrentIsCharging() {
        val p = screenshotPayload()
        val cur = 300_000 + 2500
        p[4] = (cur shr 24).toByte(); p[5] = (cur shr 16).toByte(); p[6] = (cur shr 8).toByte(); p[7] = cur.toByte()
        assertEquals(25.0, UpPackStatusParser.parse(p, 0).currentA!!, 1e-9)
    }

    @Test fun protectionAndAlarmBitsAndBalancing() {
        val p = Fixtures.payloadOf(Fixtures.UP_STATUS).copyOf()
        p[27] = 0x02 // حماية البت 1: جهد خلية منخفض
        p[31] = 0x40 // تنبيه البت 6: حرارة مرتفعة أثناء الشحن
        p[24] = 0x40 // بت 30: غير معروف
        val t = 68 + 32
        p[t + 2 + 8 + 3] = 0x05 // موازنة الخليتين 1 و3
        val s = UpPackStatusParser.parse(p, 0)
        assertTrue(s.protections!!.any { it.arabic.contains("جهد خلية منخفض") })
        assertTrue(s.protections!!.any { it.arabic.contains("غير معروف") })
        assertTrue(s.alarms!!.single().arabic.contains("حرارة مرتفعة أثناء الشحن"))
        assertEquals(setOf(1, 3), s.balancingCells)
        assertTrue(s.hasBmsWarning)
    }

    @Test fun shortPayloadThrows() {
        assertThrows(ParseException::class.java) { UpPackStatusParser.parse(ByteArray(40), 0) }
    }

    @Test fun payloadWithoutOptionalTailLeavesFieldsNullNotZero() {
        // الترويسة فقط دون مصفوفة الخلايا: القيم الأساسية تُقرأ، والباقي «غير متاح».
        val s = UpPackStatusParser.parse(Fixtures.payloadOf(Fixtures.UP_STATUS).copyOfRange(0, 70), 0)
        assertEquals(53.17, s.packVoltage!!, 1e-9)
        assertNull(s.cellMilliVolts); assertNull(s.cellTempsC); assertNull(s.balancingCells)
        assertNull(s.model); assertNull(s.firmware); assertNull(s.parallelCount); assertNull(s.cellDeltaMv)
    }

    @Test fun modelRangeLocatesSerialBytes() {
        val p = Fixtures.payloadOf(Fixtures.UP_STATUS)
        val r = UpPackStatusParser.modelRange(p)!!
        assertEquals('J'.code, p.u8(r.first))
        assertEquals(30, r.count())
    }
}

class ClassicTest {
    @Test fun assemblesAndParsesBasicInfoSplitAcrossNotifications() {
        val a = ClassicFrameAssembler()
        val r = Fixtures.CLASSIC_BASIC.toList().chunked(20).flatMap { a.feed(it.toByteArray()) }
        val f = r.filterIsInstance<FrameResult.Frame>().single()
        assertEquals(0x03, f.function)
        val s = ClassicParser.parseBasicInfo(f.payload, 5)
        assertEquals(15.60, s.packVoltage!!, 1e-9)
        assertEquals(0.0, s.currentA!!, 1e-9)
        assertEquals(4.98, s.remainingAh!!, 1e-9)
        assertEquals(5.00, s.fullAh!!, 1e-9)
        assertEquals(100.0, s.socPercent!!, 1e-9)
        assertEquals(true, s.chargeMosOn); assertEquals(true, s.dischargeMosOn)
        assertEquals(listOf(22.4, 22.3, 21.7), s.cellTempsC)
        assertNull(s.sohPercent); assertNull(s.mosTempC); assertNull(s.cellMilliVolts)
    }

    @Test fun signedCurrent() {
        val p = Fixtures.CLASSIC_BASIC.copyOfRange(4, 4 + 0x1D)
        p[2] = 0xE9.toByte(); p[3] = 0x2C // -5844 × 10mA
        assertEquals(-58.44, ClassicParser.parseBasicInfo(p, 0).currentA!!, 1e-9)
        p[2] = 0x09; p[3] = 0xC4.toByte()
        assertEquals(25.0, ClassicParser.parseBasicInfo(p, 0).currentA!!, 1e-9)
    }

    @Test fun cellVoltages() {
        val f = ClassicFrameAssembler().feed(Fixtures.CLASSIC_CELLS).filterIsInstance<FrameResult.Frame>().single()
        assertEquals(listOf(3909, 3901, 3895, 3901), ClassicParser.parseCellVoltages(f.payload))
    }

    @Test fun badChecksumAndBadEndRejected() {
        val bad = Fixtures.CLASSIC_CELLS.copyOf().also { it[5] = 0x46 }
        assertTrue(ClassicFrameAssembler().feed(bad).none { it is FrameResult.Frame })
        val badEnd = Fixtures.CLASSIC_CELLS.copyOf().also { it[it.size - 1] = 0x78 }
        assertTrue(ClassicFrameAssembler().feed(badEnd).none { it is FrameResult.Frame })
    }

    @Test fun errorStatusIsReportedNotParsed() {
        val r = ClassicFrameAssembler().feed(hexToBytes("DD 03 80 00 FF 80 77"))
        assertTrue(r.single() is FrameResult.Error)
    }
}
