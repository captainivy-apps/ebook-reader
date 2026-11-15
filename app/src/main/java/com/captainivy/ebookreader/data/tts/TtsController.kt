package com.captainivy.ebookreader.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.captainivy.ebookreader.data.Prefs
import java.io.IOException

/**
 * 朗读引擎管理器：负责按设置创建引擎、音频焦点与播放状态。
 */
class TtsController(context: Context) {

    data class Prepared(
        val engineLabel: String,
        val isSystem: Boolean,
        val fallbackMessage: String? = null
    )

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var engine: TtsEngine? = null
    private var engineId: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    @Volatile private var released = false

    /** 因音频焦点丢失等原因被自动暂停时通知界面 */
    var onAutoPaused: (() -> Unit)? = null

    /** 当前句子播放进度（0..1），始终在主线程回调 */
    var onProgress: ((Float) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    var isPaused: Boolean = false
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pause()
                onAutoPaused?.invoke()
            }
        }
    }

    suspend fun prepare(preferredEngineId: String): Prepared {
        released = false
        if (engine != null && engineId == preferredEngineId) {
            engine?.let { bindProgress(it) }
            return Prepared(engine?.label ?: "", engineId == Prefs.TTS_ENGINE_SYSTEM)
        }
        releaseEngine()

        val spec = TtsModelCatalog.byId(preferredEngineId)
        var fallback: String? = null
        if (spec != null) {
            if (TtsModelManager.isDownloaded(spec)) {
                try {
                    val sherpa = SherpaTtsEngine(spec)
                    sherpa.warmUp()
                    if (released) {
                        sherpa.release()
                        throw IOException("朗读引擎已释放")
                    }
                    engine = sherpa
                    engineId = preferredEngineId
                    bindProgress(sherpa)
                    return Prepared(sherpa.label, isSystem = false)
                } catch (e: Exception) {
                    fallback = "离线朗读加载失败，已切换到系统朗读"
                }
            } else {
                fallback = "朗读模型未下载，已切换到系统朗读"
            }
        }

        val system = SystemTtsEngine(appContext)
        try {
            system.warmUp()
        } catch (e: Exception) {
            system.release()
            throw IOException(fallback ?: e.message ?: "没有可用的朗读引擎")
        }
        if (released) {
            system.release()
            throw IOException("朗读引擎已释放")
        }
        engine = system
        engineId = Prefs.TTS_ENGINE_SYSTEM
        bindProgress(system)
        return Prepared(system.label, isSystem = true, fallbackMessage = fallback)
    }

    suspend fun speak(text: String, speed: Float) {
        val active = engine ?: throw IOException("朗读引擎未就绪")
        acquireFocus()
        isPaused = false
        active.speak(text, speed)
    }

    fun pause() {
        isPaused = true
        engine?.pause()
    }

    fun resume() {
        isPaused = false
        engine?.resume()
    }

    fun stop() {
        isPaused = false
        engine?.stop()
        abandonFocus()
    }

    fun release() {
        released = true
        stop()
        releaseEngine()
    }

    private fun bindProgress(target: TtsEngine) {
        target.onProgress = { fraction ->
            // 捕获投递时的回调，避免旧句子的进度被发送给新句子的回调
            val callback = onProgress
            mainHandler.post { callback?.invoke(fraction) }
        }
    }

    private fun releaseEngine() {
        try {
            engine?.onProgress = null
            engine?.release()
        } catch (_: Exception) {
        }
        engine = null
        engineId = null
    }

    private fun acquireFocus() {
        if (focusHeld) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
        focusHeld = false
    }
}
