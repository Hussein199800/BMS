package batari.core

import org.junit.Assert.*
import org.junit.Test

class EstimatesTest {
    @Test fun powerSignFollowsCurrent() {
        assertEquals(-3067.168, Estimates.powerW(52.52, -58.4)!!, 1e-6)
        assertEquals(1050.4, Estimates.powerW(52.52, 20.0)!!, 1e-6)
        assertNull(Estimates.powerW(null, 5.0)); assertNull(Estimates.powerW(52.0, null))
    }

    @Test fun directionWithDeadband() {
        assertEquals(FlowDirection.DISCHARGING, Estimates.direction(-58.4))
        assertEquals(FlowDirection.CHARGING, Estimates.direction(12.0))
        assertEquals(FlowDirection.IDLE, Estimates.direction(-0.2))
        assertEquals(FlowDirection.IDLE, Estimates.direction(0.0))
        assertNull(Estimates.direction(null))
    }

    @Test fun energyEstimates() {
        val nominal = Estimates.nominalPackVoltage(16)!!
        assertEquals(51.2, nominal, 1e-9)
        assertEquals(8793.6, Estimates.remainingEnergyWh(171.75, nominal)!!, 1e-6)
        // احتياطي 10% من 200 = 20 أمبير·ساعة
        assertEquals(7769.6, Estimates.usableEnergyWh(171.75, 200.0, 10, nominal)!!, 1e-6)
        assertEquals(0.0, Estimates.usableEnergyWh(15.0, 200.0, 10, nominal)!!, 1e-9)
        assertNull(Estimates.remainingEnergyWh(null, nominal))
        assertNull(Estimates.usableEnergyWh(171.75, null, 10, nominal))
        assertNull(Estimates.nominalPackVoltage(null))
    }

    @Test fun timeRemainingUsesAverageNotInstant() {
        val avg = LoadAverager(5 * 60_000L)
        for (i in 0..30) avg.add(i * 5_000L, if (i == 30) -9000.0 else -3000.0) // قفزة لحظية أخيرة
        val a = avg.average(150_000L)!!
        val t = Estimates.timeRemaining(7769.6, a) as TimeEstimate.Available
        assertEquals(7769.6 / (99000.0 / 31), t.hours, 1e-6)
        assertTrue(t.hours > 2.0) // القراءة اللحظية وحدها كانت ستعطي أقل من ساعة
    }

    @Test fun timeUnavailableCases() {
        fun avgOf(w: Double, n: Int = 20, step: Long = 5000) = LoadAverager(600_000).apply { repeat(n) { add(it * step, w) } }.average(n * step)
        assertTrue(Estimates.timeRemaining(5000.0, avgOf(-10.0)) is TimeEstimate.Unavailable) // حمل قريب من الصفر
        assertTrue(Estimates.timeRemaining(5000.0, avgOf(800.0)) is TimeEstimate.Unavailable) // شحن
        assertTrue(Estimates.timeRemaining(5000.0, avgOf(-800.0, n = 3)) is TimeEstimate.Unavailable) // قراءات قليلة
        assertTrue(Estimates.timeRemaining(5000.0, avgOf(-800.0, n = 6, step = 1000)) is TimeEstimate.Unavailable) // مدة قصيرة
        assertTrue(Estimates.timeRemaining(null, avgOf(-800.0)) is TimeEstimate.Unavailable) // بيانات مفقودة
        assertTrue(Estimates.timeRemaining(5000.0, null) is TimeEstimate.Unavailable)
        assertTrue(Estimates.timeRemaining(0.0, avgOf(-800.0)) is TimeEstimate.Unavailable) // عند الاحتياطي
        assertTrue(Estimates.timeRemaining(5000.0, avgOf(-800.0)) is TimeEstimate.Available)
    }

    @Test fun averagerDropsOldSamples() {
        val a = LoadAverager(60_000)
        a.add(0, -1000.0); a.add(30_000, -1000.0); a.add(120_000, -200.0)
        val r = a.average(120_000)!!
        assertEquals(1, r.samples); assertEquals(-200.0, r.averageW, 1e-9)
        assertNull(a.average(500_000))
    }

    @Test fun lowSocAlertFiresOnceAndRearms() {
        val a = LowSocAlert()
        assertFalse(a.shouldNotify(35.0, 30, true))
        assertTrue(a.shouldNotify(29.9, 30, true))
        assertFalse(a.shouldNotify(29.0, 30, true))
        assertFalse(a.shouldNotify(30.5, 30, true)) // داخل الهامش: لا يُعاد التسليح
        assertFalse(a.shouldNotify(29.5, 30, true))
        assertFalse(a.shouldNotify(33.0, 30, true))
        assertTrue(a.shouldNotify(29.5, 30, true))
        assertFalse(LowSocAlert().shouldNotify(10.0, 30, false))
        assertFalse(LowSocAlert().shouldNotify(null, 30, true))
    }
}

class ConnectionLossTest {
    @Test fun stalenessThreshold() {
        assertFalse(Freshness.isStale(10_000, 5_000))
        assertTrue(Freshness.isStale(15_001, 5_000))
        assertFalse(Freshness.isStale(25_000, 10_000))
        assertTrue(Freshness.isStale(31_000, 10_000))
    }

    @Test fun reconnectBackoffGrowsThenCapsAndResets() {
        val p = ReconnectPolicy()
        assertEquals(listOf(2000L, 5000L, 10000L, 30000L, 60000L, 60000L, 60000L), List(7) { p.nextDelayMs() })
        p.reset()
        assertEquals(2000L, p.nextDelayMs())
    }

    @Test fun pollHealthDisconnectsAfterThreeConsecutiveFailures() {
        val h = PollHealth()
        assertFalse(h.failure()); assertFalse(h.failure())
        h.success()
        assertFalse(h.failure()); assertFalse(h.failure()); assertTrue(h.failure())
    }

    @Test fun disconnectMidFrameThenFreshFrameAfterReset() {
        val a = UpFrameAssembler(1)
        a.feed(Fixtures.UP_STATUS.copyOfRange(0, 60)) // انقطع الاتصال هنا
        a.reset()
        val r = a.feed(Fixtures.UP_STATUS)
        assertEquals(1, r.filterIsInstance<FrameResult.Frame>().size)
        assertTrue(r.none { it is FrameResult.Error })
    }
}

class HistoryAndMaskingTest {
    @Test fun csvWritesMissingValuesAsEmptyNotZero() {
        val row = Csv.row(HistorySample(0, 52.52, null, null, 85.5, null, 36.9))
        assertEquals("1970-01-01T00:00:00Z,0,52.52,,,85.50,,36.9", row)
        val doc = Csv.document(listOf(HistorySample(1000, 52.0, -58.4, -3036.8, 85.0, 28.5, 36.9)))
        assertEquals(2, doc.trim().lines().size)
        assertTrue(doc.startsWith(Csv.HEADER))
        assertTrue(doc.contains("-58.40,-3036.8"))
    }

    @Test fun snapshotToSampleKeepsCellAndMosTempsSeparate() {
        val s = BatterySnapshot(5, ProtocolKind.UP_SERIES, packVoltage = 50.0, currentA = -10.0,
            cellTempsC = listOf(26.5, 28.5), mosTempC = 36.9).toHistorySample()
        assertEquals(28.5, s.maxCellTempC!!, 1e-9); assertEquals(36.9, s.mosTempC!!, 1e-9)
        assertEquals(-500.0, s.powerW!!, 1e-9); assertNull(s.socPercent)
    }

    @Test fun masking() {
        assertEquals("AA:C2:••:••:••:D7", Masking.mac("AA:C2:37:0B:47:D7"))
        val m = Masking.name("5YBOP026042048200028")
        assertTrue(m.startsWith("5YBO") && m.endsWith("28") && !m.contains("026042048"))
    }

    @Test fun simulatorIsAlwaysTaggedSimulated() {
        val sim = Simulator(0)
        for (t in listOf(0L, 100_000L, 160_000L, 200_000L)) {
            val s = sim.snapshot(t)
            assertEquals(ProtocolKind.SIMULATED, s.protocol)
            assertEquals(16, s.cellCount)
        }
        assertEquals(FlowDirection.DISCHARGING, Estimates.direction(sim.snapshot(10_000).currentA))
        assertEquals(FlowDirection.CHARGING, Estimates.direction(sim.snapshot(200_000).currentA))
    }
}
