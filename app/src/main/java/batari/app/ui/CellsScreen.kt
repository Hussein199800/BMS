package batari.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import batari.core.BatterySnapshot

@Composable
fun CellsScreen(snapshot: BatterySnapshot?) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val cells = snapshot?.cellMilliVolts
        if (snapshot == null || cells.isNullOrEmpty()) {
            Panel(title = "الخلايا") { Text(if (snapshot == null) "لا توجد قراءات بعد." else "جهود الخلايا: $NA من هذا الجهاز.") }
            return@Column
        }
        val max = cells.max()
        val min = cells.min()
        val maxIdx = cells.indexOf(max)
        val minIdx = cells.indexOf(min)
        val balancing = snapshot.balancingCells

        Panel(title = "ملخص الخلايا (${num(cells.size)} خلية)") {
            InfoRow("أعلى جهد", "${num(max / 1000.0, 3)} فولت — الخلية ${num(maxIdx + 1)}")
            InfoRow("أدنى جهد", "${num(min / 1000.0, 3)} فولت — الخلية ${num(minIdx + 1)}")
            InfoRow("الفرق بين الأعلى والأدنى", "${num(max - min)} ملي فولت", infoKey = "delta")
            InfoRow(
                "الموازنة",
                when {
                    balancing == null -> NA
                    balancing.isEmpty() -> "غير نشطة الآن"
                    else -> "نشطة على: " + balancing.sorted().joinToString("، ") { num(it) }
                },
                infoKey = "balance",
            )
            Note("كل القيم من قراءة واحدة في اللحظة نفسها (الساعة ${clock(snapshot.timestampMs)}).")
        }

        Panel(title = "جهد كل خلية") {
            cells.indices.chunked(4).forEach { rowIdx ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowIdx.forEach { i ->
                        val tag = buildList {
                            if (i == maxIdx && max != min) add("الأعلى ▲")
                            if (i == minIdx && max != min) add("الأدنى ▼")
                            if (balancing?.contains(i + 1) == true) add("موازنة ⇄")
                        }.joinToString(" ")
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = if (tag.isNotEmpty()) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("الخلية ${num(i + 1)}", style = MaterialTheme.typography.bodySmall)
                                Text(num(cells[i] / 1000.0, 3), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(tag.ifEmpty { "فولت" }, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                    repeat(4 - rowIdx.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Panel(title = "مقارنة مبسطة") {
            Note("طول العمود يبيّن موقع الخلية بين الأدنى والأعلى في هذه القراءة فقط. المقياس مكبَّر جدًا: الفرق الكلي هنا ${num(max - min)} ملي فولت.")
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                cells.forEachIndexed { i, mv ->
                    val frac = if (max == min) 0.6f else 0.15f + 0.85f * (mv - min) / (max - min).toFloat()
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(frac * 0.85f)
                                .background(
                                    if (i == maxIdx || i == minIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f),
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                )
                        )
                        Text(num(i + 1), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }

        Panel(title = "كيف أقرأ هذه الصفحة؟") {
            Text("• فرق صغير بين الخلايا أمر طبيعي، ويتغير مع الحمل ونسبة الشحن.")
            Text("• لا يُحكم على خلية بأنها ضعيفة من قراءة واحدة؛ المهم هو النمط المتكرر على مدى أيام.")
            Text("• عدم نشاط الموازنة ليس عطلًا؛ فهي تعمل عادةً قرب نهاية الشحن فقط.")
            Text("• التطبيق لا يضع حدودًا من عنده للخطر. إن وُجد إنذار حقيقي فسيظهر في الصفحة الرئيسية نقلًا عن نظام البطارية.")
        }
    }
}
