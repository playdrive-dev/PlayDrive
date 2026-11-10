package app.playdrive.sim

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingEventEngine
import app.playdrive.model.TurnSignal
import app.playdrive.music.Arrangement
import app.playdrive.music.Cue
import app.playdrive.music.Layer
import app.playdrive.music.MusicCommand
import app.playdrive.music.MusicDirector
import app.playdrive.music.MusicTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDriveScenarioTest {

    private data class Run(val events: List<DrivingEvent>, val commands: List<Pair<Long, MusicCommand>>, val layersAt: Map<Long, Set<Layer>>)

    private val song = setOf(Layer.CHORDS, Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.MELODY)

    private fun run(): Run {
        val engine = DrivingEventEngine()
        val director = MusicDirector(MusicTiming(bars = 8), song, Arrangement.SONG)
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
            if (t % 500 == 0L) layersAt[t] = director.layers
            last = t; t += 100
        }
        return Run(events, cmds, layersAt)
    }

    private fun Run.firstCmd(from: Long, pred: (MusicCommand) -> Boolean) = commands.firstOrNull { (t, c) -> t >= from && pred(c) }?.first

    @Test fun `데모는 60~75초이고 운전 이벤트가 의도한 순서로 나온다`() {
        assertTrue(DemoDriveScenario.DURATION_MS in 60_000..75_000)
        val r = run()
        val names = r.events.map { it::class.simpleName }
        val expected = listOf(
            "Parked", "Departed", "SmoothAcceleration", "TurnSignalOn", "CruiseStarted", "LaneChange",
            "SmoothDeceleration", "Stopped", "Departed", "SmoothAcceleration", "RainStarted", "HarshBraking",
            "SmoothAcceleration", "Stopped", "Parked",
        )
        var i = 0
        for (n in names) if (i < expected.size && n == expected[i]) i++
        assertEquals("여기서 멈춤: ${expected.getOrNull(i)}", expected.size, i)
        assertEquals(1, r.events.count { it is DrivingEvent.HarshBraking })
        assertEquals(0, r.events.count { it is DrivingEvent.HarshAcceleration || it is DrivingEvent.HarshSteering })
        assertEquals(TurnSignal.LEFT, r.events.filterIsInstance<DrivingEvent.LaneChange>().single().direction)
    }

    @Test fun `6개 반응 장면이 모두 나온다(장면별 시간표 출력)`() {
        val r = run()
        val base = setOf(Layer.CHORDS, Layer.PAD)
        val scenes = linkedMapOf<String, Long?>()
        // ① 속도 톤: 정차 0km → 50km 밝음
        val tones = r.commands.filter { it.second is MusicCommand.SpeedTone }
        scenes["① 속도 톤 첫 밝아짐(≥30km/h)"] = tones.firstOrNull { (it.second as MusicCommand.SpeedTone).kmh >= 30f }?.first
        // ⑤ 출발 드롭
        scenes["⑤ 출발 드롭 #1(drums+bass fast)"] = r.firstCmd(0) { it == MusicCommand.SetLayer(Layer.DRUMS, true, fast = true) }
        // ② 아르페지오
        scenes["② 아르페지오 ON #1"] = r.firstCmd(0) { it == MusicCommand.PerfArp(true) }
        // ④ 셰이커
        scenes["④ 깜빡이 셰이커 ON"] = r.firstCmd(0) { it == MusicCommand.PerfShaker(true) }
        scenes["④ 깜빡이 셰이커 OFF(마디 끝까지)"] = r.firstCmd(10_000) { it == MusicCommand.PerfShaker(false) }
        // ③ 근음+5도
        scenes["③ 브레이크 시작 근음+5도 #1"] = r.firstCmd(0) { it == MusicCommand.PerfRootFifth }
        // ⑤ 브레이크다운
        scenes["⑤ 신호 대기 브레이크다운(keys+synth)"] = r.layersAt.toSortedMap().entries.firstOrNull { it.key in 25_000..37_000 && it.value == base }?.key
        scenes["⑤ 재출발 드롭 #2"] = r.firstCmd(35_000) { it == MusicCommand.SetLayer(Layer.DRUMS, true, fast = true) }
        scenes["② 아르페지오 ON #2"] = r.firstCmd(35_000) { it == MusicCommand.PerfArp(true) }
        scenes["(비) 로우패스+리버브 ON"] = r.firstCmd(0) { it == MusicCommand.RainFx(true) }
        scenes["⑥ 급제동 테이프스톱"] = r.firstCmd(0) { it == MusicCommand.PlayCue(Cue.TAPE_STOP) }
        scenes["③ 브레이크 시작 근음+5도 #2"] = r.firstCmd(58_000) { it == MusicCommand.PerfRootFifth }
        scenes["주차(keys+synth)"] = r.events.filterIsInstance<DrivingEvent.Parked>().last().timeMs
        println("RootFifth at: " + r.commands.filter { it.second == MusicCommand.PerfRootFifth }.map { it.first })
        println(scenes.entries.joinToString("\n") { (k, v) -> "%6.1fs  %s".format((v ?: -1L) / 1000.0, k) })
        scenes.forEach { (k, v) -> assertTrue("장면 없음: $k", v != null) }

        // 순서·구간 확인
        assertTrue(scenes.getValue("⑤ 출발 드롭 #1(drums+bass fast)")!! in 5_000..7_000)
        assertTrue(scenes.getValue("④ 깜빡이 셰이커 ON")!! == 18_000L)
        assertTrue(scenes.getValue("③ 브레이크 시작 근음+5도 #1")!! in 24_000..24_500)
        // 급제동 브레이크(53초)에서는 근음+5도가 나오지 않는다
        assertTrue(r.commands.none { (t, c) -> c == MusicCommand.PerfRootFifth && t in 52_500..56_000 })
        assertTrue(scenes.getValue("⑤ 신호 대기 브레이크다운(keys+synth)")!! in 31_000..33_500)
        assertTrue(scenes.getValue("⑤ 재출발 드롭 #2")!! in 37_500..39_000)
        assertTrue(scenes.getValue("⑥ 급제동 테이프스톱")!! in 53_000..54_500)
        // 드롭: drums와 bass가 같은 시각에 fast로
        val d2 = scenes.getValue("⑤ 재출발 드롭 #2")!!
        assertTrue(r.commands.any { (t, c) -> t == d2 && c == MusicCommand.SetLayer(Layer.BASS, true, fast = true) })
        // 정차 시 속도 톤 0으로
        assertTrue(tones.any { (t, c) -> t in 29_000..31_000 && (c as MusicCommand.SpeedTone).kmh < 1f })
    }
}
