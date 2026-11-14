package app.playdrive.play

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.music.Arrangement
import app.playdrive.music.Layer
import app.playdrive.music.MusicDirector
import app.playdrive.music.MusicTiming

/** 앱 모드: 연주(게이지·판정·FAILED·등급·연주 기록) / 듣기(곡의 모든 악기를 처음부터 끝까지, 게임 요소 없음). */
enum class AppMode { PLAY, LISTEN }

/** 모드 전환(순수): 주행 중에는 바꿀 수 없고, 마지막 선택을 기억한다. load/save = 기기 설정(SharedPreferences). */
class ModeSwitch(load: () -> String?, private val save: (String) -> Unit) {
    var mode: AppMode = runCatching { AppMode.valueOf(load() ?: AppMode.PLAY.name) }.getOrDefault(AppMode.PLAY)
        private set

    /** 반환 = 바뀌었거나 이미 그 모드. 주행 중이면 false(그대로). */
    fun request(m: AppMode, driving: Boolean): Boolean {
        if (driving) return m == mode
        if (m != mode) { mode = m; save(m.name) }
        return true
    }
}

/**
 * 듣기 모드의 연출(순수): 게이지 없이 악기 6개 전부. 차량 반응은 가볍게만 —
 * 출발하면 드럼·베이스가 들어오고, 신호 대기에는 잔잔해지고(코드·패드), 와이퍼면 음색이 바뀐다.
 * 흔들림(급가속·급제동·급조향)은 무시: 악기가 빠지거나 멈추지 않고, 판정·음성·결과도 없다.
 */
class ListenConductor(timing: MusicTiming, available: Set<Layer>, arrangement: Arrangement) {
    val director = MusicDirector(timing, available, arrangement).also { it.gauge = Layer.entries.size }

    fun update(state: DrivingState, events: List<DrivingEvent>): Conductor.Out {
        val calm = events.filterNot { it is DrivingEvent.HarshBraking || it is DrivingEvent.HarshAcceleration || it is DrivingEvent.HarshSteering }
        return Conductor.Out(director.update(state, calm), emptyList(), emptyList(), null)
    }
}
