package batari.app.ble

import android.content.Context
import batari.app.data.SettingsStore
import batari.core.BatterySnapshot
import batari.core.ClassicFrameAssembler
import batari.core.ClassicParser
import batari.core.ParseException
import batari.core.PollHealth
import batari.core.ProtocolKind
import batari.core.ReadRequests
import batari.core.ReconnectPolicy
import batari.core.UpFrameAssembler
import batari.core.UpPackStatusParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data object Discovering : LinkState
    data object Probing : LinkState
    data class Live(val protocol: ProtocolKind) : LinkState
    data class Waiting(val seconds: Int, val reason: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

/** مصدر البيانات الحقيقي: اتصال، كشف البروتوكول، استطلاع دوري للقراءة فقط، وإعادة اتصال تدريجية. */
class BmsConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val onSnapshot: (BatterySnapshot) -> Unit,
) {
    val state = MutableStateFlow<LinkState>(LinkState.Idle)
    val lastError = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var knownProtocol: ProtocolKind? = null
    private var softFailures = 0

    private sealed interface Outcome {
        data class Retry(val reason: String) : Outcome
        data class Fatal(val reason: String) : Outcome
    }

    fun start(address: String) {
        stop()
        knownProtocol = null
        softFailures = 0
        job = scope.launch {
            val policy = ReconnectPolicy()
            while (isActive) {
                when (val o = runSession(address) { policy.reset(); softFailures = 0 }) {
                    is Outcome.Fatal -> { state.value = LinkState.Failed(o.reason); return@launch }
                    is Outcome.Retry -> {
                        lastError.value = o.reason
                        var left = (policy.nextDelayMs() / 1000).toInt()
                        while (left > 0) { state.value = LinkState.Waiting(left, o.reason); delay(1000); left-- }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        state.value = LinkState.Idle
    }

    private suspend fun runSession(address: String, onLive: () -> Unit): Outcome {
        if (!Ble.hasPermissions(context)) return Outcome.Fatal("إذن البلوتوث غير ممنوح.")
        val adapter = Ble.adapter(context)
        if (adapter == null || !adapter.isEnabled) return Outcome.Fatal("البلوتوث مطفأ. شغّله ثم اضغط «اتصال».")
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return Outcome.Fatal("عنوان الجهاز المحفوظ غير صالح.")
        val session = GattSession(context, device)
        try {
            state.value = LinkState.Connecting
            if (!session.connect(15_000)) return Outcome.Retry("تعذّر الاتصال بالجهاز (قد يكون بعيدًا أو متصلًا بتطبيق آخر).")
            state.value = LinkState.Discovering
            delay(600) // بعض الوحدات تُرجع قائمة خدمات ناقصة إن طُلبت فور الاتصال
            var link: UartLink? = null
            for (attempt in 0 until 3) {
                val services = session.discoverServices() ?: return Outcome.Retry("تعذّر قراءة خدمات الجهاز.")
                link = UartLink.find(session, services)
                if (link != null || !session.connected) break
                delay(1_000)
            }
            if (link == null) {
                // لا نعتبره عطلًا نهائيًا إن سبق أن قرأنا من هذا الجهاز، أو قبل تكرار الفشل ثلاث جلسات.
                return if (knownProtocol != null || ++softFailures < 3) Outcome.Retry("لم تظهر خدمة البيانات بعد — نعيد المحاولة.")
                else Outcome.Fatal("الجهاز لا يحتوي خدمة البيانات المتوقعة (FF00/FF01/FF02). شغّل «فحص الاتصال» وأرسل التقرير.")
            }
            if (!session.enableNotifications(link.notifyChar)) return Outcome.Retry("تعذّر تفعيل استقبال البيانات.")
            delay(400)
            state.value = LinkState.Probing
            val protocol = knownProtocol ?: probe(link)
            if (protocol == null) {
                return if (session.connected && ++softFailures >= 3)
                    Outcome.Fatal("اتصلنا بالجهاز لكنه لم يردّ على طلبات القراءة المعروفة. قد يحتاج مصادقة أو يستخدم بروتوكولًا مختلفًا. شغّل «فحص الاتصال» وأرسل التقرير.")
                else Outcome.Retry("لم يكتمل التعرف على الجهاز — نعيد المحاولة.")
            }
            knownProtocol = protocol
            state.value = LinkState.Live(protocol)
            lastError.value = null
            onLive()
            val health = PollHealth()
            while (true) {
                val snap = poll(link, protocol)
                if (!session.connected) return Outcome.Retry("انقطع الاتصال بالبطارية.")
                if (snap != null) { health.success(); lastError.value = null; onSnapshot(snap) }
                else if (health.failure()) return Outcome.Retry("الجهاز توقف عن الرد.")
                delay(settings.value.pollSeconds.coerceIn(2, 60) * 1000L)
            }
        } finally {
            session.close()
        }
    }

    private suspend fun probe(link: UartLink): ProtocolKind? {
        repeat(2) {
            if (pollUp(link) != null) return ProtocolKind.UP_SERIES
            delay(300)
            if (pollClassicBasic(link) != null) return ProtocolKind.CLASSIC
            delay(300)
        }
        return null
    }

    /** محاولة واحدة + إعادة محاولة واحدة فقط — لا إغراق للجهاز. */
    private suspend fun poll(link: UartLink, protocol: ProtocolKind): BatterySnapshot? {
        repeat(2) { attempt ->
            val s = if (protocol == ProtocolKind.UP_SERIES) pollUp(link) else pollClassic(link)
            if (s != null || !link.session.connected) return s
            if (attempt == 0) delay(500)
        }
        return null
    }

    private suspend fun pollUp(link: UartLink): BatterySnapshot? {
        val addr = settings.value.packAddress
        return when (val r = link.transact(ReadRequests.upPackStatus(addr), UpFrameAssembler(addr), TIMEOUT_MS)) {
            is Transact.Ok -> try {
                UpPackStatusParser.parse(r.frame.payload, System.currentTimeMillis())
            } catch (e: ParseException) { lastError.value = e.message; null }
            is Transact.NoFrame -> { lastError.value = r.errors.lastOrNull() ?: "لا رد خلال المهلة"; null }
            else -> null
        }
    }

    private suspend fun pollClassicBasic(link: UartLink): BatterySnapshot? =
        when (val r = link.transact(ReadRequests.classicBasicInfo(), ClassicFrameAssembler(), TIMEOUT_MS)) {
            is Transact.Ok -> try {
                if (r.frame.function != ReadRequests.CLASSIC_BASIC_INFO) null
                else ClassicParser.parseBasicInfo(r.frame.payload, System.currentTimeMillis())
            } catch (e: ParseException) { lastError.value = e.message; null }
            is Transact.NoFrame -> { lastError.value = r.errors.lastOrNull() ?: "لا رد خلال المهلة"; null }
            else -> null
        }

    private suspend fun pollClassic(link: UartLink): BatterySnapshot? {
        val basic = pollClassicBasic(link) ?: return null
        delay(150)
        val cells = (link.transact(ReadRequests.classicCellVoltages(), ClassicFrameAssembler(), TIMEOUT_MS) as? Transact.Ok)
            ?.takeIf { it.frame.function == ReadRequests.CLASSIC_CELL_VOLTAGES }
            ?.let { runCatching { ClassicParser.parseCellVoltages(it.frame.payload) }.getOrNull() }
        return basic.copy(cellMilliVolts = cells) // إن لم تصل جهود الخلايا تبقى «غير متاح»
    }

    companion object { const val TIMEOUT_MS = 2_500L }
}
