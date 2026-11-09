package batari.app.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import batari.core.ClassicFrameAssembler
import batari.core.ClassicParser
import batari.core.FrameAssembler
import batari.core.Masking
import batari.core.ReadRequest
import batari.core.ReadRequests
import batari.core.UpFrameAssembler
import batari.core.UpPackStatusParser
import batari.core.toHex
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * فحص تشخيصي: يسرد خدمات GATT ثم يرسل طلبات القراءة المسموحة فقط ويسجّل الرد الخام.
 * لا يكتب على أي خاصية غير FF02، ولا يرسل أي أمر تعديل.
 */
object Diagnostics {
    private val DEVICE_INFO = Ble.uuid16(0x180A)
    private val SERIAL_NUMBER = Ble.uuid16(0x2A25)

    suspend fun run(
        context: Context,
        address: String,
        name: String?,
        packAddress: Int,
        mask: Boolean,
        appVersion: String,
        progress: (String) -> Unit,
    ): String {
        val out = StringBuilder()
        fun line(s: String = "") { out.appendLine(s) }
        fun step(s: String) { progress(s) }

        line("=== تقرير فحص الاتصال — بطاريتي $appVersion ===")
        line("الوقت: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        line("أندرويد: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("الجهاز: " + (if (mask) Masking.name(name) else (name ?: "(بلا اسم)")) + " — " + (if (mask) Masking.mac(address) else address))
        line("إخفاء المعرّفات: " + if (mask) "مفعّل" else "معطّل")
        line()

        if (!Ble.hasPermissions(context)) { line("النتيجة: إذن البلوتوث غير ممنوح."); return out.toString() }
        val adapter = Ble.adapter(context)
        if (adapter == null || !adapter.isEnabled) { line("النتيجة: البلوتوث مطفأ."); return out.toString() }
        val session = GattSession(context, adapter.getRemoteDevice(address))
        try {
            step("جارٍ الاتصال…")
            if (!session.connect(15_000)) {
                line("النتيجة: فشل الاتصال (الحالة ${session.lastDisconnectStatus ?: "مهلة"}).")
                line("تأكد أن تطبيق JBD الأصلي مغلق تمامًا وأن الهاتف قريب من البطارية.")
                return out.toString()
            }
            line("[1] الاتصال: نجح")
            step("قراءة الخدمات…")
            val services = session.discoverServices()
            if (services == null) { line("[2] اكتشاف الخدمات: فشل"); return out.toString() }
            line("[2] خدمات GATT (${services.size}):")
            for (s in services) {
                line("  خدمة ${Ble.shortName(s.uuid)}")
                for (c in s.characteristics) line("    خاصية ${Ble.shortName(c.uuid)}  [${props(c)}]")
            }
            line()

            services.firstOrNull { it.uuid == DEVICE_INFO }?.let { info ->
                line("[3] معلومات الجهاز القياسية (180A):")
                for (c in info.characteristics) {
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) continue
                    val v = session.read(c)
                    val text = v?.let { String(it, Charsets.UTF_8).filter { ch -> ch.code in 32..126 } }
                    val shown = when {
                        text == null -> "تعذّرت القراءة"
                        mask && c.uuid == SERIAL_NUMBER -> Masking.name(text)
                        else -> text
                    }
                    line("    ${Ble.shortName(c.uuid)}: $shown")
                }
                line()
            }

            val link = UartLink.find(session, services)
            if (link == null) {
                line("[4] قناة البيانات FF00/FF01/FF02: غير موجودة أو خصائصها مختلفة.")
                line("النتيجة: لم نرسل أي طلب. أرسل هذا التقرير لتحديد القناة الصحيحة.")
                return out.toString()
            }
            line("[4] قناة البيانات: FF00 موجودة — إشعارات FF01 [${props(link.notifyChar)}]، كتابة FF02 [${props(link.writeChar)}]")
            if (!session.enableNotifications(link.notifyChar)) {
                line("    تفعيل الإشعارات: فشل"); return out.toString()
            }
            line("    تفعيل الإشعارات: نجح")
            step("الاستماع للجهاز…")
            delay(1_500)
            val unsolicited = link.drain()
            line("    بيانات وصلت دون طلب خلال 1.5 ث: " + if (unsolicited.isEmpty()) "لا شيء" else "${unsolicited.size} إشعار")
            if (mask && unsolicited.isNotEmpty()) line("      تنبيه: محتوى غير معروف البنية فلم نحجب منه شيئًا — راجعه قبل المشاركة.")
            unsolicited.take(6).forEach { line("      RX ${it.toHex()}") }
            var authSeen = unsolicited.any { isAuthFrame(it) }
            line()

            var upOk = false
            var classicOk = false

            step("اختبار بروتوكول UP…")
            line("[5] اختبار بروتوكول سلسلة UP (عنوان المجموعة $packAddress):")
            when (val r = probe(link, ReadRequests.upPackStatus(packAddress), UpFrameAssembler(packAddress), ::line)) {
                is Transact.Ok -> {
                    upOk = true
                    val payload = r.frame.payload
                    val raw = r.frame.raw.copyOf()
                    if (mask) UpPackStatusParser.modelRange(payload)?.forEach { raw[8 + it] = 0x2A }
                    line("    إطار صحيح: ${raw.size} بايت على ${r.chunks.size} إشعار (أحجامها: ${r.chunks.joinToString(",") { it.size.toString() }})، CRC مطابق")
                    line("    RX ${raw.toHex()}")
                    if (mask) line("    (بايتات الموديل/الرقم التسلسلي استُبدلت بـ 2A)")
                    runCatching { UpPackStatusParser.parse(payload, 0) }.onSuccess { s ->
                        line("    القيم المفكوكة — قارنها بتطبيق JBD في اللحظة نفسها:")
                        line("      الجهد ${s.packVoltage} V | التيار ${s.currentA} A | الشحن ${s.socPercent}% | المتبقي ${s.remainingAh} Ah | الكاملة ${s.fullAh} Ah")
                        line("      الدورات ${s.cycles} | SOH ${s.sohPercent}% | حرارة MOS ${s.mosTempC} | حرارة المحيط ${s.ambientTempC}")
                        line("      الخلايا (${s.cellCount ?: "غير متاح"}): ${s.cellMilliVolts?.joinToString(" ") ?: "غير متاح"} mV")
                        line("      حساسات الحرارة: ${s.cellTempsC?.joinToString(" ") ?: "غير متاح"}")
                        line("      الإصدار ${s.firmware ?: "غير متاح"} | التوازي ${s.parallelCount ?: "غير متاح"} | حمايات ${s.protections?.size} | تنبيهات ${s.alarms?.size}")
                    }.onFailure { line("    تعذّر فك الحمولة: ${it.message}") }
                }
                is Transact.NoFrame -> { authSeen = authSeen || r.chunks.any { isAuthFrame(it) }; noFrame(r, mask, ::line) }
                Transact.WriteFailed -> line("    فشل إرسال الطلب")
                Transact.Disconnected -> { line("    انقطع الاتصال أثناء الاختبار"); return out.toString() }
            }
            line()
            delay(400)

            step("اختبار البروتوكول الكلاسيكي…")
            line("[6] اختبار بروتوكول JBD الكلاسيكي:")
            for (req in listOf(ReadRequests.classicBasicInfo(), ReadRequests.classicCellVoltages())) {
                when (val r = probe(link, req, ClassicFrameAssembler(), ::line)) {
                    is Transact.Ok -> {
                        line("    إطار صحيح: ${r.frame.raw.size} بايت على ${r.chunks.size} إشعار، مجموع التحقق مطابق")
                        line("    RX ${r.frame.raw.toHex()}")
                        if (req.bytes[2].toInt() == ReadRequests.CLASSIC_BASIC_INFO) {
                            classicOk = true
                            runCatching { ClassicParser.parseBasicInfo(r.frame.payload, 0) }.onSuccess { s ->
                                line("    القيم: الجهد ${s.packVoltage} V | التيار ${s.currentA} A | الشحن ${s.socPercent}% | المتبقي ${s.remainingAh} Ah")
                            }
                        }
                    }
                    is Transact.NoFrame -> { authSeen = authSeen || r.chunks.any { isAuthFrame(it) }; noFrame(r, mask, ::line) }
                    Transact.WriteFailed -> line("    فشل إرسال الطلب")
                    Transact.Disconnected -> { line("    انقطع الاتصال أثناء الاختبار"); return out.toString() }
                }
                delay(400)
            }
            line()
            line("=== الخلاصة ===")
            when {
                upOk -> line("الجهاز يردّ ببروتوكول سلسلة UP. التطبيق يستطيع قراءة بياناته.")
                classicOk -> line("الجهاز يردّ ببروتوكول JBD الكلاسيكي. التطبيق يستطيع قراءة بياناته.")
                authSeen -> line("الجهاز أرسل إطارات تبدأ بـ FF AA: يبدو أنه يطلب مصادقة قبل القراءة. المصادقة غير منفذة في هذا الإصدار.")
                else -> line("الجهاز لم يردّ بإطار صحيح على أي من البروتوكولين المعروفين. أرسل هذا التقرير كاملًا.")
            }
        } finally {
            session.close()
        }
        return out.toString()
    }

    private suspend fun probe(link: UartLink, req: ReadRequest, assembler: FrameAssembler, line: (String) -> Unit): Transact {
        line("  ${req.label}")
        line("    TX ${req.bytes.toHex()}")
        return link.transact(req, assembler, 3_000)
    }

    private fun noFrame(r: Transact.NoFrame, mask: Boolean, line: (String) -> Unit) {
        if (r.chunks.isEmpty()) { line("    لا رد خلال 3 ثوانٍ"); return }
        line("    وصل ${r.chunks.size} إشعار (${r.chunks.sumOf { it.size }} بايت) دون إطار صحيح")
        r.errors.distinct().take(3).forEach { line("    سبب: $it") }
        if (mask) line("    تنبيه: الرد التالي غير معروف البنية فلم نستطع حجب أي معرّف فيه — راجعه قبل المشاركة.")
        r.chunks.take(12).forEach { line("    RX ${it.toHex()}") }
    }

    private fun isAuthFrame(b: ByteArray) = b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xAA.toByte()

    private fun props(c: BluetoothGattCharacteristic): String {
        val p = c.properties
        return buildList {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("قراءة")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("كتابة")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("كتابة بلا رد")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("إشعار")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("إشارة")
        }.joinToString("، ")
    }
}
