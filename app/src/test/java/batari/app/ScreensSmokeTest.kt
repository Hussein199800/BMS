package batari.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import batari.app.ble.LinkState
import batari.app.data.AppSettings
import batari.app.data.SourceMode
import batari.app.data.ThemeMode
import batari.app.ui.BatariTheme
import batari.app.ui.CellsScreen
import batari.app.ui.ConnectScreen
import batari.app.ui.DetailsScreen
import batari.app.ui.HistoryScreen
import batari.app.ui.HomeScreen
import batari.app.ui.SettingsScreen
import batari.core.BatterySnapshot
import batari.core.BmsFlag
import batari.core.HistorySample
import batari.core.LoadAverage
import batari.core.ProtocolKind
import batari.core.Simulator
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * يركّب كل شاشة ببيانات المحاكي وبحالات البيانات المفقودة ويتأكد أنها لا تنهار،
 * ويحفظ صورًا في build/screens للمراجعة البصرية.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-w411dp-h1400dp-xhdpi")
class ScreensSmokeTest {
    private val now = 1_000_000_000L
    private val snap = Simulator(now - 60_000).snapshot(now)
    private val avg = LoadAverage(-3050.0, 40, 200_000)

    private fun shot(name: String, theme: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
        captureRoboImage("build/screens/$name.png") { BatariTheme(theme) { Surface { content() } } }
    }

    @Test fun homeLive() = shot("home") {
        HomeScreen(snap.copy(protocol = ProtocolKind.UP_SERIES), LinkState.Live(ProtocolKind.UP_SERIES), null, SourceMode.REAL, AppSettings(), avg, now + 2000, {}, {})
    }

    @Test fun homeDark() = shot("home_dark", ThemeMode.DARK) {
        HomeScreen(snap, LinkState.Idle, null, SourceMode.DEMO, AppSettings(), avg, now + 2000, {}, {})
    }

    @Test fun homeEmptyAndFailed() = shot("home_empty") {
        HomeScreen(null, LinkState.Failed("اتصلنا بالجهاز لكنه لم يردّ على طلبات القراءة المعروفة."), null, SourceMode.REAL, AppSettings(), null, now, {}, {})
    }

    @Test fun homeStaleWithWarningsAndMissingFields() = shot("home_stale") {
        val partial = BatterySnapshot(now, ProtocolKind.CLASSIC, packVoltage = 52.5, currentA = -20.0, socPercent = 40.0,
            protections = listOf(BmsFlag("P1", "حماية: جهد خلية منخفض")), alarms = emptyList())
        HomeScreen(partial, LinkState.Waiting(5, "انقطع الاتصال بالبطارية."), null, SourceMode.REAL, AppSettings(), null, now + 120_000, {}, {})
    }

    @Test fun cells() = shot("cells") { CellsScreen(snap.copy(balancingCells = setOf(9))) }
    @Test fun cellsMissing() = shot("cells_missing") { CellsScreen(BatterySnapshot(now, ProtocolKind.CLASSIC)) }
    @Test fun details() = shot("details") { DetailsScreen(snap) }
    @Test fun detailsMissing() = shot("details_missing") { DetailsScreen(BatterySnapshot(now, ProtocolKind.CLASSIC)) }

    @Test fun history() = shot("history") {
        val samples = (0 until 200).filter { it !in 80..120 }.map {
            HistorySample(now + it * 15_000L, 52.5, -58.0, -3000.0 - it * 3, 86.0 - it * 0.02, 28.0, 36.9)
        }
        HistoryScreen(samples, SourceMode.REAL, 0, {}, {}, {})
    }

    @Test fun historyEmpty() = shot("history_empty") { HistoryScreen(emptyList(), SourceMode.DEMO, 0, {}, {}, {}) }

    @Test fun settings() = shot("settings") {
        SettingsScreen(AppSettings(deviceAddress = "AA:C2:37:0B:47:D7", deviceName = "5YBOP000000000000028"), SourceMode.REAL, {}, {}, {}, {}, {})
    }

    @Test fun cloudLogin() = shot("cloud_login") {
        batari.app.ui.CloudScreen(batari.app.CloudState(), {}, { _, _, _ -> }, {}, {}, {})
    }

    @Test fun cloudSignedIn() = shot("cloud_in") {
        batari.app.ui.CloudScreen(batari.app.CloudState(signedIn = true, report = "[1] قائمة الأجهزة → HTTP 200"), {}, { _, _, _ -> }, {}, {}, {})
    }

    @Test fun connectDiagReport() = shot("connect_report") {
        ConnectScreen(AppSettings(), emptyList(), false, null,
            DiagState.Done("=== تقرير فحص الاتصال ===\n[1] الاتصال: نجح\n    TX 01 78 10 00 10 A0 00 00 7F B2\n    RX 01 78 10 00"), {}, {}, { _, _ -> }, { _, _ -> }, {})
    }
}
