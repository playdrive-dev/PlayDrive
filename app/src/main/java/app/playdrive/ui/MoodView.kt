package app.playdrive.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.playdrive.album.AlbumNaming.PartOfDay
import app.playdrive.album.MoodState
import app.playdrive.ui.light.LightParams
import app.playdrive.ui.light.LightShader

/**
 * 무드(주행) 화면 = 흐르는 그라데이션 물결. 상태는 [MoodScene]·[WaveMotion](순수·단위 테스트), 그리기는
 *  - API 33+: Compose + AGSL RuntimeShader([LightShader])
 *  - API 33 미만: Canvas([CanvasMoodLayer])
 * 물결은 지금 들리는 소리([soundSource], 오디오 렌더 시각 기준)를 따라 늘 천천히 흐른다(30fps).
 * renderScale < 1 이면 그 배율로 작게 그린 뒤 확대한다(하드웨어 레이어, 부하 절감).
 */
class MoodView(context: Context, val renderScale: Float = 1f, forceCanvas: Boolean = false, score: WaveScore = WaveScore.EMPTY) : FrameLayout(context) {
    private fun now() = SystemClock.uptimeMillis()
    val scene = MoodScene(now(), score = score)

    /** 지금 들리는 소리(매 프레임). 음악이 없으면 null. */
    var soundSource: () -> Sound? = { null }

    /** 주행 중 화면 효과 강도. 다음 출발부터 적용. */
    var effect: EffectLevel
        get() = scene.effect
        set(v) { scene.effect = v }

    /** 유리 패널(재생·앨범 버튼 뒤, 이 뷰 기준 픽셀). */
    var glass: List<LightParams.Glass> = emptyList()
        set(v) { if (v != field) { field = v; redraw() } }

    // Compose 쪽에 "다시 그려라"를 알리는 값
    internal val version = mutableIntStateOf(0)
    internal val animating = mutableStateOf(true)

    val usesShader = !forceCanvas && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private val layer: View = if (usesShader) lightLayer() else CanvasMoodLayer(context, this)

    init {
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (renderScale < 1f) { layer.pivotX = 0f; layer.pivotY = 0f; layer.scaleX = 1f / renderScale; layer.scaleY = 1f / renderScale; layer.setLayerType(LAYER_TYPE_HARDWARE, null) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (renderScale < 1f) {
            val w = (measuredWidth * renderScale).toInt(); val h = (measuredHeight * renderScale).toInt()
            layer.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (renderScale < 1f) layer.layout(0, 0, layer.measuredWidth, layer.measuredHeight) else super.onLayout(changed, l, t, r, b)
    }

    var restricted: Boolean
        get() = scene.restricted
        set(v) { if (v != scene.restricted) { scene.setRestricted(v, now()); redraw() } }

    fun show(s: MoodState, immediate: Boolean = false) { scene.show(s, now(), immediate); redraw() }
    fun setDayPart(p: PartOfDay, immediate: Boolean = false) { scene.setDayPart(p, now(), immediate); redraw() }
    fun welcome() { scene.welcome(now()); redraw() }
    fun goodbye() { scene.goodbye(now()); redraw() }
    fun wake() { scene.wake(now()); redraw() }
    /** 결과 화면 S등급: 물결이 한 번 밝게 차오름(주차 중에만). */
    fun celebrate(strength: Float = 1f) { if (!scene.restricted) scene.waves.celebrate(now(), strength) }

    /** 디버그(wavelog:on): 프레임마다 물결 높이(가운데·양끝)를 로그로 — 한 프레임 변화량 점검(tools/check_wave_log.py). */
    var waveLog = false

    /** 지금 그릴 프레임(한 프레임에 한 번 — 소리 칸을 이어서 읽는다). */
    fun currentFrame(): MoodFrame {
        val t = now()
        val f = scene.frame(t, soundSource())
        if (waveLog && width > 0) {
            val aspect = width.toFloat() / height
            val h = (0 until 5).joinToString(" ") { i -> listOf(0.15f, aspect / 2, aspect - 0.15f).joinToString(",") { x -> "%.5f".format(WaveGeom.height(f.waves, i, x, aspect)) } }
            android.util.Log.i("PlayDrive.Wave", "t=$t h=$h e=${f.waves.lines.joinToString(",") { "%.2f/%.2f".format(it.edge, it.glow) }} flat=${"%.2f".format(f.waves.flat)} master=${"%.2f".format(f.master)}")
        }
        return f
    }

    private fun redraw() {
        version.intValue++
        layer.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lightLayer(): View = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent { LightBackground(this@MoodView) }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LightBackground(host: MoodView) {
    val version by host.version
    val animating by host.animating
    var tick by remember { mutableLongStateOf(0L) }
    // 물결은 늘 흐르고 소리에 맞춰 움직인다: 매 프레임(60fps) — 파동이 끊겨 보이지 않게
    LaunchedEffect(animating) { if (animating) { var last = 0L; while (true) withFrameMillis { tick = it } } }
    val shader = remember { LightShader.create() }
    val brush = remember { ShaderBrush(shader) }
    Spacer(Modifier.fillMaxSize().drawBehind {
        @Suppress("UNUSED_EXPRESSION") version; @Suppress("UNUSED_EXPRESSION") tick
        val f = host.currentFrame()
        LightShader.apply(shader, LightParams.mood(f, size.width, size.height, host.glass, host.renderScale))
        drawRect(brush)
    })
}
