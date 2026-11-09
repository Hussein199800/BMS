package batari.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import batari.app.ble.LinkState
import batari.app.data.AppSettings
import batari.app.data.SourceMode
import batari.core.BatterySnapshot
import batari.core.Estimates
import batari.core.FlowDirection
import batari.core.Freshness
import batari.core.LoadAverage
import batari.core.TimeEstimate
import kotlin.math.abs

fun linkText(link: LinkState, mode: SourceMode): String = when {
    mode == SourceMode.DEMO -> "وضع تجريبي — غير متصل ببطارية"
    link is LinkState.Idle -> "غير متصل"
    link is LinkState.Connecting -> "جارٍ الاتصال بالبطارية…"
    link is LinkState.Discovering -> "جارٍ قراءة خدمات الجهاز…"
    link is LinkState.Probing -> "جارٍ التعرف على الجهاز…"
    link is LinkState.Live -> "✓ متصل بالبطارية"
    link is LinkState.Waiting -> "✕ انقطع الاتصال — إعادة المحاولة بعد ${num(link.seconds)} ث"
    link is LinkState.Failed -> "✕ تعذّر الاتصال"
    else -> ""
}

@Composable
fun HomeScreen(
    snapshot: BatterySnapshot?,
    link: LinkState,
    linkError: String?,
    mode: SourceMode,
    settings: AppSettings,
    average: LoadAverage?,
    now: Long,
    onOpenConnect: () -> Unit,
    onStartDemo: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val age = snapshot?.let { now - it.timestampMs }
        val stale = age != null && mode == SourceMode.REAL &&
            (Freshness.isStale(age, settings.pollSeconds * 1000L) || link !is LinkState.Live)

        // حالة الاتصال وآخر تحديث
        Panel(container = if (stale || link is LinkState.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
            Text(linkText(link, mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (link is LinkState.Failed) Text(link.reason)
            if (link is LinkState.Waiting) Note(link.reason)
            if (snapshot != null && age != null) {
                Text(
                    (if (stale) "⚠ بيانات قديمة — آخر قراءة " else "آخر تحديث: ") + ageText(age) + " (الساعة ${clock(snapshot.timestampMs)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (link is LinkState.Live && linkError != null) Note("آخر محاولة قراءة لم تكتمل: $linkError")
            if (mode == SourceMode.REAL && (link is LinkState.Idle || link is LinkState.Failed)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenConnect) { Text("اتصل بالبطارية") }
                    if (snapshot == null) OutlinedButton(onClick = onStartDemo) { Text("جرّب الوضع التجريبي") }
                }
            }
        }

        if (snapshot == null) {
            Panel(title = "لا توجد قراءات بعد") {
                Text(
                    if (mode == SourceMode.DEMO) "جارٍ تشغيل المحاكي…"
                    else "ستظهر هنا حالة البطارية بعد الاتصال بها. التطبيق لا يعرض أي أرقام غير حقيقية ما لم تختر الوضع التجريبي بنفسك."
                )
            }
            return@Column
        }

        val dim = Modifier.alpha(if (stale) 0.55f else 1f)
        val s = snapshot
        val direction = Estimates.direction(s.currentA)
        val power = Estimates.powerW(s.packVoltage, s.currentA)
        val nominalV = Estimates.nominalPackVoltage(s.cellCount)
        val usable = Estimates.usableEnergyWh(s.remainingAh, s.fullAh, settings.reservePercent, nominalV)

        // كم بقي؟
        Panel(modifier = dim, title = "كم بقي في البطارية؟", infoKey = "soc") {
            Text(
                s.socPercent?.let { "${num(it, 0)}٪" } ?: NA,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            s.socPercent?.let {
                LinearProgressIndicator(
                    progress = { (it / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
                )
            }
            Text(
                "السعة المتبقية: ${ampHours(s.remainingAh)}",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }

        // ماذا تفعل الآن؟
        Panel(
            modifier = dim, title = "ماذا تفعل البطارية الآن؟", infoKey = "power",
            container = when (direction) {
                FlowDirection.CHARGING -> MaterialTheme.colorScheme.primaryContainer
                FlowDirection.DISCHARGING -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ) {
            Text(
                when (direction) {
                    FlowDirection.CHARGING -> "▲ البطارية تُشحن الآن"
                    FlowDirection.DISCHARGING -> "▼ البطارية تزوّد الأجهزة بالطاقة"
                    FlowDirection.IDLE -> "● البطارية خاملة — لا شحن ولا تفريغ يُذكر"
                    null -> "اتجاه التيار غير متاح"
                },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            if (power != null && direction != null) {
                Text(
                    when (direction) {
                        FlowDirection.CHARGING -> "${kilowatts(abs(power))} داخلة إلى البطارية"
                        FlowDirection.DISCHARGING -> "${kilowatts(abs(power))} خارجة من البطارية"
                        FlowDirection.IDLE -> "القدرة قريبة من الصفر (${kilowatts(abs(power))})"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Note("هذه قدرة البطارية نفسها (جانب التيار المستمر)، وقد تختلف عما تستهلكه أجهزة المنزل من العاكس.")
            } else Text("القدرة: $NA")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = dim) {
            Tile("الجهد", volts(s.packVoltage), Modifier.weight(1f), infoKey = "voltage")
            Tile(
                "التيار", amps(s.currentA), Modifier.weight(1f), infoKey = "current",
                sub = when (direction) { FlowDirection.CHARGING -> "موجب = شحن"; FlowDirection.DISCHARGING -> "سالب = تفريغ"; else -> null },
            )
        }

        Tile(
            "الطاقة المتبقية التقديرية", kilowattHours(Estimates.remainingEnergyWh(s.remainingAh, nominalV)),
            dim.fillMaxWidth(), infoKey = "energy",
            sub = if (usable != null) "منها ${kilowattHours(usable)} فوق احتياطي ${num(settings.reservePercent)}٪ الذي اخترته" else "تحتاج السعة وعدد الخلايا من الجهاز",
        )

        val estimate = if (stale) TimeEstimate.Unavailable("البيانات قديمة") else Estimates.timeRemaining(usable, average)
        Tile(
            "الوقت المتبقي التقديري",
            when (estimate) { is TimeEstimate.Available -> "نحو ${duration(estimate.hours)}"; is TimeEstimate.Unavailable -> NA },
            dim.fillMaxWidth(), infoKey = "time",
            sub = when (estimate) {
                is TimeEstimate.Available ->
                    "بمتوسط حمل ${kilowatts(estimate.averageLoadW)} خلال آخر ${num((estimate.windowCoveredMs / 60000).toInt().coerceAtLeast(1))} د، حتى احتياطي ${num(settings.reservePercent)}٪. تقدير يتغير مع الحمل."
                is TimeEstimate.Unavailable -> estimate.reason
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = dim) {
            val temps = s.cellTempsC
            val maxIdx = temps?.indices?.maxByOrNull { temps[it] }
            Tile(
                "أعلى حرارة للخلايا", celsius(maxIdx?.let { temps[it] }), Modifier.weight(1f), infoKey = "celltemp",
                sub = maxIdx?.let { "المصدر: حساس الخلايا ${num(it + 1)}" },
            )
            Tile("حرارة مفاتيح القدرة", celsius(s.mosTempC), Modifier.weight(1f), infoKey = "mos", sub = if (s.mosTempC != null) "المصدر: حساس MOS" else null)
        }

        // إنذارات نظام البطارية
        val warn = s.hasBmsWarning
        Panel(
            modifier = dim, title = "هل يوجد تنبيه من البطارية؟", infoKey = "protect",
            container = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        ) {
            when {
                s.protections == null && s.alarms == null -> Text("حالة الإنذارات: $NA")
                !warn -> Text("✓ لا — نظام البطارية لا يبلّغ عن أي حماية أو تنبيه.", style = MaterialTheme.typography.bodyLarge)
                else -> {
                    Text("⚠ نعم — نظام البطارية يبلّغ عن:", fontWeight = FontWeight.Bold)
                    (s.protections.orEmpty() + s.alarms.orEmpty()).forEach { Text("• ${it.arabic}") }
                }
            }
        }
    }
}
