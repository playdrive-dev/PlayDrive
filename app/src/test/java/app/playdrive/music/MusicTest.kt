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
        lateinit var pcm: Map<Layer, FloatArray>

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
        for ((l, d) in pcm) {
            assertEquals(l.name, timing.loopFrames, d.size)
            val peak = d.maxOf { abs(it) }
            assertTrue("$l peak=$peak", peak in 0.1f..1f)
            assertFalse("$l NaN", d.any { it.isNaN() })
        }
    }

    @Test fun `지속음 레이어는 루프 이음매가 끊기지 않는다`() {
        for (l in listOf(Layer.PAD, Layer.BASS, Layer.RAIN)) {
            val d = pcm.getValue(l)
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
    private val testPcm: Map<Layer, FloatArray> by lazy {
        val rnd = kotlin.random.Random(1)
        mapOf(
            Layer.PAD to FloatArray(timing.loopFrames) { (0.5 * kotlin.math.sin(2 * Math.PI * 440 * it / timing.sampleRate)).toFloat() },
            Layer.HIHAT to FloatArray(timing.loopFrames) { (rnd.nextFloat() * 2 - 1) * 0.5f },
        )
    }

    private fun render(m: LoopMixer, frames: Int): FloatArray = FloatArray(frames * 2).also { m.render(it, frames) }

    @Test fun `레이어 켜기는 다음 마디 경계에 맞춰 들어온다`() {
        val m = LoopMixer(timing, pcm)
        render(m, timing.barFrames / 2)                       // 마디 중간
        m.submit(MusicCommand.SetLayer(Layer.PAD, true))
        val rest = render(m, timing.barFrames / 2 - 100)      // 경계 직전까지
        assertTrue(rest.all { it == 0f })
        val after = render(m, timing.beatFrames)
        assertTrue(rms(after, timing.beatFrames / 2, timing.beatFrames) > 0.01)
    }

    @Test fun `방향지시등 필인은 다음 박 경계에서 합류`() {
        val m = LoopMixer(timing, pcm)
        render(m, timing.beatFrames / 4)
        m.submit(MusicCommand.SetLayer(Layer.HIHAT_FILL, true))
        val before = render(m, timing.beatFrames * 3 / 4 - 10)
        assertTrue(before.all { it == 0f })
        val after = render(m, timing.beatFrames / 2)
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
        render(m, timing.barFrames + timing.barFrames / 8)
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
        render(m, timing.barFrames)
        val b = timing.beatFrames
        val dry = rms(render(m, b), 0, b)
        m.submit(MusicCommand.LowpassDip)
        val wet = render(m, b)
        assertTrue(rms(wet, b / 4, b) < dry * 0.3)
        render(m, timing.barFrames * 2 + b)
        val recovered = rms(render(m, b), 0, b)
        assertTrue(recovered > dry * 0.9)
    }

    // ---------------------------------------------------------------- 연출
    private fun st(t: Long, moving: Boolean = false, gear: Gear = Gear.DRIVE, turn: TurnSignal = TurnSignal.OFF, rain: Boolean = false) =
        DrivingState(timeMs = t, moving = moving, gear = gear, turnSignal = turn, raining = rain)

    @Test fun `연출 - 인트로, 출발, 레이어 쌓기, 급제동, 비, 방향지시등, 정차, 주차`() {
        val d = MusicDirector(timing)
        d.update(st(0, gear = Gear.PARK), emptyList())
        assertEquals(setOf(Layer.PAD), d.layers)

        d.update(st(1000, moving = true), listOf(DrivingEvent.Departed(1000)))
        assertEquals(setOf(Layer.DRUMS, Layer.BASS, Layer.PAD), d.layers)

        d.update(st(1000 + timing.barMs * 2, moving = true), emptyList())
        assertTrue(Layer.HIHAT in d.layers)
        d.update(st(1000 + timing.barMs * 4, moving = true), emptyList())
        assertTrue(Layer.MELODY in d.layers)
        assertEquals(3, d.level)

        val cmds = d.update(st(20_000, moving = true), listOf(DrivingEvent.HarshBraking(20_000, 4f, true)))
        assertTrue(MusicCommand.TapeStop in cmds)
        assertEquals(1, d.level)
        assertFalse(Layer.MELODY in d.layers)

        d.update(st(21_000, moving = true, rain = true, turn = TurnSignal.LEFT), emptyList())
        assertTrue(Layer.RAIN in d.layers && Layer.HIHAT_FILL in d.layers)

        d.update(st(30_000, moving = false), listOf(DrivingEvent.Stopped(30_000)))
        assertFalse(Layer.DRUMS in d.layers)
        assertTrue(Layer.PAD in d.layers)

        d.update(st(32_000, gear = Gear.PARK), listOf(DrivingEvent.GearChanged(32_000, Gear.PARK), DrivingEvent.Parked(32_000)))
        assertEquals(setOf(Layer.PAD), d.layers)
    }
}
