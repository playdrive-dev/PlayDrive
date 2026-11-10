package app.playdrive.music

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import app.playdrive.model.TurnSignal
import app.playdrive.music.synth.SynthLayerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MusicTest {

    companion object {
        val timing = MusicTiming()
        lateinit var pcm: Map<Layer, StereoPcm>

        @BeforeClass @JvmStatic fun renderLayers() {
            pcm = SynthLayerSource.all().associate { it.layer to it.load(timing) }
        }
    }

    // ---------------------------------------------------------------- 작곡·합성
    @Test fun `음높이 있는 레이어는 마디 코드 구성음만 쓴다(불협화음 금지)`() {
        for (bar in 0 until 4) {
            val chord = Composition.chords[bar]
            Composition.pad[bar].forEach { assertTrue("pad bar$bar $it", it % 12 in chord) }
            Composition.bass[bar].forEach { assertTrue("bass bar$bar ${it.midi}", it.midi % 12 in chord) }
            Composition.melody[bar].forEach { assertTrue("melody bar$bar ${it.midi}", it.midi % 12 in chord) }
        }
    }

    @Test fun `타이밍 - 90BPM 4마디 루프`() {
        assertEquals(32_000, timing.beatFrames)
        assertEquals(512_000, timing.loopFrames)
        assertEquals(2_666, timing.barMs)
    }

    @Test fun `모든 레이어는 루프 길이가 같고 피크가 1 이하, NaN 없음`() {
        assertEquals(Layer.entries.size, pcm.size)
        for ((l, p) in pcm) {
            val d = p.left
            assertEquals(l.name, timing.loopFrames, d.size)
            val peak = d.maxOf { abs(it) }
            assertTrue("$l peak=$peak", peak in 0.1f..1f)
            assertFalse("$l NaN", d.any { it.isNaN() })
        }
    }

    @Test fun `지속음 레이어는 루프 이음매가 끊기지 않는다`() {
        for (l in listOf(Layer.PAD, Layer.BASS, Layer.RAIN)) {
            val d = pcm.getValue(l).left
            val jump = abs(d[0] - d[d.size - 1])
            val typical = d.toList().zipWithNext { a, b -> abs(a - b) }.sorted()[d.size * 99 / 100]
            assertTrue("$l seam jump=$jump typical99=$typical", jump <= typical * 2 + 1e-3f)
        }
    }

    // ---------------------------------------------------------------- 믹서
    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0; for (i in from until to) s += x[2 * i] * x[2 * i]; return sqrt(s / (to - from))
    }

    /** 효과 검증용 결정적 신호: PAD = 440Hz 사인, HIHAT = 백색잡음. */
    private val testPcm: Map<Layer, StereoPcm> by lazy {
        val rnd = kotlin.random.Random(1)
        mapOf(
            Layer.PAD to StereoPcm.mono(FloatArray(timing.loopFrames) { (0.5 * kotlin.math.sin(2 * Math.PI * 440 * it / timing.sampleRate)).toFloat() }),
            Layer.HIHAT to StereoPcm.mono(FloatArray(timing.loopFrames) { (rnd.nextFloat() * 2 - 1) * 0.5f }),
            // 스테레오 스템 흉내: 왼쪽 = 사인, 오른쪽 = 0
            Layer.CHORDS to StereoPcm(FloatArray(timing.loopFrames) { (0.5 * kotlin.math.sin(2 * Math.PI * 220 * it / timing.sampleRate)).toFloat() }, FloatArray(timing.loopFrames)),
        )
    }
    private val testCue: Map<Cue, StereoPcm> by lazy {
        val n = timing.sampleRate * 5 // 5초 원샷
        mapOf(Cue.TAPE_STOP to StereoPcm.mono(FloatArray(n) { (0.5 * kotlin.math.sin(2 * Math.PI * 1000 * it / timing.sampleRate)).toFloat() }))
    }

    private fun render(m: LoopMixer, frames: Int): FloatArray = FloatArray(frames * 2).also { m.render(it, frames) }

    @Test fun `레이어 켜기는 다음 마디 경계에 맞춰 들어온다`() {
        val m = LoopMixer(timing, pcm)
        render(m, timing.barFrames / 2)                       // 마디 중간
        m.submit(MusicCommand.SetLayer(Layer.PAD, true))
        val rest = render(m, timing.barFrames / 2 - 100)      // 경계 직전까지
        assertTrue(rest.all { it == 0f })
        val after = render(m, timing.barFrames)
        assertTrue(rms(after, timing.barFrames / 2, timing.barFrames) > 0.01) // 0.5마디 페이드 후
    }

    @Test fun `방향지시등 필인은 다음 박 경계에서 합류`() {
        val m = LoopMixer(timing, pcm)
        render(m, timing.beatFrames / 4)
        m.submit(MusicCommand.SetLayer(Layer.HIHAT_FILL, true))
        val before = render(m, timing.beatFrames * 3 / 4 - 10)
        assertTrue(before.all { it == 0f })
        val after = render(m, timing.beatFrames * 2)
        assertTrue(after.any { it != 0f })
    }

    @Test fun `모든 레이어를 동시에 켜도 클리핑 없음`() {
        val m = LoopMixer(timing, pcm)
        Layer.entries.forEach { m.submit(MusicCommand.SetLayer(it, true)) }
        val out = render(m, timing.loopFrames + timing.barFrames)
        assertTrue(out.maxOf { abs(it) } <= 1f)
    }

    @Test fun `급제동 테이프 스톱은 0_25초 안에 반응하고 마디 경계에서 복귀`() {
        val m = LoopMixer(timing, testPcm)
        m.submit(MusicCommand.SetLayer(Layer.PAD, true))
        render(m, timing.barFrames * 2 + timing.barFrames / 8) // 페이드(0.5마디)가 끝난 뒤
        val sr = timing.sampleRate
        val before = rms(render(m, sr / 10), 0, sr / 10)
        m.submit(MusicCommand.TapeStop)
        val stopStart = m.gridFrame
        val during = render(m, sr / 4)
        // 0.2~0.25초 시점에 이미 음량이 확실히 줄었다(반응 0.25초 이내)
        assertTrue(rms(during, sr / 5, sr / 4) < before * 0.75)
        // 램프(0.6초) 후 완전 정지
        render(m, sr * 4 / 10)
        val silent = render(m, sr / 10)
        assertTrue(rms(silent, 0, sr / 10) < 1e-4)
        // 다음 마디 경계 이후 복귀
        val resumeAt = timing.nextBoundary(stopStart + (0.75 * sr).toLong(), timing.barFrames)
        render(m, (resumeAt - m.gridFrame).toInt())
        val back = render(m, sr / 10)
        assertTrue(rms(back, sr / 20, sr / 10) > before * 0.9)
    }

    @Test fun `로우패스 딥은 고음을 줄였다가 회복한다`() {
        val m = LoopMixer(timing, testPcm)
        m.submit(MusicCommand.SetLayer(Layer.HIHAT, true))
        render(m, timing.barFrames * 2)
        val b = timing.beatFrames
        val dry = rms(render(m, b), 0, b)
        m.submit(MusicCommand.LowpassDip)
        val wet = render(m, b)
        assertTrue(rms(wet, b / 4, b) < dry * 0.3)
        render(m, timing.barFrames * 2 + b)
        val recovered = rms(render(m, b), 0, b)
        assertTrue(recovered > dry * 0.9)
    }

    @Test fun `스테레오 스템은 좌우를 그대로 쓴다`() {
        val m = LoopMixer(timing, testPcm)
        m.submit(MusicCommand.SetLayer(Layer.CHORDS, true))
        val out = render(m, timing.barFrames * 2)
        var l = 0.0; var r = 0.0
        for (i in timing.barFrames until timing.barFrames * 2) { l += out[2 * i] * out[2 * i]; r += out[2 * i + 1] * out[2 * i + 1] }
        assertTrue(l > 1.0 && r == 0.0)
    }

    @Test fun `효과음 큐는 즉시 재생되고 음악은 덕킹 후 마디 경계에서 복귀`() {
        val m = LoopMixer(timing, testPcm, testCue, LoopMixer.Config(cueMaxMs = 2_000))
        m.submit(MusicCommand.SetLayer(Layer.PAD, true))
        render(m, timing.barFrames * 2 + timing.barFrames / 4)
        val sr = timing.sampleRate
        val before = rms(render(m, sr / 10), 0, sr / 10)
        m.submit(MusicCommand.PlayCue(Cue.TAPE_STOP))
        val first = render(m, sr / 4)
        // 0.25초 안에 1kHz 효과음이 섞여 들어옴(신호 모양이 바뀜) → 단순 비교: 출력이 PAD만일 때와 다르다
        assertTrue(rms(first, sr / 10, sr / 4) != before)
        assertTrue(m.availableCues.contains(Cue.TAPE_STOP))
        // 2초 + 다음 마디 경계 + 0.5마디 복귀 후엔 원래 음량
        render(m, sr * 2 + timing.barFrames * 2)
        val after = rms(render(m, sr / 10), 0, sr / 10)
        assertEquals(before, after, before * 0.1)
    }

    @Test fun `효과음이 없으면 DSP 테이프 스톱으로 대체`() {
        val m = LoopMixer(timing, testPcm)
        m.submit(MusicCommand.SetLayer(Layer.PAD, true))
        render(m, timing.barFrames * 2)
        m.submit(MusicCommand.PlayCue(Cue.TAPE_STOP))
        render(m, (0.7 * timing.sampleRate).toInt())
        val silent = render(m, timing.sampleRate / 20)
        assertTrue(rms(silent, 0, timing.sampleRate / 20) < 1e-4)
    }

    // ---------------------------------------------------------------- 연출
    private fun st(t: Long, moving: Boolean = false, gear: Gear = Gear.DRIVE, turn: TurnSignal = TurnSignal.OFF, rain: Boolean = false) =
        DrivingState(timeMs = t, moving = moving, gear = gear, turnSignal = turn, raining = rain)

    private val stems = setOf(Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.CHORDS, Layer.MELODY)

    @Test fun `연출 - 요구 매핑(pad → +drums·hat → +bass·chords → +melody)`() {
        val d = MusicDirector(timing, stems)
        val bar = timing.barMs
        d.update(st(0, gear = Gear.PARK), emptyList())
        assertEquals(setOf(Layer.PAD), d.layers)                                     // 정차
        d.update(st(1000, moving = true), listOf(DrivingEvent.Departed(1000)))
        assertEquals(setOf(Layer.PAD), d.layers)                                     // 출발
        d.update(st(1500, moving = true), listOf(DrivingEvent.SmoothAcceleration(1500, 1.5f)))
        assertEquals(setOf(Layer.PAD, Layer.DRUMS, Layer.HIHAT), d.layers)           // 부드러운 가속
        d.update(st(9000, moving = true), listOf(DrivingEvent.CruiseStarted(9000, 16f)))
        assertEquals(setOf(Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.CHORDS), d.layers) // 안정 주행
        d.update(st(9000 + bar * 4, moving = true), emptyList())
        assertTrue(Layer.MELODY in d.layers)                                         // 오래 부드러운 주행
        assertEquals(3, d.stage)
    }

    @Test fun `연출 - 급제동은 테이프스톱, 급조향은 스크래치, 이후 한 마디에 하나씩 복구`() {
        val d = MusicDirector(timing, stems)
        val bar = timing.barMs
        d.update(st(0, moving = true), listOf(DrivingEvent.Departed(0), DrivingEvent.SmoothAcceleration(0, 1f), DrivingEvent.CruiseStarted(0, 15f)))
        d.update(st(bar * 5, moving = true), emptyList())
        assertEquals(6, d.layers.size)
        val t = bar * 6
        val cmds = d.update(st(t, moving = true), listOf(DrivingEvent.HarshBraking(t, 4f, true)))
        assertTrue(MusicCommand.PlayCue(Cue.TAPE_STOP) in cmds)
        assertEquals(setOf(Layer.PAD), d.layers)
        val counts = (1..6).map { k -> d.update(st(t + bar * k, moving = true), emptyList()); d.layers.size }
        assertEquals(listOf(2, 3, 4, 5, 6, 6), counts)                               // 한 마디에 하나씩
        val c2 = d.update(st(t + bar * 8, moving = true), listOf(DrivingEvent.HarshSteering(t + bar * 8, 300f)))
        assertTrue(MusicCommand.PlayCue(Cue.SCRATCH) in c2)
    }

    @Test fun `연출 - 신호대기 브레이크다운, 방향지시등, 주차`() {
        val d = MusicDirector(timing, stems)
        d.update(st(0, moving = true), listOf(DrivingEvent.Departed(0), DrivingEvent.SmoothAcceleration(0, 1f), DrivingEvent.CruiseStarted(0, 15f)))
        d.update(st(1000, moving = false), listOf(DrivingEvent.Stopped(1000)))
        assertFalse(Layer.DRUMS in d.layers); assertTrue(Layer.BASS in d.layers)
        d.update(st(2000, moving = true, turn = TurnSignal.LEFT), listOf(DrivingEvent.Departed(2000)))
        assertTrue(Layer.HIHAT in d.layers)
        d.update(st(3000, gear = Gear.PARK), listOf(DrivingEvent.Parked(3000)))
        assertEquals(setOf(Layer.PAD), d.layers)
        // 신스 모드(필인·빗소리 레이어 있음)
        val s = MusicDirector(timing)
        s.update(st(0, moving = true, turn = TurnSignal.RIGHT, rain = true), listOf(DrivingEvent.Departed(0)))
        assertTrue(Layer.HIHAT_FILL in s.layers && Layer.RAIN in s.layers)
    }

    // ---------------------------------------------------------------- SONG 세트
    @Test fun `SONG 편곡 - keys+synth → +drums+perc → +bass → +guitar, 비는 RainFx`() {
        val song = setOf(Layer.CHORDS, Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS, Layer.MELODY)
        val t8 = MusicTiming(bars = 8)
        val d = MusicDirector(t8, song, Arrangement.SONG)
        val bar = t8.barMs
        d.update(st(0, gear = Gear.PARK), emptyList())
        assertEquals(setOf(Layer.CHORDS, Layer.PAD), d.layers)
        d.update(st(1000, moving = true), listOf(DrivingEvent.Departed(1000), DrivingEvent.SmoothAcceleration(1000, 1.5f)))
        assertEquals(setOf(Layer.CHORDS, Layer.PAD, Layer.DRUMS, Layer.HIHAT), d.layers)
        d.update(st(9000, moving = true), listOf(DrivingEvent.CruiseStarted(9000, 16f)))
        assertEquals(setOf(Layer.CHORDS, Layer.PAD, Layer.DRUMS, Layer.HIHAT, Layer.BASS), d.layers)
        d.update(st(9000 + bar * 4, moving = true), emptyList())
        assertTrue(Layer.MELODY in d.layers)
        val on = d.update(st(20_000, moving = true, rain = true), emptyList())
        assertTrue(MusicCommand.RainFx(true) in on)
        assertFalse(Layer.RAIN in d.layers)
        val off = d.update(st(21_000, moving = true, rain = false), emptyList())
        assertTrue(MusicCommand.RainFx(false) in off)
        // 급제동 후 keys+synth만 남고 한 마디씩 복구
        d.update(st(30_000, moving = true), listOf(DrivingEvent.HarshBraking(30_000, 4f, true)))
        assertEquals(setOf(Layer.CHORDS, Layer.PAD), d.layers)
        d.update(st(30_000 + bar, moving = true), emptyList())
        assertEquals(3, d.layers.size)
    }

    @Test fun `비 효과는 고역을 줄이고 리버브 꼬리를 남긴다`() {
        val m = LoopMixer(timing, testPcm)
        m.submit(MusicCommand.SetLayer(Layer.HIHAT, true))
        render(m, timing.barFrames * 2)
        val b = timing.beatFrames
        val dry = rms(render(m, b), 0, b)
        m.submit(MusicCommand.RainFx(true))
        render(m, timing.barFrames * 2)               // 1마디에 걸쳐 적용
        val wet = rms(render(m, b), 0, b)
        assertTrue("dry=$dry wet=$wet", wet < dry * 0.6)
        m.submit(MusicCommand.SetLayer(Layer.HIHAT, false))
        val untilBoundary = (timing.nextBoundary(m.gridFrame, timing.barFrames) - m.gridFrame).toInt()
        render(m, untilBoundary + timing.barFrames / 2 + 200) // 다음 마디에서 0.5마디 페이드아웃 완료 직후
        val tail = render(m, timing.sampleRate / 20)
        assertTrue("리버브 꼬리", rms(tail, 0, timing.sampleRate / 20) > 1e-6)
        assertTrue(tail.maxOf { kotlin.math.abs(it) } <= 1f)
    }
}
