package app.playdrive.ui

import app.playdrive.album.MoodState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SA-1: 주행 중(requiresDO) 무드 화면은 완전히 멈춘다 — 프레임이 시간·음악 상태와 무관하게 같아야 한다. */
class MoodSceneTest {
    private val calm = MoodState(0.2f, 0.9f, 0f, false)
    private val busy = MoodState(1f, 0.1f, 1f, true)

    @Test fun `DO 중에는 시간이 흘러도 음악 상태가 바뀌어도 프레임 변화 없음`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.show(busy, 1_000)                      // 정차 중 전환을 시작해 두고
        sc.setRestricted(true, 2_345)             // 전환·숨쉬기 도중에 주행 시작
        val frozen = sc.frame(2_345)
        assertFalse(sc.animating)
        var t = 2_345L
        val states = listOf(calm, busy, MoodState(0.5f, 0.5f, 0.5f, false), busy.copy(rain = false))
        repeat(600) { k ->                        // 10분, 1초마다
            t += 1_000
            if (k % 7 == 0) sc.show(states[k % states.size], t)
            assertEquals("t=$t", frozen, sc.frame(t))
        }
        assertTrue(sc.pending != null)
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
}
