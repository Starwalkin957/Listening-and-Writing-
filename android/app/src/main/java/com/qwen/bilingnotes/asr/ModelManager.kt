package com.qwen.bilingnotes.asr

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型管理：
 * - 核心 ASR 模型：首次使用联网下载（hf-mirror 优先），之后完全离线；
 * - 声纹模型 / 标点模型：可选增强，下载失败自动跳过，不影响核心识别。
 */
object ModelManager {

    const val MODEL_DIR_NAME = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
    const val SPEAKER_FILE = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    private const val SPEAKER_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$SPEAKER_FILE"
    private const val PUNCT_TAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/" +
            "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"

    private val ASR_FILES = listOf(
        AsrEngine.ENCODER,
        AsrEngine.DECODER,
        AsrEngine.JOINER,
        "tokens.txt",
        "bpe.model",
    )

    private val ASR_BASES = listOf(
        "https://hf-mirror.com/csukuangfj/$MODEL_DIR_NAME/resolve/main/",
        "https://huggingface.co/csukuangfj/$MODEL_DIR_NAME/resolve/main/",
    )

    private val GH_PROXIES = listOf("", "https://ghproxy.net/")

    fun modelDir(context: Context): File = File(context.filesDir, "models/$MODEL_DIR_NAME")
    fun speakerModel(context: Context): File = File(context.filesDir, "models/speaker/$SPEAKER_FILE")
    fun punctModel(context: Context): File = File(context.filesDir, "models/punct/model.int8.onnx")

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return ASR_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }
    }

    /** 阻塞下载核心模型，请在 IO 线程调用 */
    fun ensureModels(context: Context, onProgress: (String) -> Unit): File {
        val dir = modelDir(context)
        dir.mkdirs()
        for (name in ASR_FILES) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            var ok = false
            var lastErr: Exception? = null
            for (base in ASR_BASES) {
                try {
                    onProgress("正在下载 $name …")
                    download(base + name, target) { pct -> onProgress("正在下载 $name  $pct%") }
                    ok = true
                    break
                } catch (e: Exception) {
                    lastErr = e
                    target.delete()
                }
            }
            if (!ok) throw IllegalStateException(
                "模型文件 $name 下载失败（可改用电脑脚本预置）：${lastErr?.message}", lastErr
            )
        }
        return dir
    }

    /** 可选：声纹模型（约 28MB）。失败返回 null，说话人区分自动禁用 */
    fun ensureSpeakerModel(context: Context, onProgress: (String) -> Unit): File? {
        val target = speakerModel(context)
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        for (proxy in GH_PROXIES) {
            try {
                onProgress("正在下载声纹模型（说话人区分）…")
                download(proxy + SPEAKER_URL, target) { pct ->
                    onProgress("正在下载声纹模型  $pct%")
                }
                return target
            } catch (_: Exception) {
                target.delete()
            }
        }
        return null
    }

    /** 可选：标点模型（int8 约 65MB tar.bz2，解出 model.int8.onnx）。失败返回 null */
    fun ensurePunctModel(context: Context, onProgress: (String) -> Unit): File? {
        val target = punctModel(context)
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        val tar = File(target.parentFile, "punct.tar.bz2")
        for (proxy in GH_PROXIES) {
            try {
                onProgress("正在下载标点模型…")
                download(proxy + PUNCT_TAR_URL, tar) { pct -> onProgress("正在下载标点模型  $pct%") }
                onProgress("正在解压标点模型…")
                extractModelFromTar(tar, target)
                tar.delete()
                return target
            } catch (_: Exception) {
                tar.delete()
                target.delete()
            }
        }
        return null
    }

    private fun extractModelFromTar(tar: File, target: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(tar.inputStream().buffered())).use { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith("model.int8.onnx")) {
                    FileOutputStream(target).use { out -> tis.copyTo(out) }
                    return
                }
                entry = tis.nextEntry
            }
        }
        throw IllegalStateException("压缩包中未找到 model.int8.onnx")
    }

    private fun download(url: String, target: File, onPct: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "BilingNotes/0.1")
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onPct(pct)
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IllegalStateException("无法写入 ${target.path}")
        } finally {
            conn.disconnect()
        }
    }

    fun hotwordsFile(context: Context): File {
        val out = File(context.filesDir, "hotwords_cs.txt")
        if (!out.exists()) {
            context.assets.open("hotwords_cs.txt").use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
        }
        return out
    }
}
