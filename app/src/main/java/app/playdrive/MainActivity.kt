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
 *  - SETTINGS: 화면 효과(라이브/차분) — 정차 중에만
 *  - DEBUG: 개발용(디버그 빌드)
 * 주행 중(CarUxRestrictions가 DO 요구 또는 시뮬레이션 주행 중)에는 MOOD 외 화면을 닫는다.
 */
class MainActivity : ComponentActivity(), DriveController.Listener {

    enum class Screen { MOOD, BREATH, ALBUM, ALBUM_DETAIL, SETTINGS, DEBUG }

    private lateinit var controller: DriveController
    private lateinit var root: FrameLayout
    private lateinit var mood: MoodView
    private lateinit var play: PlayPauseButton
    private lateinit var albumBtn: View
    private lateinit var demoBtn: View
    private lateinit var settingsBtn: View
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
    private var lastTapeMs = Long.MIN_VALUE
    private var prevLayers = 0
    private var recoverTo = 0
    private var lastAlbum: Album? = null
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
        mood = MoodView(this, renderScale = scale, forceCanvas = getSharedPreferences("render", MODE_PRIVATE).getBoolean("canvas", false))
        root.addView(mood, FrameLayout.LayoutParams(MATCH, MATCH))
        play = PlayPauseButton(this).apply { setOnClickListener { controller.togglePlay() }; isEnabled = false; alpha = 0.4f; glassBehind = mood.usesShader }
        root.addView(play, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        // 마음 앨범 버튼: 음반 아이콘 + 글자(무슨 버튼인지 바로 보이게), 높이 76dp
        albumBtn = glassPill(getString(R.string.album_title), 'A') { showAlbum(null) }
        root.addView(albumBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(20), 0) })
        // 데모 드라이브(릴리스에도 있음 — 실제 차량 신호 없이 심사·시연용으로 74초 주행을 재생). 정차 중에만 보인다.
        demoBtn = glassPill(getString(R.string.demo_drive), null) { if (prepared && !driving) { dismissOverlay(); showMood(); startDemo() } }.apply { isEnabled = false; alpha = 0.4f }
        root.addView(demoBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP), Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(20), 0, 0) })
        // 설정(화면 효과): 오른쪽 아래, 정차 중에만
        settingsBtn = GlyphButton(this, 'S', getString(R.string.settings_title)).apply { setOnClickListener { showSettings() } }
        root.addView(settingsBtn, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(20)) })
        mood.effect = loadEffect()
        // 개발용(디버그 빌드에서만 보임, 릴리스 빌드엔 없음)
        debugBtn = GlyphButton(this, 'D', "debug").apply { setOnClickListener { showDebug() }; visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE }
        root.addView(debugBtn, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(20), 0, 0, dp(20)) })
        setContentView(root)

        controller = DriveController(this, this)
        controller.prepare()
        mood.beatSource = { controller.beatPhase() }
        mood.beatInfo = { controller.beatInfo() }
        // 시간대 색온도: 지금 시각으로 시작, 1분마다 확인(바뀌면 정차 중 6초에 걸쳐)
        mood.setDayPart(currentDayPart(), immediate = true)
        root.postDelayed(object : Runnable { override fun run() { if (dayOverride == null) mood.setDayPart(currentDayPart()); root.postDelayed(this, 60_000) } }, 60_000)
        // 유리 패널 위치(버튼이 움직이거나 보이고 숨을 때마다)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGlass() }
        play.addOnLayoutChangeListener(relayout); albumBtn.addOnLayoutChangeListener(relayout); demoBtn.addOnLayoutChangeListener(relayout); settingsBtn.addOnLayoutChangeListener(relayout)
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
        controller.albums.all().firstOrNull()?.let { mood.setCover(AlbumNaming.cover(it), immediate = true) }
        ux = UxRestrictionsWatcher(this) { r -> runOnUiThread { uxRestricted = r; applyDrivingPolicy() } }
        pendingIntent = intent
        // 아이콘 숨김은 웰컴 첫 프레임부터(나머지 명령은 음악 준비가 끝난 뒤 onPrepared에서)
        debugCommands(intent).filter { it.startsWith("icon:") }.forEach { runDebugCommand(it) }
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
        // 주행 중 이벤트 반응(레이어 증감·테이프스톱·와이퍼) — 전환 간격·길이 제한은 DriveMotion이 지킨다
        val tape = recentEvents.lastOrNull { it is DrivingEvent.HarshBraking || it is DrivingEvent.HarshAcceleration }?.timeMs
        val newTape = tape != null && tape > lastTapeMs
        if (newTape) { lastTapeMs = tape!!; recoverTo = maxOf(prevLayers, layers.size); Log.i(TAG, "장면: 테이프스톱(레이어 $prevLayers → ${layers.size})") }
        if (recoverTo > 0 && layers.size >= recoverTo) { Log.i(TAG, "장면: 회복(레이어 ${layers.size})"); recoverTo = 0 }
        prevLayers = layers.size
        mood.music(layers.size, newTape, state.raining, state.speedMps * 3.6f, state.turnSignal)
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
        // 마지막 정차의 "한 겹 추가" 장면(4초) → 굿바이(2초 동안 빛이 가라앉음) → 제안 카드·앨범
        root.postDelayed({
            if (driving) return@postDelayed
            mood.goodbye(); Log.i(TAG, "장면: 굿바이")
            root.postDelayed({
                if (suggestBreath && !driving) showBreathOffer(album) else if (!driving && screen == Screen.MOOD) showAlbum(album)
            }, app.playdrive.ui.MoodScene.GOODBYE_MS)
        }, app.playdrive.ui.MoodScene.REVEAL_MS)
    }

    // ------------------------------------------------------------------ 화면들
    private fun clearPage() { breath?.cancel(); breath = null; page?.let { root.removeView(it) }; page = null; debugStatus = null; debugDetail = null }

    private fun showMood() { clearPage(); screen = Screen.MOOD; if (overlay == null) mood.wake() }

    /** 제안 카드를 닫는다. 카드가 실제로 닫혔고 무드 화면이면 빛이 다시 깨어난다(굿바이 뒤). */
    private fun dismissOverlay() {
        val had = overlay != null
        overlay?.let { root.removeView(it) }; overlay = null; updateMoodIcons()
        if (had && screen == Screen.MOOD) mood.wake()
    }

    /** 주차 후 1분 제안 카드(정차 중에만). */
    private fun showBreathOffer(album: Album?) {
        if (driving) return
        Log.i(TAG, "장면: 숨 고르기 제안")
        dismissOverlay()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(32), dp(36), dp(24))
            background = app.playdrive.ui.GlassDrawable(dp(28).toFloat(), glassBehind = mood.usesShader)  // 버튼과 같은 유리
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGlass() }
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
        Log.i(TAG, "장면: 호흡")
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
        Log.i(TAG, "장면: 마음 앨범")
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
        page = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
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
    private fun startDemo() { lastTapeMs = Long.MIN_VALUE; controller.start(DemoDriveSource(controller.engineHandler) { controller.notifyFinished() }); debugStatus?.text = "데모 드라이브 재생 중" }

    private fun requestSpeedThenStart() {
        if (checkSelfPermission(PERM_SPEED) == PackageManager.PERMISSION_GRANTED) startReal() else speedPermission.launch(PERM_SPEED)
    }

    /** 실제 신호원은 음악을 바로 틀지 않는다(웰컴 끝에 차임과 함께). */
    private fun startReal() { lastTapeMs = Long.MIN_VALUE; controller.start(PleosVehicleSignalSource(this), autoPlay = SystemClock.uptimeMillis() >= welcomeEndsAt) }

    // ------------------------------------------------------------------ 디버그 인텐트
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (prepared) handleDebugIntent(intent) else pendingIntent = intent
    }

    /**
     * 디버그 빌드 전용(테스트·스크린샷·영상). 여러 명령은 ';'로 이어서. 릴리스 빌드는 [RELEASE_CMDS](데모 드라이브·예시 앨범·아이콘 숨김)만 받는다:
     *  --es debug demo | real | stop
     *  --es debug screen:mood | screen:drive | screen:park | screen:breath | screen:album | screen:detail[:n] | screen:debug
     *  --es debug seed:albums   (기존 앨범을 모두 지우고 예시 앨범 6장 생성, 기기 안에만)
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
            cmd == "screen:settings" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); showSettings() }
            cmd == "effect:live" -> saveEffect(app.playdrive.ui.EffectLevel.LIVE)
            cmd == "effect:calm" -> saveEffect(app.playdrive.ui.EffectLevel.CALM)
            cmd == "welcome" -> { mood.welcome() }
            cmd == "goodbye" -> { mood.goodbye() }
            cmd == "wake" -> { mood.wake() }
            cmd.startsWith("daypart:") -> { // 시간대 색온도 강제(스크린샷용), auto = 시계 따라
                dayOverride = AlbumNaming.PartOfDay.entries.find { it.name == cmd.removePrefix("daypart:") }
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
            Seed(at(1, 21), 25, 82f, 0.8f, 3, 0, "빗소리랑 같이 천천히 왔다"),   // 어제 밤 · 비 · 정차 3
            Seed(at(1, 8), 34, 74f, 0f, 5, 1, ""),                          // 어제 아침 · 맑음 · 정차 5
            Seed(at(2, 18), 14, 68f, 0f, 2, 1, "노을이 길게 남았다"),          // 그제 저녁(노을) · 맑음 · 정차 2
            Seed(at(3, 14), 42, 41f, 0f, 4, 3, ""),                         // 사흘 전 한낮 · 분주 · 정차 4
            Seed(at(4, 7, 40), 6, 60f, 1f, 1, 0, ""),                       // 나흘 전 아침 · 비 · 정차 1
            Seed(at(5, 4, 30), 18, 92f, 0f, 2, 0, ""),                      // 닷새 전 새벽 · 맑음 · 정차 2
        ).forEach { d ->
            val s = DriveSummary(d.start, d.start + d.minutes * 60_000L + 300_000L, d.smooth, d.rain > 0f, d.minutes * 60)
            val layers = app.playdrive.album.CoverArt.layersFor(d.start, d.stops, d.smooth / 100f, s.movingSeconds, d.rain, d.breaks)
            controller.albums.add(Album(d.start, s, AlbumNaming.uniqueTitle(s, controller.albums.all().map { it.summary }), d.note, layers))
        }
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
        setTextColor(if (primary) 0xFF0F151D.toInt() else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(38).toFloat(); setColor(if (primary) 0xFF8FD3C6.toInt() else 0x33FFFFFF) }
        setPadding(dp(32), dp(8), dp(32), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "PlayDrive.UI"
        /** 릴리스 빌드에서도 받는 인텐트 명령(심사·시연용). */
        private val RELEASE_CMDS = setOf("demo", "seed:albums", "icon:demo:off", "icon:demo:on", "effect:live", "effect:calm")
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
        private const val GAP_DP = 24
    }
}
