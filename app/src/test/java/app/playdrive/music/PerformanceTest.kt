package app.playdrive.music

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import app.playdrive.model.TurnSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PerformanceTest {
    private val t8 = MusicTiming(bars = 8)
    private val eighth = t8.beatFrames / 2

    private fun perf(tl: ChordTimeline = ChordTimeline.SONG) = PerformanceLayer(t8, tl).also { it.recordPlayed = true }
    private fun run(p: PerformanceLayer, from: Long, to: Long) { var g = from; while (g < to) { p.process(g); g++ } }

    // ---------------------------------------------------------------- 양자화
    @Test fun `모든 연주음은 다음 8분음표 격자에서 시작(즉시 재생 없음, 최대 지연 8분음표 1개)`() {
        for (g0 in listOf(1L, 12_345L, eighth.toLong() - 1, eighth.toLong(), 3L * t8.barFrames + 777)) {
            val p = perf()
            p.command(MusicCommand.PerfRootFifth, g0)
            p.command(MusicCommand.PerfArp(true), g0)
            p.command(MusicCommand.PerfShaker(true), g0)
            run(p, g0, g0 + t8.beatFrames * 2)
            val first = (p.played.map { it.first } + p.shakerHits).min()
            assertEquals("g0=$g0", 0L, first % eighth)
            assertTrue("g0=$g0 delay", first - g0 in 0..eighth.toLong())
            (p.played.map { it.first } + p.shakerHits).forEach { assertEquals(0L, it % eighth) }
        }
    }

    // ---------------------------------------------------------------- 코드톤 선택
    @Test fun `SONG 타임라인은 Am7과 Fmaj7가 마디마다 교대`() {
        val tl = ChordTimeline.SONG
        assertEquals(8, tl.bars.size)
        (0 until 8).forEach { assertEquals(if (it % 2 == 0) "Am7" else "Fmaj7", tl.chordAtBar(it).name) }
        assertEquals("Am7", tl.chordAt(0, t8).name)
        assertEquals("Fmaj7", tl.chordAt(t8.barFrames.toLong() + 5, t8).name)
        assertEquals("Am7", tl.chordAt(t8.loopFrames.toLong() + 1, t8).name) // 루프 반복
    }

    @Test fun `모든 세트의 모든 연주음은 현재 코드 구성음 또는 A 마이너 펜타토닉`() {
        for (tl in listOf(ChordTimeline.SONG, ChordTimeline.LOOPS, ChordTimeline.SYNTH)) {
            val p = PerformanceLayer(MusicTiming(bars = tl.bars.size), tl)
            for (c in tl.bars) {
                val allowed = c.pitchClasses + ChordTimeline.A_MINOR_PENTATONIC
                val notes = (0..3).map { p.arpNote(c, it) } + p.rootFifth(c)
                notes.forEach { assertTrue("${c.name} $it", it % 12 in allowed) }
                // 아르페지오는 구성음만, 오름차순
                val arp = (0..3).map { p.arpNote(c, it) }
                arp.forEach { assertTrue(it % 12 in c.pitchClasses) }
                assertEquals(arp.sorted(), arp)
            }
        }
    }

    @Test fun `근음+5도 - Am7은 A3+E4, Fmaj7은 F3+C4`() {
        val p = perf()
        assertEquals(listOf(57, 64), p.rootFifth(ChordTimeline.AM7))
        assertEquals(listOf(53, 60), p.rootFifth(ChordTimeline.FMAJ7))
    }

    @Test fun `아르페지오는 박마다 한 음, 4음 후 처음으로, 그 박의 코드를 따른다`() {
        val p = perf()
        p.command(MusicCommand.PerfArp(true), 0)
        run(p, 0, t8.barFrames * 2L + 1)              // 2마디 = 8박 + 다음 마디 첫 박
        val notes = p.played.map { it.second.single() }
        assertEquals(9, notes.size)
        assertEquals(listOf(69, 72, 76, 79), notes.subList(0, 4))   // 1마디 Am7: A4 C5 E5 G5
        assertEquals(listOf(65, 69, 72, 76), notes.subList(4, 8))   // 2마디 Fmaj7: F4 A4 C5 E5 (리셋 후 다시 근음부터)
        assertEquals(69, notes[8])                                  // 3마디 Am7 근음
        p.played.zipWithNext { a, b -> assertEquals(t8.beatFrames.toLong(), b.first - a.first) }
    }

    // ---------------------------------------------------------------- 셰이커·정지
    @Test fun `셰이커는 8분음표마다, 끄면 그 마디 끝까지 치고 멈춘다`() {
        val p = perf()
        p.command(MusicCommand.PerfShaker(true), 100)
        run(p, 100, t8.barFrames + 10L)
        val offAt = t8.barFrames + t8.beatFrames + 10L          // 둘째 마디 2박 직후 끔
        run(p, t8.barFrames + 10L, offAt)
        p.command(MusicCommand.PerfShaker(false), offAt)
        run(p, offAt, t8.barFrames * 4L)
        val hits = p.shakerHits
        hits.zipWithNext { a, b -> assertEquals(eighth.toLong(), b - a) }
        assertEquals(t8.barFrames * 2L - eighth, hits.last())     // 둘째 마디 마지막 8분음표까지
    }

    @Test fun `테이프스톱 시 연주 레이어도 멈춘다`() {
        val p = perf()
        p.command(MusicCommand.PerfArp(true), 0)
        p.command(MusicCommand.PerfShaker(true), 0)
        run(p, 0, t8.beatFrames + 100L)
        p.silence(t8.beatFrames + 100L, t8.barFrames * 2L)
        val before = p.played.size + p.shakerHits.size
        var peak = 0f
        for (g in t8.beatFrames + 100L until t8.barFrames * 2L) { p.process(g); if (g > t8.beatFrames + 100 + 0.04 * 48_000) peak = maxOf(peak, abs(p.outL)) }
        assertEquals(before, p.played.size + p.shakerHits.size)  // 새 음 없음
        assertEquals(0f, peak)                                    // 30ms 안에 소리 끝
    }

    // ---------------------------------------------------------------- 믹서 연동
    private fun rms(x: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += x[2 * i] * x[2 * i]; return sqrt(s / (to - from)) }

    @Test fun `속도 톤 - 0km에서 먹먹(고역 감소), 60km 이상에서 밝음, 0_5초 스무딩`() {
        val t4 = MusicTiming()
        val rnd = kotlin.random.Random(3)
        val noise = mapOf(Layer.HIHAT to StereoPcm.mono(FloatArray(t4.loopFrames) { (rnd.nextFloat() * 2 - 1) * 0.5f }))
        fun level(kmh: Float): Double {
            val m = LoopMixer(t4, noise)
            m.submit(MusicCommand.SetLayer(Layer.HIHAT, true)); m.submit(MusicCommand.SpeedTone(kmh))
            val out = FloatArray(t4.sampleRate * 2 * 4); m.render(out, t4.sampleRate * 4)
            return rms(out, t4.sampleRate * 3, t4.sampleRate * 4)
        }
        val stop = level(0f); val city = level(30f); val fast = level(80f)
        assertTrue("stop=$stop city=$city fast=$fast", stop < city && city < fast)
        assertTrue(stop < fast * 0.5)
    }

    @Test fun `드롭 - fast 레이어는 마디 경계에서 20ms 안에 다 들어온다`() {
        val t4 = MusicTiming()
        val sine = StereoPcm.mono(FloatArray(t4.loopFrames) { (0.5 * kotlin.math.sin(2 * Math.PI * 220 * it / t4.sampleRate)).toFloat() })
        val m = LoopMixer(t4, mapOf(Layer.DRUMS to sine, Layer.BASS to sine))
        m.submit(MusicCommand.SetLayer(Layer.DRUMS, true, fast = true))
        m.submit(MusicCommand.SetLayer(Layer.BASS, true))
        val out = FloatArray(t4.barFrames * 2 * 2); m.render(out, t4.barFrames * 2)
        val b = t4.barFrames
        // 경계 직후 30~80ms: fast 레이어는 이미 최대, 일반 레이어는 아직 거의 0 → 합이 한 레이어 최대치에 가까움
        val early = rms(out, b + 1440, b + 3840)
        val full = rms(out, 2 * b - 4800, 2 * b - 2400) // 둘 다 들어온 뒤
        assertTrue("early=$early full=$full", early > full * 0.4 && early < full * 0.65)
        assertTrue(rms(out, 0, b - 10) == 0.0)                   // 경계 전엔 무음
    }

    // ---------------------------------------------------------------- 연출(매핑)
    private val song = setOf(Layer.CHORDS, Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.MELODY)
    private fun st(t: Long, v: Float = 0f, a: Float = 0f, moving: Boolean = v > 1f, gear: Gear = Gear.DRIVE, brake: Boolean = false, turn: TurnSignal = TurnSignal.OFF) =
        DrivingState(timeMs = t, speedMps = v, accelMps2 = a, moving = moving, gear = gear, brakePressed = brake, turnSignal = turn)

    @Test fun `매핑 - 속도톤, 아르페지오, 근음+5도, 셰이커, 브레이크다운, 출발 드롭, 테이프스톱`() {
        val d = MusicDirector(t8, song, Arrangement.SONG)
        assertTrue(MusicCommand.SpeedTone(0f) in d.update(st(0, gear = Gear.PARK), emptyList()))
        assertEquals(setOf(Layer.CHORDS, Layer.PAD), d.layers)

        // 출발(D + 속도 증가) → drums+bass 드롭(같은 배치, fast)
        val dep = d.update(st(1000, v = 1.2f, a = 1.5f), listOf(DrivingEvent.Departed(1000)))
        assertTrue(MusicCommand.SetLayer(Layer.DRUMS, true, fast = true) in dep)
        assertTrue(MusicCommand.SetLayer(Layer.BASS, true, fast = true) in dep)
        // 부드러운 가속 → 아르페지오
        assertTrue(MusicCommand.PerfArp(true) in d.update(st(1500, v = 2f, a = 1.5f), listOf(DrivingEvent.SmoothAcceleration(1500, 1.5f))))
        // 속도가 1km/h 이상 바뀌면 SpeedTone
        assertTrue(d.update(st(2000, v = 5f, a = 1.5f), emptyList()).any { it is MusicCommand.SpeedTone })
        // 가속이 끝나면 아르페지오 끔
        assertTrue(MusicCommand.PerfArp(false) in d.update(st(3000, v = 14f, a = 0.1f), emptyList()))
        // 방향지시등 → 셰이커
        assertTrue(MusicCommand.PerfShaker(true) in d.update(st(4000, v = 14f, turn = TurnSignal.LEFT), emptyList()))
        assertTrue(MusicCommand.PerfShaker(false) in d.update(st(6000, v = 14f), emptyList()))
        // 주행 중 브레이크 시작 → 0.4초 동안 부드러우면 근음+5도(한 번만)
        assertFalse(MusicCommand.PerfRootFifth in d.update(st(7000, v = 14f, brake = true), emptyList()))
        assertFalse(MusicCommand.PerfRootFifth in d.update(st(7300, v = 13.7f, a = -1.0f, brake = true), emptyList()))
        assertTrue(MusicCommand.PerfRootFifth in d.update(st(7400, v = 13.6f, a = -1.2f, brake = true), emptyList()))
        assertFalse(MusicCommand.PerfRootFifth in d.update(st(7500, v = 13.4f, a = -1.2f, brake = true), emptyList()))
        // 세게 밟으면(감속 ≥ 2.0) 근음+5도 대신 아무것도 안 함 → 급제동이면 테이프스톱
        d.update(st(7600, v = 13f), emptyList())
        d.update(st(7700, v = 13f, brake = true), emptyList())
        assertFalse(MusicCommand.PerfRootFifth in d.update(st(7900, v = 12f, a = -2.5f, brake = true), emptyList()))
        assertFalse(MusicCommand.PerfRootFifth in d.update(st(8200, v = 11f, a = -3.5f, brake = true), emptyList()))
        // 정차 2초(Stopped) → keys+synth만
        d.update(st(12_000, v = 0f, moving = false, brake = true), listOf(DrivingEvent.Stopped(12_000)))
        assertEquals(setOf(Layer.CHORDS, Layer.PAD), d.layers)
        // 정차 중 브레이크는 근음+5도 아님
        assertFalse(MusicCommand.PerfRootFifth in d.update(st(12_500, v = 0f, moving = false, brake = false), emptyList()))
        assertFalse(MusicCommand.PerfRootFifth in d.update(st(13_000, v = 0f, moving = false, brake = true), emptyList()))
        // 재출발 → 다시 드롭
        val dep2 = d.update(st(15_000, v = 1.2f, a = 1.5f), listOf(DrivingEvent.Departed(15_000)))
        assertTrue(MusicCommand.SetLayer(Layer.DRUMS, true, fast = true) in dep2 && MusicCommand.SetLayer(Layer.BASS, true, fast = true) in dep2)
        // 급제동 → 테이프스톱
        assertTrue(MusicCommand.PlayCue(Cue.TAPE_STOP) in d.update(st(20_000, v = 10f, brake = true), listOf(DrivingEvent.HarshBraking(20_000, 4f, true))))
    }
}
