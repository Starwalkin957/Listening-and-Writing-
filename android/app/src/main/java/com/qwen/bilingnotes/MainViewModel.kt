package com.qwen.bilingnotes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwen.bilingnotes.asr.AsrEngine
import com.qwen.bilingnotes.asr.AsrEvent
import com.qwen.bilingnotes.asr.ModelManager
import com.qwen.bilingnotes.asr.PunctEngine
import com.qwen.bilingnotes.audio.AudioCapture
import com.qwen.bilingnotes.data.NotesRepository
import com.qwen.bilingnotes.data.Utterance
import com.qwen.bilingnotes.llm.KnowledgeBase
import com.qwen.bilingnotes.llm.LlmClient
import com.qwen.bilingnotes.llm.LlmPrefs
import com.qwen.bilingnotes.service.RecordingService
import com.qwen.bilingnotes.speaker.SpeakerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { IDLE, DOWNLOADING, LOADING, RECORDING, FAILED }

data class RecordUiState(
    val phase: Phase = Phase.IDLE,
    val statusText: String = "点击「开始记录」。首次使用需联网下载模型，之后识别完全离线。",
    val utterances: List<Utterance> = emptyList(),
    val partial: String = "",
    val hotwordsEnabled: Boolean = false,
    val speakerEnabled: Boolean = false,
    val punctEnabled: Boolean = false,
    val autoTranslate: Boolean = false,
    val llmConfigured: Boolean = false,
    val sessionId: Long = 0,
    val seriesId: Long = 0,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state

    val repo = NotesRepository(app)

    private var engine: AsrEngine? = null
    private var speakerEngine: SpeakerEngine? = null
    private var punctEngine: PunctEngine? = null
    private var capture: AudioCapture? = null
    private var job: Job? = null

    // 当前句音频缓冲（供声纹计算），上限约 30 秒
    private val audioBuffer = ArrayDeque<FloatArray>()
    private var bufferedSamples = 0

    init {
        KnowledgeBase.init(app)
        val s = LlmPrefs.load(app)
        _state.update {
            it.copy(
                autoTranslate = s.autoTranslate,
                llmConfigured = s.configured,
                seriesId = repo.series.first().id,
            )
        }
    }

    fun refreshLlmState() {
        val s = LlmPrefs.load(getApplication())
        _state.update { it.copy(autoTranslate = s.autoTranslate, llmConfigured = s.configured) }
    }

    fun setSeries(id: Long) = _state.update { it.copy(seriesId = id) }

    fun setAutoTranslate(on: Boolean) {
        val app = getApplication<Application>()
        val s = LlmPrefs.load(app)
        LlmPrefs.save(app, s.copy(autoTranslate = on))
        _state.update { it.copy(autoTranslate = on) }
    }

    fun renameSpeaker(id: String, name: String) {
        repo.setSpeakerName(id, name)
        // 触发界面刷新
        _state.update { it.copy(utterances = it.utterances.toList()) }
    }

    fun speakerName(id: String): String = repo.speakerDisplayName(id)

    fun toggle() {
        if (_state.value.phase == Phase.RECORDING) stop() else start()
    }

    private fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                if (!ModelManager.isReady(app)) {
                    _state.update { it.copy(phase = Phase.DOWNLOADING, error = null) }
                    withContext(Dispatchers.IO) {
                        ModelManager.ensureModels(app) { msg ->
                            _state.update { it.copy(statusText = msg) }
                        }
                    }
                }

                _state.update { it.copy(phase = Phase.LOADING, statusText = "正在加载识别引擎…") }

                // 可选增强：声纹 / 标点（失败自动跳过）
                val speakerFile = withContext(Dispatchers.IO) {
                    ModelManager.ensureSpeakerModel(app) { msg ->
                        _state.update { it.copy(statusText = msg) }
                    }
                }
                val punctFile = withContext(Dispatchers.IO) {
                    ModelManager.ensurePunctModel(app) { msg ->
                        _state.update { it.copy(statusText = msg) }
                    }
                }

                val hotwords = withContext(Dispatchers.IO) { ModelManager.hotwordsFile(app) }
                val eng = withContext(Dispatchers.IO) { AsrEngine(ModelManager.modelDir(app), hotwords) }
                engine = eng
                speakerEngine = speakerFile?.let {
                    withContext(Dispatchers.IO) {
                        try { SpeakerEngine(it) } catch (_: Throwable) { null }
                    }
                }
                punctEngine = punctFile?.let {
                    withContext(Dispatchers.IO) { PunctEngine.tryCreate(it) }
                }

                val session = repo.createSession(_state.value.seriesId)
                audioBuffer.clear()
                bufferedSamples = 0

                _state.update {
                    it.copy(
                        phase = Phase.RECORDING,
                        sessionId = session.id,
                        utterances = emptyList(),
                        partial = "",
                        hotwordsEnabled = eng.hotwordsEnabled,
                        speakerEnabled = speakerEngine != null,
                        punctEnabled = punctEngine != null,
                        statusText = buildString {
                            append("正在记录（中英混说")
                            if (eng.hotwordsEnabled) append(" · 术语热词")
                            if (speakerEngine != null) append(" · 说话人区分")
                            if (punctEngine != null) append(" · 自动标点")
                            append("）")
                        },
                    )
                }

                RecordingService.start(app)

                val cap = AudioCapture()
                capture = cap
                withContext(Dispatchers.IO) {
                    eng.start()
                    cap.start { samples ->
                        bufferAudio(samples)
                        for (ev in eng.accept(samples)) {
                            when (ev) {
                                is AsrEvent.Partial ->
                                    _state.update { it.copy(partial = ev.text) }
                                is AsrEvent.Final -> handleFinal(ev.text)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                RecordingService.stop(app)
                _state.update {
                    it.copy(
                        phase = Phase.FAILED,
                        error = e.message ?: e.javaClass.simpleName,
                        statusText = "启动失败，可重试。",
                    )
                }
            }
        }
    }

    private fun bufferAudio(samples: FloatArray) {
        audioBuffer.addLast(samples)
        bufferedSamples += samples.size
        val cap = 16000 * 30
        while (bufferedSamples > cap && audioBuffer.isNotEmpty()) {
            bufferedSamples -= audioBuffer.removeFirst().size
        }
    }

    /** 在 IO 线程调用：定稿一句 → 标点 → 声纹 → 入库 → 可选同传 */
    private fun handleFinal(rawText: String) {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L) return

        val text = punctEngine?.addPunctuation(rawText) ?: rawText

        // 取最近 ≤15 秒音频算声纹
        val recent = ArrayList<FloatArray>()
        var n = 0
        for (chunk in audioBuffer.asReversed()) {
            recent.add(0, chunk)
            n += chunk.size
            if (n >= 16000 * 15) break
        }
        audioBuffer.clear()
        bufferedSamples = 0
        val flat = FloatArray(n)
        var off = 0
        for (c in recent) {
            System.arraycopy(c, 0, flat, off, c.size)
            off += c.size
        }
        val speaker = speakerEngine?.identify(flat) ?: ""

        val u = repo.addUtterance(sessionId, speaker, text) ?: return
        _state.update { it.copy(utterances = it.utterances + u, partial = "") }

        val settings = LlmPrefs.load(getApplication())
        if (settings.autoTranslate && settings.configured) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val tr = LlmClient(settings).translate(text)
                    repo.updateUtterance(sessionId, u.id) { it.translation = tr }
                    _state.update { s ->
                        s.copy(utterances = s.utterances.map {
                            if (it.id == u.id) it.copy(translation = tr) else it
                        })
                    }
                } catch (_: Exception) {
                    // 翻译失败不影响转写
                }
            }
        }
    }

    fun stop() {
        capture?.stop()
        capture = null
        val eng = engine
        engine = null
        val sessionId = _state.value.sessionId
        if (eng != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    for (ev in eng.finish()) {
                        if (ev is AsrEvent.Final) handleFinal(ev.text)
                    }
                } finally {
                    eng.release()
                    speakerEngine?.release()
                    speakerEngine = null
                    punctEngine?.release()
                    punctEngine = null
                }
            }
        }
        job?.cancel()
        job = null
        RecordingService.stop(getApplication())
        _state.update {
            it.copy(
                phase = Phase.IDLE,
                partial = "",
                statusText = if (sessionId != 0L) "已停止，纪要已自动保存到纪要库。" else "已停止。",
            )
        }
    }

    override fun onCleared() {
        capture?.stop()
        engine?.release()
        speakerEngine?.release()
        punctEngine?.release()
        RecordingService.stop(getApplication())
        super.onCleared()
    }
}
