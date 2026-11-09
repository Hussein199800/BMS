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
