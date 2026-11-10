package app.playdrive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.util.Log

/**
 * 오디오 포커스 + MediaSession 기본.
 * - 포커스 GAIN(미디어). 내비 안내 등 CAN_DUCK은 AAOS 오디오 정책이 자동으로 덕킹 → 계속 재생.
 * - LOSS_TRANSIENT는 일시정지 후 GAIN에서 재개, LOSS는 정지.
 */
class PlaybackSession(
    context: Context,
    private val handler: Handler,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var resumeOnGain = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
        .build()

    val session = MediaSession(context, "PlayDrive").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { play() }
            override fun onPause() { pause() }
            override fun onStop() { pause() }
        }, handler)
        setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "오늘의 드라이브")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "PlayDrive")
                .build()
        )
    }

    fun play(): Boolean {
        val r = am.requestAudioFocus(focusRequest)
        Log.i(TAG, "오디오 포커스 요청 결과=$r (1=GRANTED, 2=DELAYED)")
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        session.isActive = true
        setState(PlaybackState.STATE_PLAYING)
        onPlay()
        return true
    }

    fun pause(abandon: Boolean = true) {
        onPause()
        setState(PlaybackState.STATE_PAUSED)
        if (abandon) am.abandonAudioFocusRequest(focusRequest)
    }

    fun release() {
        am.abandonAudioFocusRequest(focusRequest)
        session.isActive = false
        session.release()
    }

    private fun onFocusChange(change: Int) {
        Log.i(TAG, "포커스 변경 $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; setState(PlaybackState.STATE_PLAYING); onPlay() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = true; onPause(); setState(PlaybackState.STATE_PAUSED) }
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit // 시스템이 덕킹
        }
    }

    private fun setState(state: Int) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    companion object { const val TAG = "PlayDrive.Session" }
}
