package app.playdrive.voice

import android.content.Context
import android.util.Log
import app.playdrive.music.StereoPcm
import app.playdrive.music.stems.OggDecoder
import app.playdrive.play.Grade

/**
 * 음성 안내 문장(미리 녹음한 res/raw/voice_*.ogg를 이어 붙여 재생). 파일만 바꾸면 목소리가 바뀐다(tools/make_voice.py).
 * Android TextToSpeech를 쓰지 않는 이유: Pleos 에뮬레이터에 표준 TTS 엔진이 없음(docs/dev-notes.md 2026-11-13).
 */
sealed class Line(val key: String, val whileDriving: Boolean) {
    /** 연주 정지(게이지 0): "연주가 멈췄어요. 부드럽게 달리면 다시 시작할게요." */
    data object Stop : Line("stop", true)
    /** 카운트인 직전: "다시 시작할게요." */
    data object Resume : Line("resume", true)
    /** 악기 1개(위험, 곡당 1회): "조금만 부드럽게요." */
    data object Danger : Line("danger", true)
    /** 주차 결과: "오늘의 연주는 {최고 등급}등급이에요. {그 곡의 점}점." */
    data class Result(val grade: Grade, val score: Int) : Line("result", false)
    /** 주차 결과(완주한 곡이 없을 때 — 하루 단위 실패가 아니라 격려): "이번엔 끝까지 연주한 곡이 없어요. 다음엔 한 곡 완주해 봐요." */
    data object NoSong : Line("nosong", false)
    data object WeeklyBest : Line("weekly", false)
    data object NewBadge : Line("badge", false)

    /** Pleos TTS로 읽을 문장. */
    fun text(): String = when (this) {
        Stop -> "연주가 멈췄어요. 부드럽게 달리면 다시 시작할게요."
        Resume -> "다시 시작할게요."
        Danger -> "조금만 부드럽게요."
        is Result -> "오늘의 연주는 ${grade.label}등급이에요. ${score}점."
        NoSong -> "이번엔 끝까지 연주한 곡이 없어요. 다음엔 한 곡 완주해 봐요."
        WeeklyBest -> "이번 주 최고 기록이에요."
        NewBadge -> "새 배지를 얻었어요."
    }

    /** TTS가 안 될 때 이어 붙일 res/raw 이름. */
    fun clips(): List<String> = when (this) {
        Stop -> listOf("voice_stop")
        Resume -> listOf("voice_resume")
        Danger -> listOf("voice_danger")
        is Result -> listOf("voice_grade_${grade.name.lowercase()}", "voice_score_${score.coerceIn(0, 100)}")
        NoSong -> listOf("voice_nosong")
        WeeklyBest -> listOf("voice_weekly_best")
        NewBadge -> listOf("voice_badge")
    }
}

/**
 * 언제 말할지(순수). 주행 중에는 운전과 직접 관련된 두 문장(중단·회복)만, 같은 문장은 60초 안에 다시 하지 않는다.
 * 결과·기록·배지 문장은 정차(주차) 중에만. 설정에서 끄면 아무것도 말하지 않는다.
 */
class VoicePolicy(private val minRepeatMs: Long = 60_000L) {
    var enabled = true
    private val lastSaid = HashMap<String, Long>()

    /** 말해도 되면 true를 돌려주고 기록한다. nowMs = 단조 시계. */
    fun allow(line: Line, nowMs: Long, driving: Boolean): Boolean {
        if (!enabled) return false
        if (driving && !line.whileDriving) return false
        if (driving) {
            val last = lastSaid[line.key]
            if (last != null && nowMs - last < minRepeatMs) return false
        }
        lastSaid[line.key] = nowMs
        return true
    }
}

/** 음성 클립을 디코딩해 둔다(첫 사용 때 디코딩, 이후 메모리). 엔진 스레드에서 부른다. */
class VoiceClips(private val context: Context, private val sampleRate: Int) {
    private val cache = HashMap<String, StereoPcm?>()

    fun load(names: List<String>): List<StereoPcm> = names.mapNotNull { n ->
        cache.getOrPut(n) {
            val id = context.resources.getIdentifier(n, "raw", context.packageName)
            if (id == 0) { Log.w(TAG, "음성 파일 없음: $n"); null }
            else runCatching { OggDecoder.decode(context, id, sampleRate) }.onFailure { Log.w(TAG, "음성 디코딩 실패 $n: $it") }.getOrNull()
        }
    }

    /** 자주 쓰는 문장은 미리(주행 중 첫 안내가 늦지 않게). */
    fun warmUp() { load(listOf("voice_stop", "voice_resume")) }

    companion object { const val TAG = "PlayDrive.Voice" }
}
