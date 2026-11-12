package app.playdrive.ui

import app.playdrive.album.MoodState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CALM: 주행 중 "이벤트 때만 한 번 반응(최소 변화)".
 * 기본은 정지, 음악 이벤트(레이어 증감·테이프스톱·와이퍼)에서만 전환(≤2초, 시작 간격 ≥10초), 전환 밖에선 프레임 불변.
 */
class DriveCalmTest {
    private val calm = MoodState(0.2f, 0.9f, 0f, false)
    private val busy = MoodState(1f, 0.1f, 1f, true)
    private fun calmScene() = MoodScene(startMs = 0, initial = calm).apply { effect = EffectLevel.CALM }

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
        val sc = calmScene()
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
        val sc = calmScene()
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
        val sc = calmScene()
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
        val sc = calmScene()
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
        val sc = calmScene()
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
        val sc = calmScene()
        sc.music(2, false, false, 0)
        sc.setRestricted(true, 1_000)
        sc.music(4, false, false, 2_000)
        sc.setRestricted(false, 10_000)
        assertEquals(1.06f, sc.frame(10_000).mainScale, 1e-4f)                // 튀지 않고
        assertEquals(1f, sc.frame(13_000).mainScale, 1e-4f)                   // 3초에 걸쳐
    }
}
