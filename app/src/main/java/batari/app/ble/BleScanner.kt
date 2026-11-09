package batari.app.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ScanItem(val address: String, val name: String?, val rssi: Int, val advertisesUart: Boolean)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context, private val scope: CoroutineScope) {
    val items = MutableStateFlow<List<ScanItem>>(emptyList())
    val scanning = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var stopJob: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uart = result.scanRecord?.serviceUuids?.contains(ParcelUuid(UartLink.SERVICE)) == true
            val item = ScanItem(result.device.address, result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull(), result.rssi, uart)
            items.value = (items.value.filter { it.address != item.address } + item)
                .sortedWith(compareByDescending<ScanItem> { it.advertisesUart }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
            error.value = "فشل البحث (الرمز $errorCode). أطفئ البلوتوث وشغّله ثم حاول مجددًا."
        }
    }

    fun start() {
        error.value = null
        if (!Ble.hasPermissions(context)) { error.value = "إذن البلوتوث غير ممنوح."; return }
        val adapter = Ble.adapter(context)
        if (adapter == null || !adapter.isEnabled) { error.value = "البلوتوث مطفأ. شغّله من إعدادات الهاتف."; return }
        val scanner = adapter.bluetoothLeScanner ?: run { error.value = "البحث غير متاح على هذا الهاتف."; return }
        stop()
        items.value = emptyList()
        runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        }.onFailure { error.value = "تعذّر بدء البحث: ${it.message}"; return }
        scanning.value = true
        stopJob = scope.launch { delay(20_000); stop() }
    }

    fun stop() {
        stopJob?.cancel(); stopJob = null
        if (scanning.value) runCatching { Ble.adapter(context)?.bluetoothLeScanner?.stopScan(callback) }
        scanning.value = false
    }
}
