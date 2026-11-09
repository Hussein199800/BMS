package batari.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** احتياطي شحن يُطرح في حساب الوقت والطاقة فقط — لا يُرسل للبطارية. */
    val reservePercent: Int = 10,
    val averagingMinutes: Int = 5,
    val lowSocAlertEnabled: Boolean = false,
    val lowSocThreshold: Int = 30,
    val pollSeconds: Int = 5,
    /** عنوان المجموعة في بروتوكول UP (PACK1 = 1). */
    val packAddress: Int = 1,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val maskIdentifiers: Boolean = true,
    val autoConnect: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow
    val value: AppSettings get() = _flow.value

    private fun load() = AppSettings(
        deviceAddress = prefs.getString("deviceAddress", null),
        deviceName = prefs.getString("deviceName", null),
        reservePercent = prefs.getInt("reservePercent", 10),
        averagingMinutes = prefs.getInt("averagingMinutes", 5),
        lowSocAlertEnabled = prefs.getBoolean("lowSocAlertEnabled", false),
        lowSocThreshold = prefs.getInt("lowSocThreshold", 30),
        pollSeconds = prefs.getInt("pollSeconds", 5),
        packAddress = prefs.getInt("packAddress", 1),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        maskIdentifiers = prefs.getBoolean("maskIdentifiers", true),
        autoConnect = prefs.getBoolean("autoConnect", true),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_flow.value)
        prefs.edit()
            .putString("deviceAddress", s.deviceAddress)
            .putString("deviceName", s.deviceName)
            .putInt("reservePercent", s.reservePercent)
            .putInt("averagingMinutes", s.averagingMinutes)
            .putBoolean("lowSocAlertEnabled", s.lowSocAlertEnabled)
            .putInt("lowSocThreshold", s.lowSocThreshold)
            .putInt("pollSeconds", s.pollSeconds)
            .putInt("packAddress", s.packAddress)
            .putString("theme", s.theme.name)
            .putBoolean("maskIdentifiers", s.maskIdentifiers)
            .putBoolean("autoConnect", s.autoConnect)
            .apply()
        _flow.value = s
    }
}
