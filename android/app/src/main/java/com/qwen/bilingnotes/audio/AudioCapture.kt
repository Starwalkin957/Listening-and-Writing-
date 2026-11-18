package com.qwen.bilingnotes.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 麦克风采集：16kHz 单声道 PCM Float。
 * start() 为阻塞调用，请在 IO 线程运行；stop() 可从任意线程调用。
 */
class AudioCapture {

    @Volatile
    private var running = false
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(onSamples: (FloatArray) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        require(minBuf > 0) { "设备不支持 16kHz PCM Float 采集" }

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            minBuf * 4,
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        record = rec

        // 每次读 100ms，保证实时上屏延迟
        val chunk = FloatArray(SAMPLE_RATE / 10)
        running = true
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    onSamples(if (n == chunk.size) chunk.copyOf() else chunk.copyOf(n))
                } else if (n < 0) {
                    break
                }
            }
        } finally {
            try {
                rec.stop()
            } catch (_: IllegalStateException) {
            }
            rec.release()
            record = null
            running = false
        }
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
