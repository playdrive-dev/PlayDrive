package app.playdrive.ui

/**
 * 창 모양(순수): Pleos 창 정책 — Connect-S는 앱 2개면 1:1, Connect-W는 2:1·1:2, 창은 가로/세로 전환 가능.
 * 가로:세로 < 1 이면 세로 패널 레이아웃, 아니면 가로 레이아웃.
 */
object WindowShape {
    fun narrow(w: Int, h: Int): Boolean = w in 1 until h

    /** 위 줄(데모·연주/듣기·드라이브 앨범)이 한 줄에 들어가지 않을 만큼 좁은 창(약 900dp 미만)이면 연주/듣기를 한 줄 내린다. */
    fun stackTop(w: Int, density: Float): Boolean = w / density < 900f
}
