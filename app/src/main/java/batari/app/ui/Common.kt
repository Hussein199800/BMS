package batari.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val NA = "غير متاح"

/** يعزل الأرقام/الرموز اللاتينية لتُعرض بترتيبها الصحيح داخل نص عربي (مثل الإشارة السالبة). */
fun ltr(s: String) = "⁦$s⁩"
fun num(v: Double, digits: Int): String = ltr(String.format(Locale.US, "%.${digits}f", v))
fun num(v: Int): String = ltr(v.toString())

fun volts(v: Double?) = v?.let { "${num(it, 2)} فولت" } ?: NA
fun amps(v: Double?) = v?.let { "${num(it, 1)} أمبير" } ?: NA
fun ampHours(v: Double?) = v?.let { "${num(it, 2)} أمبير·ساعة" } ?: NA
fun celsius(v: Double?) = v?.let { "${num(it, 1)} °م" } ?: NA
fun percent(v: Double?) = v?.let { "${num(it, 0)}٪" } ?: NA
fun kilowatts(absW: Double?) = absW?.let { "${num(it / 1000.0, 2)} كيلوواط" } ?: NA
fun kilowattHours(wh: Double?) = wh?.let { "${num(it / 1000.0, 2)} كيلوواط·ساعة" } ?: NA
fun onOff(b: Boolean?) = when (b) { true -> "✓ مفعّل"; false -> "✕ متوقف"; null -> NA }

fun duration(hours: Double): String {
    val totalMin = (hours * 60).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h >= 48 -> "أكثر من ${num(48)} ساعة"
        h == 0 -> "${num(m)} دقيقة"
        else -> "${num(h)} س ${num(m)} د"
    }
}

fun ageText(ageMs: Long): String {
    val s = ageMs / 1000
    return when {
        s < 4 -> "الآن"
        s < 60 -> "قبل ${num(s.toInt())} ثانية"
        s < 3600 -> "قبل ${num((s / 60).toInt())} دقيقة"
        else -> "قبل ${num((s / 3600).toInt())} ساعة"
    }
}

fun clock(ms: Long, pattern: String = "HH:mm:ss"): String = ltr(SimpleDateFormat(pattern, Locale.US).format(Date(ms)))

object Glossary {
    val entries: Map<String, Pair<String, String>> = mapOf(
        "soc" to ("نسبة الشحن (SOC)" to "كم بقي في البطارية من سعتها الكاملة، كما يحسبها نظام البطارية نفسه بعدّ التيار الداخل والخارج. قد تنحرف قليلًا مع الوقت وتُصحَّح عند اكتمال الشحن."),
        "power" to ("القدرة" to "معدل الطاقة الداخلة إلى البطارية أو الخارجة منها الآن = الجهد × التيار. هذه قدرة البطارية على جانب التيار المستمر؛ ما تستهلكه أجهزة المنزل فعليًا يختلف قليلًا بسبب خسائر العاكس، وقد يختلف كثيرًا إن كانت الألواح الشمسية تغذّي الحمل أيضًا."),
        "voltage" to ("جهد البطارية" to "مجموع جهود الخلايا. يرتفع عند الشحن وينخفض عند التفريغ والحمل العالي. لا يصلح وحده لمعرفة نسبة الشحن بدقة في بطاريات الليثيوم فوسفات."),
        "current" to ("التيار" to "الإشارة الموجبة تعني شحنًا، والسالبة تعني تفريغًا — كما يعرضها تطبيق البطارية الأصلي."),
        "energy" to ("الطاقة المتبقية التقديرية" to "تقدير وليس قياسًا: السعة المتبقية (أمبير·ساعة) × الجهد الاسمي للبطارية (عدد الخلايا × 3.2 فولت). الطاقة الفعلية التي تحصل عليها أقل قليلًا بسبب الخسائر."),
        "time" to ("الوقت المتبقي التقديري" to "الطاقة القابلة للاستخدام فوق الاحتياطي الذي اخترته ÷ متوسط الحمل خلال الدقائق الأخيرة. هو تقدير يتغير مع تغيّر الحمل، ولا يُعرض عندما يكون الحمل قريبًا من الصفر أو القراءات غير كافية. الاحتياطي للحساب فقط ولا يغيّر شيئًا في البطارية."),
        "celltemp" to ("حرارة الخلايا" to "قراءة حساسات الحرارة الموضوعة على الخلايا داخل البطارية."),
        "mos" to ("مفاتيح القدرة (MOS)" to "مفاتيح إلكترونية داخل نظام البطارية يمرّ عبرها كل التيار، ويفصل بها النظام الشحن أو التفريغ عند الخطر. حرارتها أعلى من حرارة الخلايا عادةً لأن التيار يمرّ فيها، ولذلك تُعرض منفصلة."),
        "bms" to ("نظام إدارة البطارية (BMS)" to "اللوحة الإلكترونية داخل البطارية التي تقيس الخلايا وتحميها. كل ما يعرضه هذا التطبيق مصدره هذه اللوحة."),
        "rated" to ("السعة الاسمية" to "السعة المكتوبة في إعدادات نظام البطارية كسعة تصميمية."),
        "full" to ("السعة الكاملة المبلغ عنها" to "ما يعتبره نظام البطارية حاليًا سعة الشحن الكامل. قد تختلف عن الاسمية مع تقادم البطارية أو حسب إعدادات المصنع."),
        "remaining" to ("السعة المتبقية" to "كمية الشحنة الباقية بالأمبير·ساعة كما يحسبها نظام البطارية."),
        "cycles" to ("عدد الدورات" to "عدّاد يبلّغ عنه نظام البطارية. طريقة احتسابه للدورة الواحدة يحددها المصنّع وغير موثقة لدينا، فاعتبره مؤشرًا تقريبيًا للاستخدام."),
        "soh" to ("الصحة المبلغ عنها (SOH)" to "رقم يحسبه نظام البطارية نفسه لحالة البطارية مقارنةً بالجديدة. ليس نتيجة فحص مستقل من هذا التطبيق."),
        "protect" to ("الحماية" to "عندما يتجاوز قياسٌ حدًّا مضبوطًا في نظام البطارية، يفصل النظام الشحن أو التفريغ ويرفع إشارة حماية. التطبيق يعرض هذه الإشارات كما هي ولا يستطيع تغييرها."),
        "switches" to ("مفتاحا الشحن والتفريغ" to "يبيّنان هل يسمح نظام البطارية حاليًا بمرور تيار الشحن وتيار التفريغ. للعرض فقط — هذا التطبيق لا يتحكم بهما."),
        "limiting" to ("تحديد التيار" to "وضع يخفّض فيه نظام البطارية تيار الشحن عبر دارة خاصة بدل فصله كليًا."),
        "limits" to ("حدود يطلبها نظام البطارية" to "قيم الجهد والتيار التي يبلّغ بها نظام البطارية العاكسَ كحدود للشحن والتفريغ. للعرض فقط."),
        "delta" to ("فرق الخلايا" to "الفرق بين أعلى وأدنى خلية بالملي فولت (جزء من ألف من الفولت). فرق صغير أمر طبيعي ويتغير مع الحمل ونسبة الشحن، ولا يُحكم على خلية من قراءة واحدة."),
        "balance" to ("الموازنة" to "عملية يقرّب بها نظام البطارية جهود الخلايا من بعضها، وتعمل عادةً قرب نهاية الشحن فقط. عدم نشاطها ليس عطلًا."),
        "ambient" to ("حرارة المحيط" to "حساس حرارة على لوحة نظام البطارية يقيس الحرارة داخل الصندوق."),
        "parallel" to ("عدد التوازي" to "عدد البطاريات المربوطة معًا على التوازي كما يراها نظام البطارية."),
        "protocol" to ("البروتوكول" to "صيغة الرسائل التي تعرّف عليها التطبيق عند الاتصال بالبطارية."),
    )
}

@Composable
fun InfoButton(key: String) {
    val entry = Glossary.entries[key] ?: return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = "ماذا يعني «${entry.first}»؟", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("فهمت") } },
            title = { Text("ماذا يعني «${entry.first}»؟", style = MaterialTheme.typography.titleMedium) },
            text = { Text(entry.second) },
        )
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    infoKey: String? = null,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (infoKey != null) InfoButton(infoKey)
                }
            }
            content()
        }
    }
}

@Composable
fun Tile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, infoKey: String? = null) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (infoKey != null) InfoButton(infoKey)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, infoKey: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (infoKey != null) InfoButton(infoKey) else Spacer(Modifier.width(4.dp))
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
