package app.playdrive.play

import app.playdrive.album.AlbumNaming
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** 배지(기기 안에만 저장). 이름은 게임 용어 없이. */
enum class Badge(val title: String, val desc: String) {
    FIRST_S("첫 S등급", "처음으로 S등급 곡을 완주했어요"),
    STREAK_7("7일 연속 부드러운 주행", "7일 동안 매일 흔들림 없이 한 곡 이상 완주했어요"),
    RAIN_FINISH("빗길 완주", "비 오는 길에서 B등급 이상으로 곡을 완주했어요"),
    NIGHT_FINISH("밤길 완주", "밤에 곡을 끝까지 완주했어요"),
    STOPS_50("부드러운 정지 50번", "부드러운 정지를 모두 50번 해냈어요"),
    COMBO_100("100마디 이어서", "흔들림 없이 100마디를 이어 연주했어요"),
}

/** 음악 해금: 흔들림 없던 구간의 누적 거리로. 이번엔 진행도만, 팩은 '준비 중'. */
enum class GenrePack(val title: String, val requiredKm: Int) { CITY_POP("시티팝", 300), JAZZ("재즈", 1_000) }

/** 주행 결과를 저장하고 나서 알게 된 것(결과 화면·음성용). */
data class ResultReport(
    val result: DriveResult,
    val newBadges: List<Badge>,
    val weeklyBest: Boolean,
    /** 위치 없이 고른 '비슷한 주행'과의 비교 문장(없으면 null). */
    val comparison: String?,
)

/**
 * 연주 기록 모음(순수 로직). 저장은 [PlayStore].
 * 원칙: 배지·해금·비교 모두 부드러움 기준. 해금만 예외로 '흔들림 없는 구간의 거리'를 쓴다(사양).
 */
data class PlayProfile(
    val results: List<DriveResult> = emptyList(),
    val badges: Map<Badge, Long> = emptyMap(),
) {
    val bestCombo: Int get() = results.maxOfOrNull { it.maxCombo } ?: 0
    val perfectStops: Int get() = results.sumOf { it.perfects[PerfectKind.STOP] ?: 0 }
    val safeKm: Float get() = results.sumOf { it.safeDistanceM.toDouble() }.toFloat() / 1000f

    fun progress(pack: GenrePack): Float = (safeKm / pack.requiredKm).coerceIn(0f, 1f)

    /** 결과 하나를 더하고 새 배지·주간 최고 여부·비교 문장을 돌려준다. */
    fun add(r: DriveResult, tz: TimeZone = TimeZone.getDefault()): Pair<PlayProfile, ResultReport> {
        val before = this
        val weekBefore = results.filter { sameWeek(it.startEpochMs, r.startEpochMs, tz) }
        val weekBest = weekBefore.filter { it.best != null }.maxOfOrNull { it.bestScore }
        val weeklyBest = r.best != null && weekBest != null && r.bestScore > weekBest
        val next = copy(results = results + r)
        val earned = Badge.entries.filter { it !in badges && next.qualifies(it, r, tz) }
        val withBadges = next.copy(badges = badges + earned.associateWith { r.endEpochMs })
        return withBadges to ResultReport(r, earned, weeklyBest, before.compare(r))
    }

    private fun qualifies(b: Badge, r: DriveResult, tz: TimeZone): Boolean = when (b) {
        Badge.FIRST_S -> r.songs.any { it.grade == Grade.S }
        Badge.STREAK_7 -> safeStreakDays(r.startEpochMs, tz) >= 7
        Badge.RAIN_FINISH -> r.rained && r.songs.any { it.grade.atLeast(Grade.B) }
        Badge.NIGHT_FINISH -> r.completedSongs > 0 && isNight(r.startEpochMs, tz)
        Badge.STOPS_50 -> perfectStops >= 50
        Badge.COMBO_100 -> r.maxCombo >= 100
    }

    /** [day]를 포함해 거꾸로 이어진 '흔들림 없이 완주한 주행이 있는 날' 수. */
    fun safeStreakDays(day: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val safeDays = results.filter { it.misses == 0 && it.completedSongs > 0 }.map { dayIndex(it.startEpochMs, tz) }.toSet()
        var d = dayIndex(day, tz); var n = 0
        while (d in safeDays) { n++; d-- }
        return n
    }

    /**
     * 위치 없이 '비슷한 주행'을 고른다: 움직인 시간이 ±35% 안, 정차 횟수 차이 ≤ 2, 같은 시간대 우선.
     * 반환 = 비교 문장 한 줄(점수 대신 흔들림·판정 횟수로 — 시간·속도는 비교하지 않는다).
     */
    fun compare(r: DriveResult, tz: TimeZone = TimeZone.getDefault()): String? {
        val similar = similarTo(r, tz)
        if (similar.isEmpty()) return null
        val n = similar.size
        val avgMiss = similar.map { it.misses }.average()
        val avgPerfect = similar.map { it.perfectTotal }.average()
        val dMiss = Math.round(avgMiss - r.misses).toInt()
        val dPerf = Math.round(r.perfectTotal - avgPerfect).toInt()
        return when {
            dMiss > 0 -> "비슷한 주행 ${n}번보다 흔들림이 평균 ${dMiss}번 적었어요"
            dMiss < 0 -> "비슷한 주행 ${n}번보다 흔들림이 평균 ${-dMiss}번 많았어요"
            dPerf > 0 -> "비슷한 주행 ${n}번보다 부드러운 순간이 평균 ${dPerf}번 많았어요"
            dPerf < 0 -> "비슷한 주행 ${n}번과 흔들림은 같고, 부드러운 순간은 ${-dPerf}번 적었어요"
            else -> "비슷한 주행 ${n}번과 비슷하게 연주했어요"
        }
    }

    fun similarTo(r: DriveResult, tz: TimeZone = TimeZone.getDefault()): List<DriveResult> {
        val base = results.filter { it.startEpochMs != r.startEpochMs }.filter {
            val a = it.movingSeconds.toFloat(); val b = r.movingSeconds.toFloat().coerceAtLeast(1f)
            kotlin.math.abs(a - b) / b <= 0.35f && kotlin.math.abs(it.stops - r.stops) <= 2
        }
        val part = partOf(r.startEpochMs, tz)
        val samePart = base.filter { partOf(it.startEpochMs, tz) == part }
        return (if (samePart.isNotEmpty()) samePart else base).sortedByDescending { it.startEpochMs }.take(5)
    }

    companion object {
        fun dayIndex(ms: Long, tz: TimeZone): Int = Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L).toInt()
        /** 월요일 시작 주. */
        fun sameWeek(a: Long, b: Long, tz: TimeZone): Boolean = weekIndex(a, tz) == weekIndex(b, tz)
        private fun weekIndex(ms: Long, tz: TimeZone): Int = Math.floorDiv(dayIndex(ms, tz) + 3, 7) // 1970-01-01 = 목요일
        fun partOf(ms: Long, tz: TimeZone): AlbumNaming.PartOfDay =
            AlbumNaming.partOfDay(Calendar.getInstance(tz).apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY))
        fun isNight(ms: Long, tz: TimeZone): Boolean = partOf(ms, tz).let { it == AlbumNaming.PartOfDay.NIGHT || it == AlbumNaming.PartOfDay.DAWN }
    }
}

/** 친구 기록 탭의 예시 데이터(계정·서버가 없으므로 실제 친구가 아님을 화면에 분명히 표시). */
object FriendExamples {
    data class Row(val name: String, val grade: Grade, val score: Int, val bestCombo: Int, val songs: String)
    val rows = listOf(
        Row("예시 친구 A", Grade.S, 96, 88, "3/3곡"), Row("예시 친구 B", Grade.A, 84, 52, "2/3곡"),
        Row("예시 친구 C", Grade.B, 73, 40, "1/2곡"), Row("예시 친구 D", Grade.C, 55, 23, "1/3곡"),
    )
}

/** 기기 안(filesDir/play.json)에만 저장. 위치는 저장하지 않는다. */
class PlayStore(private val file: File) {
    @Synchronized fun load(): PlayProfile = runCatching {
        if (!file.exists()) return PlayProfile()
        val o = JSONObject(file.readText())
        val rs = o.getJSONArray("results")
        val results = (0 until rs.length()).mapNotNull { rs.getJSONObject(it).toResult() }
        val bo = o.optJSONObject("badges") ?: JSONObject()
        val badges = Badge.entries.filter { bo.has(it.name) }.associateWith { bo.getLong(it.name) }
        PlayProfile(results, badges)
    }.getOrDefault(PlayProfile())

    @Synchronized fun save(p: PlayProfile) {
        val o = JSONObject()
        o.put("results", JSONArray().apply { p.results.forEach { put(it.toJson()) } })
        o.put("badges", JSONObject().apply { p.badges.forEach { (b, t) -> put(b.name, t) } })
        val tmp = File(file.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(file)
    }

    /** 결과를 더해 저장하고 보고서를 돌려준다. */
    @Synchronized fun record(r: DriveResult): ResultReport {
        val (next, report) = load().add(r)
        save(next)
        return report
    }

    @Synchronized fun clear() { file.delete() }

    private fun DriveResult.toJson() = JSONObject().apply {
        put("v", 2); put("start", startEpochMs); put("end", endEpochMs); put("maxCombo", maxCombo)
        put("songs", JSONArray().apply { songs.forEach { s -> put(JSONObject().apply {
            put("n", s.number); put("a", s.attempt); put("done", s.completed); put("score", s.score); put("grade", s.grade.name)
            put("avg", s.avgGauge.toDouble()); put("long", s.longestBars); put("perf", s.perfects)
        }) } })
        put("perfects", JSONObject().apply { perfects.forEach { (k, v) -> put(k.name, v) } })
        put("misses", misses); put("rain", rained)
        put("safeM", safeDistanceM.toDouble()); put("moving", movingSeconds); put("stops", stops)
    }

    /** v1(곡 단위 이전) 기록은 읽지 않는다(형식이 다름). */
    private fun JSONObject.toResult(): DriveResult? {
        if (optInt("v", 1) < 2) return null
        val po = optJSONObject("perfects") ?: JSONObject()
        val sa = getJSONArray("songs")
        val songs = (0 until sa.length()).map { k -> sa.getJSONObject(k).run {
            SongResult(getInt("n"), getInt("a"), getBoolean("done"), getInt("score"), Grade.valueOf(getString("grade")), getDouble("avg").toFloat(), getInt("long"), getInt("perf"))
        } }
        return DriveResult(
            getLong("start"), getLong("end"), songs, getInt("maxCombo"),
            PerfectKind.entries.associateWith { po.optInt(it.name, 0) }, getInt("misses"),
            getBoolean("rain"), getDouble("safeM").toFloat(), getInt("moving"), getInt("stops"),
        )
    }
}
