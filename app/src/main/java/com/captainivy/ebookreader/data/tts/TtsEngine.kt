package com.captainivy.ebookreader.data.tts

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朗读引擎抽象。speak 在句子播放完成（或暂停导致的中断）后返回。
 */
interface TtsEngine {

    val label: String

    /** 当前句子播放进度（0..1），不支持进度的引擎可不回调 */
    var onProgress: ((Float) -> Unit)?

    suspend fun warmUp()

    suspend fun speak(text: String, speed: Float)

    fun pause()

    fun resume()

    fun stop()

    fun release()
}

/** 基于 sherpa-onnx 的离线引擎 */
class SherpaTtsEngine(private val spec: TtsModelSpec) : TtsEngine {

    override val label: String get() = spec.name

    @Volatile override var onProgress: ((Float) -> Unit)? = null

    private val loadMutex = Mutex()
    private val speakMutex = Mutex()
    private var tts: OfflineTts? = null
    private var sampleRate = 22050

    @Volatile private var paused = false
    @Volatile private var stopped = false
    @Volatile private var speaking = false
    @Volatile private var released = false
    @Volatile private var track: AudioTrack? = null

    override suspend fun warmUp() {
        ensureLoaded()
    }

    private suspend fun ensureLoaded() {
        loadMutex.withLock {
            if (tts != null) return
            val created = withContext(Dispatchers.IO) { createEngine(spec) }
            if (released) {
                try {
                    created.release()
                } catch (_: Exception) {
                }
                throw IOException("朗读引擎已释放")
            }
            tts = created
            sampleRate = created.sampleRate()
        }
    }

    private fun createEngine(spec: TtsModelSpec): OfflineTts {
        val dir = TtsModelManager.dir(spec)
        val modelPath = File(dir, spec.modelFile).absolutePath
        val tokensPath = File(dir, spec.tokensFile).absolutePath
        val lexicon = spec.lexiconFiles.joinToString(",") {
            File(dir, it).absolutePath
        }
        val rules = spec.ruleFsts.joinToString(",") {
            File(dir, it).absolutePath
        }
        val modelConfig = when (spec.kind) {
            TtsModelKind.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    lexicon = lexicon,
                    tokens = tokensPath
                ),
                numThreads = spec.numThreads
            )
            TtsModelKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = modelPath,
                    voices = spec.voicesFile?.let { File(dir, it).absolutePath }.orEmpty(),
                    tokens = tokensPath,
                    lexicon = lexicon
                ),
                numThreads = spec.numThreads
            )
        }
        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = rules,
            maxNumSentences = 1,
            silenceScale = 0.2f
        )
        return OfflineTts(config = config)
    }

    override suspend fun speak(text: String, speed: Float) {
        speakMutex.withLock {
            if (released) return
            ensureLoaded()
            val engine = tts ?: throw IOException("朗读引擎未就绪")
            stopped = false
            speaking = true
            try {
                speakLocked(engine, text, speed)
            } finally {
                speaking = false
                if (released) freeEngine()
            }
        }
    }

    private suspend fun speakLocked(engine: OfflineTts, text: String, speed: Float) {
        val chunks = ArrayList<FloatArray>()
        val audio = withContext(Dispatchers.IO) {
            engine.generateWithCallback(
                text = text,
                sid = spec.speakerId,
                speed = speed
            ) { samples ->
                if (stopped) {
                    0
                } else {
                    chunks.add(samples.copyOf())
                    1
                }
            }
        }
        if (stopped || chunks.isEmpty()) return

        val frames = chunks.sumOf { it.size }
        val pcm = ShortArray(frames)
        var cursor = 0
        for (chunk in chunks) {
            for (value in chunk) {
                val v = (value.coerceIn(-1f, 1f) * 32767f).toInt()
                pcm[cursor++] = v.toShort()
            }
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufferSize = (pcm.size * 2).coerceAtLeast(minBuffer)
        val localTrack = try {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                audio.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        } catch (e: Exception) {
            throw IOException("无法初始化音频输出", e)
        }
        track = localTrack
        try {
            localTrack.write(pcm, 0, pcm.size)
            if (paused) localTrack.pause() else localTrack.play()
            onProgress?.invoke(0f)
            var lastReport = 0L
            while (!stopped) {
                if (paused) {
                    delay(30)
                    continue
                }
                val head = localTrack.playbackHeadPosition
                if (head >= pcm.size) {
                    onProgress?.invoke(1f)
                    break
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now
                    onProgress?.invoke(head.toFloat() / pcm.size)
                }
                delay(30)
            }
        } finally {
            track = null
            try {
                localTrack.pause()
                localTrack.flush()
            } catch (_: Exception) {
            }
            localTrack.release()
        }
    }

    override fun pause() {
        paused = true
        try {
            track?.pause()
        } catch (_: Exception) {
        }
    }

    override fun resume() {
        paused = false
        try {
            track?.play()
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        stopped = true
        paused = false
        try {
            track?.pause()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        released = true
        stopped = true
        paused = false
        if (!speaking) freeEngine()
    }

    private fun freeEngine() {
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        try {
            tts?.release()
        } catch (_: Exception) {
        }
        tts = null
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 150L
    }
}

/** 系统 TTS 引擎，无需下载模型 */
class SystemTtsEngine(context: Context) : TtsEngine {

    override val label: String = "系统朗读"

    @Volatile override var onProgress: ((Float) -> Unit)? = null

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val counter = AtomicInteger(0)
    private var tts: TextToSpeech? = null

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ticker: Runnable? = null
    @Volatile private var startWatchdog: Runnable? = null
    @Volatile private var rangeSeen = false
    @Volatile private var speakStartedAt = 0L
    @Volatile private var speakSpeed = 1f
    @Volatile private var speakTextLength = 0

    /** 该设备在 1x 语速下的朗读速度（字/秒），根据每次完成情况动态校准 */
    @Volatile private var charsPerSecondAt1x = DEFAULT_CPS

    @Volatile private var paused = false
    @Volatile private var stopped = false

    init {
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            val languageOk = if (status == TextToSpeech.SUCCESS && engine != null) {
                val results = listOf(
                    engine.setLanguage(Locale.SIMPLIFIED_CHINESE),
                    engine.setLanguage(Locale.CHINA)
                )
                results.any {
                    it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                }
            } else {
                false
            }
            ready.complete(status == TextToSpeech.SUCCESS && languageOk)
        }
    }

    override suspend fun warmUp() {
        if (!ready.await()) throw IOException("系统未安装可用的中文朗读引擎")
    }

    override suspend fun speak(text: String, speed: Float) {
        warmUp()
        val engine = tts ?: throw IOException("系统朗读不可用")
        engine.setSpeechRate(speed.coerceIn(0.5f, 2.5f))
        stopped = false
        paused = false
        prepareProgress(text.length, speed.coerceIn(0.5f, 2.5f))
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val id = "tts-${counter.incrementAndGet()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != id) return
                    stopStartWatchdog()
                    speakStartedAt = SystemClock.uptimeMillis()
                    startProgressTicker()
                    onProgress?.invoke(0f)
                }

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int
                ) {
                    if (utteranceId != id) return
                    if (text.isNotEmpty()) {
                        // 该引擎支持 range 回调，停止计时估算，改用精确字符进度
                        rangeSeen = true
                        stopStartWatchdog()
                        stopProgressTicker()
                        onProgress?.invoke(
                            (end.toFloat() / text.length).coerceIn(0f, 1f)
                        )
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != id) return
                    stopStartWatchdog()
                    stopProgressTicker()
                    updateSpeechRateEstimate(SystemClock.uptimeMillis())
                    onProgress?.invoke(1f)
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != id) return
                    stopStartWatchdog()
                    stopProgressTicker()
                    if (cont.isActive) {
                        cont.resumeWith(Result.failure(IOException("系统朗读失败")))
                    }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId != id) return
                    stopStartWatchdog()
                    stopProgressTicker()
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS && cont.isActive) {
                stopStartWatchdog()
                stopProgressTicker()
                cont.resumeWith(Result.failure(IOException("系统朗读启动失败")))
            }
            cont.invokeOnCancellation {
                try {
                    engine.stop()
                } catch (_: Exception) {
                }
            }
        }
        if (stopped) return
    }

    override fun pause() {
        paused = true
        stopStartWatchdog()
        stopProgressTicker()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    override fun resume() {
        paused = false
    }

    override fun stop() {
        stopped = true
        paused = false
        stopStartWatchdog()
        stopProgressTicker()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        stopped = true
        stopStartWatchdog()
        stopProgressTicker()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    /**
     * 部分系统 TTS 引擎不回调 onRangeStart，此时用计时器估算进度。
     * 收到第一次 range 回调后会立刻停止计时器。
     */
    private fun prepareProgress(textLength: Int, speed: Float) {
        stopProgressTicker()
        stopStartWatchdog()
        rangeSeen = false
        speakStartedAt = 0L
        speakSpeed = speed
        speakTextLength = textLength
        // 个别引擎不回调 onStart，500ms 后仍未开始则直接启动估算进度
        val watchdog = Runnable {
            if (!stopped && !paused && speakStartedAt == 0L) {
                speakStartedAt = SystemClock.uptimeMillis()
                startProgressTicker()
            }
        }
        startWatchdog = watchdog
        handler.postDelayed(watchdog, START_WATCHDOG_MS)
    }

    private fun startProgressTicker() {
        stopStartWatchdog()
        stopProgressTicker()
        if (speakStartedAt == 0L) speakStartedAt = SystemClock.uptimeMillis()
        val durationMs = estimateDurationMs(speakTextLength, speakSpeed)
        val runnable = object : Runnable {
            override fun run() {
                if (stopped || paused || rangeSeen) return
                val elapsed = (SystemClock.uptimeMillis() - speakStartedAt).toFloat()
                val fraction = (elapsed / durationMs).coerceIn(0f, 0.95f)
                if (fraction > 0f) onProgress?.invoke(fraction)
                handler.postDelayed(this, TIMER_INTERVAL_MS)
            }
        }
        ticker = runnable
        handler.postDelayed(runnable, TIMER_INTERVAL_MS)
    }

    private fun stopProgressTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    private fun stopStartWatchdog() {
        startWatchdog?.let { handler.removeCallbacks(it) }
        startWatchdog = null
    }

    private fun estimateDurationMs(textLength: Int, speed: Float): Float {
        val cps = (charsPerSecondAt1x * speed).coerceAtLeast(1f)
        return (textLength / cps * 1000f).coerceAtLeast(800f)
    }

    private fun updateSpeechRateEstimate(now: Long) {
        if (speakStartedAt == 0L) return
        val elapsedMs = (now - speakStartedAt).coerceAtLeast(300L)
        if (speakTextLength <= 0) return
        val measured = speakTextLength / (elapsedMs / 1000f * speakSpeed)
        if (measured in 1f..30f) {
            charsPerSecondAt1x = charsPerSecondAt1x * 0.7f + measured * 0.3f
        }
    }

    private companion object {
        const val DEFAULT_CPS = 4.5f
        const val TIMER_INTERVAL_MS = 200L
        const val START_WATCHDOG_MS = 500L
    }
}
