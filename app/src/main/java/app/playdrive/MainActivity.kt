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
import app.playdrive.ui.CoverView
import app.playdrive.ui.GlyphButton
import app.playdrive.ui.MoodView
import app.playdrive.ui.PlayPauseButton
import app.playdrive.ui.UxRestrictionsWatcher
import app.playdrive.ui.Screen
import app.playdrive.ui.ScreenPolicy
import app.playdrive.play.Badge
import app.playdrive.play.DriveResult
import app.playdrive.play.FriendExamples
import app.playdrive.play.GenrePack
import app.playdrive.play.Grade
import app.playdrive.play.PerfectKind
import app.playdrive.play.ResultReport
import app.playdrive.sim.DriveScenario

/**
 * 화면:
 *  - MOOD(기본·주행 중 유일): 단색 + 추상 그래픽 + 재생/일시정지. 글자·숫자·알림 없음.
 *  - 주차하면 바로 오늘의 연주(RESULT) → 확인 → 드라이브 앨범
 *  - ALBUM: 드라이브 앨범(주행 1회 = 1장) — 정사각 커버 격자 + 제목 한 줄
 *  - ALBUM_DETAIL: 큰 커버 + 제목 + 날짜
 *  - SETTINGS: 화면 효과(라이브/차분), 음성 안내 켜기/끄기 — 정차 중에만
 *  - RESULT: 주차 후 오늘의 연주(등급·점·이어진 마디·판정·흔들림·곡 완성도·새 배지) — 정차 중에만
 *  - RECORDS: 연주 기록(내 기록 / 비슷한 주행 / 친구 기록(예시) / 배지·음악) — 정차 중에만
 *  - DEBUG: 개발용(디버그 빌드)
 * 주행 중(CarUxRestrictions가 DO 요구 또는 시뮬레이션 주행 중)에는 MOOD 외 화면을 닫는다.
 */
class MainActivity : ComponentActivity(), DriveController.Listener {

    private lateinit var controller: DriveController
    private lateinit var root: FrameLayout
    private lateinit var mood: MoodView
    private lateinit var play: PlayPauseButton
    private lateinit var albumBtn: View
    private lateinit var recordsBtn: View
    /** 듣기 모드의 곡 목록 버튼(연주 기록 버튼 자리). */
    private lateinit var songsBtn: View
    /** 연주 / 듣기 선택(정차 중 첫 화면, 마지막 선택 기억, 주행 중엔 막힘). */
    private lateinit var modeToggle: LinearLayout
    private lateinit var modeSwitch: app.playdrive.play.ModeSwitch
    private val listenMode get() = modeSwitch.mode == app.playdrive.play.AppMode.LISTEN
    private lateinit var demoBtn: View
    private lateinit var settingsBtn: View
    private lateinit var debugBtn: GlyphButton
    private var ux: UxRestrictionsWatcher? = null
    private val throttle = MoodThrottle()

    private var screen = Screen.MOOD
    private var overlay: View? = null          // 제안 카드
    private var page: View? = null             // ALBUM / RESULT / RECORDS / SETTINGS / DEBUG
    private var debugStatus: TextView? = null
    private var debugDetail: TextView? = null

    private var uxRestricted = false
    private var simMoving = false
    private val driving get() = uxRestricted || simMoving
    private var lastState = DrivingState()
    private var lastAlbum: Album? = null
    private var lastReport: ResultReport? = null
    private var lastRecordsTab = 0
    private var prepared = false
    private var pendingIntent: Intent? = null
    private var debugIcon = true
    private var demoIcon = true
    private var welcomeEndsAt = 0L
    private var dayOverride: AlbumNaming.PartOfDay? = null

    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startReal() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        val scale = getSharedPreferences("render", MODE_PRIVATE).getFloat("scale", DEFAULT_RENDER_SCALE)
        // 물결 악보(실제 스템에서 뽑은 킥·스네어·베이스 음·멜로디 — tools/make_wave_score.py)
        val score = runCatching { resources.openRawResource(R.raw.wave_score).use { app.playdrive.ui.WaveScore.parse(it) } }.getOrDefault(app.playdrive.ui.WaveScore.EMPTY)
        mood = MoodView(this, renderScale = scale, forceCanvas = getSharedPreferences("render", MODE_PRIVATE).getBoolean("canvas", false), score = score)
        root.addView(mood, FrameLayout.LayoutParams(MATCH, MATCH))
        play = PlayPauseButton(this).apply { setOnClickListener { controller.togglePlay() }; isEnabled = false; alpha = 0.4f; glassBehind = mood.usesShader }
        root.addView(play, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        // 드라이브 앨범 버튼: 음반 아이콘 + 글자(무슨 버튼인지 바로 보이게), 높이 76dp
        albumBtn = glassPill(getString(R.string.album_title), 'A') { showAlbum(null) }
        root.addView(albumBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(20), 0) })
        // 연주 기록(정차 중에만): 위 가운데
        // 연주 / 듣기: 위 가운데
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        modeSwitch = app.playdrive.play.ModeSwitch({ prefs.getString("mode", null) }) { prefs.edit().putString("mode", it).apply() }
        modeToggle = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0)
            background = GradientDrawable().apply { cornerRadius = dp(38).toFloat(); setColor(if (mood.usesShader) 0x0AFFFFFF else 0x22FFFFFF); setStroke(1, 0x55FFFFFF) }
        }
        root.addView(modeToggle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP + 8), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
        // 연주 기록(연주 모드) / 곡 목록(듣기 모드): 왼쪽 아래, 정차 중에만
        recordsBtn = glassPill(getString(R.string.records_title), null) { showRecords(0) }
        root.addView(recordsBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(20), 0, 0, dp(20)) })
        songsBtn = glassPill(getString(R.string.songs_title), null) { showSongs() }
        root.addView(songsBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(20), 0, 0, dp(20)) })
        // 데모 드라이브(릴리스에도 있음 — 실제 차량 신호 없이 심사·시연용으로 74초 주행을 재생). 정차 중에만 보인다.
        demoBtn = glassPill(getString(R.string.demo_drive), null) { if (prepared && !driving) { dismissOverlay(); showMood(); startDemo() } }.apply { isEnabled = false; alpha = 0.4f }
        root.addView(demoBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP), Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(20), 0, 0) })
        // 설정(화면 효과): 오른쪽 아래, 정차 중에만
        settingsBtn = GlyphButton(this, 'S', getString(R.string.settings_title)).apply { setOnClickListener { showSettings() } }
        root.addView(settingsBtn, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(20)) })
        mood.effect = loadEffect()
        val voiceOn = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("voice", true)
        // 개발용(디버그 빌드에서만 보임, 릴리스 빌드엔 없음)
        debugBtn = GlyphButton(this, 'D', "debug").apply { setOnClickListener { showDebug() }; visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE }
        root.addView(debugBtn, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(20), 0, 0, dp(20 + TOUCH_DP + 12)) })
        setContentView(root)
        // 창 비율이 바뀌면(내비 옆 1:1·2:1·1:2, 가로/세로 전환) 지금 화면을 새 비율로 다시 그린다. 음악·물결은 그대로 이어진다.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) root.post { onWindowShape(r - l, b - t) }
        }

        controller = DriveController(this, this)
        controller.voiceEnabled = voiceOn
        controller.prepare()
        controller.setListen(listenMode)
        refreshModeToggle()
        mood.soundSource = { controller.sound() }
        // 시간대 색온도: 지금 시각으로 시작, 1분마다 확인(바뀌면 정차 중 6초에 걸쳐)
        mood.setDayPart(currentDayPart(), immediate = true)
        root.postDelayed(object : Runnable { override fun run() { if (dayOverride == null) mood.setDayPart(currentDayPart()); root.postDelayed(this, 60_000) } }, 60_000)
        // 유리 패널 위치(버튼이 움직이거나 보이고 숨을 때마다)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGlass() }
        play.addOnLayoutChangeListener(relayout); albumBtn.addOnLayoutChangeListener(relayout); recordsBtn.addOnLayoutChangeListener(relayout); songsBtn.addOnLayoutChangeListener(relayout); modeToggle.addOnLayoutChangeListener(relayout); demoBtn.addOnLayoutChangeListener(relayout); settingsBtn.addOnLayoutChangeListener(relayout)
        // 웰컴: 검은 화면 → 3초 동안 빛이 피어남 → (음악 준비가 끝났으면) 첫 다운비트에 차임 + 음악 시작
        mood.welcome()
        welcomeEndsAt = SystemClock.uptimeMillis() + app.playdrive.ui.MoodScene.WELCOME_MS
        // 첫 화면이 실제로 그려지는 순간부터 3초를 센다(Compose 준비에 1~2초 걸려도 피어남을 다 보이게)
        mood.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                mood.post {
                    mood.welcome()
                    welcomeEndsAt = SystemClock.uptimeMillis() + app.playdrive.ui.MoodScene.WELCOME_MS
                    if (prepared) root.postDelayed({ controller.playWithChime() }, app.playdrive.ui.MoodScene.WELCOME_MS)
                    mood.viewTreeObserver.let { o -> if (o.isAlive) mood.post { o.removeOnDrawListener(this) } }
                }
            }
        })
        // 시작 화면: 가장 최근 앨범 커버를 은은하게 깔아 둔다(새 주행을 시작하면 새 커버로)
        ux = UxRestrictionsWatcher(this) { r -> runOnUiThread { uxRestricted = r; applyDrivingPolicy() } }
        pendingIntent = intent
        // 아이콘 숨김은 웰컴 첫 프레임부터(나머지 명령은 음악 준비가 끝난 뒤 onPrepared에서)
        debugCommands(intent).filter { it.startsWith("icon:") }.forEach { runDebugCommand(it) }
    }

    // ------------------------------------------------------------------ 주행 정책
    private fun applyDrivingPolicy() {
        mood.restricted = driving
        if (driving) { dismissOverlay(); if (!ScreenPolicy.allowedWhileDriving(screen)) showMood() }
        updateMoodIcons()
    }

    /** 무드 화면 위 아이콘: 주행 중·제안 카드가 떠 있을 때는 숨긴다. */
    private fun updateMoodIcons() {
        if (!::modeToggle.isInitialized) return
        val show = !driving && overlay == null && page == null
        play.visibility = if (page == null) View.VISIBLE else View.INVISIBLE   // 결과·기록 화면(반투명 바탕) 뒤로 비치지 않게
        albumBtn.visibility = if (show) View.VISIBLE else View.GONE
        recordsBtn.visibility = if (show && demoIcon && !listenMode) View.VISIBLE else View.GONE
        songsBtn.visibility = if (show && demoIcon && listenMode) View.VISIBLE else View.GONE
        modeToggle.visibility = if (show && demoIcon) View.VISIBLE else View.GONE
        modeToggle.isEnabled = !driving; for (i in 0 until modeToggle.childCount) modeToggle.getChildAt(i).isEnabled = !driving   // 주행 중 전환 불가
        demoBtn.visibility = if (show && demoIcon) View.VISIBLE else View.GONE
        settingsBtn.visibility = if (show && demoIcon) View.VISIBLE else View.GONE
        debugBtn.visibility = if (BuildConfig.DEBUG && show && debugIcon) View.VISIBLE else View.GONE
        updateGlass()
    }

    /** 셰이더가 버튼 뒤를 흐리게 그릴 자리(무드 뷰 기준 픽셀). */
    private fun updateGlass() {
        val list = mutableListOf<app.playdrive.ui.light.LightParams.Glass>()
        fun add(v: View, radius: Float) {
            if (v.visibility != View.VISIBLE || v.width == 0) return
            list += app.playdrive.ui.light.LightParams.Glass(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), radius)
        }
        add(play, play.width / 2f)
        add(albumBtn, albumBtn.height / 2f)
        add(recordsBtn, recordsBtn.height / 2f)
        add(songsBtn, songsBtn.height / 2f)
        add(modeToggle, modeToggle.height / 2f)
        add(demoBtn, demoBtn.height / 2f)
        add(settingsBtn, settingsBtn.height / 2f)
        overlay?.let { add(it, dp(28).toFloat()) }
        mood.glass = list
    }

    private fun currentDayPart() = AlbumNaming.partOfDay(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))

    // ------------------------------------------------------------------ DriveController.Listener
    override fun onPrepared(elapsedMs: Long) {
        prepared = true
        play.isEnabled = true; play.alpha = 1f
        demoBtn.isEnabled = true; demoBtn.alpha = 1f
        debugStatus?.text = "준비 완료 (음원 로드 ${elapsedMs}ms)"
        val pi = pendingIntent; pendingIntent = null
        if (pi != null && debugCommands(pi).isNotEmpty()) handleDebugIntent(pi) else requestSpeedThenStart()
        // 웰컴이 끝나는 순간(이미 끝났으면 지금) 차임과 함께 음악 시작
        root.postDelayed({ if (SystemClock.uptimeMillis() >= welcomeEndsAt - 50) controller.playWithChime() }, (welcomeEndsAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
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

    override fun onCover(cover: CoverSpec) {}  // 커버는 드라이브 앨범·결과 화면에서(주행 화면은 물결만)

    override fun onSourceFinished() { debugStatus?.text = "데모 드라이브 끝" }

    override fun onDriveFinished(album: Album, report: ResultReport?) {
        lastAlbum = album
        if (report != null) lastReport = report
        // 주차하면 바로 오늘의 연주(결과). 확인을 누르면 드라이브 앨범.
        if (driving) return
        dismissOverlay()
        if (report != null) { showResult(report, reveal = true) { if (!driving) showAlbum(album) }; root.postDelayed({ if (screen == Screen.RESULT) controller.announce(report) }, 2_000) } else showAlbum(album)   // 결과 음성은 등장 연출 2초에
    }

    // ------------------------------------------------------------------ 화면들
    private fun clearPage() { page?.let { root.removeView(it) }; page = null; debugStatus = null; debugDetail = null; updateMoodIcons() }

    private fun showMood() { clearPage(); screen = Screen.MOOD; if (overlay == null) mood.wake() }

    /** 제안 카드를 닫는다. 카드가 실제로 닫혔고 무드 화면이면 빛이 다시 깨어난다(굿바이 뒤). */
    private fun dismissOverlay() {
        val had = overlay != null
        overlay?.let { root.removeView(it) }; overlay = null; updateMoodIcons()
        if (had && screen == Screen.MOOD) mood.wake()
    }

    /** 드라이브 앨범: 정사각 커버 격자 + 커버 아래 제목 한 줄. highlight = 방금 생긴 앨범(첫 칸, 은은한 테두리). */
    private fun showAlbum(highlight: Album?, banner: String? = null) {
        if (driving) return
        Log.i(TAG, "장면: 드라이브 앨범")
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

    /** 앨범 상세: 큰 커버 + 제목 + 날짜(정차 중에만). */
    private fun showAlbumDetail(a: Album) {
        if (driving) return
        Log.i(TAG, "장면: 앨범 상세")
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

    /** 설정: 화면 효과 라이브/차분(정차 중에만 열림, 다음 출발부터 적용). */
    private fun showSettings() {
        if (driving) return
        clearPage(); screen = Screen.SETTINGS
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(PAD_DP), dp(20), dp(PAD_DP), dp(PAD_DP)); setBackgroundColor(0xFF0F151D.toInt()) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(getString(R.string.settings_title), TITLE_SP, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { showMood() } }, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        col.addView(head)
        val cur = mood.effect
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(32), 0, dp(12)) }
        row.addView(text(getString(R.string.settings_effect), BODY_SP), LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
        for ((level, label) in listOf(app.playdrive.ui.EffectLevel.LIVE to R.string.settings_effect_live, app.playdrive.ui.EffectLevel.CALM to R.string.settings_effect_calm)) {
            row.addView(button(getString(label), primary = level == cur) { saveEffect(level); showSettings() }.apply { contentDescription = "${getString(R.string.settings_effect)} ${getString(label)}" })
        }
        col.addView(row)
        val voiceOn = controller.voiceEnabled
        val vrow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        vrow.addView(text(getString(R.string.settings_voice), BODY_SP), LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
        for ((on, label) in listOf(true to R.string.settings_voice_on, false to R.string.settings_voice_off)) {
            vrow.addView(button(getString(label), primary = on == voiceOn) { saveVoice(on); showSettings() }.apply { contentDescription = "${getString(R.string.settings_voice)} ${getString(label)}" })
        }
        col.addView(vrow)
        page = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun saveVoice(on: Boolean) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("voice", on).apply()
        controller.voiceEnabled = on
    }

    // ------------------------------------------------------------------ 창 비율(Pleos 창 정책)
    private var lastNarrow: Boolean? = null

    /** 가로가 넓은 창 = 가로 레이아웃, 세로로 긴 창(가로:세로 < 1) = 세로 패널 레이아웃. */
    private fun onWindowShape(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val narrow = app.playdrive.ui.WindowShape.narrow(w, h)
        Log.i(TAG, "창 ${w}×$h → ${if (narrow) "세로" else "가로"} 레이아웃")
        // 세로 창: 위 줄이 좁으면 연주/듣기를 한 줄 아래로
        (modeToggle.layoutParams as FrameLayout.LayoutParams).let { it.topMargin = dp(if (app.playdrive.ui.WindowShape.stackTop(w, resources.displayMetrics.density)) 16 + TOUCH_DP + 12 else 16); modeToggle.layoutParams = it }
        updateGlass()
        if (lastNarrow == narrow && lastNarrow != null) { relayoutPage(); return }
        lastNarrow = narrow
        relayoutPage()
    }

    /** 정차 중 화면은 새 크기로 다시 만든다(결과·기록·앨범·곡 목록·설정). */
    private fun relayoutPage() {
        if (driving) return
        when (screen) {
            Screen.RESULT -> lastReport?.let { r -> showResult(r) { if (!driving) lastAlbum?.let { showAlbum(it) } ?: showMood() } }
            Screen.RECORDS -> showRecords(lastRecordsTab)
            Screen.ALBUM -> showAlbum(null)
            Screen.SONGS -> showSongs()
            Screen.SETTINGS -> showSettings()
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ 연주 / 듣기
    private fun refreshModeToggle() {
        modeToggle.removeAllViews()
        for ((m, label) in listOf(app.playdrive.play.AppMode.PLAY to R.string.mode_play, app.playdrive.play.AppMode.LISTEN to R.string.mode_listen)) {
            modeToggle.addView(button(getString(label), primary = m == modeSwitch.mode) { setMode(m) }.apply {
                contentDescription = getString(label); isEnabled = !driving
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(2), dp(4), dp(2), dp(4))
            })
        }
        updateMoodIcons()
    }

    /** 정차 중에만 바뀐다(주행 중엔 버튼도 막혀 있다). */
    private fun setMode(m: app.playdrive.play.AppMode) {
        if (!modeSwitch.request(m, driving)) return
        controller.setListen(m == app.playdrive.play.AppMode.LISTEN)
        Log.i(TAG, "모드 선택: $m")
        refreshModeToggle()
        if (screen == Screen.SONGS && m == app.playdrive.play.AppMode.PLAY) showMood()
    }

    /** 듣기 모드 곡 목록(정차 중에만): 팩별 곡 + 장르(해금된 것 / 잠긴 것은 연주 모드에서 흔들림 없이 달린 거리로 열림). */
    private fun showSongs() {
        if (driving) return
        clearPage(); screen = Screen.SONGS
        Log.i(TAG, "장면: 곡 목록")
        val profile = controller.play.load()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(PAD_DP), dp(20), dp(PAD_DP), 0); setBackgroundColor(0xFF0F151D.toInt()) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(getString(R.string.songs_title), TITLE_SP, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { showMood() } }, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        col.addView(head)
        col.addView(text(getString(R.string.songs_note), BODY_SP, color = 0xFF9AA8B4.toInt()).apply { setPadding(0, dp(4), 0, dp(8)); phraseBreak() })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(PAD_DP)) }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        fun row(main: String, sub: String, highlight: Boolean = false, locked: Boolean = false) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (highlight) 0x2A8FD3C6 else 0x14FFFFFF) }
            addView(text(main, BODY_SP, bold = true, color = if (locked) 0xFFB5BFC8.toInt() else Color.WHITE))
            addView(text(sub, BODY_SP, color = 0xFF9AA8B4.toInt()).apply { phraseBreak() })
            layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        for (pack in app.playdrive.music.MusicPacks.installed) {
            list.addView(text("${pack.name}  ·  ${pack.genre}", BODY_SP, bold = true, color = 0xFF8FD3C6.toInt()).apply { setPadding(0, dp(8), 0, dp(4)) })
            pack.songs.forEachIndexed { i, sng ->
                list.addView(row(sng.title, getString(R.string.songs_song_sub, sng.stems.size, sng.bpm) + if (i == 0) "  ·  " + getString(R.string.songs_now) else "", highlight = i == 0))
            }
        }
        list.addView(text(getString(R.string.songs_genres), BODY_SP, bold = true, color = 0xFF8FD3C6.toInt()).apply { setPadding(0, dp(20), 0, dp(4)) })
        list.addView(row("${app.playdrive.music.MusicPacks.BASIC.genre}  ·  ${getString(R.string.songs_unlocked)}", getString(R.string.songs_basic_genre)))
        for (g in app.playdrive.music.MusicPacks.lockedGenres) {
            val p = profile.progress(g)
            if (p >= 1f) list.addView(row("${g.title}  ·  ${getString(R.string.songs_unlocked)}", getString(R.string.songs_coming)))
            else {
                list.addView(row("${g.title}  ·  ${getString(R.string.songs_locked)}", getString(R.string.songs_locked_sub, g.requiredKm) + "  (%.1f / %d km)".format(profile.safeKm, g.requiredKm), locked = true))
                list.addView(ProgressBarView(p), LinearLayout.LayoutParams(MATCH, dp(14)).apply { setMargins(dp(24), 0, dp(24), dp(12)) })
            }
        }
        page = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------------ 연주 기록(정차 중에만)
    private fun gradeText(r: DriveResult) = r.best?.let { "${it.grade.label}등급 ${it.score}점" } ?: "완주한 곡 없음"

    /**
     * 오늘의 연주(정차 중에만). 정보는 커버·최고 등급·제목 / 숫자 카드 3개(완주 곡·최장 마디·흔들림) / 곡별 등급 칩 / 새 배지뿐.
     * 남색 바탕, 청록 포인트 한 색. 세로 패널(폭 < 높이)이면 위에서 아래로 쌓는다.
     */
    // ------------------------------------------------------------------ 결과·기록 화면(리듬게임 결과 화면 패턴: 축하 → 핵심 하나 크게 → 세부는 그림)
    private val RC = app.playdrive.ui.ResultColors

    /** 상태 문구: CLEARED(모두 완주) / RETRY CLEAR(실패 후 다시 완주) / FAILED(완주한 곡 없음). */
    private fun statusOf(r: DriveResult): Pair<String, Int> = when {
        r.completedSongs == 0 -> "FAILED" to RC.RED
        r.songs.any { !it.completed } -> "RETRY CLEAR" to RC.TEAL
        else -> "CLEARED" to RC.TEAL
    }

    /** 결과·기록 화면 바탕: 뒤의 물결이 비치는 남색 막(등급이 높을수록 물결이 더 보이게). */
    private fun scrim(g: Grade?) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        ((when (g) { Grade.S -> 0xA8; Grade.A -> 0xB8; Grade.B -> 0xC4; Grade.C -> 0xCC; else -> 0xD8 }) shl 24) or (RC.NAVY and 0xFFFFFF),
        0xF00B1430.toInt()))

    private fun cardBg(color: Int = RC.CARD, stroke: Int? = null) = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(color); stroke?.let { setStroke(dp(2), it) } }

    /** 결과 하나의 커버(앨범이 있으면 그 커버, 없으면 요약으로 만든 커버). */
    /** 연주 결과의 앨범(결과 시작 시각과 앨범 ID는 다른 시계에서 와서 몇 초 어긋난다 → 10분 안에서 가장 가까운 것). */
    private fun albumOf(r: DriveResult) = controller.albums.all().filter { kotlin.math.abs(it.id - r.startEpochMs) < 600_000 }.minByOrNull { kotlin.math.abs(it.id - r.startEpochMs) }

    private fun coverOf(r: DriveResult): CoverSpec {
        albumOf(r)?.let { return AlbumNaming.cover(it) }
        return app.playdrive.album.CoverArt.spec(r.startEpochMs, app.playdrive.album.CoverArt.layersFor(r.startEpochMs, r.stops, (1f - r.misses / 10f).coerceIn(0.2f, 0.95f), r.movingSeconds, if (r.rained) 0.5f else 0f, r.misses.coerceAtMost(3)))
    }

    private fun titleOf(r: DriveResult) = albumOf(r)?.title ?: AlbumNaming.dateLine(r.startEpochMs)

    /** "목 · 밤" */
    private fun dayLabel(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val d = listOf("일", "월", "화", "수", "목", "금", "토")[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        return "$d · ${AlbumNaming.partOfDay(c.get(java.util.Calendar.HOUR_OF_DAY)).word}"
    }

    /** 커버 카드: 물결 커버 + 왼쪽 아래 큰 등급(완주 곡이 없으면 붉은 테두리 + FAILED). */
    private fun coverCard(spec: CoverSpec?, grade: Grade?, sizePx: Int, gradeSp: Float, failed: Boolean = false): FrameLayout = FrameLayout(this).apply {
        clipToOutline = true; outlineProvider = roundedOutline(dp(20))
        addView(CoverView(this@MainActivity).apply { this.spec = spec }, FrameLayout.LayoutParams(MATCH, MATCH))
        val label = if (failed || grade == null || grade == Grade.FAILED) "FAILED" else grade.label
        addView(text(label, if (label == "FAILED") gradeSp * 0.4f else gradeSp, bold = true, color = if (label == "FAILED") RC.RED else Color.WHITE).apply {
            includeFontPadding = false; setShadowLayer(dp(16).toFloat(), 0f, 0f, 0xAA000000.toInt()); setPadding(dp(18), 0, 0, dp(10))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        if (failed) addView(View(this@MainActivity).apply { background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setStroke(dp(3), RC.RED) } }, FrameLayout.LayoutParams(MATCH, MATCH))
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
    }

    private fun pill(label: String, color: Int = RC.TEXT, bg: Int = RC.NAVY, stroke: Int? = null) = text(label, BODY_SP, color = color).apply {
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(bg); stroke?.let { setStroke(dp(2), it) } }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), dp(12), dp(6)) }
    }

    private fun countUp(tv: TextView, to: Int, delay: Long, fmt: (Int) -> String) {
        tv.text = fmt(0)
        android.animation.ValueAnimator.ofInt(0, to).apply {
            duration = 700; startDelay = delay; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { tv.text = fmt(it.animatedValue as Int) }
            start()
        }
    }

    /**
     * 오늘의 연주. reveal = 등장 연출(2.5초 안, 화면을 누르면 바로 끝 모습):
     * 0.0초 화면 정가운데 상태 문구 크게(커버·카드는 아직 숨김) → 0.8초 왼쪽 위 머리 자리로 / 0.9초 커버, 숫자 카드 3개 차례로 카운트업 / 1.7초 등급이 커버 위에 도장 찍듯(S = 물결이 한 번 밝게) / 2.0초 결과 음성.
     */
    private fun showResult(report: ResultReport, reveal: Boolean = false, onDone: () -> Unit) {
        if (driving) return
        val r = report.result
        Log.i(TAG, "장면: 오늘의 연주 완주 ${r.completedSongs}/${r.attemptedSongs} 최고 ${r.bestGrade}")
        clearPage(); screen = Screen.RESULT
        val narrow = root.width in 1 until root.height
        val album = lastAlbum ?: controller.albums.all().firstOrNull()
        val best = r.best
        val (status, statusColor) = statusOf(r)
        val frame = FrameLayout(this).apply { background = scrim(best?.grade); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (narrow) 32 else 48), dp(24), dp(if (narrow) 32 else 48), dp(24)) }
        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // 머리: 정리된 상태 문구 + 제목
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val statusSmall = text(status, 44f, bold = true, color = statusColor).apply { letterSpacing = 0.12f; includeFontPadding = false }
        head.addView(statusSmall)
        head.addView(text(getString(R.string.result_title), TITLE_SP, color = RC.SUB).apply { setPadding(dp(24), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)

        // 커버 + 등급(가장 크게) + 곡 점
        val coverBox = FrameLayout(this).apply { clipToOutline = true; outlineProvider = roundedOutline(dp(20)) }
        coverBox.addView(CoverView(this).apply { spec = album?.let { AlbumNaming.cover(it) } }, FrameLayout.LayoutParams(MATCH, MATCH))
        val gradeView = text(best?.grade?.label ?: "–", if (narrow) 132f else 176f, bold = true, color = Color.WHITE).apply {
            includeFontPadding = false; setShadowLayer(dp(22).toFloat(), 0f, 0f, 0xAA000000.toInt()); setPadding(dp(28), 0, 0, dp(12))
        }
        coverBox.addView(gradeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val scoreView = text(best?.let { "${it.score}" } ?: "", if (narrow) 56f else 72f, bold = true, color = Color.WHITE).apply {
            includeFontPadding = false; setShadowLayer(dp(16).toFloat(), 0f, 0f, 0xAA000000.toInt()); setPadding(0, 0, dp(24), dp(22))
        }
        coverBox.addView(scoreView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        val title = text(album?.title ?: "", BODY_SP, color = RC.SUB).apply { setPadding(dp(4), dp(12), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; if (narrow) gravity = Gravity.CENTER_HORIZONTAL }
        left.addView(coverBox); left.addView(title)

        // 숫자 카드 3개: 완주 곡 / 최장 마디 / MISS(3번 이상만 붉게) — 오른쪽 폭을 꽉 채우게 크게
        val numbers = listOf(Triple(r.completedSongs, "/${r.attemptedSongs}", "완주 곡"), Triple(r.maxCombo, "", "최장 마디"), Triple(r.misses, "", "MISS"))
        val cardViews = mutableListOf<View>(); val numberViews = mutableListOf<TextView>()
        val cards = LinearLayout(this)
        numbers.forEachIndexed { i, (n, suffix, label) ->
            val num = text("$n$suffix", if (narrow) 64f else 96f, bold = true, color = if (label == "MISS" && n >= 3) RC.RED else Color.WHITE).apply { includeFontPadding = false }
            numberViews += num
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (narrow) 20 else 32), dp(if (narrow) 24 else 40), dp(16), dp(if (narrow) 24 else 40)); background = cardBg()
                addView(num); addView(text(label, BODY_SP, color = RC.SUB).apply { setPadding(0, dp(10), 0, 0) })
            }
            cardViews += card
            cards.addView(card, LinearLayout.LayoutParams(0, if (narrow) ViewGroup.LayoutParams.WRAP_CONTENT else MATCH, 1f).apply { if (i > 0) leftMargin = dp(16) })
        }

        // 곡별 결과: 가로 카드(곡 번호 · 큰 등급 · 그 곡 점). FAILED = 붉은 테두리 + FAILED, 다시 도전 = RETRY
        val chips = LinearLayout(this)
        val songW = if (r.songs.size > 3) dp(if (narrow) 200 else 260) else 0
        r.songs.forEachIndexed { i, sng ->
            val failed = sng.grade == Grade.FAILED
            chips.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), dp(18), dp(20), dp(18))
                background = cardBg(RC.CARD, if (failed) RC.RED else null)
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text("${sng.number}곡", BODY_SP, color = RC.SUB))
                    if (sng.attempt > 1) addView(text("RETRY", BODY_SP, bold = true, color = RC.TEAL).apply { setPadding(dp(12), 0, 0, 0) })
                })
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.BOTTOM; setPadding(0, dp(6), 0, 0)
                    addView(text(if (failed) "FAILED" else sng.grade.label, if (failed) 48f else (if (narrow) 64f else 112f), bold = true, color = if (failed) RC.RED else Color.WHITE).apply { includeFontPadding = false })
                    if (!failed) addView(text("${sng.score}점", TITLE_SP, bold = true, color = RC.SUB).apply { setPadding(dp(14), 0, 0, dp(8)) })
                })
            }, LinearLayout.LayoutParams(songW, if (narrow) ViewGroup.LayoutParams.WRAP_CONTENT else MATCH, if (songW == 0) 1f else 0f).apply { if (i > 0) leftMargin = dp(16) })
        }
        if (r.songs.isEmpty()) chips.addView(text("곡을 끝까지 연주하기 전에 도착했어요", BODY_SP, color = RC.SUB))

        // 새 배지: 메달(기록 탭과 같은 모양) + "새 배지" + 이름, 카드 하나
        val badges = LinearLayout(this).apply { visibility = if (report.newBadges.isEmpty()) View.GONE else View.VISIBLE }
        report.newBadges.forEachIndexed { i, b ->
            badges.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(16), dp(32), dp(16)); background = cardBg()
                addView(app.playdrive.ui.MedalView(this@MainActivity, b.glyph, true), LinearLayout.LayoutParams(dp(96), dp(96)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, 0, 0)
                    addView(text("새 배지", BODY_SP, bold = true, color = RC.TEAL)); addView(text(b.title, TITLE_SP, bold = true, color = Color.WHITE))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(16) })
        }

        val buttons = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        buttons.addView(button(getString(R.string.result_detail), primary = false) { showResultDetail(frame, r) })
        buttons.addView(button(getString(R.string.records_title), primary = false) { showRecords(0) })
        buttons.addView(button(getString(R.string.result_ok), primary = true) { onDone() })
        val demoNote = text(getString(R.string.result_demo_zone_note), BODY_SP, color = 0xFF6F7F9C.toInt()).apply { visibility = if (r.demoZoneData) View.VISIBLE else View.GONE; maxLines = 2; phraseBreak() }

        if (narrow) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, dp(12)) }
            body.addView(left, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(cards, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
            body.addView(if (r.songs.size > 3) HorizontalScrollViewCompat(chips) else chips, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            body.addView(badges, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            col.addView(demoNote, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            col.addView(buttons, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val body = LinearLayout(this).apply { setPadding(0, dp(24), 0, 0); isBaselineAligned = false }
            body.addView(left, LinearLayout.LayoutParams(0, MATCH, 0.45f))
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(44), 0, 0, 0) }
            // 오른쪽 폭·높이를 꽉: 숫자 카드와 곡 카드가 남은 높이를 나눠 갖는다
            right.addView(cards, LinearLayout.LayoutParams(MATCH, 0, 1.15f))
            right.addView(if (r.songs.size > 3) HorizontalScrollViewCompat(chips) else chips, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(20) })
            right.addView(badges, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
            right.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(20)))
            right.addView(demoNote, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            right.addView(buttons, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(right, LinearLayout.LayoutParams(0, MATCH, 0.55f))
            col.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        page = frame
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        updateMoodIcons()
        coverBox.post {
            val w = if (narrow) (col.width * 0.72f).toInt() else left.width
            val maxH = if (narrow) (col.height * 0.36f).toInt() else left.height - title.height - dp(8)
            val side = minOf(w, maxH).coerceAtLeast(dp(160))
            coverBox.layoutParams = LinearLayout.LayoutParams(side, side)
        }
        if (!reveal) return

        // ---- 등장 연출
        val big = text(status, if (narrow) 96f else 150f, bold = true, color = statusColor).apply { letterSpacing = 0.1f; includeFontPadding = false; alpha = 0f; scaleX = 1.25f; scaleY = 1.25f }
        frame.addView(big, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val fadeIns = listOf(head, chips, badges, buttons, demoNote, title)
        statusSmall.alpha = 0f
        fadeIns.forEach { if (it !== head) it.alpha = 0f }
        head.alpha = 1f
        cardViews.forEach { it.alpha = 0f; it.translationY = dp(24).toFloat() }
        gradeView.alpha = 0f; scoreView.alpha = 0f
        coverBox.alpha = 0f   // 상태 문구가 정가운데에 뜨는 동안 커버·카드와 겹치지 않게
        val steps = mutableListOf<Runnable>()
        var done = false
        fun at(ms: Long, block: () -> Unit) { val rn = Runnable { if (!done) block() }; steps += rn; frame.postDelayed(rn, ms) }
        fun finish() {
            if (done) return
            done = true; steps.forEach { frame.removeCallbacks(it) }
            frame.removeView(big)
            (listOf<View>(statusSmall, gradeView, scoreView, coverBox) + fadeIns + cardViews).forEach { it.animate().cancel(); it.alpha = 1f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f }
            numbers.forEachIndexed { i, (n, suffix, _) -> numberViews[i].text = "$n$suffix" }
            frame.setOnClickListener(null)
        }
        frame.setOnClickListener { finish() }
        big.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start()
        at(800) {
            // 위로 작아지며 머리의 상태 문구 자리로
            val a = IntArray(2); val b = IntArray(2); big.getLocationInWindow(a); statusSmall.getLocationInWindow(b)
            val k = statusSmall.height.toFloat() / big.height.coerceAtLeast(1)
            big.pivotX = 0f; big.pivotY = 0f
            big.animate().translationX((b[0] - a[0]).toFloat()).translationY((b[1] - a[1]).toFloat()).scaleX(k).scaleY(k).setDuration(400)
                .withEndAction { if (!done) { statusSmall.alpha = 1f; frame.removeView(big) } }.start()
        }
        at(900) { coverBox.animate().alpha(1f).setDuration(300).start() }
        cardViews.forEachIndexed { i, v ->
            at(900L + i * 150) {
                v.animate().alpha(1f).translationY(0f).setDuration(250).start()
                val (n, suffix, _) = numbers[i]
                countUp(numberViews[i], n, 0) { "$it$suffix" }
            }
        }
        at(1300) { title.animate().alpha(1f).setDuration(300).start(); chips.animate().alpha(1f).setDuration(300).start() }
        at(1700) {
            // 등급: 도장 찍듯(살짝 크게 → 제자리)
            gradeView.scaleX = 1.8f; gradeView.scaleY = 1.8f; gradeView.pivotX = 0f; gradeView.pivotY = gradeView.height.toFloat()
            gradeView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            scoreView.animate().alpha(1f).setStartDelay(150).setDuration(250).start()
            if (best?.grade == Grade.S) mood.celebrate(1f) else if (best?.grade == Grade.A) mood.celebrate(0.5f)
        }
        at(2000) { listOf(badges, buttons, demoNote).forEach { it.animate().alpha(1f).setDuration(300).start() } }
        at(2500) { finish() }
    }

    /** 자세히: PERFECT(종류별)·MISS와 MISS의 뜻(처음 보는 사람을 위해). */
    private fun showResultDetail(frame: FrameLayout, r: DriveResult) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(32), dp(40), dp(32)); background = cardBg(RC.CARD_DEEP, 0x553FD0C0)
            elevation = dp(8).toFloat(); isClickable = true
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(getString(R.string.result_detail), TITLE_SP, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val scrimView = View(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true }
        head.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { frame.removeView(panel); frame.removeView(scrimView) } }, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        panel.addView(head)
        fun big(n: Int, label: String, color: Int) = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM; setPadding(0, dp(16), 0, dp(4))
            addView(text("$n", 72f, bold = true, color = color).apply { includeFontPadding = false })
            addView(text(label, TITLE_SP, bold = true, color = color).apply { setPadding(dp(16), 0, 0, dp(8)) })
        }
        panel.addView(big(r.perfectTotal, "PERFECT", RC.TEAL))
        val kinds = LinearLayout(this)
        app.playdrive.play.PerfectKind.entries.forEach { k -> val n = r.perfects[k] ?: 0; if (n > 0) kinds.addView(pill("${k.label}  $n", bg = RC.CARD)) }
        panel.addView(kinds)
        panel.addView(big(r.misses, "MISS", if (r.misses > 0) RC.RED else Color.WHITE))
        panel.addView(text(getString(R.string.result_miss_meaning), BODY_SP, color = RC.SUB).apply { phraseBreak() })
        frame.addView(scrimView, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(panel, FrameLayout.LayoutParams(minOf(dp(900), root.width - dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** 연주 기록: 탭 0 내 기록, 1 비슷한 주행(VS), 2 친구(예시, 단상), 3 배지(메달)·음악 해금. */
    private fun showRecords(tab: Int) {
        if (driving) return
        clearPage(); screen = Screen.RECORDS; lastRecordsTab = tab
        Log.i(TAG, "장면: 연주 기록 탭 $tab")
        val profile = controller.play.load()
        val narrow = root.width in 1 until root.height
        val frame = FrameLayout(this).apply { background = scrim(null); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (narrow) 28 else 44), dp(20), dp(if (narrow) 28 else 44), 0) }
        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(getString(R.string.records_title), TITLE_SP, bold = true).apply { setPadding(0, 0, dp(24), 0) })
        val tabRow = LinearLayout(this)
        listOf(R.string.records_tab_mine, R.string.records_tab_similar, R.string.records_tab_friends, R.string.records_tab_badges).forEachIndexed { i, id -> tabRow.addView(button(getString(id), primary = i == tab) { showRecords(i) }) }
        head.addView(HorizontalScrollViewCompat(tabRow), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { showMood() } }, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        col.addView(head)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, dp(PAD_DP)) }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        fun section(s: String) = text(s, TITLE_SP, bold = true).apply { setPadding(0, dp(28), 0, dp(14)) }
        fun note(s: String) = text(s, BODY_SP, color = RC.SUB).apply { phraseBreak() }
        val results = profile.results
        val latest = results.maxByOrNull { it.startEpochMs }
        when (tab) {
            0 -> {
                if (results.isEmpty()) { list.addView(note(getString(R.string.records_empty))); }
                else {
                    val now = System.currentTimeMillis()
                    val weekStart = java.util.Calendar.getInstance().apply { timeInMillis = now; firstDayOfWeek = java.util.Calendar.MONDAY; set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
                    val hero = results.filter { it.startEpochMs >= weekStart && it.best != null }.maxByOrNull { it.bestScore } ?: results.filter { it.best != null }.maxByOrNull { it.bestScore } ?: latest!!
                    val card = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(24), dp(32), dp(24)); background = cardBg(); orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
                    card.addView(coverCard(coverOf(hero), hero.bestGrade, dp(if (narrow) 300 else 280), 104f, hero.completedSongs == 0))
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(if (narrow) 0 else dp(36), if (narrow) dp(20) else 0, 0, 0) }
                    info.addView(text("이번 주 최고 기록", BODY_SP, bold = true, color = RC.TEAL))
                    info.addView(LinearLayout(this).apply {
                        gravity = Gravity.BOTTOM
                        addView(text("${hero.bestScore}", 96f, bold = true).apply { includeFontPadding = false })
                        addView(text("점", TITLE_SP, color = RC.SUB).apply { setPadding(dp(8), 0, 0, dp(12)) })
                    })
                    info.addView(text(titleOf(hero), TITLE_SP, color = Color.WHITE).apply { setPadding(0, dp(4), 0, dp(14)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    val chipRow = android.widget.LinearLayout(this)
                    chipRow.addView(pill("완주 ${hero.completedSongs}/${hero.attemptedSongs}곡")); chipRow.addView(pill("최장 ${hero.maxCombo}마디")); chipRow.addView(pill("MISS ${hero.misses}"))
                    profile.finishStreakDays(latest!!.startEpochMs).takeIf { it >= 2 }?.let { chipRow.addView(pill("연속 ${it}일 완주", color = RC.TEAL_DARK, bg = RC.TEAL)) }
                    info.addView(HorizontalScrollViewCompat(chipRow))
                    card.addView(info, LinearLayout.LayoutParams(if (narrow) MATCH else 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    list.addView(card)
                    list.addView(section("최근 연주"))
                    val row = LinearLayout(this)
                    results.sortedByDescending { it.startEpochMs }.take(12).forEach { res ->
                        row.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(20), 0)
                            addView(coverCard(coverOf(res), res.bestGrade, dp(210), 80f, res.completedSongs == 0))
                            addView(text(dayLabel(res.startEpochMs), BODY_SP, color = RC.SUB).apply { setPadding(dp(4), dp(10), 0, 0) })
                        })
                    }
                    list.addView(HorizontalScrollViewCompat(row))
                }
            }
            1 -> {
                // 비슷한 주행이 있는 가장 최근 주행(가장 최근 주행이 비교할 상대가 없으면 그 전 것)
                val base = results.sortedByDescending { it.startEpochMs }.firstOrNull { profile.similarTo(it).isNotEmpty() }
                val past = base?.let { profile.similarTo(it) }?.maxByOrNull { it.startEpochMs }
                val today = base?.let { AlbumNaming.dateLine(it.startEpochMs).substringBefore(" ·") == AlbumNaming.dateLine(System.currentTimeMillis()).substringBefore(" ·") } ?: false
                if (base == null || past == null) list.addView(note(getString(R.string.records_similar_none)))
                else {
                    fun side(res: DriveResult, label: String) = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        addView(text(label, BODY_SP, bold = true, color = RC.SUB).apply { setPadding(0, 0, 0, dp(12)); gravity = Gravity.CENTER })
                        addView(coverCard(coverOf(res), res.bestGrade, dp(if (narrow) 220 else 300), if (narrow) 80f else 104f, res.completedSongs == 0))
                        addView(LinearLayout(this@MainActivity).apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setPadding(0, dp(14), 0, 0)
                            addView(text("${res.misses}", 64f, bold = true, color = if (res.misses > 0) RC.RED else Color.WHITE).apply { includeFontPadding = false })
                            addView(text("MISS", TITLE_SP, bold = true, color = RC.SUB).apply { setPadding(dp(10), 0, 0, dp(6)) })
                        })
                    }
                    val vs = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(28), dp(24), dp(28)); background = cardBg() }
                    vs.addView(side(base, if (today) "오늘" else dayLabel(base.startEpochMs)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    vs.addView(text("VS", if (narrow) 64f else 96f, bold = true, color = RC.TEAL).apply { includeFontPadding = false; setPadding(dp(16), 0, dp(16), 0) })
                    vs.addView(side(past, "지난 ${dayLabel(past.startEpochMs)}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    list.addView(vs)
                    val d = past.misses - base.misses
                    val line = when { d > 0 -> "지난번보다 MISS ${d}번 줄었어요"; d < 0 -> "지난번보다 MISS ${-d}번 늘었어요"; else -> "지난번과 MISS가 같아요" }
                    list.addView(text(line, 48f, bold = true, color = if (d >= 0) Color.WHITE else RC.RED).apply { setPadding(0, dp(28), 0, dp(8)); gravity = Gravity.CENTER; phraseBreak() }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
                    profile.copy(results = results - base).compare(base)?.let { list.addView(note(it).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)) }
                    list.addView(note(getString(R.string.records_similar_basis)).apply { setPadding(0, dp(28), 0, 0); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            2 -> {
                list.addView(note(getString(R.string.records_friends_note)))
                val mine = results.filter { it.best != null }.maxByOrNull { it.bestScore }
                data class P(val name: String, val initial: String, val grade: Grade, val score: Int, val me: Boolean)
                val people = (FriendExamples.rows.map { P(it.name, it.name.last().toString(), it.grade, it.score, false) } +
                    listOfNotNull(mine?.let { P("나", "나", it.bestGrade!!, it.bestScore, true) })).sortedByDescending { it.score }
                fun avatar(p: P, size: Int) = text(p.initial, size * 0.42f / resources.displayMetrics.density, bold = true, color = if (p.me) RC.TEAL_DARK else Color.WHITE).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (p.me) RC.TEAL else RC.CARD); setStroke(dp(3), if (p.me) 0xFFBDF4EC.toInt() else 0xFF3A4870.toInt()) }
                    layoutParams = LinearLayout.LayoutParams(size, size)
                }
                // 단상: 2위 · 1위(가장 높게) · 3위
                val podium = LinearLayout(this).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setPadding(0, dp(24), 0, 0) }
                listOf(1 to 170, 0 to 240, 2 to 120).forEach { (rank, h) ->
                    val p = people.getOrNull(rank) ?: return@forEach
                    podium.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        addView(avatar(p, dp(if (rank == 0) 112 else 92)))
                        addView(text(p.name, BODY_SP, color = if (p.me) RC.TEAL else RC.SUB).apply { setPadding(0, dp(10), 0, 0); gravity = Gravity.CENTER })
                        addView(LinearLayout(this@MainActivity).apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            addView(text(p.grade.label, if (rank == 0) 80f else 64f, bold = true).apply { includeFontPadding = false })
                            addView(text("${p.score}", TITLE_SP, bold = true, color = RC.SUB).apply { setPadding(dp(10), 0, 0, dp(6)) })
                        })
                        addView(text("${rank + 1}", 56f, bold = true, color = if (rank == 0) RC.TEAL else RC.SUB).apply {
                            gravity = Gravity.CENTER; includeFontPadding = false
                            background = GradientDrawable().apply { cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f); setColor(if (p.me) 0xFF1E4A5C.toInt() else RC.CARD) }
                        }, LinearLayout.LayoutParams(dp(if (narrow) 190 else 260), dp(h)).apply { topMargin = dp(14) })
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, dp(6), 0) })
                }
                list.addView(podium, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
                people.drop(3).forEachIndexed { i, p ->
                    list.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(10), dp(28), dp(10))
                        if (p.me) background = cardBg(0x221E4A5C, RC.TEAL)
                        addView(text("${i + 4}", TITLE_SP, bold = true, color = RC.SUB).apply { minWidth = dp(56) })
                        addView(avatar(p, dp(60)))
                        addView(text(p.name, BODY_SP, color = if (p.me) RC.TEAL else Color.WHITE).apply { setPadding(dp(18), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(text(p.grade.label, 44f, bold = true).apply { includeFontPadding = false })
                        addView(text("${p.score}", TITLE_SP, color = RC.SUB).apply { setPadding(dp(18), 0, 0, 0) })
                    }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (i == 0) 20 else 8) })
                    if (!p.me) list.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(MATCH, 1))
                }
            }
            else -> {
                val now = System.currentTimeMillis()
                val cols = if (narrow) 2 else 3
                Badge.entries.chunked(cols).forEach { rowBadges ->
                    list.addView(LinearLayout(this).apply {
                        rowBadges.forEach { b ->
                            val earnedAt = profile.badges[b]
                            val (cur, goal) = profile.badgeProgress(b, now)
                            addView(LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), dp(16), dp(12), dp(16))
                                addView(app.playdrive.ui.MedalView(this@MainActivity, b.glyph, earnedAt != null), LinearLayout.LayoutParams(dp(132), dp(132)))
                                addView(text(b.title, BODY_SP, bold = true, color = if (earnedAt != null) Color.WHITE else RC.SUB).apply { setPadding(0, dp(14), 0, dp(4)); gravity = Gravity.CENTER })
                                if (earnedAt != null) addView(text(AlbumNaming.dateLine(earnedAt).substringBefore(" ·").substringBeforeLast(" "), BODY_SP, color = RC.TEAL).apply { gravity = Gravity.CENTER })
                                else {
                                    addView(ProgressBarView(cur.toFloat() / goal), LinearLayout.LayoutParams(dp(200), dp(12)).apply { topMargin = dp(6) })
                                    addView(text(when (b) { Badge.STOPS_50 -> "PERFECT 정지 $cur/$goal"; Badge.COMBO_100 -> "최장 $cur/${goal}마디"; Badge.STREAK_7 -> "$cur/${goal}일"; else -> "$cur/$goal" }, BODY_SP, color = RC.SUB).apply { setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
                                }
                            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        }
                        repeat(cols - rowBadges.size) { addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f)) }
                    }, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                list.addView(section("음악 해금"))
                list.addView(note("연주 모드에서 MISS 없이 달린 거리로 열려요").apply { setPadding(0, 0, 0, dp(14)) })
                val genres = LinearLayout(this).apply { orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
                GenrePack.entries.forEachIndexed { i, g ->
                    val p = profile.progress(g); val open = p >= 1f
                    genres.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(28), dp(32), dp(28)); background = cardBg()
                        addView(LinearLayout(this@MainActivity).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(app.playdrive.ui.LockView(this@MainActivity, open), LinearLayout.LayoutParams(dp(56), dp(56)))
                            addView(text(g.title, 48f, bold = true).apply { setPadding(dp(18), 0, 0, 0); includeFontPadding = false })
                        })
                        addView(ProgressBarView(p), LinearLayout.LayoutParams(MATCH, dp(14)).apply { topMargin = dp(24) })
                        addView(text("%.0f / %d km".format(profile.safeKm, g.requiredKm), TITLE_SP, bold = true, color = RC.SUB).apply { setPadding(0, dp(12), 0, 0) })
                        if (open) addView(button("듣기 모드에서 듣기", primary = true) { setMode(app.playdrive.play.AppMode.LISTEN); showSongs() })
                    }, LinearLayout.LayoutParams(if (narrow) MATCH else 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) { if (narrow) topMargin = dp(16) else leftMargin = dp(20) } })
                }
                list.addView(genres, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        page = frame
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        updateMoodIcons()
    }

    @Suppress("FunctionName")
    private fun HorizontalScrollViewCompat(child: View) = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(child) }

    /** 해금 진행도 막대(정차 중 화면, 움직임 없음). */
    @Suppress("FunctionName")
    private fun ProgressBarView(p: Float) = object : View(this) {
        val bg = android.graphics.Paint().apply { color = 0x22FFFFFF }
        val fg = android.graphics.Paint().apply { color = 0xFF8FD3C6.toInt() }
        override fun onDraw(c: android.graphics.Canvas) {
            val r = height / 2f
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)
            if (p > 0f) c.drawRoundRect(0f, 0f, (width * p).coerceAtLeast(height.toFloat()), height.toFloat(), r, r, fg)
        }
    }

    /**
     * 예시 연주 기록(seed:albums의 6장과 같은 날짜): 비 오는 밤 A(빗길·밤길 배지), 나머지 B·C·A, S는 없음.
     * → demo:safe를 마치면 '첫 S등급' 배지 하나와 '이번 주 최고 기록'이 나온다. 기기 안에만.
     */
    private fun seedPlay() {
        controller.play.clear()
        var profile = app.playdrive.play.PlayProfile()
        // (곡의 점, 완주 여부) — S는 없음
        val plans = listOf(
            listOf(84 to true, 72 to true), listOf(79 to true), listOf(61 to false, 74 to true),
            listOf(45 to false), listOf(77 to true, 58 to true), listOf(88 to true, 81 to true),
        )
        controller.albums.all().sortedBy { it.summary.startEpochMs }.forEachIndexed { i, a ->
            val s = a.summary
            val plan = plans[i % plans.size]
            var n = 1
            val songs = plan.mapIndexed { k, (score, done) ->
                val song = app.playdrive.play.SongResult(n, if (k > 0 && !plan[k - 1].second) 2 else 1, done, score, app.playdrive.play.PlayRules.grade(score, done), score / 100f, 20 + 7 * k, 1 + k)
                if (done) n++
                song
            }
            val perf = mapOf(PerfectKind.START to (i % 2), PerfectKind.CHANGE to (if (i % 3 == 0) 1 else 0), PerfectKind.STOP to (1 + i % 3))
            val r = DriveResult(s.startEpochMs, s.endEpochMs, songs, listOf(18, 31, 12, 9, 22, 40)[i % 6], perf, listOf(2, 1, 3, 5, 1, 2)[i % 6],
                s.rained, s.movingSeconds * 9f, s.movingSeconds, 1 + i % 4)
            profile = profile.add(r).first
        }
        controller.play.save(profile)
        Log.i(TAG, "예시 연주 기록 ${profile.results.size}개, 배지 ${profile.badges.keys}")
    }

    private fun loadEffect() = runCatching { app.playdrive.ui.EffectLevel.valueOf(getSharedPreferences("settings", MODE_PRIVATE).getString("effect", null) ?: "LIVE") }.getOrDefault(app.playdrive.ui.EffectLevel.LIVE)

    private fun saveEffect(level: app.playdrive.ui.EffectLevel) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("effect", level.name).apply()
        mood.effect = level
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
    private fun startDemo(scenario: DriveScenario = app.playdrive.sim.DemoDriveScenario.scenario) {

        controller.start(DemoDriveSource(controller.engineHandler, scenario) { controller.notifyFinished() })
        debugStatus?.text = "${scenario.name} 재생 중"
    }

    private fun requestSpeedThenStart() {
        if (checkSelfPermission(PERM_SPEED) == PackageManager.PERMISSION_GRANTED) startReal() else speedPermission.launch(PERM_SPEED)
    }

    /** 실제 신호원은 음악을 바로 틀지 않는다(웰컴 끝에 차임과 함께). */
    private fun startReal() { controller.start(PleosVehicleSignalSource(this), autoPlay = SystemClock.uptimeMillis() >= welcomeEndsAt) }

    // ------------------------------------------------------------------ 디버그 인텐트
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (prepared) handleDebugIntent(intent) else pendingIntent = intent
    }

    /**
     * 디버그 빌드 전용(테스트·스크린샷·영상). 여러 명령은 ';'로 이어서. 릴리스 빌드는 [RELEASE_CMDS](데모 드라이브·예시 앨범·아이콘 숨김)만 받는다:
     *  --es debug demo | real | stop
     *  --es debug screen:mood | screen:drive | screen:album | screen:detail[:n] | screen:debug
     *  --es debug seed:albums   (기존 앨범을 모두 지우고 예시 앨범 6장 생성, 기기 안에만)
     *  --es debug demo:safe | demo:rough   (연주 기록 시연: 64마디 클라이맥스·S등급 / 흔들림 반복·완주 못함)
     *  --es debug seed:play | reset:play   (예시 연주 기록 — seed:albums 다음에 / 모두 지움), voice:on|off, screen:result, screen:records[:0~3]
     *  --es debug icon:debug:off|on, icon:demo:off|on   (무드 화면의 디버그·데모·설정 버튼 숨김 — 스크린샷·영상용)
     *  --es debug effect:live|calm   (주행 중 화면 효과, 설정과 같음) / screen:settings
     *  --es debug welcome | goodbye | wake | daypart:MORNING|DAY|EVENING|NIGHT|DAWN|auto | rain:on|off | render:1.0|0.5|canvas
     *  --es debug record:start|stop (= rec:audio:on|off)   앱 최종 믹스를 files/demo_audio.wav(32bit float 48kHz)로 — 시연 영상용
     *  --es debug layers:PAD,BASS,...  /  cue:TAPE_STOP|SCRATCH  /  fx:TapeStop|LowpassDip|PitchWobble
     */
    private fun handleDebugIntent(intent: Intent) = debugCommands(intent).forEach { runDebugCommand(it) }

    /** "a;b;c" 처럼 여러 명령을 한 번에(릴리스 빌드는 [RELEASE_CMDS]만). */
    private fun debugCommands(intent: Intent): List<String> =
        intent.getStringExtra("debug")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() && (BuildConfig.DEBUG || it in RELEASE_CMDS) }.orEmpty()

    private fun runDebugCommand(cmd: String) {
        Log.i(TAG, "debug intent: $cmd")
        when {
            cmd == "demo" -> { showMood(); startDemo() }
            cmd == "demo:safe" -> { dismissOverlay(); showMood(); startDemo(DriveScenario.SAFE) }    // 흔들림 없이 64마디 → 클라이맥스, S등급
            cmd == "demo:rough" -> { dismissOverlay(); showMood(); startDemo(DriveScenario.ROUGH) }  // 흔들림이 계속 → 완주 못함
            cmd == "seed:play" -> seedPlay()
            cmd == "bars:off" || cmd == "bars:on" -> { // 스크린샷·영상용: 차량 상태바·하단 바 숨김(몰입 모드)
                val c = window.insetsController
                if (cmd == "bars:off") { c?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c?.hide(android.view.WindowInsets.Type.systemBars()) }
                else c?.show(android.view.WindowInsets.Type.systemBars())
            }
            cmd == "panel:on" || cmd == "panel:off" -> { // 세로형 보조 패널(약 1:1.6) 미리보기: 앱 전체를 가운데 세로 상자 안에 배치
                val lp = root.layoutParams as FrameLayout.LayoutParams
                if (cmd == "panel:on") { val h = (root.parent as View).height; lp.width = (h / 1.6f).toInt(); lp.gravity = Gravity.CENTER }
                else { lp.width = MATCH; lp.gravity = Gravity.NO_GRAVITY }
                root.layoutParams = lp
                root.post { updateGlass(); if (screen == Screen.RESULT) lastReport?.let { showResult(it) { showMood() } } else if (screen == Screen.RECORDS) showRecords(lastRecordsTab) }
            }
            cmd == "reset:play" -> controller.play.clear()
            cmd == "voice:on" -> saveVoice(true)
            cmd == "voice:off" -> saveVoice(false)
            cmd == "voice:tts" -> controller.preferTts = true    // Pleos TTS 우선(기본)
            cmd == "voice:ogg" -> controller.preferTts = false   // 녹음 음성만(시연 녹화: TTS 소리는 앱 믹스에 담기지 않음)
            cmd == "screen:result" || cmd == "screen:result:reveal" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); (lastReport ?: controller.play.load().results.maxByOrNull { it.startEpochMs }?.let { ResultReport(it, emptyList(), false, null) })?.let { lastReport = it; showResult(it, reveal = cmd.endsWith("reveal")) { showMood() } } }
            cmd.startsWith("screen:records") -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); showRecords(cmd.substringAfter("screen:records", "").removePrefix(":").toIntOrNull() ?: 0) }
            cmd == "real" -> requestSpeedThenStart()
            cmd == "stop" -> controller.stop()
            cmd == "screen:mood" -> { dismissOverlay(); showMood() }
            cmd == "screen:drive" -> { // 주행 중 모습(시뮬레이션): 상태를 먼저 그린 뒤 UX 제한 → 그래픽 고정
                dismissOverlay(); showMood(); simMoving = false; applyDrivingPolicy()
                mood.show(MoodState(energy = 0.85f, calm = 0.8f, brightness = 0.9f, rain = false), immediate = true)
                simMoving = true; applyDrivingPolicy()
            }
            cmd == "screen:album" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); showAlbum(controller.albums.all().firstOrNull()) }
            cmd == "screen:detail" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); controller.albums.all().firstOrNull()?.let { showAlbumDetail(it) } }
            cmd.startsWith("screen:detail:") -> { // n번째 앨범(0부터) 상세
                simMoving = false; applyDrivingPolicy(); dismissOverlay()
                controller.albums.all().getOrNull(cmd.removePrefix("screen:detail:").toIntOrNull() ?: 0)?.let { showAlbumDetail(it) }
            }
            cmd == "screen:debug" -> showDebug()
            cmd == "screen:settings" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); showSettings() }
            cmd == "mode:play" -> setMode(app.playdrive.play.AppMode.PLAY)
            cmd == "mode:listen" -> setMode(app.playdrive.play.AppMode.LISTEN)
            cmd == "screen:songs" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); showSongs() }
            cmd == "wavelog:on" -> mood.waveLog = true
            cmd == "wavelog:off" -> mood.waveLog = false
            cmd == "effect:live" -> saveEffect(app.playdrive.ui.EffectLevel.LIVE)
            cmd == "effect:calm" -> saveEffect(app.playdrive.ui.EffectLevel.CALM)
            cmd == "welcome" -> { mood.welcome() }
            cmd == "goodbye" -> { mood.goodbye() }
            cmd == "wake" -> { mood.wake() }
            cmd.startsWith("daypart:") -> { // 시간대 색온도 강제(스크린샷용), auto = 시계 따라
                dayOverride = AlbumNaming.PartOfDay.entries.find { it.name == cmd.removePrefix("daypart:") }
                app.playdrive.ui.light.LightParams.coverDayOverride = dayOverride
                mood.setDayPart(dayOverride ?: currentDayPart(), immediate = true)
            }
            cmd.startsWith("rain:") -> { // 정차 중 비 모습(스크린샷용)
                val cur = mood.scene.mood(SystemClock.uptimeMillis())
                mood.show(cur.copy(rain = cmd == "rain:on"), immediate = true)
            }
            cmd.startsWith("render:") -> { // 무드 셰이더 해상도 배율 또는 canvas(비교용) → 다시 시작
                val v = cmd.removePrefix("render:")
                getSharedPreferences("render", MODE_PRIVATE).edit().putBoolean("canvas", v == "canvas").putFloat("scale", v.toFloatOrNull() ?: DEFAULT_RENDER_SCALE).apply()
                recreate()
            }
            cmd == "icon:debug:off" -> { debugIcon = false; updateMoodIcons() }   // 스크린샷용
            cmd == "icon:debug:on" -> { debugIcon = true; updateMoodIcons() }
            cmd == "icon:demo:off" -> { demoIcon = false; updateMoodIcons() }     // 스크린샷용
            cmd == "icon:demo:on" -> { demoIcon = true; updateMoodIcons() }
            cmd == "seed:albums" -> seedAlbums()
            cmd == "rec:audio:on" || cmd == "record:start" -> { // 시연 영상용: 앱 소리를 WAV로(에뮬레이터 화면 녹화엔 앱 소리가 안 담김) — tools/record_demo.sh
                val dir = filesDir  // 꺼낼 때: adb exec-out run-as app.playdrive --user <사용자> cat files/demo_audio.wav
                controller.startAudioTap(java.io.File(dir, "demo_audio.wav"))?.let { Log.i(TAG, "audio tap: $it") }
            }
            cmd == "rec:audio:off" || cmd == "record:stop" -> {
                val start = controller.stopAudioTap()
                val dir = filesDir  // 꺼낼 때: adb exec-out run-as app.playdrive --user <사용자> cat files/demo_audio.wav
                java.io.File(dir, "demo_audio.txt").writeText("startEpochMs=$start\n")
                Log.i(TAG, "audio tap done: startEpochMs=$start")
            }
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

    /**
     * 스크린샷용 예시 앨범 6장: 시간대·날씨·정차 횟수가 모두 다른 가상의 주행. 기존(데모로 쌓인) 앨범은 모두 지운다. 기기 안에만 저장.
     * 커버는 [CoverArt.layersFor]: 정차 횟수 = 빛의 개수, 부드러움 = 크기·선명도, 주행 시간 = 퍼짐, 비 = 물방울, 테이프스톱 = 물결 흩어짐.
     */
    private fun seedAlbums() {
        controller.albums.clear()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun at(daysAgo: Int, hour: Int, minute: Int = 10): Long {
            val c = java.util.Calendar.getInstance(); c.timeInMillis = now - daysAgo * day
            c.set(java.util.Calendar.HOUR_OF_DAY, hour); c.set(java.util.Calendar.MINUTE, minute); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0); return c.timeInMillis
        }
        data class Seed(val start: Long, val minutes: Int, val smooth: Float, val rain: Float, val stops: Int, val breaks: Int, val note: String)
        listOf(
            Seed(at(1, 21), 25, 82f, 0.8f, 3, 0, ""),   // 어제 밤 · 비 · 정차 3
            Seed(at(1, 8), 34, 74f, 0f, 5, 1, ""),                          // 어제 아침 · 맑음 · 정차 5
            Seed(at(2, 18), 14, 68f, 0f, 2, 1, ""),          // 그제 저녁(노을) · 맑음 · 정차 2
            Seed(at(3, 14), 42, 41f, 0f, 4, 3, ""),                         // 사흘 전 한낮 · 분주 · 정차 4
            Seed(at(4, 7, 40), 6, 60f, 1f, 1, 0, ""),                       // 나흘 전 아침 · 비 · 정차 1
            Seed(at(5, 4, 30), 18, 92f, 0f, 2, 0, ""),                      // 닷새 전 새벽 · 맑음 · 정차 2
        ).forEach { d ->
            val s = DriveSummary(d.start, d.start + d.minutes * 60_000L + 300_000L, d.smooth, d.rain > 0f, d.minutes * 60)
            val layers = app.playdrive.album.CoverArt.layersFor(d.start, d.stops, d.smooth / 100f, s.movingSeconds, d.rain, d.breaks)
            controller.albums.add(Album(d.start, s, AlbumNaming.uniqueTitle(s, controller.albums.all().map { it.summary }), d.note, layers))
        }
        if (screen == Screen.ALBUM) showAlbum(null)
    }

    override fun onDestroy() {
        ux?.release()
        controller.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 작은 도우미
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 글꼴 하나(시스템 산세리프)로 통일. 제목(굵게)은 중간 굵기 + 자간 조금 넓게. */
    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = if (bold) FONT_TITLE else FONT_BODY
        if (bold) letterSpacing = 0.04f
    }

    /** 무드 화면 위 유리 버튼(높이 76dp): 아이콘(선택) + 글자. 흐림은 뒤의 셰이더가, 1px 하이라이트 테두리는 여기서. */
    private fun glassPill(label: String, glyph: Char?, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(if (glyph != null) 16 else 28), 0, dp(28), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(38).toFloat(); setColor(if (mood.usesShader) 0x0AFFFFFF else 0x22FFFFFF)
            setStroke(1, 0x55FFFFFF)
        }
        isClickable = true; isFocusable = true; contentDescription = label
        if (glyph != null) addView(GlyphButton(this@MainActivity, glyph, label, bare = true), LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(text(label, BODY_SP).apply { if (glyph != null) setPadding(dp(10), 0, 0, 0) })
        setOnClickListener { onClick() }
    }

    private fun button(label: String, primary: Boolean, size: Float = BODY_SP, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = size; isAllCaps = false; typeface = FONT_TITLE; letterSpacing = 0.02f; minHeight = dp(TOUCH_DP); minimumHeight = dp(TOUCH_DP); minWidth = dp(120)
        setTextColor(if (primary) 0xFF04342C.toInt() else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(38).toFloat(); setColor(if (primary) 0xFF3FD0C0.toInt() else 0x33FFFFFF) }
        setPadding(dp(32), dp(8), dp(32), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "PlayDrive.UI"
        /** 릴리스 빌드에서도 받는 인텐트 명령(심사·시연용). */
        private val RELEASE_CMDS = setOf("demo", "demo:safe", "demo:rough", "seed:albums", "seed:play", "icon:demo:off", "icon:demo:on", "effect:live", "effect:calm", "voice:on", "voice:off", "voice:tts", "voice:ogg", "mode:play", "mode:listen")
        private const val PERM_SPEED = "android.car.permission.CAR_SPEED"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** 무드 셰이더 해상도 배율(1 = 원래, 0.5 = 절반으로 그린 뒤 확대). 측정 결과로 정함(docs/dev-notes.md). */
        const val DEFAULT_RENDER_SCALE = 0.5f
        private val FONT_BODY: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private val FONT_TITLE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        /** 차량 화면 기준: 터치 영역 최소 76dp, 본문 24sp 이상, 제목 32sp 이상. */
        const val TOUCH_DP = 76
        const val BODY_SP = 24f
        const val TITLE_SP = 32f
        private const val PAD_DP = 32
        // 결과 화면 색: 남색 바탕, 청록 포인트 한 색
        private const val NAVY = 0xFF0E1830.toInt()
        private const val CARD = 0xFF16233F.toInt()
        private const val TEAL = 0xFF8FD3C6.toInt()
        private const val SOFT = 0xFFA9B6C6.toInt()
        private const val MUTED_RED = 0xFFB0605F.toInt()
        private const val GAP_DP = 24
    }
}
