package batari.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import batari.app.data.AppSettings
import batari.app.data.SourceMode
import batari.app.data.ThemeMode
import batari.core.Masking

@Composable
private fun Stepper(label: String, value: Int, suffix: String, step: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        FilledTonalButton(onClick = { onChange((value - step).coerceIn(range)) }, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("${num(value)}$suffix", fontWeight = FontWeight.Bold)
        FilledTonalButton(onClick = { onChange((value + step).coerceIn(range)) }, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(
    s: AppSettings,
    mode: SourceMode,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onOpenConnect: () -> Unit,
    onToggleDemo: (Boolean) -> Unit,
    onEnableAlert: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(title = "البطارية") {
            if (s.deviceAddress == null) Text("لم تُحفظ بطارية بعد.")
            else {
                Text("البطارية المحفوظة: " + (if (s.maskIdentifiers) Masking.name(s.deviceName) else (s.deviceName ?: "(بلا اسم)")))
                Text(ltr(if (s.maskIdentifiers) Masking.mac(s.deviceAddress) else s.deviceAddress), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenConnect) { Text("البحث والاتصال وفحص الاتصال") }
            }
            if (s.deviceAddress != null) OutlinedButton(onClick = { update { it.copy(deviceAddress = null, deviceName = null) } }) { Text("نسيان البطارية المحفوظة") }
            Toggle("الاتصال تلقائيًا عند فتح التطبيق", s.autoConnect) { v -> update { it.copy(autoConnect = v) } }
            Stepper("قراءة جديدة كل", s.pollSeconds, " ث", 1, 2..60) { v -> update { it.copy(pollSeconds = v) } }
            Stepper("رقم المجموعة (PACK)", s.packAddress, "", 1, 0..15) { v -> update { it.copy(packAddress = v) } }
            Note("رقم المجموعة يُستخدم في بروتوكول سلسلة UP فقط. اتركه 1 إن كان التطبيق الأصلي يعرض PACK1.")
        }

        Panel(title = "الحسابات (لا تغيّر شيئًا في البطارية)") {
            Stepper("احتياطي الشحن في الحساب", s.reservePercent, "٪", 5, 0..50) { v -> update { it.copy(reservePercent = v) } }
            Note("يُطرح من الطاقة والوقت المتبقي فقط، كأنك تقول: «لا أريد النزول تحت هذه النسبة».")
            Stepper("متوسط الحمل خلال آخر", s.averagingMinutes, " د", 1, 1..60) { v -> update { it.copy(averagingMinutes = v) } }
            Note("الوقت المتبقي = الطاقة فوق الاحتياطي ÷ متوسط القدرة الخارجة خلال هذه المدة. الطاقة = السعة المتبقية × (عدد الخلايا × 3.2 فولت).")
        }

        Panel(title = "تنبيه انخفاض الشحن") {
            Toggle("نبّهني عند انخفاض الشحن", s.lowSocAlertEnabled) { v ->
                update { it.copy(lowSocAlertEnabled = v) }
                if (v) onEnableAlert()
            }
            Stepper("عند النزول تحت", s.lowSocThreshold, "٪", 5, 5..90) { v -> update { it.copy(lowSocThreshold = v) } }
            Note("مهم: التنبيه يعمل فقط ما دام التطبيق مفتوحًا أو في الخلفية ومتصلًا بالبطارية. أندرويد قد يوقف التطبيق في الخلفية لتوفير الطاقة، وعندها لن يصلك تنبيه. لا تعتمد عليه وحده.")
        }

        Panel(title = "المظهر") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "حسب الهاتف", ThemeMode.LIGHT to "فاتح", ThemeMode.DARK to "داكن").forEach { (m, label) ->
                    FilterChip(selected = s.theme == m, onClick = { update { it.copy(theme = m) } }, label = { Text(label) })
                }
            }
        }

        Panel(title = "الوضع التجريبي") {
            Toggle("عرض بيانات محاكاة لتجربة الواجهة", mode == SourceMode.DEMO, onToggleDemo)
            Note("يقطع الاتصال بالبطارية ويعرض أرقامًا مولّدة مع شريط تحذير ثابت. لا تُسجَّل هذه الأرقام ولا تُطلق تنبيهات. التطبيق لا ينتقل إلى هذا الوضع من تلقاء نفسه أبدًا.")
        }

        Panel(title = "الخصوصية والأمان") {
            Toggle("إخفاء المعرّفات في التقارير والإعدادات", s.maskIdentifiers) { v -> update { it.copy(maskIdentifiers = v) } }
            Text("• التطبيق بلا حساب وبلا خادم، ولا يملك إذن الإنترنت أصلًا.")
            Text("• القراءات والسجل محفوظة على هذا الهاتف فقط.")
            Text("• للقراءة فقط: التطبيق يرسل طلبات قراءة محددة، ولا يحتوي أي أمر يغيّر إعدادات البطارية أو يشغّل/يوقف الشحن والتفريغ.")
            Text("• إذن البلوتوث يُستخدم للبحث عن البطارية والاتصال بها فقط. في أندرويد 11 وما قبله يشترط النظام إذن الموقع للبحث عن أجهزة البلوتوث؛ التطبيق لا يقرأ موقعك.")
        }
    }
}
