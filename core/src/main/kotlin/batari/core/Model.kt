package batari.core

/** أي بروتوكول فُكّت به القراءة. */
enum class ProtocolKind(val label: String) {
    /** بروتوكول سلسلة JBD UP (شبيه Modbus، دالة القراءة 0x78، CRC16). */
    UP_SERIES("JBD UP (0x78)"),

    /** بروتوكول JBD الكلاسيكي (DD A5 … 77). */
    CLASSIC("JBD الكلاسيكي (DD A5)"),

    /** بيانات المحاكي — ليست من بطارية. */
    SIMULATED("محاكي"),
}

enum class BmsOperation { IDLE, CHARGING, DISCHARGING }

/** إنذار أو حماية بلّغ عنها نظام البطارية نفسه (وليس تنبيهًا من التطبيق). */
data class BmsFlag(val code: String, val arabic: String)

/**
 * لقطة واحدة متزامنة من قراءات البطارية.
 * كل حقل لم يوفّره الجهاز يبقى null ويُعرض «غير متاح» — لا يُستبدل بصفر أبدًا.
 * التيار: موجب = شحن، سالب = تفريغ.
 */
data class BatterySnapshot(
    val timestampMs: Long,
    val protocol: ProtocolKind,
    val packVoltage: Double? = null,
    val currentA: Double? = null,
    val socPercent: Double? = null,
    val remainingAh: Double? = null,
    val fullAh: Double? = null,
    val ratedAh: Double? = null,
    val cycles: Int? = null,
    val sohPercent: Int? = null,
    val bmsOperation: BmsOperation? = null,
    val cellMilliVolts: List<Int>? = null,
    val cellTempsC: List<Double>? = null,
    val mosTempC: Double? = null,
    val ambientTempC: Double? = null,
    /** أرقام الخلايا (تبدأ من 1) التي تجري موازنتها الآن؛ null = الجهاز لا يوفّر المعلومة. */
    val balancingCells: Set<Int>? = null,
    val protections: List<BmsFlag>? = null,
    val alarms: List<BmsFlag>? = null,
    val chargeMosOn: Boolean? = null,
    val dischargeMosOn: Boolean? = null,
    val heaterOn: Boolean? = null,
    val currentLimitingOn: Boolean? = null,
    val chargeVoltageLimitV: Double? = null,
    val chargeCurrentLimitA: Double? = null,
    val dischargeVoltageLimitV: Double? = null,
    val dischargeCurrentLimitA: Double? = null,
    val firmware: String? = null,
    val model: String? = null,
    val parallelCount: Int? = null,
) {
    val cellCount: Int? get() = cellMilliVolts?.size
    val maxCellMv: Int? get() = cellMilliVolts?.maxOrNull()
    val minCellMv: Int? get() = cellMilliVolts?.minOrNull()
    val cellDeltaMv: Int? get() = if (cellMilliVolts.isNullOrEmpty()) null else maxCellMv!! - minCellMv!!
    val hasBmsWarning: Boolean get() = !protections.isNullOrEmpty() || !alarms.isNullOrEmpty()
}
