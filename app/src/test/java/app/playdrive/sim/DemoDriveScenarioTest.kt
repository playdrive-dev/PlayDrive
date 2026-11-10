package app.playdrive.sim

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingEventEngine
import app.playdrive.model.TurnSignal
import app.playdrive.music.Layer
import app.playdrive.music.MusicCommand
import app.playdrive.music.MusicDirector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDriveScenarioTest {

    private data class Run(val events: List<DrivingEvent>, val commands: List<Pair<Long, MusicCommand>>, val layersAt: Map<Long, Set<Layer>>)

    private fun run(director: MusicDirector = MusicDirector(available = setOf(Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.CHORDS, Layer.MELODY))): Run {
        val engine = DrivingEventEngine()
        val events = mutableListOf<DrivingEvent>()
        val cmds = mutableListOf<Pair<Long, MusicCommand>>()
        val layersAt = mutableMapOf<Long, Set<Layer>>()
        var last = -1L
        var t = 0L
        while (t <= DemoDriveScenario.DURATION_MS) {
            for (s in DemoDriveScenario.samples(last, t, 0)) {
                val e = engine.onSample(s)
                events += e
                director.update(engine.state, e).forEach { cmds += t to it }
            }
            if (t % 1000 == 0L) layersAt[t] = director.layers
            last = t; t += 100
        }
        return Run(events, cmds, layersAt)
    }

    @Test fun `데모 시나리오는 의도한 순서대로 이벤트를 낸다`() {
        val r = run()
        val names = r.events.map { it::class.simpleName }
        println(r.events.joinToString("\n") { "%6.1fs %s".format(it.timeMs / 1000.0, it) })
        val expectedOrder = listOf(
            "Parked", "Departed", "SmoothAcceleration", "CruiseStarted", "TurnSignalOn", "LaneChange",
            "HarshBraking", "SmoothAcceleration", "RainStarted", "CruiseStarted", "SmoothDeceleration",
            "Stopped", "RainStopped", "Parked",
        )
        var idx = 0
        for (n in names) if (idx < expectedOrder.size && n == expectedOrder[idx]) idx++
        assertEquals("순서대로 나와야 할 이벤트 중 ${expectedOrder.getOrNull(idx)}에서 멈춤", expectedOrder.size, idx)
        assertEquals(0, r.events.count { it is DrivingEvent.HarshAcceleration })
        assertEquals(1, r.events.count { it is DrivingEvent.HarshBraking })
        assertEquals(0, r.events.count { it is DrivingEvent.HarshSteering })
        assertEquals(TurnSignal.LEFT, r.events.filterIsInstance<DrivingEvent.LaneChange>().single().direction)
        assertTrue(r.events.filterIsInstance<DrivingEvent.OccupancyChanged>().last().solo)
    }

    @Test fun `데모 시나리오의 음악 반응(스템 매핑)`() {
        val r = run()
        println(r.layersAt.toSortedMap().entries.joinToString("\n") { "%4ds %s".format(it.key / 1000, it.value) })
        assertEquals(setOf(Layer.PAD), r.layersAt[2_000])                              // 정차
        assertEquals(setOf(Layer.PAD, Layer.DRUMS, Layer.HIHAT), r.layersAt[8_000])    // 부드러운 가속
        assertTrue(Layer.BASS in r.layersAt.getValue(22_000) && Layer.CHORDS in r.layersAt.getValue(22_000)) // 정속
        assertTrue(r.commands.any { (t, c) -> c == MusicCommand.PlayCue(app.playdrive.music.Cue.TAPE_STOP) && t in 30_000..32_000 })
        assertEquals(setOf(Layer.PAD), r.layersAt[31_000])                             // 급제동 직후
        assertTrue(r.layersAt.getValue(36_000).size in 2..4)                           // 한 마디씩 복구 중
        assertTrue(Layer.MELODY in r.layersAt.getValue(46_000) || Layer.CHORDS in r.layersAt.getValue(46_000))
        assertTrue(Layer.DRUMS !in r.layersAt.getValue(54_000))                        // 신호 대기 브레이크다운
        assertEquals(setOf(Layer.PAD), r.layersAt[58_000])                             // 주차
    }

    @Test fun `데모 시나리오의 음악 반응(SONG 세트)`() {
        val t8 = app.playdrive.music.MusicTiming(bars = 8)
        val r = run(MusicDirector(t8, setOf(Layer.CHORDS, Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.MELODY), app.playdrive.music.Arrangement.SONG))
        val base = setOf(Layer.CHORDS, Layer.PAD)
        assertEquals(base, r.layersAt[2_000])
        assertEquals(base + Layer.DRUMS + Layer.HIHAT, r.layersAt[8_000])
        assertTrue(Layer.BASS in r.layersAt.getValue(22_000))
        assertTrue(Layer.MELODY in r.layersAt.getValue(29_000))
        assertEquals(base, r.layersAt[31_000])
        assertTrue(r.commands.any { (t, c) -> c == MusicCommand.RainFx(true) && t == 35_000L })
        assertTrue(r.commands.any { (t, c) -> c == MusicCommand.RainFx(false) && t == 55_000L })
        assertEquals(base, r.layersAt[58_000])
    }
}
