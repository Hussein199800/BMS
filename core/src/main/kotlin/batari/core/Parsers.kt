package batari.core

class ParseException(message: String) : Exception(message)

/**
 * فك سجل حالة المجموعة (0x1000) في سلسلة JBD UP.
 * المصدر: تنفيذ syssi/esphome-jbd-bms (jbd_bms_up_pack) ووصف PhracturedBlue لـ UP16S010،
 * وكلاهما مبني على التقاطات من أجهزة حقيقية — لكنه لم يُتحقق منه بعد على بطارية المستخدم.
 */
object UpPackStatusParser {
    /** موضع وطول رقم/اسم الموديل داخل الحمولة (لإخفائه في التقارير)، أو null. */
    fun modelRange(d: ByteArray): IntRange? {
        if (d.size < 68) return null
        val t = 68 + 2 * d.u16(66)
        if (d.size < t + 2) return null
        val modelAt = t + 2 + 2 * d.u16(t) + 6
        return if (d.size >= modelAt + 30) modelAt until modelAt + 30 else null
    }

    fun parse(d: ByteArray, nowMs: Long): BatterySnapshot {
        if (d.size < 68) throw ParseException("حمولة حالة المجموعة قصيرة: ${d.size} بايت")
        fun temp(i: Int) = (d.u16(i) - 500) / 10.0

        val protectMask = (d.u16(24).toLong() shl 16) or d.u16(26).toLong()
        val alarmMask = (d.u16(28).toLong() shl 16) or d.u16(30).toLong()
        val mos = d.u16(32)
        val cellCount = d.u16(66)
        if (cellCount > 32) throw ParseException("عدد خلايا غير معقول: $cellCount")

        var cells: List<Int>? = null
        var temps: List<Double>? = null
        var balancing: Set<Int>? = null
        var firmware: String? = null
        var model: String? = null
        var parallel: Int? = null

        var off = 68
        if (d.size >= off + 2 * cellCount) {
            cells = List(cellCount) { d.u16(off + 2 * it) }
            off += 2 * cellCount
            if (d.size >= off + 2) {
                val tCount = d.u16(off)
                off += 2
                if (tCount <= 16 && d.size >= off + 2 * tCount) {
                    temps = List(tCount) { temp(off + 2 * it) }
                    off += 2 * tCount
                    // [2 غير معروف][قناع الموازنة 2][إصدار البرنامج 2][الموديل 30][عدد التوازي 2]
                    if (d.size >= off + 6) {
                        val mask = d.u16(off + 2)
                        balancing = (1..minOf(cellCount, 16)).filter { mask and (1 shl (it - 1)) != 0 }.toSet()
                        firmware = "${d.u8(off + 4)}.${d.u8(off + 5)}"
                        off += 6
                        if (d.size >= off + 30) {
                            model = String(d.copyOfRange(off, off + 30).takeWhile { it != 0.toByte() }.toByteArray(), Charsets.US_ASCII)
                                .filter { it.code in 32..126 }.ifBlank { null }
                            off += 30
                            if (d.size >= off + 2) parallel = d.u16(off)
                        }
                    }
                }
            }
        }

        return BatterySnapshot(
            timestampMs = nowMs,
            protocol = ProtocolKind.UP_SERIES,
            packVoltage = d.u16(0) / 100.0,
            currentA = (d.u32(4) - 300_000L) / 100.0,
            socPercent = d.u16(8) / 100.0,
            remainingAh = d.u16(10) / 100.0,
            fullAh = d.u16(12) / 100.0,
            ratedAh = d.u16(14) / 100.0,
            mosTempC = temp(16),
            ambientTempC = temp(18),
            bmsOperation = when (d.u16(20)) {
                0 -> BmsOperation.IDLE
                1 -> BmsOperation.CHARGING
                2 -> BmsOperation.DISCHARGING
                else -> null
            },
            sohPercent = d.u16(22),
            protections = flags(protectMask, UP_PROTECTIONS, "P"),
            alarms = flags(alarmMask, UP_ALARMS, "A"),
            dischargeMosOn = mos and 0x01 != 0,
            chargeMosOn = mos and 0x02 != 0,
            heaterOn = mos and 0x08 != 0,
            currentLimitingOn = mos and 0x80 != 0,
            cycles = d.u16(36),
            chargeVoltageLimitV = d.u16(58) / 10.0,
            chargeCurrentLimitA = d.u16(60) / 10.0,
            dischargeVoltageLimitV = d.u16(62) / 10.0,
            dischargeCurrentLimitA = d.u16(64) / 10.0,
            cellMilliVolts = cells,
            cellTempsC = temps,
            balancingCells = balancing,
            firmware = firmware,
            model = model,
            parallelCount = parallel,
        )
    }

    private val UP_PROTECTIONS = listOf(
        "حماية: جهد خلية مرتفع", "حماية: جهد خلية منخفض", "حماية: جهد المجموعة مرتفع", "حماية: جهد المجموعة منخفض",
        "حماية: تيار شحن زائد (المستوى 1)", "حماية: تيار شحن زائد (المستوى 2)",
        "حماية: تيار تفريغ زائد (المستوى 1)", "حماية: تيار تفريغ زائد (المستوى 2)",
        "حماية: حرارة مرتفعة أثناء الشحن", "حماية: حرارة منخفضة أثناء الشحن",
        "حماية: حرارة مرتفعة أثناء التفريغ", "حماية: حرارة منخفضة أثناء التفريغ",
        "حماية: حرارة مفاتيح القدرة (MOS) مرتفعة", "حماية: حرارة المحيط مرتفعة", "حماية: حرارة المحيط منخفضة",
        "حماية: فرق جهد الخلايا كبير", "حماية: فرق الحرارة كبير", "حماية: نسبة الشحن منخفضة جدًا",
        "حماية: دارة قصر", "حماية: خلية غير متصلة بالقياس", "حماية: هبوط حرارة",
        "عطل: مفتاح الشحن (MOS)", "عطل: مفتاح التفريغ (MOS)", "عطل: تحديد التيار",
        "عطل: نظام الإطفاء (Aerosol)", "حماية: اكتمال الشحن", "عطل: اتصال دارة القياس (AFE)",
    )
    private val UP_ALARMS = listOf(
        "تنبيه: جهد خلية مرتفع", "تنبيه: جهد خلية منخفض", "تنبيه: جهد المجموعة مرتفع", "تنبيه: جهد المجموعة منخفض",
        "تنبيه: تيار شحن زائد", "تنبيه: تيار تفريغ زائد",
        "تنبيه: حرارة مرتفعة أثناء الشحن", "تنبيه: حرارة منخفضة أثناء الشحن",
        "تنبيه: حرارة مرتفعة أثناء التفريغ", "تنبيه: حرارة منخفضة أثناء التفريغ",
        "تنبيه: حرارة مفاتيح القدرة (MOS) مرتفعة", "تنبيه: حرارة المحيط مرتفعة", "تنبيه: حرارة المحيط منخفضة",
        "تنبيه: فرق جهد الخلايا كبير", "تنبيه: فرق الحرارة كبير", "تنبيه: نسبة الشحن منخفضة",
        "تنبيه: عطل الذاكرة (EEP)", "تنبيه: ساعة النظام غير طبيعية",
    )
}

internal fun flags(mask: Long, names: List<String>, prefix: String): List<BmsFlag> =
    (0 until 32).filter { mask and (1L shl it) != 0L }.map {
        BmsFlag("$prefix$it", names.getOrNull(it) ?: "رمز غير معروف (البت $it)")
    }

/** فك أمرَي 0x03 و0x04 في بروتوكول JBD الكلاسيكي (المصدر: وثيقة Jiabaida وتنفيذ syssi jbd_bms_ble). */
object ClassicParser {
    fun parseBasicInfo(d: ByteArray, nowMs: Long): BatterySnapshot {
        if (d.size < 23) throw ParseException("حمولة المعلومات الأساسية قصيرة: ${d.size} بايت")
        val fet = d.u8(20)
        // البت 0x80: الوحدات 100 ملي أمبير/100 ملي أمبير·ساعة بدل 10.
        val scale = if (fet and 0x80 != 0) 0.1 else 0.01
        val cellCount = d.u8(21)
        val balanceMask = (d.u16(14).toLong() shl 16) or d.u16(12).toLong()
        val ntc = d.u8(22)
        val temps = if (d.size >= 23 + 2 * ntc) List(ntc) { (d.u16(23 + 2 * it) - 2731) / 10.0 } else null
        return BatterySnapshot(
            timestampMs = nowMs,
            protocol = ProtocolKind.CLASSIC,
            packVoltage = d.u16(0) / 100.0,
            currentA = d.s16(2) * scale,
            remainingAh = d.u16(4) * scale,
            fullAh = d.u16(6) * scale,
            cycles = d.u16(8),
            balancingCells = (1..minOf(cellCount, 32)).filter { balanceMask and (1L shl (it - 1)) != 0L }.toSet(),
            protections = flags(d.u16(16).toLong(), CLASSIC_PROTECTIONS, "C"),
            alarms = emptyList(),
            firmware = "${d.u8(18) shr 4}.${d.u8(18) and 0x0F}",
            socPercent = d.u8(19).toDouble(),
            chargeMosOn = fet and 0x01 != 0,
            dischargeMosOn = fet and 0x02 != 0,
            cellTempsC = temps,
        )
    }

    fun parseCellVoltages(d: ByteArray): List<Int> {
        if (d.isEmpty() || d.size % 2 != 0 || d.size > 64) throw ParseException("طول جهود الخلايا غير صالح: ${d.size}")
        return List(d.size / 2) { d.u16(2 * it) }
    }

    private val CLASSIC_PROTECTIONS = listOf(
        "حماية: جهد خلية مرتفع", "حماية: جهد خلية منخفض", "حماية: جهد المجموعة مرتفع", "حماية: جهد المجموعة منخفض",
        "حماية: حرارة مرتفعة أثناء الشحن", "حماية: حرارة منخفضة أثناء الشحن",
        "حماية: حرارة مرتفعة أثناء التفريغ", "حماية: حرارة منخفضة أثناء التفريغ",
        "حماية: تيار شحن زائد", "حماية: تيار تفريغ زائد", "حماية: دارة قصر",
        "عطل: دارة القياس الأمامية", "قفل برمجي لمفاتيح القدرة (MOS)", "إيقاف: انتهاء مهلة الشحن",
    )
}
