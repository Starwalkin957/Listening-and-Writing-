package com.qwen.bilingnotes.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

sealed class AsrEvent {
    /** 当前句的中间结果（随说随写） */
    data class Partial(val text: String) : AsrEvent()

    /** 一句话结束（端点检测触发）的最终结果 */
    data class Final(val text: String) : AsrEvent()
}

/**
 * 端侧流式中英混说识别引擎（sherpa-onnx + streaming zipformer bilingual zh-en）。
 * 完全离线；支持热词增强（计算机专业术语）。
 */
class AsrEngine(
    modelDir: File,
    hotwordsFile: File? = null,
    numThreads: Int = 2,
) {
    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream? = null
    val hotwordsEnabled: Boolean

    init {
        val base = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, ENCODER).absolutePath,
                decoder = File(modelDir, DECODER).absolutePath,
                joiner = File(modelDir, JOINER).absolutePath,
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer",
        )

        fun config(withHotwords: Boolean): OnlineRecognizerConfig {
            val model = if (withHotwords) {
                base.copy(
                    modelingUnit = "cjkchar+bbpe",
                    bpeVocab = File(modelDir, "bpe.model").absolutePath,
                )
            } else {
                base
            }
            return OnlineRecognizerConfig(
                featConfig = FeatureConfig(),
                modelConfig = model,
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                hotwordsFile = if (withHotwords) hotwordsFile!!.absolutePath else "",
                hotwordsScore = 2.0f,
            )
        }

        val canHotwords = hotwordsFile != null &&
            hotwordsFile.exists() &&
            File(modelDir, "bpe.model").exists()

        var enabled = false
        recognizer = if (canHotwords) {
            try {
                val r = OnlineRecognizer(config = config(true))
                enabled = true
                r
            } catch (_: Throwable) {
                // 热词配置与当前引擎版本不兼容时，退回普通模式，保证核心功能可用
                OnlineRecognizer(config = config(false))
            }
        } else {
            OnlineRecognizer(config = config(false))
        }
        hotwordsEnabled = enabled
    }

    fun start() {
        stream?.release()
        stream = recognizer.createStream()
    }

    /** 送入一批采样，返回本次产生的中间/最终结果 */
    fun accept(samples: FloatArray): List<AsrEvent> {
        val s = stream ?: return emptyList()
        s.acceptWaveform(samples, AudioCaptureSampleRate)
        val out = mutableListOf<AsrEvent>()
        while (recognizer.isReady(s)) {
            recognizer.decode(s)
            val endpoint = recognizer.isEndpoint(s)
            val text = recognizer.getResult(s).text.trim()
            if (endpoint) {
                if (text.isNotEmpty()) out += AsrEvent.Final(text)
                recognizer.reset(s)
            } else if (text.isNotEmpty()) {
                out += AsrEvent.Partial(text)
            }
        }
        return out
    }

    /** 停止时冲刷尾部音频，把最后一句定稿 */
    fun finish(): List<AsrEvent> {
        val s = stream ?: return emptyList()
        s.acceptWaveform(FloatArray(16000 / 2), AudioCaptureSampleRate) // 0.5s 静音尾部
        val out = mutableListOf<AsrEvent>()
        while (recognizer.isReady(s)) {
            recognizer.decode(s)
        }
        val text = recognizer.getResult(s).text.trim()
        if (text.isNotEmpty()) out += AsrEvent.Final(text)
        s.release()
        stream = null
        return out
    }

    fun release() {
        stream?.release()
        stream = null
        recognizer.release()
    }

    companion object {
        private const val AudioCaptureSampleRate = 16000
        const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER = "decoder-epoch-99-avg-1.onnx"
        const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    }
}
