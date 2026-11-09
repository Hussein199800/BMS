package batari.core

import kotlin.math.abs

enum class FlowDirection { CHARGING, DISCHARGING, IDLE }

sealed interface TimeEstimate {
    data class Available(val hours: Double, val averageLoadW: Double, val windowCoveredMs: Long) : TimeEstimate
    data class Unavailable(val reason: String) : TimeEstimate
}

object Estimates {
    /** الجهد الاسمي لخلية LiFePO4 — يُستخدم لتحويل أمبير·ساعة إلى واط·ساعة تقديريًا. */
    const val LFP_NOMINAL_CELL_V = 3.2

    /** تحت هذا التيار نعتبر البطارية «خاملة» في العرض (حدّ عرض في التطبيق، ليس إعدادًا في البطارية). */
    const val IDLE_DEADBAND_A = 0.5

    /** أدنى متوسط حمل يُعرض عنده وقت متبقٍّ. */
    const val MIN_LOAD_W = 50.0
    const val MIN_SAMPLES = 5
    const val MIN_SPAN_MS = 60_000L

    /** القدرة على جانب التيار المستمر: موجبة = داخلة (شحن)، سالبة = خارجة (تفريغ). */
    fun powerW(voltage: Double?, currentA: Double?): Double? =
        if (voltage == null || currentA == null) null else voltage * currentA

    fun direction(currentA: Double?): FlowDirection? = when {
        currentA == null -> null
        currentA >= IDLE_DEADBAND_A -> FlowDirection.CHARGING
        currentA <= -IDLE_DEADBAND_A -> FlowDirection.DISCHARGING
        else -> FlowDirection.IDLE
    }

    fun nominalPackVoltage(cellCount: Int?): Double? = cellCount?.takeIf { it > 0 }?.let { it * LFP_NOMINAL_CELL_V }

    /** الطاقة المتبقية التقديرية = السعة المتبقية (أمبير·ساعة) × الجهد الاسمي. */
    fun remainingEnergyWh(remainingAh: Double?, nominalV: Double?): Double? =
        if (remainingAh == null || nominalV == null) null else remainingAh * nominalV

    /** الطاقة القابلة للاستخدام فوق الاحتياطي الذي اختاره المستخدم (للحساب فقط). */
    fun usableEnergyWh(remainingAh: Double?, fullAh: Double?, reservePercent: Int, nominalV: Double?): Double? {
        if (remainingAh == null || fullAh == null || nominalV == null) return null
        return ((remainingAh - fullAh * reservePercent / 100.0).coerceAtLeast(0.0)) * nominalV
    }

    fun timeRemaining(usableWh: Double?, avg: LoadAverage?): TimeEstimate = when {
        usableWh == null -> TimeEstimate.Unavailable("السعة غير متاحة من الجهاز")
        avg == null || avg.samples < MIN_SAMPLES || avg.spanMs < MIN_SPAN_MS ->
            TimeEstimate.Unavailable("نجمع قراءات كافية لحساب المتوسط…")
        avg.averageW > -MIN_LOAD_W && avg.averageW < MIN_LOAD_W -> TimeEstimate.Unavailable("الحمل قريب من الصفر")
        avg.averageW > 0 -> TimeEstimate.Unavailable("البطارية تُشحن الآن")
        usableWh <= 0.0 -> TimeEstimate.Unavailable("الشحن عند الاحتياطي المحدد أو أقل")
        else -> TimeEstimate.Available(usableWh / abs(avg.averageW), abs(avg.averageW), avg.spanMs)
    }
}

data class LoadAverage(val averageW: Double, val samples: Int, val spanMs: Long)

/** متوسط القدرة (بإشارتها) خلال نافذة زمنية حديثة قابلة للضبط. */
class LoadAverager(var windowMs: Long) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(timestampMs: Long, powerW: Double) {
        samples.addLast(timestampMs to powerW)
        trim(timestampMs)
    }

    fun clear() = samples.clear()

    fun average(nowMs: Long): LoadAverage? {
        trim(nowMs)
        if (samples.isEmpty()) return null
        return LoadAverage(samples.sumOf { it.second } / samples.size, samples.size, samples.last().first - samples.first().first)
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) samples.removeFirst()
    }
}

/** تنبيه انخفاض الشحن: يُطلق مرة واحدة عند النزول تحت الحد، ويُعاد تسليحه بعد الصعود فوقه بهامش. */
class LowSocAlert(private val hysteresis: Double = 2.0) {
    private var fired = false
    fun shouldNotify(soc: Double?, threshold: Int, enabled: Boolean): Boolean {
        if (!enabled || soc == null) return false
        if (soc >= threshold + hysteresis) fired = false
        if (soc < threshold && !fired) { fired = true; return true }
        return false
    }
}

object Freshness {
    /** القراءة قديمة إذا تجاوز عمرها ثلاثة أضعاف فترة الاستطلاع (و15 ثانية على الأقل). */
    fun isStale(ageMs: Long, pollIntervalMs: Long): Boolean = ageMs > maxOf(3 * pollIntervalMs, 15_000L)
}

/** إعادة اتصال تدريجية: 2، 5، 10، 30، 60 ثانية ثم تثبت على 60. */
class ReconnectPolicy(private val steps: List<Long> = listOf(2_000, 5_000, 10_000, 30_000, 60_000)) {
    private var attempt = 0
    fun nextDelayMs(): Long = steps[minOf(attempt++, steps.size - 1)]
    fun reset() { attempt = 0 }
}

/** يعدّ إخفاقات الاستطلاع المتتالية ويقرر متى نعتبر الاتصال منقطعًا. */
class PollHealth(private val maxConsecutiveFailures: Int = 3) {
    var consecutiveFailures = 0; private set
    fun success() { consecutiveFailures = 0 }
    /** @return true إذا وجب قطع الاتصال وإعادة المحاولة. */
    fun failure(): Boolean = ++consecutiveFailures >= maxConsecutiveFailures
}
