package batari.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import batari.app.ble.BleScanner
import batari.app.ble.Diagnostics
import batari.app.data.SourceMode
import batari.core.BatterySnapshot
import batari.core.Csv
import batari.core.HistorySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloudState(
    val signedIn: Boolean = false,
    val captcha: ByteArray? = null,
    val captchaUuid: String? = null,
    val busy: String? = null,
    val error: String? = null,
    val report: String? = null,
)

sealed interface DiagState {
    data object Idle : DiagState
    data class Running(val step: String) : DiagState
    data class Done(val report: String) : DiagState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val scanner = BleScanner(app, viewModelScope)

    val settings = repo.settings.flow
    val mode = repo.mode
    val link = repo.connection.state
    val linkError = repo.connection.lastError

    /** اللقطة المعروضة: من المصدر الحالي فقط — لا خلط بين الحقيقي والتجريبي. */
    val snapshot: StateFlow<BatterySnapshot?> =
        combine(repo.mode, repo.realSnapshot, repo.demoSnapshot) { m, real, demo -> if (m == SourceMode.DEMO) demo else real }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val diag = MutableStateFlow<DiagState>(DiagState.Idle)
    val history = MutableStateFlow<List<HistorySample>>(emptyList())

    fun autoConnectIfPossible() {
        val s = settings.value
        if (s.autoConnect && s.deviceAddress != null && mode.value == SourceMode.REAL &&
            link.value is batari.app.ble.LinkState.Idle && batari.app.ble.Ble.hasPermissions(getApplication())
        ) repo.connect(s.deviceAddress, s.deviceName)
    }

    fun connect(address: String, name: String?) { scanner.stop(); repo.connect(address, name) }
    fun disconnect() = repo.disconnect()
    fun startDemo() = repo.startDemo()
    fun stopDemo() = repo.stopDemo()

    fun runDiagnostics(address: String, name: String?) {
        if (diag.value is DiagState.Running) return
        scanner.stop()
        repo.stopDemo()
        repo.disconnect()
        diag.value = DiagState.Running("جارٍ التحضير…")
        viewModelScope.launch {
            val s = settings.value
            val version = runCatching {
                getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName
            }.getOrNull() ?: "?"
            val report = runCatching {
                withContext(Dispatchers.Default) {
                    kotlinx.coroutines.delay(800) // مهلة قصيرة ليُغلق الاتصال السابق
                    Diagnostics.run(getApplication(), address, name, s.packAddress, s.maskIdentifiers, version) {
                        diag.value = DiagState.Running(it)
                    }
                }
            }.getOrElse { "فشل الفحص بخطأ غير متوقع: ${it.javaClass.simpleName}: ${it.message}" }
            diag.value = DiagState.Done(report)
        }
    }

    // ——— سيرفر البطارية ———
    private val tokens = batari.app.cloud.TokenStore(app)
    val cloud = MutableStateFlow(CloudState(signedIn = tokens.load() != null))

    private fun appVersion(): String = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun loadCaptcha() {
        if (cloud.value.busy != null) return
        cloud.value = cloud.value.copy(busy = "جلب رمز التحقق…", error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { batari.app.cloud.JbdCloud.captcha() } }
            cloud.value = r.fold(
                { cloud.value.copy(busy = null, captcha = it.png, captchaUuid = it.uuid) },
                { cloud.value.copy(busy = null, error = "تعذّر الوصول إلى الخادم. تأكد من الإنترنت. (${it.javaClass.simpleName})") },
            )
        }
    }

    fun cloudLogin(username: String, password: String, code: String) {
        val uuid = cloud.value.captchaUuid ?: return
        if (cloud.value.busy != null) return
        cloud.value = cloud.value.copy(busy = "تسجيل الدخول…", error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { batari.app.cloud.JbdCloud.login(username, password, code, uuid).getOrThrow() }
            }
            r.onSuccess { tokens.save(it); cloud.value = CloudState(signedIn = true) }
                .onFailure { cloud.value = cloud.value.copy(busy = null, error = it.message ?: "فشل الدخول", captcha = null, captchaUuid = null); loadCaptcha() }
        }
    }

    /** يحذف الرمز من هذا الهاتف فقط — لا يُخرجك من تطبيق JBD الرسمي. */
    fun cloudLogout() { tokens.clear(); cloud.value = CloudState() }

    fun runCloudDiagnostics() {
        val token = tokens.load() ?: run { cloud.value = CloudState(error = "انتهت الجلسة. سجّل الدخول من جديد."); return }
        if (cloud.value.busy != null) return
        cloud.value = cloud.value.copy(busy = "جارٍ الفحص…", error = null, report = null)
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    batari.app.cloud.CloudDiagnostics.run(token, settings.value.maskIdentifiers, appVersion()) { cloud.value = cloud.value.copy(busy = it) }
                }.getOrElse { "فشل الفحص: ${it.javaClass.simpleName}: ${it.message}" }
            }
            cloud.value = cloud.value.copy(busy = null, report = report)
        }
    }

    fun clearDiagnostics() { diag.value = DiagState.Idle }

    fun loadHistory(rangeMs: Long) {
        viewModelScope.launch {
            history.value = withContext(Dispatchers.IO) { repo.historySince(System.currentTimeMillis() - rangeMs) }
        }
    }

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) { Csv.document(repo.historySince(0)) }

    fun clearHistory() {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.history.clear() }; history.value = emptyList() }
    }

    override fun onCleared() { scanner.stop() }
}
