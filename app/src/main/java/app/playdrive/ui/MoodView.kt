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
import app.playdrive.album.CoverSpec
import app.playdrive.album.MoodState
import app.playdrive.ui.light.LightParams
import app.playdrive.ui.light.LightShader

/**
 * 무드 화면. 상태는 [MoodScene](순수·단위 테스트), 그리기는
 *  - API 33+: Compose + AGSL RuntimeShader([LightShader]) — "빛을 재료로 쓰는 화면"
 *  - API 33 미만: Canvas([CanvasMoodLayer])
 * 주행 중(restricted)에는 다시 그리기를 요청하지 않는다 → 마지막 프레임이 그대로 남는다(SA-1).
 *
 * renderScale < 1 이면 그 배율로 작게 그린 뒤 확대한다(하드웨어 레이어, 부하 절감).
 */
class MoodView(context: Context, val renderScale: Float = 1f, forceCanvas: Boolean = false) : FrameLayout(context) {
    private fun now() = SystemClock.uptimeMillis()
    val scene = MoodScene(now())

    /** 박 안 위치(0~1) 또는 음악이 쉬면 null. 정차 중 메인 빛 숨쉬기에 쓴다. */
    var beatSource: () -> Float? = { null }

    /** 유리 패널(재생·앨범 버튼 뒤, 이 뷰 기준 픽셀). */
    var glass: List<LightParams.Glass> = emptyList()
        set(v) { if (v != field) { field = v; redraw(force = true) } }

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
        set(v) { if (v != scene.restricted) { scene.setRestricted(v, now()); animating.value = !v; redraw(force = true) } }

    fun show(s: MoodState, immediate: Boolean = false) { scene.show(s, now(), immediate); redraw() }
    fun setCover(c: CoverSpec?, immediate: Boolean = false) { scene.setCover(c, now(), immediate); redraw() }
    fun setDayPart(p: PartOfDay, immediate: Boolean = false) { scene.setDayPart(p, now(), immediate); redraw() }
    fun welcome() { scene.welcome(now()); redraw() }
    fun goodbye() { scene.goodbye(now()); redraw() }
    fun wake() { scene.wake(now()); redraw() }

    /** 지금 그릴 프레임. */
    fun currentFrame(): MoodFrame = scene.frame(now(), if (scene.restricted) null else beatSource())

    private fun redraw(force: Boolean = false) {
        if (scene.restricted && !force) return
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
    // 정차 중에만 매 프레임 다시 그린다. 주행 중에는 루프 자체가 없다.
    // 정차 중 움직임은 10~12초 주기라 30fps면 충분하다(부하 절감).
    LaunchedEffect(animating) { if (animating) { var last = 0L; while (true) withFrameMillis { if (it - last >= 33) { last = it; tick = it } } } }
    val shader = remember { LightShader.create() }
    val brush = remember { ShaderBrush(shader) }
    Spacer(Modifier.fillMaxSize().drawBehind {
        @Suppress("UNUSED_EXPRESSION") version; @Suppress("UNUSED_EXPRESSION") tick
        val f = host.currentFrame()
        LightShader.apply(shader, LightParams.mood(f, size.width, size.height, host.glass, host.renderScale))
        drawRect(brush)
    })
}
