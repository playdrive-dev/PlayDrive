package app.playdrive.music

/**
 * 플레이스홀더 곡: A 단조, 4마디 진행 Am – F – C – G (마디당 1코드).
 * 음높이가 있는 레이어(베이스·패드·멜로디)는 각 마디 코드의 구성음만 쓴다
 * → 어떤 레이어 조합을 켜도 불협화음이 나지 않는다(단위 테스트로 검증).
 */
object Composition {
    data class Note(val beat: Float, val lengthBeats: Float, val midi: Int, val velocity: Float = 1f)

    /** 마디별 코드 구성음(피치 클래스, C=0). */
    val chords: List<Set<Int>> = listOf(
        setOf(9, 0, 4),   // Am: A C E
        setOf(5, 9, 0),   // F : F A C
        setOf(0, 4, 7),   // C : C E G
        setOf(7, 11, 2),  // G : G B D
    )
    val chordNames = listOf("Am", "F", "C", "G")
    private val bassRoots = listOf(45, 41, 48, 43) // A2 F2 C3 G2

    /** 패드 보이싱(마디별) — 음 이동이 작게. */
    val pad: List<List<Int>> = listOf(
        listOf(57, 60, 64), // A3 C4 E4
        listOf(57, 60, 65), // A3 C4 F4
        listOf(55, 60, 64), // G3 C4 E4
        listOf(55, 59, 62), // G3 B3 D4
    )

    /** 베이스: 근음·옥타브·5도로 8분 리듬. */
    val bass: List<List<Note>> = bassRoots.map { r ->
        listOf(
            Note(0f, 0.75f, r), Note(1f, 0.5f, r, 0.8f), Note(1.5f, 0.5f, r + 12, 0.7f),
            Note(2f, 0.75f, r), Note(3f, 0.5f, r + 7, 0.8f), Note(3.5f, 0.5f, r + 12, 0.7f),
        )
    }

    /** 멜로디: 코드 구성음만. */
    val melody: List<List<Note>> = listOf(
        listOf(Note(0f, 1f, 76), Note(1f, 0.5f, 72, 0.8f), Note(1.5f, 0.5f, 69, 0.8f), Note(2f, 1.5f, 72), Note(3.5f, 0.5f, 76, 0.8f)),
        listOf(Note(0f, 1f, 77), Note(1f, 0.5f, 72, 0.8f), Note(1.5f, 0.5f, 69, 0.8f), Note(2f, 2f, 72)),
        listOf(Note(0f, 1f, 76), Note(1f, 0.5f, 79, 0.8f), Note(1.5f, 0.5f, 76, 0.8f), Note(2f, 1.5f, 72), Note(3.5f, 0.5f, 67, 0.8f)),
        listOf(Note(0f, 1.5f, 74), Note(1.5f, 0.5f, 71, 0.8f), Note(2f, 2f, 67)),
    )

    /** 드럼(박 단위): 킥 1, 2.5, 3 / 스네어 2, 4 (0부터 센 박). */
    val kicks = listOf(0f, 1.5f, 2f)
    val snares = listOf(1f, 3f)
}
