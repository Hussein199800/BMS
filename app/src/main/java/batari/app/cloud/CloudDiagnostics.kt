package batari.app.cloud

import batari.core.CloudCrypto
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** فحص السيرفر: يطلب مسارات القراءة فقط ويكتب الردود الخام (مع حجب المعرّفات) لنعرف شكل البيانات ووحداتها. */
object CloudDiagnostics {
    suspend fun run(token: String, mask: Boolean, appVersion: String, progress: (String) -> Unit): String {
        val out = StringBuilder()
        fun line(s: String = "") { out.appendLine(s) }
        fun show(title: String, r: HttpResult) {
            line("$title → HTTP ${r.code}")
            val body = if (mask) CloudCrypto.maskJson(r.body) else r.body
            line(body.take(6000) + if (body.length > 6000) "\n…(مقتطع، الطول ${body.length})" else "")
            line()
        }
        line("=== تقرير فحص السيرفر — بطاريتي $appVersion ===")
        line("الوقت: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        line("الخادم: ${JbdCloud.BASE}")
        line("إخفاء المعرّفات: " + if (mask) "مفعّل" else "معطّل")
        line("(رمز الدخول وكلمة المرور غير موجودين في هذا التقرير)")
        line()

        progress("قراءة قائمة الأجهزة…")
        val list = JbdCloud.request("GET", "bluetooth/server/device/myList", token)
        show("[1] قائمة الأجهزة", list)
        if (list.code == 401 || list.code == 403) { line("النتيجة: انتهت صلاحية الدخول. سجّل الدخول من جديد."); return out.toString() }
        val devices = JbdCloud.deviceArray(list.body)
        if (devices == null || devices.length() == 0) { line("النتيجة: لا توجد أجهزة في الحساب أو شكل الرد غير متوقع."); return out.toString() }
        val dev = devices.getJSONObject(0)
        val mac = dev.optString("macAddr")
        line("عدد الأجهزة: ${devices.length()} — نفحص الأول. مفاتيحه: ${dev.keys().asSequence().joinToString(",")}")
        line()
        if (mac.isEmpty()) { line("النتيجة: لم نجد macAddr في الجهاز الأول."); return out.toString() }
        val m = JbdCloud.enc(mac)

        progress("البيانات الحية…")
        show("[2] realtimeInfo", JbdCloud.request("GET", "bluetooth/server/device/realtimeInfo?deviceAddr=$m", token))

        progress("طلب قراءة حديثة للملخص…")
        val t0 = System.currentTimeMillis()
        val rc = JbdCloud.request("POST", "bluetooth/server/ess/readCollectInfo", token, JSONObject().put("macAddr", mac))
        show("[3] readCollectInfo", rc)
        line("    انتظار التنفيذ: " + waitBatch(token, rc) + " (${System.currentTimeMillis() - t0} مللي ثانية)")
        show("[4] collectInfo", JbdCloud.request("GET", "bluetooth/server/ess/collectInfo?macAddr=$m", token))

        progress("طلب قراءة حديثة لبيانات المجموعة 1…")
        val t1 = System.currentTimeMillis()
        val rb = JbdCloud.request("POST", "bluetooth/server/ess/readBaseInfo", token, JSONObject().put("macAddr", mac).put("addrCode", 1))
        show("[5] readBaseInfo", rb)
        line("    انتظار التنفيذ: " + waitBatch(token, rb) + " (${System.currentTimeMillis() - t1} مللي ثانية)")
        show("[6] baseInfo", JbdCloud.request("GET", "bluetooth/server/ess/baseInfo?macAddr=$m&addrCode=1", token))

        line("=== انتهى. أرسل هذا التقرير كاملًا، ومعه لقطة من تطبيق JBD في الدقيقة نفسها إن أمكن. ===")
        return out.toString()
    }

    /** كما يفعل التطبيق الرسمي: حتى 6 استعلامات بفاصل 800 مللي ثانية. */
    private suspend fun waitBatch(token: String, r: HttpResult): String {
        val j = r.json ?: return "لا يوجد batchId (رد غير JSON)"
        val batch = j.optString("batchId").ifEmpty { j.optJSONObject("data")?.optString("batchId").orEmpty() }
        if (batch.isEmpty()) return "لا يوجد batchId"
        repeat(6) {
            delay(800)
            val s = JbdCloud.request("GET", "bluetooth/server/cmd/execStatus?batchId=${JbdCloud.enc(batch)}", token)
            val body = s.body.trim()
            val done = body == "true" || s.json?.opt("data")?.let { it != JSONObject.NULL && it != false && it.toString() != "" } == true
            if (done) return "اكتمل بعد ${it + 1} استعلام (الرد: ${body.take(120)})"
            if (it == 5) return "لم يكتمل (آخر رد: ${body.take(120)})"
        }
        return "لم يكتمل"
    }
}
