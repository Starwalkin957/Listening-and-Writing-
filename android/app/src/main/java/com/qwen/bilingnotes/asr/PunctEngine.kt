package com.qwen.bilingnotes.asr

import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.io.File

/** 离线标点恢复（ct-transformer 中英模型），可选增强 */
class PunctEngine private constructor(private val punct: OfflinePunctuation) {

    fun addPunctuation(text: String): String = try {
        punct.addPunctuation(text)
    } catch (_: Throwable) {
        text
    }

    fun release() = punct.release()

    companion object {
        fun tryCreate(modelFile: File): PunctEngine? {
            if (!modelFile.exists()) return null
            return try {
                PunctEngine(
                    OfflinePunctuation(
                        config = OfflinePunctuationConfig(
                            model = OfflinePunctuationModelConfig(
                                ctTransformer = modelFile.absolutePath,
                                numThreads = 1,
                                provider = "cpu",
                            )
                        )
                    )
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
