package app.playdrive.play

import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.music.Arrangement
import app.playdrive.music.Cue
import app.playdrive.music.Layer
import app.playdrive.music.MusicCommand
import app.playdrive.music.MusicDirector
import app.playdrive.music.MusicTiming
import app.playdrive.voice.Line

/**
 * 운전 → 연주 기록(곡·게이지) → 음악 명령·음성(순수). DriveController와 테스트가 같은 경로를 쓴다.
 *
 *  - 게이지 = 남은 악기 수 → [MusicDirector.gauge] (흔들림으로 줄면 다음 박에 빠짐)
 *  - 악기 1개 = 위험: 먹먹하게(Muffle 1) + "조금만 부드럽게요"(곡당 1회)
 *  - 게이지 0 = 연주 정지: 테이프스톱 → 잔잔한 keys+synth만, 먹먹·작게(Muffle 2) + "연주가 멈췄어요…"
 *  - 재시작: "다시 시작할게요" → 4박 스틱 카운트인 → 같은 곡 처음부터, 악기 3개
 *  - 곡 완주(64마디): 마무리 화음 + 다음 마디 머리에서 다음 곡(심벌, 악기 4개)
 *  - 이어진 마디: 16마디마다 필인, 32 합류, 48 클라이맥스(게이지와 별개, 흔들림이면 0)
 *  - 판정: 소리 보상 + 악기 +1
 */
class Conductor(
    timing: MusicTiming,
    available: Set<Layer>,
    arrangement: Arrangement,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    val tracker = PlayTracker(timing.barMs, clock)
    val director = MusicDirector(timing, available, arrangement).also { it.gauge = 0 }

    class Out(
        val commands: List<MusicCommand>,
        val events: List<PlayEvent>,
        val lines: List<Line>,
        val result: DriveResult?,
    )

    /** 지금 연주 상태(화면: 빛 원의 밝기·크기 = 남은 악기 수, 정지 때 어둡게). */
    val gauge: Int get() = tracker.gauge
    val mode: PlayMode get() = tracker.mode

    fun update(state: DrivingState, events: List<DrivingEvent>): Out {
        val pe = tracker.update(state, events)
        val cmds = mutableListOf<MusicCommand>()
        val lines = mutableListOf<Line>()

        // 연주 기록 사건 → 게이지·정지를 연출에 먼저 알리고, 소리 보상·음성을 붙인다
        for (e in pe) when (e) {
            is PlayEvent.Gauge -> if (e.loss) director.lossNextBeat = true
            is PlayEvent.Danger -> if (e.on) { cmds += MusicCommand.Muffle(1); if (e.firstInSong) lines += Line.Danger } else if (tracker.mode == PlayMode.RUNNING) cmds += MusicCommand.Muffle(0)
            is PlayEvent.Failed -> { cmds += MusicCommand.PlayCue(Cue.TAPE_STOP); cmds += MusicCommand.Muffle(2); cmds += MusicCommand.PerfBed(0); lines += Line.Stop }
            is PlayEvent.CountIn -> { if (e.retry) lines += Line.Resume; cmds += MusicCommand.Muffle(0); cmds += MusicCommand.RestartSong(countIn = true) }
            is PlayEvent.OverSpeed -> cmds += MusicCommand.Muffle(if (e.on || tracker.gauge == 1) 1 else 0)
            is PlayEvent.FinalStretch -> cmds += MusicCommand.PerfBed(2)
            is PlayEvent.Arrived -> cmds += MusicCommand.PerfReward(MusicCommand.Reward.CADENCE)
            is PlayEvent.SongComplete -> { cmds += MusicCommand.PerfReward(MusicCommand.Reward.CADENCE); cmds += MusicCommand.RestartSong(countIn = false) }
            is PlayEvent.Combo -> {
                if (e.isFill) cmds += MusicCommand.PerfFill
                if (e.isJoin) cmds += MusicCommand.PerfBed(1)
                if (e.isClimax) cmds += MusicCommand.PerfBed(2)
            }
            is PlayEvent.Perfect -> cmds += MusicCommand.PerfReward(when (e.kind) {
                PerfectKind.START -> MusicCommand.Reward.DROP
                PerfectKind.CHANGE -> MusicCommand.Reward.SWELL
                PerfectKind.STOP -> MusicCommand.Reward.CADENCE
                PerfectKind.TURN -> MusicCommand.Reward.SWELL
                PerfectKind.ZONE -> MusicCommand.Reward.DROP
            })
            is PlayEvent.Miss -> cmds += MusicCommand.PerfBed(0)
            is PlayEvent.SongStart -> Unit
        }
        director.gauge = tracker.gauge
        director.stopped = tracker.mode == PlayMode.FAILED
        // 연출(레이어·드롭·반응형 연주·비·속도 톤) — 게이지 반영 후
        val dir = director.update(state, events)
        // 곡 처음부터/카운트인 명령은 레이어 변경보다 먼저 믹서에 들어가야 그 순간까지 미뤄진다
        val restart = cmds.filterIsInstance<MusicCommand.RestartSong>()
        val ordered = restart + dir + (cmds - restart.toSet())
        return Out(ordered, pe, lines, tracker.lastResult)
    }
}
