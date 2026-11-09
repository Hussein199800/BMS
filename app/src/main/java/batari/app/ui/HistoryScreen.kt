package batari.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import batari.app.data.SourceMode
import batari.core.HistorySample

private val RANGES = listOf("ساعة" to 3_600_000L, "6 ساعات" to 6 * 3_600_000L, "24 ساعة" to 24 * 3_600_000L, "7 أيام" to 7 * 24 * 3_600_000L)

private class Metric(val label: String, val unit: String, val digits: Int, val get: (HistorySample) -> Double?)

private val METRICS = listOf(
    Metric("نسبة الشحن", "٪", 0) { it.socPercent },
    Metric("القدرة", "كيلوواط", 2) { it.powerW?.div(1000.0) },
    Metric("الجهد", "فولت", 2) { it.voltage },
    Metric("التيار", "أمبير", 1) { it.currentA },
    Metric("حرارة الخلايا", "°م", 1) { it.maxCellTempC },
    Metric("حرارة MOS", "°م", 1) { it.mosTempC },
)

@Composable
fun HistoryScreen(
    samples: List<HistorySample>,
    mode: SourceMode,
    refreshKey: Long,
    onLoad: (Long) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    var range by remember { mutableIntStateOf(0) }
    var metric by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(range, refreshKey) { onLoad(RANGES[range].second) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mode == SourceMode.DEMO) {
            Panel(container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("بيانات الوضع التجريبي لا تُسجَّل. ما تراه هنا هو سجل القراءات الحقيقية السابقة فقط (إن وُجد).")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RANGES.forEachIndexed { i, r -> FilterChip(selected = range == i, onClick = { range = i }, label = { Text("آخر ${r.first}") }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            METRICS.forEachIndexed { i, m -> FilterChip(selected = metric == i, onClick = { metric = i }, label = { Text(m.label) }) }
        }

        val m = METRICS[metric]
        val points = samples.mapNotNull { s -> m.get(s)?.let { s.timestampMs to it } }
        Panel(title = "${m.label} (${m.unit})") {
            if (points.size < 2) {
                Text(if (samples.isEmpty()) "لا توجد قراءات مسجّلة في هذه المدة." else "هذه القيمة $NA في القراءات المسجّلة.")
                Note("التسجيل يجري فقط أثناء اتصال التطبيق بالبطارية، بمعدل قراءة كل 15 ثانية تقريبًا.")
            } else {
                val minV = points.minOf { it.second }
                val maxV = points.maxOf { it.second }
                val t0 = points.first().first
                val t1 = points.last().first
                Text("الأعلى: ${num(maxV, m.digits)} ${m.unit}", style = MaterialTheme.typography.bodySmall)
                val line = MaterialTheme.colorScheme.primary
                val grid = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                    val w = size.width
                    val h = size.height
                    val span = (t1 - t0).coerceAtLeast(1L).toFloat()
                    val vr = (maxV - minV).takeIf { it > 1e-9 } ?: 1.0
                    // المحور الزمني من اليسار (الأقدم) إلى اليمين (الأحدث).
                    fun x(t: Long) = (t - t0) / span * w
                    fun y(v: Double) = (h - ((v - minV) / vr * h)).toFloat()
                    for (i in 0..4) drawLine(grid, Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f)
                    val path = Path()
                    var prev: Pair<Long, Double>? = null
                    for (p in points) {
                        // فجوة أطول من دقيقتين = لم يكن هناك اتصال؛ لا نرسم خطًا يوهم بوجود بيانات.
                        if (prev == null || p.first - prev.first > GAP_MS) path.moveTo(x(p.first), y(p.second))
                        else path.lineTo(x(p.first), y(p.second))
                        prev = p
                    }
                    drawPath(path, line, style = Stroke(width = 5f, cap = StrokeCap.Round))
                    points.forEach { drawCircle(line, 2.5f, Offset(x(it.first), y(it.second))) }
                }
                Text("الأدنى: ${num(minV, m.digits)} ${m.unit}", style = MaterialTheme.typography.bodySmall)
                val pattern = if (t1 - t0 > 24 * 3_600_000L) "MM-dd HH:mm" else "HH:mm"
                Row(Modifier.fillMaxWidth()) {
                    // في RTL: العنصر الأول يظهر يمينًا = الأحدث.
                    Text("الأحدث ${clock(t1, pattern)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text("الأقدم ${clock(t0, pattern)}", style = MaterialTheme.typography.bodySmall)
                }
                Note("الرسم يغطي المدة المسجّلة فعلًا فقط (${num(points.size)} قراءة). الانقطاعات في الخط هي فترات لم يكن التطبيق متصلًا فيها.")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport) { Text("تصدير السجل كاملًا (CSV)") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("مسح السجل") }
        }
        Note("السجل محفوظ على هذا الهاتف فقط ويُحذف منه تلقائيًا ما مضى عليه أكثر من 30 يومًا.")
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح كل السجل؟") },
            text = { Text("ستُحذف كل القراءات المسجّلة من هذا الهاتف ولا يمكن استرجاعها.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("امسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}

private const val GAP_MS = 120_000L
