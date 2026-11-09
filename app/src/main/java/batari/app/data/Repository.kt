package batari.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import batari.app.MainActivity
import batari.app.ble.BmsConnection
import batari.core.BatterySnapshot
import batari.core.HistorySample
import batari.core.LoadAverage
import batari.core.LoadAverager
import batari.core.LowSocAlert
import batari.core.Estimates
import batari.core.Simulator
import batari.core.toHistorySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SourceMode { REAL, DEMO }

/**
 * يفصل المصدر الحقيقي عن المحاكي: لكلٍّ منهما لقطته ومتوسطه، والسجل والتنبيهات للحقيقي فقط.
 * التطبيق يبدأ دائمًا في الوضع الحقيقي ولا ينتقل إلى التجريبي إلا بضغطة من المستخدم.
 */
class Repository(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val history = HistoryDb(context)

    val mode = MutableStateFlow(SourceMode.REAL)
    val realSnapshot = MutableStateFlow<BatterySnapshot?>(null)
    val demoSnapshot = MutableStateFlow<BatterySnapshot?>(null)

    private val realAverager = LoadAverager(5 * 60_000L)
    private val demoAverager = LoadAverager(5 * 60_000L)
    private val lowSoc = LowSocAlert()
    private var lastHistoryMs = 0L
    private var demoJob: Job? = null

    val connection = BmsConnection(context, scope, settings, ::onRealSnapshot)

    private fun onRealSnapshot(s: BatterySnapshot) {
        realSnapshot.value = s
        Estimates.powerW(s.packVoltage, s.currentA)?.let { synchronized(realAverager) { realAverager.add(s.timestampMs, it) } }
        if (s.timestampMs - lastHistoryMs >= HISTORY_INTERVAL_MS) {
            lastHistoryMs = s.timestampMs
            runCatching {
                history.insert(s.toHistorySample())
                history.prune(s.timestampMs - RETENTION_MS)
            }
        }
        val st = settings.value
        if (lowSoc.shouldNotify(s.socPercent, st.lowSocThreshold, st.lowSocAlertEnabled)) notifyLowSoc(s.socPercent!!, st.lowSocThreshold)
    }

    fun average(nowMs: Long): LoadAverage? {
        val window = settings.value.averagingMinutes.coerceIn(1, 60) * 60_000L
        val a = if (mode.value == SourceMode.DEMO) demoAverager else realAverager
        return synchronized(a) { a.windowMs = window; a.average(nowMs) }
    }

    fun connect(address: String, name: String?) {
        stopDemo()
        settings.update { it.copy(deviceAddress = address, deviceName = name) }
        synchronized(realAverager) { realAverager.clear() }
        connection.start(address)
    }

    fun disconnect() = connection.stop()

    fun startDemo() {
        connection.stop()
        demoJob?.cancel()
        synchronized(demoAverager) { demoAverager.clear() }
        mode.value = SourceMode.DEMO
        demoJob = scope.launch {
            val sim = Simulator(System.currentTimeMillis())
            while (isActive) {
                val s = sim.snapshot(System.currentTimeMillis())
                demoSnapshot.value = s
                Estimates.powerW(s.packVoltage, s.currentA)?.let { synchronized(demoAverager) { demoAverager.add(s.timestampMs, it) } }
                delay(2_000)
            }
        }
    }

    fun stopDemo() {
        demoJob?.cancel(); demoJob = null
        demoSnapshot.value = null
        mode.value = SourceMode.REAL
    }

    fun historySince(sinceMs: Long): List<HistorySample> = history.query(sinceMs)

    private fun notifyLowSoc(soc: Double, threshold: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("low_soc", "انخفاض شحن البطارية", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(context, "low_soc")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("شحن البطارية منخفض")
            .setContentText("نسبة الشحن ${"%.0f".format(java.util.Locale.US, soc)}% — أقل من الحد الذي اخترته ($threshold%).")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }

    companion object {
        const val HISTORY_INTERVAL_MS = 15_000L
        const val RETENTION_MS = 30L * 24 * 3600 * 1000
    }
}
