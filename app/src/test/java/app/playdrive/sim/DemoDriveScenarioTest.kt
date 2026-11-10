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

    private fun run(): Run {
        val engine = DrivingEventEngine()
        val director = MusicDirector()
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

    @Test fun `데모 시나리오의 음악 반응`() {
        val r = run()
        println(r.layersAt.toSortedMap().entries.joinToString("\n") { "%4ds %s".format(it.key / 1000, it.value) })
        assertEquals(setOf(Layer.PAD), r.layersAt[2_000])                              // 인트로
        assertTrue(Layer.DRUMS in r.layersAt.getValue(8_000))                          // 출발 → 비트
        assertTrue(Layer.MELODY in r.layersAt.getValue(20_000))                        // 레이어가 쌓임
        assertTrue(Layer.HIHAT_FILL in r.layersAt.getValue(23_000))                    // 방향지시등 필인
        assertTrue(r.commands.any { (t, c) -> c == MusicCommand.TapeStop && t in 30_000..32_000 }) // 급제동
        assertTrue(Layer.MELODY !in r.layersAt.getValue(32_000))                       // 레이어 감소
        assertTrue(Layer.MELODY in r.layersAt.getValue(46_000))                        // 회복
        assertTrue(Layer.RAIN in r.layersAt.getValue(40_000))                          // 비
        assertTrue(Layer.DRUMS !in r.layersAt.getValue(54_000))                        // 정차 브레이크다운
        assertEquals(setOf(Layer.PAD), r.layersAt[58_000])                             // 주차 아웃트로
    }
}
