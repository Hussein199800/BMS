package batari.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import batari.app.DiagState
import batari.app.ble.Ble
import batari.app.ble.ScanItem
import batari.app.data.AppSettings

@Composable
fun ConnectScreen(
    settings: AppSettings,
    items: List<ScanItem>,
    scanning: Boolean,
    scanError: String?,
    diag: DiagState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String, String?) -> Unit,
    onDiagnose: (String, String?) -> Unit,
    onCloseDiag: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Ble.hasPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Ble.hasPermissions(context)
        if (granted) onScan()
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (diag) {
            is DiagState.Running -> {
                Panel(title = "فحص الاتصال جارٍ") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(diag.step)
                    }
                    Note("يستغرق نحو نصف دقيقة. أبقِ الهاتف قرب البطارية.")
                }
                return@Column
            }
            is DiagState.Done -> {
                Panel(title = "تقرير فحص الاتصال") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, diag.report), "مشاركة التقرير"))
                        }) { Text("مشاركة") }
                        OutlinedButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("تقرير فحص الاتصال", diag.report))
                        }) { Text("نسخ") }
                        OutlinedButton(onClick = onCloseDiag) { Text("إغلاق") }
                    }
                    Note(if (settings.maskIdentifiers) "العنوان والاسم والرقم التسلسلي محجوبة في التقرير. راجعه قبل المشاركة." else "تنبيه: إخفاء المعرّفات معطّل، والتقرير يحتوي العنوان والاسم كاملين.")
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text(diag.report, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp).fillMaxWidth())
                    }
                }
                return@Column
            }
            DiagState.Idle -> Unit
        }

        if (!granted) {
            Panel(title = "نحتاج إذن البلوتوث") {
                Text(
                    if (android.os.Build.VERSION.SDK_INT >= 31)
                        "للبحث عن البطارية والاتصال بها يحتاج التطبيق إذن «الأجهزة المجاورة» (البلوتوث). لا يُستخدم لتحديد موقعك."
                    else
                        "في إصدار أندرويد على هاتفك، يشترط النظام إذن «الموقع» للبحث عن أجهزة البلوتوث. التطبيق لا يقرأ موقعك ولا يحفظه."
                )
                Button(onClick = { launcher.launch(Ble.requiredPermissions()) }) { Text("منح الإذن") }
            }
            return@Column
        }

        Panel(title = "البحث عن البطارية") {
            Text("قبل البدء: أغلق تطبيق JBD الأصلي تمامًا، فالبطارية تقبل اتصالًا واحدًا في كل مرة.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scanning) {
                    OutlinedButton(onClick = onStopScan) { Text("إيقاف البحث") }
                    CircularProgressIndicator(Modifier.padding(4.dp))
                } else Button(onClick = onScan) { Text("ابحث عن الأجهزة القريبة") }
            }
            if (scanError != null) Text("⚠ $scanError", color = MaterialTheme.colorScheme.error)
            Note("«اتصال» يبدأ المراقبة مباشرة. «فحص» يكتب تقريرًا تقنيًا تستطيع مشاركته إن لم يعمل الاتصال.")
        }

        if (items.isEmpty() && !scanning && settings.deviceAddress != null) {
            DeviceCard(ScanItem(settings.deviceAddress, settings.deviceName, 0, false), saved = true, showRssi = false, onConnect, onDiagnose)
        }
        items.forEach { DeviceCard(it, saved = it.address == settings.deviceAddress, showRssi = true, onConnect, onDiagnose) }
        if (items.isEmpty() && scanning) Note("جارٍ البحث… إن لم تظهر البطارية، اقترب منها وتأكد أن البلوتوث يعمل.")
    }
}

@Composable
private fun DeviceCard(item: ScanItem, saved: Boolean, showRssi: Boolean, onConnect: (String, String?) -> Unit, onDiagnose: (String, String?) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (item.advertisesUart || saved) 2.dp else 1.dp, if (item.advertisesUart || saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name ?: "(جهاز بلا اسم)", fontWeight = FontWeight.Bold)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(item.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
            }
            if (showRssi) {
                val q = when { item.rssi >= -65 -> "قوية"; item.rssi >= -80 -> "متوسطة"; else -> "ضعيفة" }
                Text("قوة الإشارة: $q (${num(item.rssi)} dBm)", style = MaterialTheme.typography.bodySmall)
            }
            if (saved) Text("★ البطارية المحفوظة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (item.advertisesUart) Text("✓ يعلن عن خدمة البيانات FF00 (محتمل أن يكون نظام بطارية JBD)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConnect(item.address, item.name) }) { Text("اتصال") }
                OutlinedButton(onClick = { onDiagnose(item.address, item.name) }) { Text("فحص الاتصال") }
            }
        }
    }
}
