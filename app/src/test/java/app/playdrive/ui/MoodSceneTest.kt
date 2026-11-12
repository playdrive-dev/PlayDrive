package app.playdrive.ui

import app.playdrive.album.MoodState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 주행 중(requiresDO) 화면 정책 "이벤트 때만 한 번 반응(최소 변화)":
 * 기본은 정지, 음악 이벤트(레이어 증감·테이프스톱·와이퍼)에서만 전환(≤2초, 시작 간격 ≥10초), 전환 밖에선 프레임 불변.
 */
class MoodSceneTest {
    private val calm = MoodState(0.2f, 0.9f, 0f, false)
    private val busy = MoodState(1f, 0.1f, 1f, true)

    /** MoodView처럼 이벤트를 넣고, 알려 준 시각에 tick 하며 100ms마다 프레임을 본다. */
    private class Sim(val sc: MoodScene, var t: Long) {
        val frames = mutableListOf<Pair<Long, MoodFrame>>()
        fun music(layers: Int, tape: Boolean = false, rain: Boolean = false) { sc.music(layers, tape, rain, t) }
        fun run(untilMs: Long, events: Map<Long, () -> Unit> = emptyMap()) {
            while (t < untilMs) {
                t += 100
                events[t]?.invoke()
                sc.nextWakeMs(t)?.let { if (it <= t) sc.tick(t) }
                frames += t to sc.frame(t, beatPhase = (t % 667) / 667f)
            }
        }
    }

    private fun assertDriveRules(sc: MoodScene, frames: List<Pair<Long, MoodFrame>>) {
        val tr = sc.driveTransitions
        tr.zipWithNext().forEach { (a, b) -> assertTrue("간격 ${b.first - a.first}ms", b.first - a.first >= DriveMotion.MIN_GAP_MS) }  // (2)
        tr.forEach { (_, d) -> assertTrue("길이 ${d}ms", d in 1..DriveMotion.MAX_TRANSITION_MS) }                                     // (3)
        fun inTransition(a: Long, b: Long) = tr.any { (s, d) -> b >= s && a <= s + d }
        frames.zipWithNext().forEach { (a, b) ->                                                                                       // (1)
            if (!inTransition(a.first, b.first)) assertEquals("t=${b.first}", a.second, b.second)
        }
    }

    @Test fun `주행 중 600초 - 전환 밖 프레임 불변, 간격 10초 이상, 길이 2초 이하`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.music(2, false, false, 0)
        sc.show(busy, 1_000)                      // 정차 중 전환을 시작해 두고
        sc.setRestricted(true, 2_345)             // 전환·숨쉬기 도중에 주행 시작
        assertFalse(sc.animating)
        val sim = Sim(sc, 2_300)
        // 이벤트를 촘촘하게: 2.7초마다 레이어 증감, 30초마다 테이프스톱, 47초마다 비 ON/OFF, 사이사이 음악 상태 변화
        var layers = 2; var rain = false
        val ev = mutableMapOf<Long, () -> Unit>()
        var k = 0
        for (ms in 2_400L..602_300L step 100) {
            if (ms % 2_700 == 0L) ev[ms] = { layers = if (++k % 5 == 0) 2 else (layers + 1).coerceAtMost(6); sim.music(layers, rain = rain) }
            if (ms % 30_000 == 0L) ev[ms] = { layers = 2; sim.music(layers, tape = true, rain = rain) }
            if (ms % 47_000 == 0L) ev[ms] = { rain = !rain; sim.music(layers, rain = rain) }
            if (ms % 7_000 == 0L) ev.putIfAbsent(ms) { sc.show(if (ms % 14_000 == 0L) calm else busy, ms) }
        }
        sim.run(602_400, ev)
        assertTrue("전환이 일어남", sc.driveTransitions.size > 20)
        assertDriveRules(sc, sim.frames)
        assertTrue(sc.pending != null)            // 음악 상태(색·물결)는 보관만
    }

    @Test fun `주행 중 이벤트가 없으면 완전 정지`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.music(4, false, false, 0)
        sc.setRestricted(true, 2_345)
        val frozen = sc.frame(2_345)
        val sim = Sim(sc, 2_345)
        sim.music(4)                              // 같은 상태 반복(변화 없음)
        sim.run(602_345, (1..600).associate { (2_345L + it * 1_000).let { ms -> ms - ms % 100 } to { sim.music(4) } })
        assertTrue(sc.driveTransitions.isEmpty())
        assertNull(sc.nextWakeMs(sim.t))
        sim.frames.forEach { (t, f) -> assertEquals("t=$t", frozen, f) }
    }

    @Test fun `레이어 추가 - 메인 빛 크기 6%, 밝기 8%를 1·5초에 걸쳐, 이후 유지`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.music(2, false, false, 0)
        sc.setRestricted(true, 1_000)
        sc.music(4, false, false, 5_000)          // 출발 드롭
        assertTrue(sc.animatingAt(5_500))
        val mid = sc.frame(5_750)
        assertTrue(mid.mainScale > 1.01f && mid.mainScale < 1.05f)
        val end = sc.frame(6_500)
        assertEquals(1.06f, end.mainScale, 1e-4f); assertEquals(1.08f, end.mainGain, 1e-4f)
        assertFalse(sc.animatingAt(6_500))
        assertEquals(end, sc.frame(60_000))
        // 3초 뒤 레이어가 줄어도 10초가 지나야 반영(그 사이 이벤트는 모아서)
        sc.music(2, false, false, 8_000)
        assertFalse(sc.animatingAt(8_100))
        assertEquals(15_000L, sc.nextWakeMs(8_100))
        sc.tick(15_000)
        assertEquals(1f, sc.frame(16_500).mainScale, 1e-4f)
    }

    @Test fun `테이프스톱 - 0·7초에 밝기 -25%, 레이어가 돌아오면 1·5초에 걸쳐 원래대로`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.music(5, false, false, 0)
        sc.setRestricted(true, 1_000)
        sc.music(2, true, false, 20_000)                                      // 테이프스톱 + 레이어 줄어듦(같은 갱신)
        assertTrue("0.7초에 이미 −25%", sc.frame(20_700).mainGain <= 0.75f + 1e-4f)   // (레이어 감소분은 1.5초에 걸쳐 더해짐)
        assertEquals(0.75f * (1f - 0.08f), sc.frame(21_500).mainGain, 1e-4f)  // −25% + 레이어 감소 한 단계(1.5초)
        for (n in 3..5) sc.music(n, false, false, 20_000L + (n - 2) * 2_700)   // 한 마디씩 회복
        assertEquals(30_000L, sc.nextWakeMs(28_200))
        sc.tick(30_000)
        assertEquals(1f, sc.frame(31_500).mainGain, 1e-4f)
    }

    @Test fun `와이퍼 - 물방울 2초 페이드 인·아웃`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setRestricted(true, 1_000)
        assertEquals(0f, sc.frame(1_000).rainAmount, 0f)
        sc.music(0, false, true, 5_000)
        val mid = sc.frame(6_000).rainAmount
        assertTrue(mid > 0.3f && mid < 0.7f)
        assertEquals(1f, sc.frame(7_000).rainAmount, 1e-6f)
        sc.music(0, false, false, 15_000)
        assertEquals(0f, sc.frame(17_000).rainAmount, 1e-6f)
    }

    @Test fun `정차하면 주행 중 반응이 3초에 걸쳐 정차 모습으로`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.music(2, false, false, 0)
        sc.setRestricted(true, 1_000)
        sc.music(4, false, false, 2_000)
        sc.setRestricted(false, 10_000)
        assertEquals(1.06f, sc.frame(10_000).mainScale, 1e-4f)                // 튀지 않고
        assertEquals(1f, sc.frame(13_000).mainScale, 1e-4f)                   // 3초에 걸쳐
    }

    @Test fun `정차하면 보관한 상태로 부드럽게 바뀌고 숨쉬기를 멈춘 곳에서 이어간다`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setRestricted(true, 5_000)
        val phaseAtStop = sc.breathPhase(5_000)
        sc.show(busy, 20_000)
        sc.setRestricted(false, 60_000)
        assertNull(sc.pending)
        assertTrue(sc.animating)
        assertEquals(phaseAtStop, sc.breathPhase(60_000), 1e-6f)      // 튀지 않음
        assertEquals(calm.energy, sc.mood(60_000).energy, 1e-6f)       // 순간 변화 없이 시작해서
        val mid = sc.mood(61_500).energy
        assertTrue(mid > calm.energy && mid < busy.energy)              // 3초에 걸쳐
        assertEquals(busy, sc.mood(63_000))                             // 보관한 상태로
    }

    @Test fun `정차 중에는 숨 쉬듯 움직인다`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        assertNotEquals(sc.frame(0), sc.frame(3_000))
        sc.show(busy, 4_000)
        assertNotEquals(sc.frame(4_000).background, sc.frame(7_000).background)
    }

    @Test fun `debug 즉시 표시 후 DO - 그 모습 그대로 멈춤`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.show(busy, 1_000, immediate = true)
        sc.setRestricted(true, 1_000)
        assertEquals(busy, sc.mood(50_000))
        assertEquals(sc.frame(1_000), sc.frame(50_000))
    }

    private fun cover(n: Int) = app.playdrive.album.CoverArt.spec(7L, List(n) { app.playdrive.album.CoverArt.layer(it, 7L, 0.8f, 30f, if (it == 1) 0.5f else 0f, it % 2) })

    @Test fun `주행 중 새 겹은 화면에 안 나오고, 정차 순간 한 겹 추가되는 장면 4초`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setCover(cover(1), 0, immediate = true)
        sc.setRestricted(true, 1_000)
        val frozen = sc.frame(1_000)
        sc.setCover(cover(2), 20_000)                        // 주행 중 한 겹 더 그려짐
        assertEquals(frozen, sc.frame(25_000))               // 화면은 그대로
        assertEquals(1, sc.frame(25_000).cover!!.layers.size)
        sc.setRestricted(false, 30_000)                      // 정차
        val f0 = sc.frame(30_000)
        assertEquals(2, f0.cover!!.layers.size)
        assertEquals(1, f0.revealFrom)                        // 두 번째 겹만 새로
        assertEquals(0f, f0.reveal, 1e-6f)
        val mid = sc.frame(31_600)
        assertTrue(mid.reveal > 0.3f && mid.reveal < 0.8f)
        assertTrue(mid.coverAlpha > 0.5f)                     // 장면 동안 커버가 또렷
        assertTrue(mid.moodAlpha < 0.5f)                      // 무드 원은 옅게
        val end = sc.frame(34_100)
        assertEquals(1f, end.reveal, 1e-6f)
        assertEquals(MoodScene.COVER_ALPHA, end.coverAlpha, 1e-6f)  // 다시 은은하게
        assertEquals(1f, end.moodAlpha, 1e-6f)
    }

    @Test fun `장면 도중 출발하면 완성된 모습으로 맞춘 뒤 정지 - 정지 프레임이 완성된 한 장`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setCover(cover(1), 0, immediate = true)
        sc.setCover(cover(2), 1_000)
        sc.welcome(1_000)
        sc.setRestricted(true, 2_000)                         // 장면 1초째·웰컴 도중에 출발
        val f = sc.frame(2_000)
        assertEquals(2, f.revealFrom); assertEquals(1f, f.reveal, 0f)   // 겹은 다 그려진 모습
        assertEquals(1f, f.master, 0f)                                  // 빛도 다 피어난 모습
        assertEquals(1f, f.pulse, 0f)
        repeat(100) { k -> assertEquals(f, sc.frame(2_000L + k * 997, beatPhase = (k % 10) / 10f)) }
    }

    @Test fun `웰컴 3초에 피어나고 굿바이 2초에 가라앉는다`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.welcome(0)
        assertEquals(0f, sc.frame(0).master, 1e-6f)
        val mid = sc.frame(1_500).master
        assertTrue(mid > 0.3f && mid < 0.7f)
        assertEquals(1f, sc.frame(3_000).master, 1e-6f)
        sc.goodbye(10_000)
        assertEquals(1f, sc.frame(10_000).master, 1e-6f)
        assertEquals(MoodScene.GOODBYE_LEVEL, sc.frame(12_000).master, 1e-6f)
        sc.wake(20_000)
        assertEquals(1f, sc.frame(21_500).master, 1e-6f)
    }

    @Test fun `박 숨쉬기 - 진폭 3% 안, 박과 박 사이 이어짐, 주행 중엔 1`() {
        val vals = (0..100).map { MoodScene.pulseShape(it / 100f) }
        assertTrue(vals.all { it in 1f..1.0301f })
        assertTrue(vals.max() > 1.02f)                                         // 2~4% 변화
        assertEquals(MoodScene.pulseShape(0f), MoodScene.pulseShape(1f), 1e-4f) // 박 경계에서 튀지 않음
        for (k in 1..100) assertTrue("부드러움", kotlin.math.abs(vals[k] - vals[k - 1]) < 0.003f)
        val sc = MoodScene(startMs = 0, initial = calm)
        assertTrue(sc.frame(0, beatPhase = 0.3f).pulse > 1.02f)
        sc.setRestricted(true, 1)
        assertEquals(1f, sc.frame(5, beatPhase = 0.3f).pulse, 0f)
    }

    @Test fun `시간대 색온도 - 주행 중엔 보관, 정차하면 6초에 걸쳐`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setDayPart(app.playdrive.album.AlbumNaming.PartOfDay.EVENING, 0, immediate = true)
        sc.setRestricted(true, 1_000)
        val f = sc.frame(1_000)
        sc.setDayPart(app.playdrive.album.AlbumNaming.PartOfDay.NIGHT, 2_000)
        assertEquals(f, sc.frame(50_000))
        sc.setRestricted(false, 60_000)
        val a = sc.frame(63_000)
        assertEquals(app.playdrive.album.AlbumNaming.PartOfDay.NIGHT, a.dayTo)
        assertTrue(a.dayMix > 0.2f && a.dayMix < 0.8f)
        assertEquals(1f, sc.frame(66_000).dayMix, 1e-6f)
    }

    @Test fun `느린 움직임의 시각은 600초에서 순환해도 이어진다`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        val a = sc.frame(599_999).timeSec; val b = sc.frame(600_000).timeSec
        assertTrue(a > 599.9f && b < 0.01f)
        // 셰이더의 모든 주기(10초·12초·600초)는 600초를 나눈다
        for (period in listOf(10f, 12f, 600f)) assertEquals(0f, (600f / period) % 1f, 0f)
    }
}
