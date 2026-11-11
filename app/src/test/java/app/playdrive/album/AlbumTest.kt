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
        assertTrue(c.layers[0].color != AlbumNaming.cover(busy, kst).layers[0].color)
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

    @Test fun `무드 상태는 눈에 띄게 달라질 때만 넘긴다`() {
        val th = MoodThrottle()
        val a = MoodState(0.2f, 0.5f, 0f, false)
        assertEquals(a, th.offer(a))
        assertEquals(null, th.offer(a.copy(energy = 0.25f)))
        assertEquals(a.copy(rain = true), th.offer(a.copy(rain = true)))
    }

    @Test fun `같은 날 같은 시간대에 제목이 겹치면 두 번째 길로 구분한다`() {
        val first = DriveSummary(epoch(2026, 11, 10, 7), 0, 80f, true, 900)
        val second = first.copy(startEpochMs = first.startEpochMs + 3_600_000)        // 같은 아침, 또 비
        val third = first.copy(startEpochMs = first.startEpochMs + 2 * 3_600_000)
        val otherDay = first.copy(startEpochMs = epoch(2026, 11, 17, 7))              // 다음 주 화요일
        val calm = first.copy(startEpochMs = first.startEpochMs + 1_800_000, rained = false) // 같은 아침이지만 다른 제목
        val all = listOf(first, second, third, otherDay, calm)
        assertEquals("화요일 아침, 빗속의 출근길", AlbumNaming.uniqueTitle(first, all, kst))
        assertEquals("화요일 아침, 빗속의 두 번째 길", AlbumNaming.uniqueTitle(second, all, kst))
        assertEquals("화요일 아침, 빗속의 세 번째 길", AlbumNaming.uniqueTitle(third, all, kst))
        assertEquals("화요일 아침, 빗속의 출근길", AlbumNaming.uniqueTitle(otherDay, all, kst))
        assertEquals("화요일 아침, 고요한 출근길", AlbumNaming.uniqueTitle(calm, all, kst))
        // 새 주행(아직 저장 전)도 같은 규칙: 이미 있는 두 장 뒤 → 세 번째
        assertEquals("화요일 아침, 빗속의 세 번째 길", AlbumNaming.uniqueTitle(third, listOf(first, second), kst))
        val many = (0 until 12).map { first.copy(startEpochMs = first.startEpochMs + it * 60_000L) }
        val titles = many.map { AlbumNaming.uniqueTitle(it, many, kst) }
        assertTrue(titles.none { t -> t.any { it.isDigit() } })
        assertEquals(10, titles.take(10).toSet().size)
    }

    @Test fun `상세 화면 날짜 한 줄`() {
        assertEquals("11월 10일 화요일 · 오후 9:10", AlbumNaming.dateLine(epoch(2026, 11, 10, 21), kst))
        assertEquals("11월 10일 화요일 · 오전 12:10", AlbumNaming.dateLine(epoch(2026, 11, 10, 0), kst))
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
