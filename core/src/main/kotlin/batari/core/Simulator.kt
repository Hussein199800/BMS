package batari.core

import kotlin.math.sin

/**
 * محاكي لاختبار الواجهة فقط. القيم قريبة من لقطات الشاشة لكنها مولّدة حسابيًا
 * وتحمل دائمًا [ProtocolKind.SIMULATED] حتى لا تختلط ببيانات حقيقية.
 */
class Simulator(private val startMs: Long, private val startRemainingAh: Double = 171.75) {
    private val fullAh = 200.0

    fun snapshot(nowMs: Long): BatterySnapshot {
        val t = (nowMs - startMs) / 1000.0
        // حمل يتأرجح حول -58 أمبير، مع فترة شحن قصيرة كل 4 دقائق لتجربة كل الحالات.
        val phase = (t % 240.0)
        val current = when {
            phase < 150 -> -58.4 + 6 * sin(t / 7.0)
            phase < 170 -> 0.1 * sin(t)
            else -> 35.0 + 4 * sin(t / 5.0)
        }
        val remaining = (startRemainingAh + current * t / 3600.0 * 0.2).coerceIn(5.0, fullAh)
        val base = 3283 + (current / 20).toInt()
        val offsets = intArrayOf(2, 0, -4, -3, -3, 0, -2, 2, 3, 0, -1, 0, -4, 1, 1, 3)
        val cells = offsets.mapIndexed { i, o -> base + o + ((sin(t / 11 + i) * 1.2).toInt()) }
        return BatterySnapshot(
            timestampMs = nowMs,
            protocol = ProtocolKind.SIMULATED,
            packVoltage = cells.sum() / 1000.0,
            currentA = Math.round(current * 100) / 100.0,
            socPercent = Math.round(remaining / fullAh * 10000) / 100.0,
            remainingAh = Math.round(remaining * 100) / 100.0,
            fullAh = fullAh,
            ratedAh = 200.0,
            cycles = 78,
            sohPercent = 100,
            bmsOperation = when (Estimates.direction(current)) {
                FlowDirection.CHARGING -> BmsOperation.CHARGING
                FlowDirection.DISCHARGING -> BmsOperation.DISCHARGING
                else -> BmsOperation.IDLE
            },
            cellMilliVolts = cells,
            cellTempsC = listOf(26.5, 27.0, 28.5, 27.7).map { Math.round((it + 0.3 * sin(t / 30)) * 10) / 10.0 },
            mosTempC = 36.9,
            ambientTempC = 27.5,
            balancingCells = emptySet(),
            protections = emptyList(),
            alarms = emptyList(),
            chargeMosOn = true,
            dischargeMosOn = true,
            heaterOn = false,
            currentLimitingOn = false,
            firmware = "محاكي",
            model = "محاكي",
            parallelCount = 1,
        )
    }
}
