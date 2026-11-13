package app.playdrive.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 연주 기록 보상(필인·합류·클라이맥스·판정 소리)과 음성 덕킹. */
class PlayMusicTest {
    private val t8 = MusicTiming(bars = 8)
    private val eighth = t8.beatFrames / 2

    private fun perf() = PerformanceLayer(t8, ChordTimeline.SONG).also { it.recordPlayed = true }
    private fun run(p: PerformanceLayer, from: Long, to: Long) { var g = from; while (g < to) { p.process(g); g++ } }

    /** 연주음이 그 순간 코드의 구성음인지. */
    private fun assertChordTones(p: PerformanceLayer) {
        assertTrue(p.played.isNotEmpty())
        for ((at, notes) in p.played) {
            val c = ChordTimeline.SONG.chordAt(at, t8)
            notes.forEach { assertTrue("${c.name} $it @$at", it % 12 in c.pitchClasses) }
        }
    }

    @Test fun `판정 보상 — 드롭 강화는 다음 박, 마무리 화음은 다음 8분음표, 모두 코드 구성음`() {
        for (g0 in listOf(1L, 30_000L, t8.barFrames - 5L)) {
            val p = perf()
            p.command(MusicCommand.PerfReward(MusicCommand.Reward.DROP), g0)
            p.command(MusicCommand.PerfReward(MusicCommand.Reward.CADENCE), g0)
            run(p, g0, g0 + t8.barFrames)
            val drop = p.played.first { it.second.size == 3 }.first
            assertEquals(0L, drop % t8.beatFrames); assertTrue(drop - g0 in 0..t8.beatFrames.toLong())
            val cad = p.played.first { it.second.size == 5 }.first
            assertEquals(0L, cad % eighth); assertTrue(cad - g0 in 0..eighth.toLong())
            assertChordTones(p)
        }
    }

    @Test fun `심벌 스웰은 박 경계에서 끝나고 그 박에 심벌`() {
        val p = perf(); val g0 = 10_000L
        p.command(MusicCommand.PerfReward(MusicCommand.Reward.SWELL), g0)
        run(p, g0, g0 + t8.barFrames)
        val swell = p.noiseHits.first { it.second == "swell" }.first
        val cym = p.noiseHits.first { it.second == "cymbal" }.first
        assertEquals(t8.beatFrames.toLong(), cym - swell)
        assertEquals(0L, cym % t8.beatFrames)
        assertTrue(swell >= g0)
    }

    @Test fun `필인은 마디 마지막 박의 16분음표 롤 + 다음 다운비트 심벌`() {
        val p = perf(); val g0 = 5_000L
        p.command(MusicCommand.PerfFill, g0)
        run(p, g0, 2L * t8.barFrames + 10)
        val rolls = p.noiseHits.filter { it.second == "roll" }.map { it.first }
        val lastBeat = t8.barFrames - t8.beatFrames.toLong()
        assertEquals(List(4) { lastBeat + it * t8.beatFrames / 4L }, rolls)
        assertEquals(t8.barFrames.toLong(), p.noiseHits.single { it.second == "cymbal" }.first)
    }

    @Test fun `합류는 박마다, 클라이맥스는 8분음표마다 코드톤 — 테이프스톱이면 멈춤`() {
        val p = perf()
        p.command(MusicCommand.PerfBed(1), 0)
        run(p, 0, t8.barFrames.toLong())
        assertEquals(4, p.played.size)
        p.played.forEach { assertEquals(0L, it.first % t8.beatFrames) }
        p.played.clear()
        p.command(MusicCommand.PerfBed(2), t8.barFrames.toLong())
        run(p, t8.barFrames.toLong(), 3L * t8.barFrames)
        val single = p.played.filter { it.second.size == 1 }
        assertEquals(16, single.size)                                         // 두 마디 × 8분음표 8개
        assertEquals(2, p.played.count { it.second.size == 3 })               // 다운비트 화음
        assertChordTones(p)
        p.silence(3L * t8.barFrames, 4L * t8.barFrames)
        assertEquals(0, p.bedLevel)
    }

    @Test fun `연주 기록 보상은 직접 PerfArp와 겹쳐 두 번 치지 않는다`() {
        val p = perf()
        p.command(MusicCommand.PerfBed(1), 0)
        p.command(MusicCommand.PerfArp(true), 0)
        run(p, 0, t8.barFrames.toLong())
        assertEquals(4, p.played.size)
    }

    // ------------------------------------------------------------------ 음성 덕킹
    private fun mixer(): LoopMixer {
        val tone = FloatArray(t8.loopFrames) { (0.3 * kotlin.math.sin(2 * Math.PI * 220 * it / 48_000.0)).toFloat() }
        return LoopMixer(t8, mapOf(Layer.PAD to StereoPcm.mono(tone)))
    }

    @Test fun `음성 동안 음악은 -12dB, 끝나면 1초에 걸쳐 복귀`() {
        val m = mixer()
        m.submit(MusicCommand.SetLayer(Layer.PAD, true, fast = true))
        val out = FloatArray(2 * 960)
        repeat(300) { m.render(out, 960) } // 6초: 레이어 켜짐
        val clip = StereoPcm.mono(FloatArray(48_000) { 0.1f })   // 1초짜리 음성
        m.submit(MusicCommand.Speak(listOf(clip), "test"))
        repeat(10) { m.render(out, 960) }                       // 200ms 뒤
        assertEquals(0.2512f, m.voiceDuckGain, 1e-3f)
        assertTrue(m.speaking)
        repeat(50) { m.render(out, 960) }                       // 음성 끝(약 1.08초) 지남
        assertTrue(!m.speaking)
        val afterEnd = m.voiceDuckGain
        assertTrue("복귀 중 $afterEnd", afterEnd > 0.2512f && afterEnd < 1f)
        repeat(50) { m.render(out, 960) }                       // +1초
        assertEquals(1f, m.voiceDuckGain, 1e-3f)
        // 20log10(0.2512) = −12dB
        assertEquals(-12.0, 20 * kotlin.math.log10(0.2512), 0.01)
    }

    @Test fun `여러 문장은 짧은 쉼을 두고 이어서, 그동안 덕킹 유지`() {
        val m = mixer()
        val out = FloatArray(2 * 960)
        val a = StereoPcm.mono(FloatArray(24_000) { 0.2f }); val b = StereoPcm.mono(FloatArray(24_000) { -0.2f })
        m.submit(MusicCommand.Speak(listOf(a, b), "two"))
        var minDuckWhileSpeaking = 1f; var frames = 0
        while (frames < 48_000 * 3) {
            m.render(out, 960); frames += 960
            if (m.speaking) minDuckWhileSpeaking = minOf(minDuckWhileSpeaking, m.voiceDuckGain)
            if (m.speaking && frames > 48_000 / 5) assertTrue(abs(m.voiceDuckGain - 0.2512f) < 1e-3f)
        }
        assertTrue(!m.speaking)
    }
}
