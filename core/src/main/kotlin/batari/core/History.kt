package batari.core

import java.util.Locale

/** صف واحد في السجل المحلي. الحقول غير المتاحة تبقى null وتُكتب فارغة في CSV. */
data class HistorySample(
    val timestampMs: Long,
    val voltage: Double?,
    val currentA: Double?,
    val powerW: Double?,
    val socPercent: Double?,
    val maxCellTempC: Double?,
    val mosTempC: Double?,
)

fun BatterySnapshot.toHistorySample() = HistorySample(
    timestampMs, packVoltage, currentA, Estimates.powerW(packVoltage, currentA), socPercent,
    cellTempsC?.maxOrNull(), mosTempC,
)

object Csv {
    const val HEADER = "timestamp_iso,timestamp_ms,voltage_v,current_a,power_w,soc_percent,max_cell_temp_c,mos_temp_c"

    fun row(s: HistorySample): String {
        fun f(v: Double?, digits: Int) = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: ""
        val iso = java.time.Instant.ofEpochMilli(s.timestampMs).toString()
        return listOf(iso, s.timestampMs.toString(), f(s.voltage, 2), f(s.currentA, 2), f(s.powerW, 1),
            f(s.socPercent, 2), f(s.maxCellTempC, 1), f(s.mosTempC, 1)).joinToString(",")
    }

    fun document(samples: List<HistorySample>): String =
        buildString { appendLine(HEADER); samples.forEach { appendLine(row(it)) } }
}

object Masking {
    /** AA:C2:37:0B:47:D7 ← AA:C2:••:••:••:D7 */
    fun mac(address: String): String {
        val p = address.split(":")
        return if (p.size == 6) "${p[0]}:${p[1]}:••:••:••:${p[5]}" else "••••"
    }

    /** يُبقي أول 4 وآخر 2 من الاسم/الرقم التسلسلي. */
    fun name(name: String?): String = when {
        name.isNullOrBlank() -> "(بلا اسم)"
        name.length <= 8 -> name
        else -> name.take(4) + "•".repeat(name.length - 6) + name.takeLast(2)
    }
}
