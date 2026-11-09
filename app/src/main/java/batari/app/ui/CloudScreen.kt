package batari.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import batari.app.CloudState

@Composable
fun CloudScreen(
    state: CloudState,
    onLoadCaptcha: () -> Unit,
    onLogin: (String, String, String) -> Unit,
    onDiagnose: () -> Unit,
    onLogout: () -> Unit,
    onCloseReport: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.busy != null) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text(state.busy)
                }
            }
        }
        if (state.error != null) Panel(container = MaterialTheme.colorScheme.errorContainer) { Text("⚠ ${state.error}") }

        if (!state.signedIn) {
            var user by rememberSaveable { mutableStateOf("") }
            var pass by remember { mutableStateOf("") } // لا تُحفظ حتى عند تدوير الشاشة
            var code by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(Unit) { if (state.captcha == null) onLoadCaptcha() }
            Panel(title = "الدخول بحساب JBD") {
                Note("البريد وكلمة المرور نفساهما في تطبيق JBD BMS. تُرسل كلمة المرور مشفّرة إلى خادم JBD فقط ولا تُحفظ على الهاتف.")
                OutlinedTextField(user, { user = it }, label = { Text("البريد الإلكتروني") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bmp = remember(state.captcha) { state.captcha?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "رمز التحقق", modifier = Modifier.height(48.dp))
                    else Text("لا يوجد رمز تحقق بعد")
                    OutlinedButton(onClick = { code = ""; onLoadCaptcha() }) { Text("رمز آخر") }
                }
                OutlinedTextField(code, { code = it }, label = { Text("اكتب الرمز الظاهر في الصورة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { onLogin(user, pass, code); code = "" },
                    enabled = state.busy == null && state.captchaUuid != null && user.isNotBlank() && pass.isNotEmpty() && code.isNotBlank(),
                ) { Text("دخول") }
            }
            return@Column
        }

        Panel(title = "✓ مسجّل الدخول في سيرفر البطارية") {
            Text("هذه المرحلة للتحقق فقط: شغّل الفحص وأرسل التقرير، وبعدها تُضاف القراءة الحية من السيرفر إلى الشاشة الرئيسية.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDiagnose, enabled = state.busy == null) { Text("فحص السيرفر") }
                OutlinedButton(onClick = onLogout) { Text("حذف الدخول من هذا الهاتف") }
            }
            Note("الفحص يطلب القراءة فقط، والتطبيق لا يستطيع طلب أي مسار تعديل من الخادم.")
        }

        if (state.report != null) {
            Panel(title = "تقرير فحص السيرفر") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.report), "مشاركة التقرير"))
                    }) { Text("مشاركة") }
                    OutlinedButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("تقرير فحص السيرفر", state.report))
                    }) { Text("نسخ") }
                    OutlinedButton(onClick = onCloseReport) { Text("إغلاق") }
                }
                Note("المعرّفات (العنوان، الاسم، البريد، الرقم التسلسلي) محجوبة آليًا إن كان الإخفاء مفعّلًا. راجع التقرير قبل المشاركة.")
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Text(state.report, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp).fillMaxWidth())
                }
            }
        }
    }
}
