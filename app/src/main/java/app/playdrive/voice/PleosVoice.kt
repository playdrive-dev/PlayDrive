package app.playdrive.voice

import android.content.Context
import android.os.Handler
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/**
 * Pleos TTS(TextToSpeech 3.3.1, Mode.ON_DEVICE)로 문장을 읽는다. 권한 pleos.car.permission.TTS_SERVICE(normal).
 * - 확인(2026-11-13, 에뮬레이터): 초기화 약 100ms, 한국어 문장 onReady → onStart → onDone.
 * - addEventListener는 initialize 성공 뒤에(먼저 부르면 SDK 내부 lateinit 예외).
 * - 한 번에 한 문장: 앞 문장이 끝나면(onDone/onStop/onError) 다음 문장.
 * - TTS 소리는 앱 AudioTrack이 아니라 TTS 서비스 쪽으로 나가므로, 그동안 음악 덕킹은 [onTalking]으로 믹서에 알린다.
 * 실패(초기화 실패·onError)하면 [onFallback]으로 녹음 음성(OGG)을 쓰게 넘긴다.
 */
class PleosVoice(
    context: Context,
    private val handler: Handler,
    private val onTalking: (Boolean) -> Unit,
    private val onFallback: (Line) -> Unit,
) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile var ready = false; private set
    private var failed = false
    private val queue = ArrayDeque<Line>()
    private var current: Line? = null

    fun init() {
        val t = runCatching { TextToSpeech(app, Mode.ON_DEVICE) }.getOrElse { Log.w(TAG, "Pleos TTS 생성 실패: $it"); failed = true; return }
        tts = t
        val t0 = System.currentTimeMillis()
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() = handler.post {
                    runCatching {
                        t.addEventListener(listener)
                        t.setLocale(Locale.KOREA)
                    }.onFailure { Log.w(TAG, "Pleos TTS 설정 실패: $it") }
                    ready = true
                    Log.i(TAG, "Pleos TTS 준비 ${System.currentTimeMillis() - t0}ms")
                }.let { }
                override fun onFailure(type: ErrorType) = handler.post { failed = true; Log.w(TAG, "Pleos TTS 초기화 실패 $type → 녹음 음성") }.let { }
            })
        }.onFailure { failed = true; Log.w(TAG, "Pleos TTS 초기화 예외: $it") }
    }

    /** 엔진 스레드에서. TTS가 준비되지 않았으면 false(→ 녹음 음성). */
    fun speak(line: Line): Boolean {
        if (!ready || failed) return false
        queue.addLast(line)
        if (current == null) next()
        return true
    }

    private fun next() {
        val line = queue.removeFirstOrNull() ?: run { current = null; onTalking(false); return }
        current = line
        onTalking(true)
        runCatching { tts?.speak(line.text(), true) }.onFailure { Log.w(TAG, "speak 예외 $it"); done(error = true) }
        Log.i(TAG, "Pleos TTS: ${line.text()}")
    }

    private fun done(error: Boolean) {
        val line = current
        if (error && line != null) onFallback(line)
        current = null
        next()
    }

    private val listener = object : EventListener {
        override fun onReady() {}
        override fun onStart() {}
        override fun onDone() { handler.post { done(false) } }
        override fun onStop() { handler.post { done(false) } }
        override fun onError(type: ErrorType) { handler.post { Log.w(TAG, "Pleos TTS 오류 $type → 녹음 음성"); done(true) } }
        override fun onUpdatedRms(rms: Double) {}
    }

    fun release() { runCatching { tts?.release() } }

    companion object { const val TAG = "PlayDrive.Voice" }
}
