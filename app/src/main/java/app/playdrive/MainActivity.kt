package app.playdrive

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.playdrive.album.Album
import app.playdrive.album.AlbumNaming
import app.playdrive.album.CoverSpec
import app.playdrive.album.DriveSummary
import app.playdrive.album.MoodState
import app.playdrive.album.MoodThrottle
import app.playdrive.engine.DrivingEvent
import app.playdrive.engine.DrivingState
import app.playdrive.music.Cue
import app.playdrive.music.Layer
import app.playdrive.music.MusicCommand
import app.playdrive.signal.PleosVehicleSignalSource
import app.playdrive.sim.DemoDriveSource
import app.playdrive.ui.BreathView
import app.playdrive.ui.CoverView
import app.playdrive.ui.GlyphButton
import app.playdrive.ui.MoodView
import app.playdrive.ui.PlayPauseButton
import app.playdrive.ui.UxRestrictionsWatcher

/**
 * 화면:
 *  - MOOD(기본·주행 중 유일): 단색 + 추상 그래픽 + 재생/일시정지. 글자·숫자·알림 없음.
 *  - 주차 후 1분: P단 + 운전석만 착석 → 제안 카드 → 1분 호흡(BREATH) → 마음 앨범
 *  - ALBUM: 마음 앨범(주행 1회 = 1장) — 정사각 커버 격자 + 제목 한 줄
 *  - ALBUM_DETAIL: 큰 커버 + 제목 + 날짜 + 회고 한 줄(입력은 정차 중에만)
 *  - DEBUG: 개발용(디버그 빌드)
 * 주행 중(CarUxRestrictions가 DO 요구 또는 시뮬레이션 주행 중)에는 MOOD 외 화면을 닫는다.
 */
class MainActivity : ComponentActivity(), DriveController.Listener {

    enum class Screen { MOOD, BREATH, ALBUM, ALBUM_DETAIL, DEBUG }

    private lateinit var controller: DriveController
    private lateinit var root: FrameLayout
    private lateinit var mood: MoodView
    private lateinit var play: PlayPauseButton
    private lateinit var albumBtn: View
    private lateinit var debugBtn: GlyphButton
    private var ux: UxRestrictionsWatcher? = null
    private val throttle = MoodThrottle()

    private var screen = Screen.MOOD
    private var overlay: View? = null          // 제안 카드
    private var page: View? = null             // BREATH / ALBUM / DEBUG
    private var breath: BreathView? = null
    private var debugStatus: TextView? = null
    private var debugDetail: TextView? = null

    private var uxRestricted = false
    private var simMoving = false
    private val driving get() = uxRestricted || simMoving
    private var lastState = DrivingState()
    private var lastAlbum: Album? = null
    private var prepared = false
    private var pendingIntent: Intent? = null
    private var debugIcon = true

    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startReal() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        mood = MoodView(this)
        root.addView(mood, FrameLayout.LayoutParams(MATCH, MATCH))
        play = PlayPauseButton(this).apply { setOnClickListener { controller.togglePlay() }; isEnabled = false; alpha = 0.4f }
        root.addView(play, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        // 마음 앨범 버튼: 음반 아이콘 + 글자(무슨 버튼인지 바로 보이게), 높이 76dp
        albumBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(28), 0)
            background = GradientDrawable().apply { cornerRadius = dp(38).toFloat(); setColor(0x29FFFFFF) }
            isClickable = true; isFocusable = true; contentDescription = getString(R.string.album_title)
            addView(GlyphButton(this@MainActivity, 'A', getString(R.string.album_title), bare = true), LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(text(getString(R.string.album_title), BODY_SP).apply { setPadding(dp(10), 0, 0, 0) })
            setOnClickListener { showAlbum(null) }
        }
        root.addView(albumBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(20), 0) })
        // 개발용(디버그 빌드에서만 보임, 릴리스 빌드엔 없음)
        debugBtn = GlyphButton(this, 'D', "debug").apply { setOnClickListener { showDebug() }; visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE }
        root.addView(debugBtn, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP), Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(20), 0, 0) })
        setContentView(root)

        controller = DriveController(this, this)
        controller.prepare()
        // 시작 화면: 가장 최근 앨범 커버를 은은하게 깔아 둔다(새 주행을 시작하면 새 커버로)
        controller.albums.all().firstOrNull()?.let { mood.setCover(AlbumNaming.cover(it), immediate = true) }
        ux = UxRestrictionsWatcher(this) { r -> runOnUiThread { uxRestricted = r; applyDrivingPolicy() } }
        pendingIntent = intent
    }

    // ------------------------------------------------------------------ 주행 정책
    private fun applyDrivingPolicy() {
        mood.restricted = driving
        if (driving) { dismissOverlay(); if (screen != Screen.MOOD && screen != Screen.DEBUG) showMood() }
        updateMoodIcons()
    }

    /** 무드 화면 위 아이콘: 주행 중·제안 카드가 떠 있을 때는 숨긴다. */
    private fun updateMoodIcons() {
        val show = !driving && overlay == null
        albumBtn.visibility = if (show) View.VISIBLE else View.GONE
        debugBtn.visibility = if (BuildConfig.DEBUG && show && debugIcon) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ DriveController.Listener
    override fun onPrepared(elapsedMs: Long) {
        prepared = true
        play.isEnabled = true; play.alpha = 1f
        debugStatus?.text = "준비 완료 (음원 로드 ${elapsedMs}ms)"
        val pi = pendingIntent; pendingIntent = null
        if (pi?.getStringExtra("debug") != null) handleDebugIntent(pi) else requestSpeedThenStart()
    }

    override fun onPlayingChanged(playing: Boolean) { play.playing = playing }

    override fun onUpdate(state: DrivingState, recentEvents: List<DrivingEvent>, layers: Set<Layer>, debug: String) {
        lastState = state
        if (simMoving != state.moving) { simMoving = state.moving; applyDrivingPolicy() }
        throttle.offer(MoodState.from(state, layers.size, controller.totalLayers))?.let { mood.show(it) }  // 주행 중이면 MoodView가 보관만 한다
        debugDetail?.text = buildString {
            appendLine("속도 ${"%.1f".format(state.speedMps * 3.6f)} km/h  가속 ${"%.2f".format(state.accelMps2)}  기어 ${state.gear}  브레이크 ${state.brakePressed}")
            appendLine("주행=${state.moving} 정속=${state.cruising} 방향지시등=${state.turnSignal} 비=${state.raining} 혼자=${state.solo}  UX제한=$uxRestricted")
            appendLine("레이어: ${layers.joinToString()}   [$debug]")
            recentEvents.asReversed().take(8).forEach { appendLine(it.toString()) }
        }
    }

    override fun onCover(cover: CoverSpec) { mood.setCover(cover) }  // 주행 중이면 MoodView가 보관만, 정차하면 한 겹 그리는 장면

    override fun onSourceFinished() { debugStatus?.text = "데모 드라이브 끝" }

    override fun onDriveFinished(album: Album, suggestBreath: Boolean) {
        lastAlbum = album
        // 마지막 정차의 "한 겹 추가" 장면(4초)을 다 보여준 뒤 제안 카드·앨범으로
        root.postDelayed({
            if (suggestBreath && !driving) showBreathOffer(album) else if (!driving && screen == Screen.MOOD) showAlbum(album)
        }, app.playdrive.ui.MoodScene.REVEAL_MS)
    }

    // ------------------------------------------------------------------ 화면들
    private fun clearPage() { breath?.cancel(); breath = null; page?.let { root.removeView(it) }; page = null; debugStatus = null; debugDetail = null }

    private fun showMood() { clearPage(); screen = Screen.MOOD }

    private fun dismissOverlay() { overlay?.let { root.removeView(it) }; overlay = null; updateMoodIcons() }

    /** 주차 후 1분 제안 카드(정차 중에만). */
    private fun showBreathOffer(album: Album?) {
        if (driving) return
        dismissOverlay()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(32), dp(36), dp(24))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xF01B2633.toInt()) }
        }
        card.addView(text(getString(R.string.breath_offer_title), TITLE_SP, bold = true))
        card.addView(text(getString(R.string.breath_offer_body), BODY_SP, color = 0xFFC9D3DC.toInt()).apply { setPadding(0, dp(12), 0, dp(20)); setLineSpacing(0f, 1.15f); phraseBreak() })
        val row = LinearLayout(this).apply { gravity = Gravity.END }
        row.addView(button(getString(R.string.breath_decline), primary = false) { dismissOverlay(); showAlbum(album ?: lastAlbum) })
        row.addView(button(getString(R.string.breath_accept), primary = true) { dismissOverlay(); showBreath(album ?: lastAlbum) })
        card.addView(row)
        overlay = card
        root.addView(card, FrameLayout.LayoutParams(dp(760).coerceAtMost(root.width - dp(48)).takeIf { it > 0 } ?: dp(760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })  // 재생 버튼과 겹치지 않게 위쪽
        updateMoodIcons()
    }

    /** 1분 호흡: 오늘 곡을 아주 작게 + 들숨/날숨 원. 끝나면 마음 앨범으로. */
    private fun showBreath(album: Album?, frozenAtMs: Long? = null) {
        if (driving) return
        clearPage(); screen = Screen.BREATH
        controller.setBreathMode(true)
        val frame = FrameLayout(this)
        val phaseText = text("", TITLE_SP, color = 0xFFDDE7EE.toInt()).apply { gravity = Gravity.CENTER }
        val bv = BreathView(this, onPhase = { inhale -> runOnUiThread { phaseText.text = getString(if (inhale) R.string.breath_inhale else R.string.breath_exhale) } },
            onDone = { runOnUiThread { controller.setBreathMode(false); showAlbum(album, getString(R.string.breath_done)) } })
        bv.frozenAtMs = frozenAtMs
        breath = bv
        frame.addView(bv, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(phaseText, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(104) })
        // 오늘 주행 곡(아주 작게): 커버 + 제목
        if (album != null) {
            val mini = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0.7f }
            mini.addView(CoverView(this).apply { spec = AlbumNaming.cover(album) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            mini.addView(text(album.title, BODY_SP, color = 0xFFAFC0CC.toInt()).apply { setPadding(dp(14), 0, 0, 0) })
            frame.addView(mini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        }
        frame.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { controller.setBreathMode(false); showMood() } },
            FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(20), 0) })
        page = frame
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        if (frozenAtMs == null) bv.begin()
    }

    /** 마음 앨범: 정사각 커버 격자 + 커버 아래 제목 한 줄. highlight = 방금 생긴 앨범(첫 칸, 은은한 테두리). */
    private fun showAlbum(highlight: Album?, banner: String? = null) {
        if (driving) return
        clearPage(); screen = Screen.ALBUM
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(PAD_DP), dp(20), dp(PAD_DP), 0); setBackgroundColor(0xFF0F151D.toInt()) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(getString(R.string.album_title), TITLE_SP, bold = true))
        val sub = banner ?: if (highlight != null) getString(R.string.album_new) else null
        head.addView(text(sub ?: "", BODY_SP, color = 0xFF8FD3C6.toInt()).apply { setPadding(dp(24), 0, 0, 0); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { showMood() } }, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        col.addView(head)
        val albums = controller.albums.all()
        if (albums.isEmpty()) {
            col.addView(text(getString(R.string.album_empty), BODY_SP, color = 0xFF9AA8B4.toInt()).apply { setPadding(0, dp(24), 0, 0) })
        } else {
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, dp(PAD_DP)) }
            col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)) },
                LinearLayout.LayoutParams(MATCH, 0, 1f))
            // 폭을 알아야 열 수를 정할 수 있다
            grid.post { fillGrid(grid, albums, highlight?.id) }
        }
        page = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /**
     * 열 수: 제목이 한 줄에 다 들어가는 가장 많은 열(최대 4, 최소 2).
     * 커버는 정사각형, 한 화면에 한 줄은 온전히 보이도록 높이에 맞춰 줄인다.
     */
    private fun fillGrid(grid: LinearLayout, albums: List<Album>, highlightId: Long?) {
        val avail = grid.width
        val gap = dp(GAP_DP)
        val probe = text("", BODY_SP)
        val widest = albums.maxOf { probe.paint.measureText(it.title) } + dp(8)
        val cols = (4 downTo 2).firstOrNull { n -> (avail - gap * (n - 1)) / n >= widest } ?: 2
        val cell = (avail - gap * (cols - 1)) / cols
        val viewH = (grid.parent as View).height
        val cover = minOf(cell, viewH - dp(16) - dp(56))
        albums.chunked(cols).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { isBaselineAligned = false; setPadding(0, if (r == 0) 0 else gap, 0, 0) }
            chunk.forEachIndexed { k, a ->
                row.addView(albumCell(a, cover, a.id == highlightId), LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (k > 0) leftMargin = gap })
            }
            grid.addView(row)
        }
    }

    private fun albumCell(a: Album, size: Int, highlight: Boolean): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true; contentDescription = a.title
            setOnClickListener { showAlbumDetail(a) }
        }
        val ring = dp(3)
        val frame = FrameLayout(this).apply {
            setPadding(ring, ring, ring, ring)
            if (highlight) background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setStroke(ring, 0x998FD3C6.toInt()) } // 새 앨범: 은은한 청록 테두리
            addView(CoverView(this@MainActivity).apply { spec = AlbumNaming.cover(a); clipToOutline = true; outlineProvider = roundedOutline(dp(16)) }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        cell.addView(frame, LinearLayout.LayoutParams(size, size))
        // "두 번째 길"은 한 덩어리로(줄바꿈 시 "… 빗속의 / 두 번째 길")
        val shown = a.title.replace(Regex("(\\S+) 번째 길$")) { "${it.groupValues[1]}\u00A0번째\u00A0길" }
        cell.addView(text(shown, BODY_SP, color = if (highlight) 0xFFDDF3EE.toInt() else Color.WHITE).apply {
            // 한 줄이 기본. 24sp에서 칸보다 긴 제목(예: "… 빗속의 두 번째 길")만 어절 단위로 둘째 줄까지 — 구분되는 끝부분이 잘리지 않게
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; phraseBreak(); setPadding(dp(4), dp(10), dp(4), 0)
        })
        return cell
    }

    /** 앨범 상세: 큰 커버 + 제목 + 날짜 + 회고 한 줄(쓴 것은 글로, 없으면 입력칸 — 정차 중에만). */
    private fun showAlbumDetail(a: Album) {
        if (driving) return
        clearPage(); screen = Screen.ALBUM_DETAIL
        val album = controller.albums.all().find { it.id == a.id } ?: a
        val pageView = FrameLayout(this).apply { setBackgroundColor(0xFF0F151D.toInt()) }
        // 위쪽은 뒤로 버튼 자리(20 + 76 + 8dp)를 비워 둔다 → 제목이 버튼 밑으로 들어가지 않음
        val body = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(PAD_DP), dp(104), dp(PAD_DP), dp(PAD_DP)) }
        val cover = CoverView(this).apply { spec = AlbumNaming.cover(album); clipToOutline = true; outlineProvider = roundedOutline(dp(20)) }
        body.addView(cover, LinearLayout.LayoutParams(dp(360), dp(360)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(36), 0, 0, 0) }
        info.addView(text(album.title, TITLE_SP, bold = true).apply { setLineSpacing(0f, 1.1f); phraseBreak() })
        info.addView(text(AlbumNaming.dateLine(album.summary.startEpochMs, sep = "\n"), BODY_SP, color = 0xFF9AA8B4.toInt()).apply { setPadding(0, dp(12), 0, dp(28)) })
        if (album.reflection.isNotBlank()) {
            info.addView(text("“${album.reflection}”", BODY_SP, color = 0xFFDDE7EE.toInt()).apply { setLineSpacing(0f, 1.2f); phraseBreak() })
        } else {
            val edit = EditText(this).apply {
                hint = getString(R.string.album_reflection_hint); textSize = BODY_SP; minHeight = dp(TOUCH_DP)
                setTextColor(Color.WHITE); setHintTextColor(0xFF6F7E8A.toInt()); inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = false; maxLines = 3; setHorizontallyScrolling(false); isEnabled = !driving
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE; phraseBreak()
                filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            }
            info.addView(edit, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
            info.addView(LinearLayout(this).apply {
                gravity = Gravity.END; setPadding(0, dp(12), 0, 0)
                addView(button(getString(R.string.album_reflection_save), primary = true) {
                    val t = edit.text.toString().trim()
                    if (!driving && t.isNotEmpty()) { controller.albums.updateReflection(album.id, t); showAlbumDetail(album) }
                })
            })
        }
        body.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pageView.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        pageView.addView(GlyphButton(this, '<', "앨범 목록으로").apply { setOnClickListener { showAlbum(null) } },
            FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP), Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(20), 0, 0) })
        // 커버 = 화면 높이에 맞춘 정사각형(가로로 긴 화면에서도 왼쪽 절반을 넘지 않게)
        body.post {
            val side = minOf(body.height - body.paddingTop - body.paddingBottom, (body.width * 0.45f).toInt())
            if (side > 0) cover.layoutParams = LinearLayout.LayoutParams(side, side)
        }
        page = pageView
        root.addView(pageView, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** 한국어 줄바꿈을 글자 단위가 아니라 어절(구) 단위로(API 33+). */
    private fun TextView.phraseBreak() {
        if (android.os.Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }

    private fun roundedOutline(radius: Int) = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat()) }
    }

    /** 개발용 디버그 패널(디버그 빌드). */
    private fun showDebug() {
        clearPage(); screen = Screen.DEBUG
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)); setBackgroundColor(0xF0101216.toInt()) }
        debugStatus = text(if (prepared) "준비 완료" else "음악 준비 중…", 20f)
        col.addView(debugStatus)
        val r1 = LinearLayout(this)
        r1.addView(button("데모 드라이브 (74초)", false) { startDemo() })
        r1.addView(button("실제 차량 신호", false) { requestSpeedThenStart() })
        r1.addView(button("정지", false) { controller.stop() })
        r1.addView(button("닫기", false) { showMood() })
        col.addView(r1)
        val r2 = LinearLayout(this)
        for (l in Layer.entries) r2.addView(button(l.name, false, 11f) { controller.debugCommand(MusicCommand.SetLayer(l, true)) })
        r2.addView(button("테이프스톱", false, 11f) { controller.debugCommand(MusicCommand.PlayCue(Cue.TAPE_STOP)) })
        col.addView(r2)
        debugDetail = text("", 14f, color = 0xFFC8CDD2.toInt())
        col.addView(debugDetail)
        page = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------------ 신호원
    private fun startDemo() { controller.start(DemoDriveSource(controller.engineHandler) { controller.notifyFinished() }); debugStatus?.text = "데모 드라이브 재생 중" }

    private fun requestSpeedThenStart() {
        if (checkSelfPermission(PERM_SPEED) == PackageManager.PERMISSION_GRANTED) startReal() else speedPermission.launch(PERM_SPEED)
    }

    private fun startReal() { controller.start(PleosVehicleSignalSource(this)) }

    // ------------------------------------------------------------------ 디버그 인텐트
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (prepared) handleDebugIntent(intent) else pendingIntent = intent
    }

    /**
     * 디버그 빌드 전용(테스트·스크린샷·영상):
     *  --es debug demo | real | stop
     *  --es debug screen:mood | screen:drive | screen:park | screen:breath | screen:album | screen:detail[:n] | screen:debug
     *  --es debug seed:albums   (기존 앨범을 지우고 예시 앨범 3장 생성, 기기 안에만)
     *  --es debug icon:debug:off|on   (무드 화면의 디버그 아이콘 숨김 — 스크린샷용)
     *  --es debug layers:PAD,BASS,...  /  cue:TAPE_STOP|SCRATCH  /  fx:TapeStop|LowpassDip|PitchWobble
     */
    private fun handleDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val cmd = intent.getStringExtra("debug") ?: return
        Log.i(TAG, "debug intent: $cmd")
        when {
            cmd == "demo" -> { showMood(); startDemo() }
            cmd == "real" -> requestSpeedThenStart()
            cmd == "stop" -> controller.stop()
            cmd == "screen:mood" -> { dismissOverlay(); showMood() }
            cmd == "screen:drive" -> { // 주행 중 모습(시뮬레이션): 상태를 먼저 그린 뒤 UX 제한 → 그래픽 고정
                dismissOverlay(); showMood(); simMoving = false; applyDrivingPolicy()
                mood.show(MoodState(energy = 0.85f, calm = 0.8f, brightness = 0.9f, rain = false), immediate = true)
                simMoving = true; applyDrivingPolicy()
            }
            cmd == "screen:park" -> { simMoving = false; applyDrivingPolicy(); showMood(); showBreathOffer(lastAlbum ?: controller.albums.all().firstOrNull()) }
            cmd == "screen:breath" -> { simMoving = false; applyDrivingPolicy(); showBreath(lastAlbum ?: controller.albums.all().firstOrNull(), frozenAtMs = 3_600L) }
            cmd == "screen:album" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); showAlbum(controller.albums.all().firstOrNull()) }
            cmd == "screen:detail" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); controller.albums.all().firstOrNull()?.let { showAlbumDetail(it) } }
            cmd.startsWith("screen:detail:") -> { // n번째 앨범(0부터) 상세
                simMoving = false; applyDrivingPolicy(); dismissOverlay()
                controller.albums.all().getOrNull(cmd.removePrefix("screen:detail:").toIntOrNull() ?: 0)?.let { showAlbumDetail(it) }
            }
            cmd == "screen:debug" -> showDebug()
            cmd == "icon:debug:off" -> { debugIcon = false; updateMoodIcons() }   // 스크린샷용
            cmd == "icon:debug:on" -> { debugIcon = true; updateMoodIcons() }
            cmd == "seed:albums" -> seedAlbums()
            cmd.startsWith("layers:") -> {
                val want = cmd.removePrefix("layers:").split(",").mapNotNull { n -> Layer.entries.find { it.name == n.trim() } }.toSet()
                Layer.entries.forEach { controller.debugCommand(MusicCommand.SetLayer(it, it in want)) }
            }
            cmd.startsWith("cue:") -> Cue.entries.find { it.name == cmd.removePrefix("cue:") }?.let { controller.debugCommand(MusicCommand.PlayCue(it)) }
            cmd.startsWith("fx:") -> when (cmd.removePrefix("fx:")) {
                "TapeStop" -> controller.debugCommand(MusicCommand.TapeStop)
                "LowpassDip" -> controller.debugCommand(MusicCommand.LowpassDip)
                "PitchWobble" -> controller.debugCommand(MusicCommand.PitchWobble)
            }
        }
    }

    /** 스크린샷용 예시 앨범(가상의 주행 3회: 요일·시간대·날씨가 모두 다름). 기존 앨범은 지운다. 기기 안에만 저장. */
    private fun seedAlbums() {
        controller.albums.clear()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun at(daysAgo: Int, hour: Int): Long {
            val c = java.util.Calendar.getInstance(); c.timeInMillis = now - daysAgo * day
            c.set(java.util.Calendar.HOUR_OF_DAY, hour); c.set(java.util.Calendar.MINUTE, 10); return c.timeInMillis
        }
        listOf(
            DriveSummary(at(1, 21), at(1, 21) + 1_500_000, 82f, true, 1400) to "빗소리랑 같이 천천히 왔다",  // 어제 밤, 비
            DriveSummary(at(2, 8), at(2, 8) + 1_800_000, 74f, false, 1700) to "",                          // 그제 아침, 맑음
            DriveSummary(at(4, 14), at(4, 14) + 2_400_000, 41f, false, 2200) to "",                        // 나흘 전 한낮, 맑음·분주
        ).forEach { (s, note) -> controller.albums.add(Album(s.startEpochMs, s, AlbumNaming.uniqueTitle(s, controller.albums.all().map { it.summary }), note)) }
        controller.albums.all().firstOrNull()?.let { mood.setCover(AlbumNaming.cover(it), immediate = true) }
        if (screen == Screen.ALBUM) showAlbum(null)
    }

    override fun onDestroy() {
        ux?.release()
        controller.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 작은 도우미
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, primary: Boolean, size: Float = BODY_SP, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = size; isAllCaps = false; minHeight = dp(TOUCH_DP); minimumHeight = dp(TOUCH_DP); minWidth = dp(120)
        setTextColor(if (primary) 0xFF0F151D.toInt() else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(38).toFloat(); setColor(if (primary) 0xFF8FD3C6.toInt() else 0x33FFFFFF) }
        setPadding(dp(32), dp(8), dp(32), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "PlayDrive.UI"
        private const val PERM_SPEED = "android.car.permission.CAR_SPEED"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** 차량 화면 기준: 터치 영역 최소 76dp, 본문 24sp 이상, 제목 32sp 이상. */
        const val TOUCH_DP = 76
        const val BODY_SP = 24f
        const val TITLE_SP = 32f
        private const val PAD_DP = 32
        private const val GAP_DP = 24
    }
}
