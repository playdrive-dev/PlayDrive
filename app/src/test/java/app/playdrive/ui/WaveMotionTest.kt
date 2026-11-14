package app.playdrive.ui

import app.playdrive.music.VisualCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 물결 = 지금 연주되는 음악. 악기 수와 물결 1:1, 소리와 같은 순간의 파동(정점 = 박자), 상황별 모습, 움직임의 연속성,
 * 운전 방해 기준(밝기 변화 초당 10% 이내, 밝기 점멸 없음, 흐름 주기 20초 이상, CALM = 박자 파동 없음).
 */
class WaveMotionTest {
    private val score = File("src/main/res/raw/wave_score.txt").inputStream().use { WaveScore.parse(it) }
    private val all = Inst.entries.toSet()
    private fun take(n: Int) = listOf(Inst.CHORDS, Inst.PAD, Inst.DRUMS, Inst.BASS, Inst.HIHAT, Inst.MELODY).take(n).toSet()
    private val aspect = 1682f / 1210f

    /** 오디오 시계: t ms → 악보 칸(48kHz, 1000프레임 = 1칸). */
    private fun stepAt(t: Long) = (t * 48 / WaveScore.HOP).toInt()
    private fun fracAt(t: Long) = ((t * 48) % WaveScore.HOP).toFloat() / WaveScore.HOP

    /** 60fps로 흘리며 프레임을 모은다. */
    private fun run(w: WaveMotion, from: Long, to: Long, effect: EffectLevel = EffectLevel.LIVE, fps: Int = 60, sound: (Long) -> Sound): List<Pair<Long, WaveState>> {
        val out = ArrayList<Pair<Long, WaveState>>()
        var t = from
        val dt = 1000L / fps
        while (t <= to) {
            out += t to w.step(t, sound(t).copy(step = stepAt(t), stepFrac = fracAt(t)), effect)
            t += dt
        }
        return out
    }

    private fun driving(on: Set<Inst>) = Sound(playing = true, on = on, moving = true, life = on.size)

    @Test fun `악보 - 실제 스템에서 뽑은 1024칸, 킥은 박 근처, 멜로디·베이스 음 높이 있음`() {
        assertEquals(WaveScore.STEPS, score.steps)
        val kicks = (0 until 128).filter { score.kick[it] > 0.25f }
        assertTrue(kicks.isNotEmpty() && kicks.all { (it % 16) <= 3 || (it % 16) >= 14 })
        assertTrue((0 until 128).count { score.snare[it] > 0.25f } >= 2)
        assertTrue(score.bassPitch.count { it > 0f } > 200 && score.guitarPitch.count { it > 0f } > 200)
    }

    @Test fun `물결 한 줄 = 악기 하나 - 들어오면 서서히 차오르고 빠지면 그 물결만 잦아듦`() {
        val w = WaveMotion(score)
        var fr = run(w, 0, 12_000) { driving(take(4)) }.last().second
        Inst.entries.forEach { x -> assertEquals("$x", x in take(4), fr.lines[x.ordinal].edge > 0.98f) }
        val up = run(w, 12_016, 16_000) { driving(all) }
        assertTrue(up.first { it.first >= 12_300 }.second.lines[Inst.HIHAT.ordinal].edge in 0.2f..0.95f)
        fr = up.last().second
        assertTrue(fr.lines.all { it.edge > 0.98f && it.glow > 0.9f })
        val down = run(w, 16_016, 20_000) { driving(all - Inst.BASS) }
        assertTrue(down.first { it.first >= 16_500 }.second.lines[Inst.BASS.ordinal].fold > 0.5f)
        fr = down.last().second
        assertTrue(fr.lines[Inst.BASS.ordinal].edge < 0.02f && fr.lines[Inst.BASS.ordinal].glow < 0.02f)
        assertTrue((all - Inst.BASS).all { fr.lines[it.ordinal].edge > 0.98f })
    }

    @Test fun `킥 파동의 정점은 그 킥이 들리는 순간 - 악보를 미리 읽어 80ms 전부터 차오름`() {
        val w = WaveMotion(score)
        val fr = run(w, 0, 10_000) { driving(take(4)) }
        val kicks = w.pulses.filter { it.second == "kick" }.map { it.first }
        assertTrue(kicks.size > 10)
        for (t in kicks) assertTrue("t=$t", (stepAt(t) - 1..stepAt(t) + 1).any { score.at(score.kick, it) > 0.25f })
        val k = kicks[kicks.size / 2]
        assertTrue(fr.any { (t, s) -> t in k - 70 until k && s.pulses.any { p -> p.kind == 0 && p.t in -80f..0f } })
        val w2 = WaveMotion(score); run(w2, 0, 10_000) { driving(setOf(Inst.CHORDS, Inst.PAD)) }
        assertTrue(w2.pulses.none { it.second == "kick" || it.second == "snare" })
    }

    @Test fun `파동 포락선 - 정점에서 1, 상승은 짧고(80ms) 하강은 길게(0·6~0·9초), 연속 킥은 겹쳐 쌓이되 상한`() {
        assertEquals(1f, WaveGeom.env(0f), 1e-6f)
        assertEquals(0f, WaveGeom.env(-81f), 0f)
        assertTrue(WaveGeom.env(-40f) in 0.3f..0.7f)
        val tail = (0..2000).first { WaveGeom.env(it.toFloat()) < 0.1f }
        assertTrue("풀림 $tail ms", tail in 600..900)
        val one = WaveState(lines = List(6) { WaveLine(0.8f, 0f, 0f, 0f, 1f, 1f) }, pulses = listOf(Pulse(0f, WaveMotion.KICK_AMP, 0.5f, 0)))
        val two = one.copy(pulses = listOf(Pulse(333f, WaveMotion.KICK_AMP, 0.5f, 0), Pulse(0f, WaveMotion.KICK_AMP, 0.5f, 0)))
        val x = aspect / 2
        assertTrue(abs(WaveGeom.disp(two, 4, x, aspect)) > abs(WaveGeom.disp(one, 4, x, aspect)))
        val many = one.copy(pulses = List(6) { Pulse(0f, WaveMotion.WIDE_AMP, 0.5f, 1) })
        assertTrue(abs(WaveGeom.disp(many, 4, x, aspect)) < WaveGeom.CAP)
    }

    @Test fun `진행파 - 가운데서 좌우로 번지고(화면 폭 약 1초), 앞쪽 물결이 먼저`() {
        val base = WaveState(lines = List(6) { WaveLine(0.8f, 0f, 0f, 0f, 1f, 1f) })
        fun peakTime(i: Int, x: Float) = (-100..2000 step 5).maxBy { t -> abs(WaveGeom.disp(base.copy(pulses = listOf(Pulse(t.toFloat(), WaveMotion.KICK_AMP, 0.5f, 0))), i, x, aspect)) }
        val front = peakTime(4, aspect / 2)
        assertTrue(front in -5..5)
        val edgeT = peakTime(4, 0f)
        assertTrue("가장자리 도착 $edgeT ms", edgeT in 400..700)
        assertTrue(peakTime(0, aspect / 2) - front in 150..200)
        assertTrue(peakTime(3, aspect / 2) - front in 30..60)
    }

    @Test fun `움직임은 끊기지 않는다 - 60fps 프레임별 물결 높이 변화량에 튐 없음(600초, 모든 상황)`() {
        val w = WaveMotion(score)
        val rnd = kotlin.random.Random(5)
        var layers = 4; var nextChange = 0L
        var stopped = false; var countIn = false; var tapeAt = -1L; var moving = true
        val xs = listOf(0.15f, aspect / 2, aspect - 0.15f)
        var prevH: List<Float>? = null; var prevD: List<Float>? = null
        var worstD = 0f; var worstJerk = 0f; var at = 0L
        run(w, 0, 600_000) { t ->
            if (t >= nextChange) {
                nextChange = t + 1_500 + rnd.nextLong(6_000)
                when (rnd.nextInt(9)) {
                    0 -> { tapeAt = t; layers = (layers - 2).coerceAtLeast(0) }
                    1 -> { stopped = true; layers = 2 }
                    2 -> if (stopped) { stopped = false; countIn = true } else layers = 6
                    3 -> { countIn = false; stopped = false }
                    4 -> moving = !moving
                    else -> layers = (layers + if (rnd.nextBoolean()) 1 else -1).coerceIn(1, 6)
                }
            }
            val tape = if (tapeAt >= 0 && t - tapeAt < 1_500) ((t - tapeAt) / 700f).coerceAtMost(1f) else -1f
            val cue = if (t % 5_000 < 16) listOf(VisualCue.entries[((t / 5_000) % 6).toInt()] to 90f) else emptyList()
            Sound(playing = true, on = take(layers), moving = moving, life = if (stopped) 0 else layers, stopped = stopped, countIn = countIn,
                tape = tape, cues = cue, songBar = ((t / 2_667) % 65).toInt(), climax = (t / 2_667) % 65 > 48)
        }.forEach { (t, s) ->
            val h = (0 until 5).flatMap { i -> xs.map { x -> WaveGeom.height(s, i, x, aspect) } }
            prevH?.let { ph ->
                val d = h.zip(ph) { a, b -> a - b }
                d.forEach { worstD = maxOf(worstD, abs(it)) }
                prevD?.let { pd -> d.zip(pd) { a, b -> abs(a - b) }.max().let { if (it > worstJerk) { worstJerk = it; at = t } } }
                prevD = d
            }
            prevH = h
        }
        // 한 프레임 이동 ≤ 화면 높이 1%(약 12px — 가장 큰 움직임은 FAILED 때 맨 위 물결이 평평한 선으로 2초에 걸쳐 내려오는 것),
        // 프레임 사이 속도 변화 ≤ 0.3%(약 3.6px — 가장 큰 것은 넓은 파동이 정점 전 80ms에 차오르는 순간)
        println("60fps 한 프레임 최대 이동 $worstD, 최대 속도 변화 $worstJerk (t=$at)")
        assertTrue("한 프레임 최대 이동 $worstD", worstD < 0.010f)
        assertTrue("속도 튐 $worstJerk (t=$at)", worstJerk < 0.003f)
    }

    @Test fun `밝기 변화 초당 10% 이내 - 600초 주행(악기 증감·테이프스톱·FAILED·카운트인·보상·정차)`() {
        val w = WaveMotion(score)
        val rnd = kotlin.random.Random(3)
        var layers = 4; var nextChange = 0L
        var stopped = false; var countIn = false; var tapeAt = -1L
        val fr = run(w, 0, 600_000, fps = 30) { t ->
            if (t >= nextChange) {
                nextChange = t + 2_000 + rnd.nextLong(8_000)
                when (rnd.nextInt(8)) {
                    0 -> { tapeAt = t; layers = (layers - 2).coerceAtLeast(0) }
                    1 -> { stopped = true; layers = 2 }
                    2 -> if (stopped) { stopped = false; countIn = true } else layers = 6
                    3 -> countIn = false
                    else -> layers = (layers + if (rnd.nextBoolean()) 1 else -1).coerceIn(1, 6)
                }
            }
            val tape = if (tapeAt >= 0 && t - tapeAt < 1_500) ((t - tapeAt) / 700f).coerceAtMost(1f) else -1f
            val cue = if (t % 7_000 < 33) listOf(VisualCue.entries[((t / 7_000) % 6).toInt()] to 50f) else emptyList()
            Sound(playing = true, on = take(layers), moving = (t / 30_000) % 4 != 3L, life = if (stopped) 0 else layers, stopped = stopped, countIn = countIn,
                tape = tape, cues = cue, songBar = ((t / 2_667) % 65).toInt(), climax = (t / 2_667) % 65 > 48)
        }
        for (i in fr.indices) {
            val j = fr.indexOfFirst { it.first >= fr[i].first + 1_000 }.takeIf { it >= 0 } ?: break
            val d = abs(fr[j].second.luma() - fr[i].second.luma())
            assertTrue("t=${fr[i].first} 1초 밝기 변화 $d", d <= WaveMotion.MAX_LUMA_PER_S + 1e-4f)
        }
    }

    @Test fun `타격은 모양만 움직이고 밝기 점멸은 없다`() {
        val w = WaveMotion(score)
        val fr = run(w, 0, 60_000) { driving(all) }.drop(900)
        val l = fr.map { it.second.luma() }
        var flashes = 0; var low = l[0]; var high = l[0]; var rising = true
        for (v in l) {
            if (rising) { high = maxOf(high, v); if (high - v > 0.005f) { flashes++; rising = false; low = v } }
            else { low = minOf(low, v); if (v - low > 0.005f) { rising = true; high = v } }
        }
        assertTrue("밝기 왕복 $flashes 번/분", flashes <= 60)
        assertTrue(w.pulses.count { it.second == "kick" } > 60)
    }

    @Test fun `흐름 한 주기 20초 이상, 위상은 이어지고, 정차하면 거의 멈춘 듯 느리게`() {
        assertTrue(WaveMotion.PERIODS.all { it >= 20f })
        fun speed(moving: Boolean): Float {
            val w = WaveMotion(score)
            val fr = run(w, 0, 20_000) { driving(take(4)).copy(moving = moving) }
            return (fr.last().second.lines[0].phase - fr[fr.size - 61].second.lines[0].phase)
        }
        assertTrue(speed(false) < speed(true) * 0.3f)
        val w = WaveMotion(score)
        run(w, 0, 5_000) { driving(take(4)) }
        val st = run(w, 5_016, 10_000) { Sound(playing = true, on = setOf(Inst.CHORDS, Inst.PAD), moving = false, life = 4) }.last().second
        assertTrue(st.lines[Inst.DRUMS.ordinal].edge < 0.02f && st.lines[Inst.BASS.ordinal].edge < 0.02f)
        assertTrue(st.lines[Inst.CHORDS.ordinal].edge > 0.98f && st.lines[Inst.PAD.ordinal].edge > 0.98f)
    }

    @Test fun `위험 - 낮고 느리고 바램, FAILED - 평평한 선으로 눕고 어두워짐, 카운트인 스틱 4번이 점점 커지고 다시 일어남`() {
        val w = WaveMotion(score)
        val norm = run(w, 0, 8_000) { driving(take(4)) }.last().second
        val danger = run(w, 8_016, 12_000) { driving(take(1)) }.last().second
        assertTrue(danger.fade > 0.98f)
        assertTrue(danger.lines[Inst.CHORDS.ordinal].amp < norm.lines[Inst.CHORDS.ordinal].amp * 0.7f)
        assertTrue(danger.lines[Inst.CHORDS.ordinal].y > norm.lines[Inst.CHORDS.ordinal].y)
        val failed = run(w, 12_016, 18_000) { Sound(playing = true, on = setOf(Inst.CHORDS, Inst.PAD), moving = true, life = 0, stopped = true, tape = if (it < 12_800) (it - 12_016) / 700f else -1f) }
        val f = failed.last().second
        assertTrue(f.flat > 0.98f && f.dim > 0.25f)
        assertTrue(failed.first { it.first >= 12_780 }.second.sag > 0.7f)
        val stickAt = (0 until 4).map { 18_100L + it * 667 }
        val ci = run(w, 18_016, 21_000) { t -> Sound(playing = true, on = setOf(Inst.CHORDS, Inst.PAD), moving = true, life = 0, countIn = true, cues = stickAt.filter { s -> t in s - 100..s - 84 }.map { VisualCue.STICK to 90f }) }
        assertEquals(4, w.pulses.count { it.second == "stick" })
        val amps = ci.last().second.pulses.filter { it.kind == 1 }.map { it.amp }
        assertTrue("$amps", amps.size >= 3 && amps.zipWithNext().all { (a, b) -> b > a })
        assertTrue(ci.last().second.flat > 0.98f)
        val back = run(w, 21_016, 24_000) { driving(take(3)) }.last().second
        assertTrue(back.flat < 0.02f)
        assertTrue(take(3).all { back.lines[it.ordinal].edge > 0.98f })
    }

    @Test fun `곡 진행 - 64마디에 걸쳐 높아지고, 클라이맥스에 가장 높게, 다음 곡에서 잔잔해짐`() {
        val w = WaveMotion(score)
        val a = run(w, 0, 10_000) { driving(all).copy(songBar = 2) }.last().second
        val b = run(w, 10_016, 20_000) { driving(all).copy(songBar = 40) }.last().second
        val c = run(w, 20_016, 30_000) { driving(all).copy(songBar = 63, climax = true) }.last().second
        val d = run(w, 30_016, 42_000) { driving(take(4)).copy(songBar = 1) }.last().second
        val y = { s: WaveState -> s.lines[Inst.CHORDS.ordinal].y }
        assertTrue(y(b) < y(a) && y(c) < y(b) && y(d) > y(c))
        assertTrue(c.lines[Inst.CHORDS.ordinal].amp > a.lines[Inst.CHORDS.ordinal].amp)
    }

    @Test fun `보상은 소리가 들리는 순간에 정점, 해당 물결이 한 번 더 크게`() {
        val w = WaveMotion(score)
        run(w, 0, 6_000) { driving(all) }
        run(w, 6_016, 6_016) { driving(all).copy(cues = listOf(VisualCue.DROP to 80f)) }
        assertEquals(6_096L, w.pulses.first { it.second == "drop" }.first)
        val after = run(w, 6_032, 6_300) { driving(all) }
        val peak = after.first { it.first >= 6_096 }.second
        assertTrue(peak.pulses.any { it.kind == 1 && abs(it.t) < 20f })
        assertTrue(after.maxOf { it.second.lines[Inst.DRUMS.ordinal].swell } > 0.4f && after.maxOf { it.second.lines[Inst.BASS.ordinal].swell } > 0.4f)
    }

    @Test fun `CALM - 악기별 물결은 유지, 박자 파동·보상 파동 없음, 멜로디는 잔잔한 선`() {
        val w = WaveMotion(score)
        val fr = run(w, 0, 30_000, EffectLevel.CALM) { t -> driving(all).copy(cues = if (t % 5_000 < 16) listOf(VisualCue.FILL to 50f) else emptyList()) }
        assertTrue(w.pulses.isEmpty())
        val last = fr.last().second
        assertTrue(last.lines.all { it.edge > 0.98f })
        assertTrue(last.pulses.isEmpty())
        assertTrue(last.melY.all { abs(it - 0.5f) < 0.01f })
    }

    @Test fun `음악이 없으면 패드·코드 물결만 잔잔하게, 입자·반짝임 없음`() {
        val w = WaveMotion(score)
        val f = run(w, 0, 5_000) { Sound() }.last().second
        assertTrue(f.lines[Inst.PAD.ordinal].edge > 0.5f && f.lines[Inst.CHORDS.ordinal].edge > 0.5f)
        assertFalse(listOf(Inst.DRUMS, Inst.BASS, Inst.HIHAT, Inst.MELODY).any { f.lines[it.ordinal].edge > 0.001f })
        assertTrue(w.pulses.isEmpty())
    }

    @Test fun `스프링 - 목표가 한 번에 바뀌어도 값은 이어지고 넘치지 않음`() {
        val sp = Spring(0f, 6f)
        var prev = 0f; var prevV = 0f
        for (k in 1..120) {
            val v = sp.step(1f, 1f / 60)
            assertTrue(v <= 1f + 1e-5f)
            assertTrue("프레임 $k", abs((v - prev) - prevV) < 0.05f)
            prevV = v - prev; prev = v
        }
        assertTrue(prev > 0.98f)
    }
}
