package com.qwen.bilingnotes.speaker

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import java.io.File

/**
 * 端侧说话人区分：3D-Speaker 声纹嵌入 + 在线注册/检索。
 * 每句话结束时用该句音频计算声纹，与已注册说话人比对（余弦相似度阈值 0.6），
 * 未命中则自动注册为「说话人N」。全程离线。
 */
class SpeakerEngine(modelFile: File) {

    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(
            model = modelFile.absolutePath,
            numThreads = 1,
            debug = false,
            provider = "cpu",
        )
    )
    private val manager = SpeakerEmbeddingManager(extractor.dim())
    private var counter = 0

    /** samples 为整句 16kHz 音频；过短（<0.6s）时返回空串表示无法判定 */
    fun identify(samples: FloatArray): String {
        if (samples.size < 16000 * 6 / 10) return ""
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            val emb = extractor.compute(stream)
            var name = manager.search(emb, THRESHOLD)
            if (name.isEmpty()) {
                counter++
                name = "说话人$counter"
                manager.add(name, emb)
            }
            name
        } catch (_: Throwable) {
            ""
        } finally {
            stream.release()
        }
    }

    fun release() {
        manager.release()
        extractor.release()
    }

    companion object {
        private const val THRESHOLD = 0.6f
    }
}
