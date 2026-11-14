package app.playdrive.music

import app.playdrive.play.GenrePack

/**
 * 음악 팩(앞으로 팩을 더할 수 있게 곡 데이터를 팩 단위로): 팩 이름, 장르, 곡 목록, 곡마다 스템 파일(res/raw 이름)·물결 악보.
 * 지금은 기본 팩 1개. 잠긴 장르([GenrePack])는 연주 모드에서 흔들림 없이 달린 거리로 열린다(PlayProfile.progress).
 */
data class SongInfo(
    val id: String,
    val title: String,
    val bpm: Int,
    /** 반복 단위(마디). 연주 모드의 한 곡 = 64마디. */
    val loopBars: Int,
    /** 악기 → res/raw 파일 이름. */
    val stems: Map<Layer, String>,
    /** 물결 악보(res/raw, tools/make_wave_score.py). */
    val waveScore: String?,
)

data class MusicPack(val id: String, val name: String, val genre: String, val songs: List<SongInfo>, val unlock: GenrePack? = null)

object MusicPacks {
    val BASIC = MusicPack(
        id = "basic", name = "기본 팩", genre = "로파이",
        songs = listOf(
            SongInfo(
                id = "lofi_night_drive", title = "밤길 로파이", bpm = 90, loopBars = 8,
                stems = mapOf(
                    Layer.CHORDS to "song_keys", Layer.PAD to "song_synth", Layer.DRUMS to "song_drums",
                    Layer.HIHAT to "song_perc", Layer.BASS to "song_bass", Layer.MELODY to "song_guitar",
                ),
                waveScore = "wave_score",
            ),
        ),
    )

    /** 설치된 팩(곡이 있는 것). */
    val installed: List<MusicPack> = listOf(BASIC)

    /** 해금으로 열리는 장르(곡은 뮤직팩으로 추가 예정). */
    val lockedGenres: List<GenrePack> = GenrePack.entries
}
