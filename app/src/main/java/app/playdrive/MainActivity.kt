package app.playdrive

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
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
 *  - ALBUM: 마음 앨범(주행 1회 = 1장), 회고 한 줄은 정차 중에만
 *  - DEBUG: 개발용(디버그 빌드)
 * 주행 중(CarUxRestrictions가 DO 요구 또는 시뮬레이션 주행 중)에는 MOOD 외 화면을 닫는다.
 */
class MainActivity : ComponentActivity(), DriveController.Listener {

    enum class Screen { MOOD, BREATH, ALBUM, DEBUG }

    private lateinit var controller: DriveController
    private lateinit var root: FrameLayout
    private lateinit var mood: MoodView
    private lateinit var play: PlayPauseButton
    private lateinit var albumBtn: GlyphButton
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

    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startReal() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        mood = MoodView(this)
        root.addView(mood, FrameLayout.LayoutParams(MATCH, MATCH))
        play = PlayPauseButton(this).apply { setOnClickListener { controller.togglePlay() }; isEnabled = false; alpha = 0.4f }
        root.addView(play, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        albumBtn = GlyphButton(this, 'A', getString(R.string.album_title)).apply { setOnClickListener { showAlbum(null) } }
        root.addView(albumBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(20), 0) })
        debugBtn = GlyphButton(this, 'D', "debug").apply { setOnClickListener { showDebug() }; visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE }
        root.addView(debugBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(26), 0, 0) })
        setContentView(root)

        controller = DriveController(this, this)
        controller.prepare()
        ux = UxRestrictionsWatcher(this) { r -> runOnUiThread { uxRestricted = r; applyDrivingPolicy() } }
        pendingIntent = intent
    }

    // ------------------------------------------------------------------ 주행 정책
    private fun applyDrivingPolicy() {
        mood.restricted = driving
        val parkedOnly = if (driving) View.GONE else View.VISIBLE
        albumBtn.visibility = parkedOnly
        debugBtn.visibility = if (BuildConfig.DEBUG && !driving) View.VISIBLE else View.GONE
        if (driving) { dismissOverlay(); if (screen != Screen.MOOD && screen != Screen.DEBUG) showMood() }
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
        throttle.offer(MoodState.from(state, layers.size, controller.totalLayers), SystemClock.elapsedRealtime(), driving)?.let { mood.show(it) }
        debugDetail?.text = buildString {
            appendLine("속도 ${"%.1f".format(state.speedMps * 3.6f)} km/h  가속 ${"%.2f".format(state.accelMps2)}  기어 ${state.gear}  브레이크 ${state.brakePressed}")
            appendLine("주행=${state.moving} 정속=${state.cruising} 방향지시등=${state.turnSignal} 비=${state.raining} 혼자=${state.solo}  UX제한=$uxRestricted")
            appendLine("레이어: ${layers.joinToString()}   [$debug]")
            recentEvents.asReversed().take(8).forEach { appendLine(it.toString()) }
        }
    }

    override fun onSourceFinished() { debugStatus?.text = "데모 드라이브 끝" }

    override fun onDriveFinished(album: Album, suggestBreath: Boolean) {
        lastAlbum = album
        if (suggestBreath && !driving) showBreathOffer(album) else if (!driving && screen == Screen.MOOD) showAlbum(album)
    }

    // ------------------------------------------------------------------ 화면들
    private fun clearPage() { breath?.cancel(); breath = null; page?.let { root.removeView(it) }; page = null; debugStatus = null; debugDetail = null }

    private fun showMood() { clearPage(); screen = Screen.MOOD }

    private fun dismissOverlay() { overlay?.let { root.removeView(it) }; overlay = null }

    /** 주차 후 1분 제안 카드(정차 중에만). */
    private fun showBreathOffer(album: Album?) {
        if (driving) return
        dismissOverlay()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xF01B2633.toInt()) }
        }
        card.addView(text(getString(R.string.breath_offer_title), 22f, bold = true))
        card.addView(text(getString(R.string.breath_offer_body), 16f, color = 0xFFC9D3DC.toInt()).apply { setPadding(0, dp(8), 0, dp(16)) })
        val row = LinearLayout(this).apply { gravity = Gravity.END }
        row.addView(button(getString(R.string.breath_decline), primary = false) { dismissOverlay(); showAlbum(album ?: lastAlbum) })
        row.addView(button(getString(R.string.breath_accept), primary = true) { dismissOverlay(); showBreath(album ?: lastAlbum) })
        card.addView(row)
        overlay = card
        root.addView(card, FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** 1분 호흡: 오늘 곡을 아주 작게 + 들숨/날숨 원. 끝나면 마음 앨범으로. */
    private fun showBreath(album: Album?, frozenAtMs: Long? = null) {
        if (driving) return
        clearPage(); screen = Screen.BREATH
        controller.setBreathMode(true)
        val frame = FrameLayout(this)
        val phaseText = text("", 20f, color = 0xFFDDE7EE.toInt()).apply { gravity = Gravity.CENTER }
        val bv = BreathView(this, onPhase = { inhale -> runOnUiThread { phaseText.text = getString(if (inhale) R.string.breath_inhale else R.string.breath_exhale) } },
            onDone = { runOnUiThread { controller.setBreathMode(false); showAlbum(album, getString(R.string.breath_done)) } })
        bv.frozenAtMs = frozenAtMs
        breath = bv
        frame.addView(bv, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(phaseText, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(96) })
        // 오늘 주행 곡(아주 작게): 커버 + 제목
        if (album != null) {
            val mini = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0.7f }
            mini.addView(CoverView(this).apply { spec = AlbumNaming.cover(album.summary) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            mini.addView(text(album.title, 13f, color = 0xFFAFC0CC.toInt()).apply { setPadding(dp(10), 0, 0, 0) })
            frame.addView(mini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        }
        frame.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { controller.setBreathMode(false); showMood() } },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(20), 0) })
        page = frame
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        if (frozenAtMs == null) bv.begin()
    }

    /** 마음 앨범. highlight = 방금 생긴 앨범(맨 위 강조). */
    private fun showAlbum(highlight: Album?, banner: String? = null) {
        if (driving) return
        clearPage(); screen = Screen.ALBUM
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24)); setBackgroundColor(0xFF0F151D.toInt()) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(getString(R.string.album_title), 26f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(GlyphButton(this, 'X', "닫기").apply { setOnClickListener { showMood() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(head)
        val sub = banner ?: if (highlight != null) getString(R.string.album_new) else null
        if (sub != null) col.addView(text(sub, 15f, color = 0xFF8FD3C6.toInt()).apply { setPadding(0, dp(4), 0, dp(12)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val albums = controller.albums.all()
        if (albums.isEmpty()) list.addView(text(getString(R.string.album_empty), 16f, color = 0xFF9AA8B4.toInt()).apply { setPadding(0, dp(24), 0, 0) })
        for (a in albums) list.addView(albumRow(a, a.id == highlight?.id))
        col.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        page = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun albumRow(a: Album, highlight: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (highlight) 0xFF1E3138.toInt() else 0xFF161F29.toInt()) }
        }
        row.addView(CoverView(this).apply { spec = AlbumNaming.cover(a.summary) }, LinearLayout.LayoutParams(dp(96), dp(96)))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        right.addView(text(a.title, 19f, bold = true))
        val edit = EditText(this).apply {
            setText(a.reflection); hint = getString(R.string.album_reflection_hint); textSize = 15f
            setTextColor(Color.WHITE); setHintTextColor(0xFF6F7E8A.toInt()); inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true; isEnabled = !driving
        }
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        line.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(button(getString(R.string.album_reflection_save), primary = false) {
            if (!driving) controller.albums.updateReflection(a.id, edit.text.toString()); edit.clearFocus()
        })
        right.addView(line)
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return LinearLayout(this).apply { setPadding(0, 0, 0, dp(12)); addView(row, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)) }
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
     *  --es debug screen:mood | screen:drive | screen:park | screen:breath | screen:album | screen:debug
     *  --es debug seed:albums   (예시 앨범 3장 생성, 기기 안에만)
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
            cmd == "screen:drive" -> { // 주행 중 모습(시뮬레이션): UX 제한 상태로 그래픽 고정
                dismissOverlay(); showMood(); simMoving = true; applyDrivingPolicy()
                mood.show(MoodState(energy = 0.85f, calm = 0.8f, brightness = 0.9f, rain = false))
            }
            cmd == "screen:park" -> { simMoving = false; applyDrivingPolicy(); showMood(); showBreathOffer(lastAlbum ?: controller.albums.all().firstOrNull()) }
            cmd == "screen:breath" -> { simMoving = false; applyDrivingPolicy(); showBreath(lastAlbum ?: controller.albums.all().firstOrNull(), frozenAtMs = 3_600L) }
            cmd == "screen:album" -> { simMoving = false; applyDrivingPolicy(); dismissOverlay(); showAlbum(controller.albums.all().firstOrNull()) }
            cmd == "screen:debug" -> showDebug()
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

    /** 스크린샷용 예시 앨범(가상의 주행 3회, 기기 안에만 저장). */
    private fun seedAlbums() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun at(daysAgo: Int, hour: Int): Long {
            val c = java.util.Calendar.getInstance(); c.timeInMillis = now - daysAgo * day
            c.set(java.util.Calendar.HOUR_OF_DAY, hour); c.set(java.util.Calendar.MINUTE, 10); return c.timeInMillis
        }
        listOf(
            DriveSummary(at(1, 21), at(1, 21) + 1_500_000, 82f, true, 1400),
            DriveSummary(at(2, 8), at(2, 8) + 1_800_000, 74f, false, 1700),
            DriveSummary(at(4, 14), at(4, 14) + 2_400_000, 41f, false, 2200),
        ).forEach { s -> controller.albums.add(Album(s.startEpochMs, s, AlbumNaming.title(s), if (s.rained) "빗소리랑 같이 천천히 왔다" else "")) }
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

    private fun button(label: String, primary: Boolean, size: Float = 16f, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = size; isAllCaps = false
        setTextColor(if (primary) 0xFF0F151D.toInt() else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(if (primary) 0xFF8FD3C6.toInt() else 0x33FFFFFF) }
        setPadding(dp(20), dp(8), dp(20), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "PlayDrive.UI"
        private const val PERM_SPEED = "android.car.permission.CAR_SPEED"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
