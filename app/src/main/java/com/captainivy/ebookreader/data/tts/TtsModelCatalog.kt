package com.captainivy.ebookreader.data.tts

import com.captainivy.ebookreader.data.Prefs

/** 离线 TTS 引擎类型 */
enum class TtsModelKind { VITS, KOKORO }

data class TtsModelFile(
    val localName: String,
    val approxBytes: Long = 0L
)

/**
 * 可下载的离线 TTS 模型。
 *
 * 模型以 sherpa-onnx 官方 GitHub Release 压缩包分发：
 * - 原始地址：github.com
 * - 国内镜像：gh-proxy.com 等 GitHub 加速代理
 * 下载后仅解压运行所需的文件，跳过 espeak-ng-data 等用不到的资源。
 */
data class TtsModelSpec(
    val id: String,
    val name: String,
    val language: String,
    val description: String,
    val archiveName: String,
    val archiveBytes: Long,
    val files: List<TtsModelFile>,
    val kind: TtsModelKind,
    val modelFile: String,
    val tokensFile: String = "tokens.txt",
    val lexiconFiles: List<String> = emptyList(),
    val ruleFsts: List<String> = emptyList(),
    val voicesFile: String? = null,
    val speakerId: Int = 0,
    val numThreads: Int = 2
) {
    val approxSizeMb: Int
        get() = (archiveBytes / (1024 * 1024)).toInt()

    val extractedBytes: Long
        get() = files.sumOf { it.approxBytes }

    fun localFiles(): List<String> = files.map { it.localName }
}

object TtsModelCatalog {

    private fun file(name: String, mb: Double): TtsModelFile =
        TtsModelFile(name, (mb * 1024 * 1024).toLong())

    private fun archiveMb(mb: Int): Long = mb.toLong() * 1024 * 1024

    private val COMMON_ZH_RULES = listOf("date.fst", "number.fst", "phone.fst")

    val models: List<TtsModelSpec> = listOf(
        TtsModelSpec(
            id = "vits_zh_aishell3",
            name = "AISHELL-3 中文",
            language = "中文",
            description = "多音色轻量模型，体积小、合成速度快，适合日常朗读。",
            archiveName = "vits-zh-aishell3.tar.bz2",
            archiveBytes = archiveMb(140),
            files = listOf(
                file("vits-aishell3.int8.onnx", 38.0),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 1.9),
                file("date.fst", 0.1),
                file("number.fst", 0.1),
                file("phone.fst", 0.1)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "vits-aishell3.int8.onnx",
            lexiconFiles = listOf("lexicon.txt"),
            ruleFsts = COMMON_ZH_RULES,
            speakerId = 14
        ),
        TtsModelSpec(
            id = "vits_zh_icefall_aishell3",
            name = "AISHELL-3 轻量版",
            language = "中文",
            description = "30MB 轻量中文模型，174 种音色，资源占用低。",
            archiveName = "vits-icefall-zh-aishell3.tar.bz2",
            archiveBytes = archiveMb(30),
            files = listOf(
                file("model.onnx", 30.0),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 1.9),
                file("date.fst", 0.1),
                file("number.fst", 0.1),
                file("phone.fst", 0.1)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "model.onnx",
            lexiconFiles = listOf("lexicon.txt"),
            ruleFsts = COMMON_ZH_RULES,
            speakerId = 10
        ),
        TtsModelSpec(
            id = "vits_zh_fanchen_c",
            name = "繁辰 C 中文",
            language = "中文",
            description = "187 种音色，音质自然，适合长时间听书。",
            archiveName = "vits-zh-hf-fanchen-C.tar.bz2",
            archiveBytes = archiveMb(114),
            files = listOf(
                file("vits-zh-hf-fanchen-C.onnx", 115.6),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 2.3),
                file("date.fst", 0.1),
                file("number.fst", 0.1),
                file("phone.fst", 0.1)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "vits-zh-hf-fanchen-C.onnx",
            lexiconFiles = listOf("lexicon.txt"),
            ruleFsts = COMMON_ZH_RULES
        ),
        TtsModelSpec(
            id = "vits_zh_fanchen_wnj",
            name = "繁辰 WNJ 中文男声",
            language = "中文",
            description = "中文男声模型，适合播讲、纪实类书籍。",
            archiveName = "vits-zh-hf-fanchen-wnj.tar.bz2",
            archiveBytes = archiveMb(114),
            files = listOf(
                file("vits-zh-hf-fanchen-wnj.onnx", 115.5),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 2.3),
                file("date.fst", 0.1),
                file("number.fst", 0.1),
                file("phone.fst", 0.1)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "vits-zh-hf-fanchen-wnj.onnx",
            lexiconFiles = listOf("lexicon.txt"),
            ruleFsts = COMMON_ZH_RULES
        ),
        TtsModelSpec(
            id = "vits_zh_eula",
            name = "Eula 中文",
            language = "中文",
            description = "804 种音色，声音选择丰富。",
            archiveName = "vits-zh-hf-eula.tar.bz2",
            archiveBytes = archiveMb(115),
            files = listOf(
                file("eula.onnx", 116.3),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 2.6),
                file("date.fst", 0.1),
                file("number.fst", 0.1),
                file("phone.fst", 0.1)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "eula.onnx",
            lexiconFiles = listOf("lexicon.txt"),
            ruleFsts = COMMON_ZH_RULES
        ),
        TtsModelSpec(
            id = "vits_en_ljs",
            name = "LJSpeech 英文女声",
            language = "英文",
            description = "经典英文女声，吐字清晰，适合英文原著。",
            archiveName = "vits-ljs.tar.bz2",
            archiveBytes = archiveMb(104),
            files = listOf(
                file("vits-ljs.onnx", 108.8),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 3.7)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "vits-ljs.onnx",
            lexiconFiles = listOf("lexicon.txt")
        ),
        TtsModelSpec(
            id = "vits_en_vctk",
            name = "VCTK 英文多音色",
            language = "英文",
            description = "109 种英文音色，可按喜好切换不同朗读声音。",
            archiveName = "vits-vctk.tar.bz2",
            archiveBytes = archiveMb(145),
            files = listOf(
                file("vits-vctk.onnx", 121.3),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 35.0)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "vits-vctk.onnx",
            lexiconFiles = listOf("lexicon.txt")
        ),
        TtsModelSpec(
            id = "vits_melo_zh_en",
            name = "MeloTTS 中英双语",
            language = "中文 + 英文",
            description = "轻量中英双语模型，英文单词和中文混读自然。",
            archiveName = "vits-melo-tts-zh_en.tar.bz2",
            archiveBytes = archiveMb(159),
            files = listOf(
                file("model.onnx", 162.5),
                file("tokens.txt", 0.01),
                file("lexicon.txt", 6.5),
                file("date.fst", 0.1),
                file("number.fst", 0.1)
            ),
            kind = TtsModelKind.VITS,
            modelFile = "model.onnx",
            lexiconFiles = listOf("lexicon.txt"),
            ruleFsts = listOf("date.fst", "number.fst")
        ),
        TtsModelSpec(
            id = "kokoro_multi_zh_en",
            name = "Kokoro v1.0 中英双语",
            language = "中文 + 英文",
            description = "53 种音色，中英混合朗读质量高；模型较大，低配设备合成较慢。",
            archiveName = "kokoro-multi-lang-v1_0.tar.bz2",
            archiveBytes = archiveMb(334),
            files = listOf(
                file("model.onnx", 310.5),
                file("voices.bin", 26.9),
                file("tokens.txt", 0.01),
                file("lexicon-us-en.txt", 5.7),
                file("lexicon-zh.txt", 2.3),
                file("phone-zh.fst", 0.1),
                file("date-zh.fst", 0.1),
                file("number-zh.fst", 0.1)
            ),
            kind = TtsModelKind.KOKORO,
            modelFile = "model.onnx",
            lexiconFiles = listOf("lexicon-us-en.txt", "lexicon-zh.txt"),
            ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst"),
            voicesFile = "voices.bin",
            speakerId = 47,
            numThreads = 4
        ),
        TtsModelSpec(
            id = "kokoro_multi_zh_en_v11",
            name = "Kokoro v1.1 中英双语",
            language = "中文 + 英文",
            description = "103 种音色，中英混合朗读质量高；模型较大，低配设备合成较慢。",
            archiveName = "kokoro-multi-lang-v1_1.tar.bz2",
            archiveBytes = archiveMb(348),
            files = listOf(
                file("model.onnx", 310.5),
                file("voices.bin", 51.3),
                file("tokens.txt", 0.01),
                file("lexicon-us-en.txt", 5.7),
                file("lexicon-zh.txt", 2.0),
                file("phone-zh.fst", 0.1),
                file("date-zh.fst", 0.1),
                file("number-zh.fst", 0.1)
            ),
            kind = TtsModelKind.KOKORO,
            modelFile = "model.onnx",
            lexiconFiles = listOf("lexicon-us-en.txt", "lexicon-zh.txt"),
            ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst"),
            voicesFile = "voices.bin",
            speakerId = 3,
            numThreads = 4
        )
    )

    private const val GITHUB_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/"
    )

    fun byId(id: String?): TtsModelSpec? = models.firstOrNull { it.id == id }

    /** 依次尝试的下载地址 */
    fun urls(spec: TtsModelSpec, source: String): List<String> {
        val original = GITHUB_BASE + spec.archiveName
        return if (source == Prefs.TTS_SOURCE_ORIGINAL) {
            listOf(original)
        } else {
            MIRROR_PREFIXES.map { it + original }.distinct()
        }
    }
}
