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
