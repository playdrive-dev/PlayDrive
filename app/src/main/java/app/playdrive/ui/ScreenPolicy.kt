package app.playdrive.ui

/** 앱 화면. 주행 중에는 무드 화면(+ 디버그 빌드의 개발 패널)만 — 결과·기록·앨범·설정은 정차 중에만. */
enum class Screen { MOOD, ALBUM, ALBUM_DETAIL, SETTINGS, RESULT, RECORDS, SONGS, DEBUG }

object ScreenPolicy {
    /** 주행 중 보여도 되는 화면. 무드 화면에는 글자·숫자가 없다(재생/일시정지 버튼뿐). */
    fun allowedWhileDriving(s: Screen): Boolean = s == Screen.MOOD || s == Screen.DEBUG

    /** 숫자·텍스트를 보여주는 화면(주행 중 금지 대상). */
    val textScreens = setOf(Screen.ALBUM, Screen.ALBUM_DETAIL, Screen.SETTINGS, Screen.RESULT, Screen.RECORDS, Screen.SONGS)
}
