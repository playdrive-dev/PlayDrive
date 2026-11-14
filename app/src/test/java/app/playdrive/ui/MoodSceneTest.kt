package app.playdrive.ui

import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.MoodState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 무드(주행) 화면 바탕: 주행 중엔 색·음악 상태를 보관만 하고 정차 때 반영, 웰컴·굿바이, 효과 강도는 다음 출발부터. 물결은 [WaveMotionTest]. */
class MoodSceneTest {
    private val calm = MoodState(0.2f, 0.9f, 0f, false)
    private val busy = MoodState(1f, 0.1f, 1f, true)

    @Test fun `주행 중 음악 상태는 보관, 정차하면 3초에 걸쳐`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setRestricted(true, 5_000)
        sc.show(busy, 20_000)
        assertEquals(calm, sc.mood(30_000))
        sc.setRestricted(false, 60_000)
        assertNull(sc.pending)
        assertEquals(calm.energy, sc.mood(60_000).energy, 1e-6f)
        val mid = sc.mood(61_500).energy
        assertTrue(mid > calm.energy && mid < busy.energy)
        assertEquals(busy, sc.mood(63_000))
    }

    @Test fun `시간대 색 - 주행 중엔 보관, 정차하면 6초에 걸쳐`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setDayPart(PartOfDay.EVENING, 0, immediate = true)
        sc.setRestricted(true, 1_000)
        sc.setDayPart(PartOfDay.NIGHT, 2_000)
        assertEquals(PartOfDay.EVENING, sc.frame(50_000).dayTo)
        sc.setRestricted(false, 60_000)
        val a = sc.frame(63_000)
        assertEquals(PartOfDay.NIGHT, a.dayTo)
        assertTrue(a.dayMix > 0.2f && a.dayMix < 0.8f)
        assertEquals(1f, sc.frame(66_000).dayMix, 1e-6f)
    }

    @Test fun `웰컴 3초에 피어나고 굿바이 2초에 가라앉는다, 출발하면 다 피어난 모습`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.welcome(0)
        assertEquals(0f, sc.frame(0).master, 1e-6f)
        val mid = sc.frame(1_500).master
        assertTrue(mid > 0.3f && mid < 0.7f)
        assertEquals(1f, sc.frame(3_000).master, 1e-6f)
        sc.goodbye(10_000)
        assertEquals(MoodScene.GOODBYE_LEVEL, sc.frame(12_000).master, 1e-6f)
        sc.wake(20_000)
        assertEquals(1f, sc.frame(21_500).master, 1e-6f)
        sc.welcome(30_000); sc.setRestricted(true, 30_500)
        assertEquals(1f, sc.frame(30_500).master, 0f)
    }

    @Test fun `효과 강도는 다음 출발부터`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setRestricted(true, 0)
        sc.effect = EffectLevel.CALM
        assertEquals(EffectLevel.LIVE, sc.activeEffect)
        sc.setRestricted(false, 1_000); sc.setRestricted(true, 2_000)
        assertEquals(EffectLevel.CALM, sc.activeEffect)
    }

    @Test fun `물결은 주행 중에도 흐른다(멈춘 화면 아님)`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        sc.setRestricted(true, 0)
        val a = sc.frame(1_000, Sound(playing = true, on = setOf(Inst.PAD, Inst.CHORDS), moving = true)).waves.lines[0].phase
        val b = sc.frame(5_000, Sound(playing = true, on = setOf(Inst.PAD, Inst.CHORDS), moving = true)).waves.lines[0].phase
        assertTrue(b != a)
    }

    @Test fun `느린 움직임의 시각은 600초에서 순환해도 이어진다`() {
        val sc = MoodScene(startMs = 0, initial = calm)
        val a = sc.frame(599_999).timeSec; val b = sc.frame(600_000).timeSec
        assertTrue(a > 599.9f && b < 0.01f)
    }
}
