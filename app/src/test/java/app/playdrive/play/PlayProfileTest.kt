package app.playdrive.play

import app.playdrive.ui.Screen
import app.playdrive.ui.ScreenPolicy
import app.playdrive.voice.Line
import app.playdrive.voice.VoicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PlayProfileTest {
    private val tz = TimeZone.getTimeZone("Asia/Seoul")

    /** 2026-11-13(금) 기준 [daysAgo]일 전 [hour]시. */
    private fun at(daysAgo: Int, hour: Int): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.NOVEMBER, 13, hour, 0); add(Calendar.DAY_OF_MONTH, -daysAgo)
    }.timeInMillis

    /** score < 0 이면 완주한 곡 없음(미완주 한 곡). */
    private fun r(start: Long, score: Int, misses: Int = 0, rain: Boolean = false, combo: Int = 20, stops: Int = 1, moving: Int = 900, perfectStops: Int = 1): DriveResult {
        val done = score >= 0
        val song = SongResult(1, 1, done, maxOf(score, 0), PlayRules.grade(maxOf(score, 0), done), 0.8f, 20, 1)
        return DriveResult(start, start + moving * 1000L, listOf(song), combo,
            mapOf(PerfectKind.START to 0, PerfectKind.CHANGE to 0, PerfectKind.STOP to perfectStops), misses, rain, 5_000f, moving, stops)
    }

    @Test fun `첫 S등급 배지는 한 번만`() {
        var p = PlayProfile()
        val (p1, rep1) = p.add(r(at(0, 10), 95), tz); p = p1
        assertEquals(listOf(Badge.FIRST_S), rep1.newBadges)
        val (_, rep2) = p.add(r(at(0, 12), 97), tz)
        assertTrue(rep2.newBadges.isEmpty())
    }

    @Test fun `빗길 완주는 B등급 이상, 밤길 완주는 밤·새벽에 완주`() {
        assertTrue(Badge.RAIN_FINISH in PlayProfile().add(r(at(0, 10), 70, rain = true), tz).second.newBadges)
        assertFalse(Badge.RAIN_FINISH in PlayProfile().add(r(at(0, 10), 69, rain = true), tz).second.newBadges)
        assertTrue(Badge.NIGHT_FINISH in PlayProfile().add(r(at(0, 22), 55), tz).second.newBadges)
        assertTrue(Badge.NIGHT_FINISH in PlayProfile().add(r(at(0, 3), 55), tz).second.newBadges)
        assertFalse(Badge.NIGHT_FINISH in PlayProfile().add(r(at(0, 22), -1), tz).second.newBadges) // 완주한 곡 없음
        assertFalse(Badge.NIGHT_FINISH in PlayProfile().add(r(at(0, 14), 80), tz).second.newBadges)
    }

    @Test fun `7일 연속 흔들림 없는 주행`() {
        var p = PlayProfile()
        for (d in 6 downTo 1) p = p.add(r(at(d, 9), 80), tz).first
        assertFalse(Badge.STREAK_7 in p.badges)
        assertTrue(Badge.STREAK_7 in p.add(r(at(0, 9), 80), tz).second.newBadges)
        // 중간 하루가 흔들림이면 끊김
        var q = PlayProfile()
        for (d in 6 downTo 1) q = q.add(r(at(d, 9), 80, misses = if (d == 3) 1 else 0), tz).first
        assertFalse(Badge.STREAK_7 in q.add(r(at(0, 9), 80), tz).second.newBadges)
    }

    @Test fun `부드러운 정지 누적 50번과 100마디`() {
        var p = PlayProfile()
        repeat(4) { p = p.add(r(at(0, 9) + it, 80, perfectStops = 12), tz).first }
        assertFalse(Badge.STOPS_50 in p.badges)
        assertTrue(Badge.STOPS_50 in p.add(r(at(0, 11), 80, perfectStops = 2), tz).second.newBadges)
        assertTrue(Badge.COMBO_100 in PlayProfile().add(r(at(0, 9), 80, combo = 100), tz).second.newBadges)
        assertFalse(Badge.COMBO_100 in PlayProfile().add(r(at(0, 9), 80, combo = 99), tz).second.newBadges)
    }

    @Test fun `이번 주 최고 기록 — 월요일 시작, 같은 주의 이전 기록보다 높을 때만`() {
        var p = PlayProfile().add(r(at(4, 9), 70), tz).first   // 월요일 11/9
        assertTrue(p.add(r(at(0, 9), 71), tz).second.weeklyBest)
        assertFalse(p.add(r(at(0, 9), 70), tz).second.weeklyBest)
        p = PlayProfile().add(r(at(5, 9), 99), tz).first       // 지난주 일요일 11/8
        assertFalse(p.add(r(at(0, 9), 80), tz).second.weeklyBest) // 이번 주 첫 기록은 '최고 기록' 문장 없음
    }

    @Test fun `비슷한 주행 비교는 위치 없이 시간·정차 횟수·시간대로, 흔들림으로 문장`() {
        var p = PlayProfile()
        p = p.add(r(at(3, 8), 70, misses = 3, moving = 1_000, stops = 3), tz).first
        p = p.add(r(at(2, 8), 75, misses = 1, moving = 1_100, stops = 4), tz).first
        p = p.add(r(at(1, 8), 90, misses = 0, moving = 4_000, stops = 9), tz).first   // 길이가 달라 제외
        val today = r(at(0, 8), 95, misses = 0, moving = 1_050, stops = 3)
        assertEquals(2, p.similarTo(today, tz).size)
        assertEquals("비슷한 주행 2번보다 MISS가 평균 2번 적었어요", p.compare(today, tz))
        assertNull(PlayProfile().compare(today, tz))
    }

    @Test fun `해금 진행도는 흔들림 없던 거리만`() {
        val p = PlayProfile(listOf(r(at(0, 9), 80).copy(safeDistanceM = 150_000f)))
        assertEquals(0.5f, p.progress(GenrePack.CITY_POP), 1e-4f)
        assertEquals(0.15f, p.progress(GenrePack.JAZZ), 1e-4f)
    }

    @Test fun `배지 진행도와 연속 완주 일수`() {
        var p = PlayProfile()
        for (d in 3 downTo 0) p = p.add(r(at(d, 9), 80, perfectStops = 4), tz).first
        assertEquals(16 to 50, p.badgeProgress(Badge.STOPS_50, at(0, 12), tz))
        assertEquals(20 to 100, p.badgeProgress(Badge.COMBO_100, at(0, 12), tz))
        assertEquals(4, p.finishStreakDays(at(0, 12), tz))
        assertEquals(0, p.finishStreakDays(at(-2, 12), tz))
        assertEquals(1 to 1, p.add(r(at(0, 13), 95), tz).first.badgeProgress(Badge.FIRST_S, at(0, 14), tz))
    }

    // ------------------------------------------------------------------ 주행 중 텍스트·음성 정책
    @Test fun `주행 중에는 글자·숫자가 있는 화면을 열 수 없다`() {
        for (s in ScreenPolicy.textScreens) assertFalse("$s", ScreenPolicy.allowedWhileDriving(s))
        assertTrue(ScreenPolicy.allowedWhileDriving(Screen.MOOD))
        assertTrue(Screen.RESULT in ScreenPolicy.textScreens && Screen.RECORDS in ScreenPolicy.textScreens)
    }

    @Test fun `주행 중 음성은 중단·회복만, 같은 문장은 60초 간격`() {
        val v = VoicePolicy()
        assertFalse(v.allow(Line.Result(Grade.S, 99), 0, driving = true))
        assertTrue(v.allow(Line.Danger, 0, driving = true))
        assertFalse(v.allow(Line.NewBadge, 0, driving = true))
        assertTrue(v.allow(Line.Stop, 1_000, driving = true))
        assertFalse(v.allow(Line.Stop, 60_999, driving = true))
        assertTrue(v.allow(Line.Resume, 2_000, driving = true))  // 다른 문장은 따로 센다
        assertTrue(v.allow(Line.Stop, 61_000, driving = true))
        assertTrue(v.allow(Line.Result(Grade.S, 99), 61_500, driving = false))
        v.enabled = false
        assertFalse(v.allow(Line.NoSong, 200_000, driving = false))
    }

    @Test fun `결과 문장은 등급 파일과 점수 파일을 이어 붙인다`() {
        assertEquals(listOf("voice_grade_s", "voice_score_92"), Line.Result(Grade.S, 92).clips())
        assertEquals(listOf("voice_nosong"), Line.NoSong.clips())
        assertEquals("오늘의 연주는 S등급이에요. 92점.", Line.Result(Grade.S, 92).text())
        // 모든 문장의 녹음 파일이 있다
        val raw = java.io.File("src/main/res/raw")
        listOf(Line.Stop, Line.Resume, Line.Danger, Line.NoSong, Line.WeeklyBest, Line.NewBadge, Line.Result(Grade.C, 0), Line.Result(Grade.A, 100))
            .flatMap { it.clips() }.forEach { assertTrue(it, java.io.File(raw, "$it.ogg").exists()) }
        assertNotNull(Line.Stop.clips().single())
    }
}
