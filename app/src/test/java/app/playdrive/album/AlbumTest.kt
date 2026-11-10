package app.playdrive.album

import app.playdrive.engine.DrivingEventEngine
import app.playdrive.engine.DrivingState
import app.playdrive.model.Gear
import app.playdrive.sim.DemoDriveScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class AlbumTest {
    private val kst = TimeZone.getTimeZone("Asia/Seoul")
    private fun epoch(y: Int, m: Int, d: Int, h: Int) = Calendar.getInstance(kst).apply { set(y, m - 1, d, h, 10, 0) }.timeInMillis

    @Test fun `앨범 제목은 요일·시간대·분위기 문장이고 숫자가 없다`() {
        // 2026-11-10은 화요일
        val rainyNight = DriveSummary(epoch(2026, 11, 10, 21), 0, 80f, true, 900)
        assertEquals("화요일 밤, 빗속의 귀갓길", AlbumNaming.title(rainyNight, kst))
        assertEquals("화요일 아침, 고요한 출근길", AlbumNaming.title(rainyNight.copy(startEpochMs = epoch(2026, 11, 10, 8), rained = false), kst))
        assertEquals("화요일 한낮, 분주했던 드라이브", AlbumNaming.title(rainyNight.copy(startEpochMs = epoch(2026, 11, 10, 14), rained = false, smoothness = 30f), kst))
        for (s in listOf(10f, 50f, 90f)) for (h in 0..23) {
            val t = AlbumNaming.title(rainyNight.copy(startEpochMs = epoch(2026, 11, 12, h), smoothness = s, rained = false), kst)
            assertFalse("숫자·점수 금지: $t", t.any { it.isDigit() } || t.contains("점"))
        }
    }

    @Test fun `커버는 부드러움·비·시간대로 달라진다`() {
        val calm = DriveSummary(epoch(2026, 11, 10, 21), 0, 90f, false, 900)
        val busy = calm.copy(smoothness = 20f)
        val rain = calm.copy(rained = true)
        val morning = calm.copy(startEpochMs = epoch(2026, 11, 10, 8))
        val c = AlbumNaming.cover(calm, kst)
        assertTrue(c.circleColor != AlbumNaming.cover(busy, kst).circleColor)
        assertTrue(AlbumNaming.cover(busy, kst).wave > c.wave)
        assertEquals(0, c.rainLines); assertTrue(AlbumNaming.cover(rain, kst).rainLines > 0)
        assertTrue(c.background != AlbumNaming.cover(morning, kst).background)
    }

    @Test fun `주차 후 1분 - P단 + 운전석만 착석일 때만, 동승자가 있으면 제안하지 않음`() {
        val solo = DrivingState(gear = Gear.PARK, driverPresent = true, solo = true)
        assertTrue(BreathCoach.shouldSuggest(solo, justFinishedDrive = true))
        assertFalse(BreathCoach.shouldSuggest(solo.copy(solo = false), true))          // 동승자
        assertFalse(BreathCoach.shouldSuggest(solo.copy(gear = Gear.DRIVE), true))     // P단 아님
        assertFalse(BreathCoach.shouldSuggest(solo, justFinishedDrive = false))        // 주행 안 함
        assertFalse(BreathCoach.shouldSuggest(solo.copy(driverPresent = false, solo = false), true))
    }

    @Test fun `호흡 - 들숨 4초 커지고 날숨 6초 작아짐, 1분 = 6회`() {
        assertEquals(true to 0f, BreathCoach.phase(0))
        assertEquals(true, BreathCoach.phase(3_999).first)
        assertEquals(false to 1f, BreathCoach.phase(4_000))
        assertEquals(0.5f, BreathCoach.phase(7_000).second, 1e-3f)
        assertEquals(6L, BreathCoach.DURATION_MS / (BreathCoach.INHALE_MS + BreathCoach.EXHALE_MS))
    }

    @Test fun `데모 주행 1회 = 앨범 1장(비 포함, P단에서 끝)`() {
        val engine = DrivingEventEngine()
        var clock = epoch(2026, 11, 10, 21)
        val rec = DriveRecorder { clock }
        val summaries = mutableListOf<DriveSummary>()
        var last = -1L; var t = 0L
        var finalState: DrivingState? = null
        while (t <= DemoDriveScenario.DURATION_MS) {
            for (s in DemoDriveScenario.samples(last, t, 0)) {
                val e = engine.onSample(s)
                rec.update(engine.state, e)?.let { summaries += it; finalState = engine.state }
            }
            last = t; t += 100; clock += 100
        }
        assertEquals(1, summaries.size)
        val s = summaries.single()
        assertTrue(s.rained)
        assertTrue(s.movingSeconds in 40..70)
        assertTrue(s.smoothness in 0f..100f)
        assertNotNull(finalState)
        assertTrue(BreathCoach.shouldSuggest(finalState!!, true))                     // 데모 끝 = 혼자, P단 → 제안
        assertEquals("화요일 밤, 빗속의 귀갓길", AlbumNaming.title(s, kst))
    }

    @Test fun `주행 중 무드 그래픽은 10초 이상 간격으로만 바뀐다(정차 중엔 바로)`() {
        val th = MoodThrottle()
        val a = MoodState(0.2f, 0.5f, 0f, false); val b = MoodState(0.8f, 0.5f, 0f, false); val c = MoodState(0.2f, 0.9f, 0.9f, true)
        assertEquals(a, th.offer(a, 0, restricted = true))
        assertEquals(null, th.offer(b, 5_000, restricted = true))
        assertEquals(b, th.offer(b, 10_000, restricted = true))
        assertEquals(c, th.offer(c, 10_500, restricted = false))
    }

    @Test fun `웰니스 표현만 - 화면 문구에 의료·치료·점수 표현이 없다`() {
        val banned = listOf("치료", "치유", "진단", "처방", "의학", "의료", "질환", "스트레스 측정", "혈압", "심박", "점수", "스코어", "콤보", "랭킹")
        val files = listOf(File("src/main/res/values/strings.xml")) +
            File("src/main/java/app/playdrive").walkTopDown().filter { it.name in setOf("MainActivity.kt", "Views.kt", "Album.kt") }.toList()
        assertTrue(files.all { it.exists() })
        for (f in files) {
            // 주석은 제외하고 문자열 리터럴만 검사
            val literals = Regex("\"([^\"\\\\]|\\\\.)*\"|>([^<]+)<").findAll(f.readText()).map { it.value }.toList()
            for (w in banned) assertTrue("${f.name}: '$w'", literals.none { it.contains(w) })
        }
    }
}
