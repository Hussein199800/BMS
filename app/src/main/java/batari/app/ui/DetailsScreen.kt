package batari.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import batari.core.BatterySnapshot
import batari.core.BmsOperation

@Composable
fun DetailsScreen(s: BatterySnapshot?) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s == null) {
            Panel(title = "التفاصيل") { Text("لا توجد قراءات بعد.") }
            return@Column
        }
        Panel(title = "السعة") {
            InfoRow("السعة الاسمية", ampHours(s.ratedAh), "rated")
            InfoRow("السعة الكاملة المبلغ عنها", ampHours(s.fullAh), "full")
            InfoRow("السعة المتبقية", ampHours(s.remainingAh), "remaining")
            InfoRow("نسبة الشحن", s.socPercent?.let { "${num(it, 1)}٪" } ?: NA, "soc")
        }
        Panel(title = "العمر والاستخدام") {
            InfoRow("عدد الدورات كما يبلّغ عنه النظام", s.cycles?.let { num(it) } ?: NA, "cycles")
            InfoRow("الصحة المبلغ عنها من نظام البطارية", s.sohPercent?.let { "${num(it)}٪" } ?: NA, "soh")
        }
        Panel(title = "الحماية والإنذارات", infoKey = "protect",
            container = if (s.hasBmsWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
            Text("الحمايات الفعّالة:", fontWeight = FontWeight.Bold)
            when {
                s.protections == null -> Text(NA)
                s.protections!!.isEmpty() -> Text("✓ لا شيء")
                else -> s.protections!!.forEach { Text("⚠ ${it.arabic}") }
            }
            Text("التنبيهات:", fontWeight = FontWeight.Bold)
            when {
                s.alarms == null -> Text(NA)
                s.alarms!!.isEmpty() -> Text("✓ لا شيء")
                else -> s.alarms!!.forEach { Text("⚠ ${it.arabic}") }
            }
            Note("هذه الإشارات منقولة كما هي من نظام البطارية.")
        }
        Panel(title = "المفاتيح (للعرض فقط)", infoKey = "switches") {
            InfoRow("مفتاح الشحن", onOff(s.chargeMosOn))
            InfoRow("مفتاح التفريغ", onOff(s.dischargeMosOn))
            InfoRow("التسخين", onOff(s.heaterOn))
            InfoRow("تحديد التيار", onOff(s.currentLimitingOn), "limiting")
            InfoRow(
                "الحالة كما يبلّغ عنها النظام",
                when (s.bmsOperation) {
                    BmsOperation.CHARGING -> "شحن"; BmsOperation.DISCHARGING -> "تفريغ"; BmsOperation.IDLE -> "خمول"; null -> NA
                },
            )
            Note("هذا التطبيق لا يستطيع تشغيل أو إيقاف أي مفتاح.")
        }
        Panel(title = "الحرارة") {
            val temps = s.cellTempsC
            if (temps == null) InfoRow("حساسات الخلايا", NA, "celltemp")
            else temps.forEachIndexed { i, t -> InfoRow("حساس الخلايا ${num(i + 1)}", celsius(t), if (i == 0) "celltemp" else null) }
            InfoRow("مفاتيح القدرة (MOS)", celsius(s.mosTempC), "mos")
            InfoRow("المحيط (داخل الصندوق)", celsius(s.ambientTempC), "ambient")
        }
        Panel(title = "حدود يطلبها نظام البطارية من العاكس", infoKey = "limits") {
            InfoRow("أقصى جهد شحن", volts(s.chargeVoltageLimitV))
            InfoRow("أقصى تيار شحن", amps(s.chargeCurrentLimitA))
            InfoRow("أدنى جهد تفريغ", volts(s.dischargeVoltageLimitV))
            InfoRow("أقصى تيار تفريغ", amps(s.dischargeCurrentLimitA))
            Note("للعرض فقط. تفسير هذه الحقول مأخوذ من مصدر مفتوح ولم يُتحقق منه على بطاريتك بعد.")
        }
        Panel(title = "الجهاز والبروتوكول") {
            InfoRow("البروتوكول المكتشف", s.protocol.label, "protocol")
            InfoRow("الموديل كما يبلّغ عنه الجهاز", s.model ?: NA)
            InfoRow("إصدار برنامج النظام", s.firmware?.let { ltr(it) } ?: NA)
            InfoRow("عدد الخلايا", s.cellCount?.let { num(it) } ?: NA)
            InfoRow("عدد التوازي", s.parallelCount?.let { num(it) } ?: NA, "parallel")
        }
    }
}
