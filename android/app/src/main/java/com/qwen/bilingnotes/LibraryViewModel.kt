package com.qwen.bilingnotes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwen.bilingnotes.data.NotesRepository
import com.qwen.bilingnotes.data.Session
import com.qwen.bilingnotes.llm.KnowledgeBase
import com.qwen.bilingnotes.llm.LlmClient
import com.qwen.bilingnotes.llm.LlmPrefs
import com.qwen.bilingnotes.util.FileTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val sessions: List<Session> = emptyList(),
    val seriesOptions: List<Pair<Long, String>> = emptyList(),
    val filterSeriesId: Long = 0, // 0 = 全部
    val selected: Session? = null,
    val busyText: String? = null,
    val message: String? = null,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state

    // 与录音页共享同一份数据文件；各自实例通过重新加载保持同步
    private var repo = NotesRepository(app)

    fun refresh() {
        repo = NotesRepository(getApplication())
        val filter = _state.value.filterSeriesId
        val selectedId = _state.value.selected?.id
        _state.update {
            it.copy(
                sessions = repo.sessions.sortedByDescending { s -> s.createdAt }
                    .filter { s -> filter == 0L || s.seriesId == filter },
                seriesOptions = repo.series.map { s -> s.id to s.name },
                selected = selectedId?.let { id -> repo.session(id) },
            )
        }
    }

    fun setFilter(seriesId: Long) {
        _state.update { it.copy(filterSeriesId = seriesId) }
        refresh()
    }

    fun select(id: Long?) {
        _state.update { it.copy(selected = id?.let { sid -> repo.session(sid) }) }
    }

    fun deleteSession(id: Long) {
        repo.deleteSession(id)
        _state.update { it.copy(selected = null) }
        refresh()
    }

    fun renameSession(id: Long, title: String) {
        repo.session(id)?.let { it.title = title }
        repo.save()
        refresh()
    }

    fun assignSeries(sessionId: Long, seriesId: Long) {
        repo.session(sessionId)?.let { it.seriesId = seriesId }
        repo.save()
        refresh()
    }

    fun newSeries(name: String) {
        if (name.isBlank()) return
        repo.addSeries(name.trim())
        refresh()
    }

    fun speakerName(id: String): String = repo.speakerDisplayName(id)

    fun repoSeriesName(id: Long): String = repo.seriesName(id)

    private fun transcriptOf(s: Session): String = buildString {
        for (u in s.utterances) {
            val sp = repo.speakerDisplayName(u.speaker)
            appendLine(if (sp.isNotEmpty()) "$sp：${u.text}" else u.text)
        }
        for (a in s.attachments) {
            appendLine("【附件 ${a.name}】")
            appendLine(a.text.take(8000))
        }
    }

    private fun llmAction(sessionId: Long, label: String, block: (LlmClient, Session, String) -> Unit) {
        val app = getApplication<Application>()
        val settings = LlmPrefs.load(app)
        if (!settings.configured) {
            _state.update { it.copy(message = "请先在「设置」中配置 LLM 服务") }
            return
        }
        val s = repo.session(sessionId) ?: return
        if (s.utterances.isEmpty() && s.attachments.isEmpty()) {
            _state.update { it.copy(message = "该纪要还没有内容") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busyText = label, message = null) }
            try {
                withContext(Dispatchers.IO) {
                    KnowledgeBase.init(app)
                    val text = transcriptOf(s)
                    block(LlmClient(settings), s, KnowledgeBase.contextFor(text))
                }
                repo.save()
                refresh()
                _state.update { it.copy(busyText = null, message = "$label 完成") }
            } catch (e: Exception) {
                _state.update { it.copy(busyText = null, message = "$label 失败：${e.message}") }
            }
        }
    }

    fun summarize(sessionId: Long) = llmAction(sessionId, "生成总结") { client, s, ctx ->
        s.summary = client.summarize(transcriptOf(s), ctx)
    }

    fun correct(sessionId: Long) = llmAction(sessionId, "术语纠错") { client, s, ctx ->
        s.corrected = client.correct(transcriptOf(s), ctx)
    }

    fun translateAll(sessionId: Long) = llmAction(sessionId, "全文翻译") { client, s, _ ->
        for (u in s.utterances) {
            if (u.translation.isEmpty()) {
                u.translation = try {
                    client.translate(u.text)
                } catch (_: Exception) {
                    ""
                }
            }
        }
    }

    // ---------- 文件整合 ----------

    fun importFile(sessionId: Long, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _state.update { it.copy(busyText = "解析文件…") }
            try {
                val r = withContext(Dispatchers.IO) { FileTextExtractor.extract(app, uri) }
                repo.addAttachment(sessionId, r.name, r.kind, r.text)
                refresh()
                _state.update { it.copy(busyText = null, message = "已整合附件：${r.name}") }
            } catch (e: Exception) {
                _state.update { it.copy(busyText = null, message = "文件解析失败：${e.message}") }
            }
        }
    }

    // ---------- 备份 ----------

    fun exportBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { repo.exportBackup(it) }
                        ?: throw IllegalStateException("无法写入所选位置")
                }
                _state.update { it.copy(message = "备份已导出") }
            } catch (e: Exception) {
                _state.update { it.copy(message = "导出失败：${e.message}") }
            }
        }
    }

    fun importBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _state.update { it.copy(busyText = "导入备份…") }
            try {
                val n = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { repo.importBackup(it) }
                        ?: throw IllegalStateException("无法读取所选文件")
                }
                refresh()
                _state.update { it.copy(busyText = null, message = "已导入 $n 条纪要") }
            } catch (e: Exception) {
                _state.update { it.copy(busyText = null, message = "导入失败：${e.message}") }
            }
        }
    }

    fun exportSessionMarkdown(sessionId: Long, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val md = repo.sessionMarkdown(sessionId)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(md.toByteArray()) }
                        ?: throw IllegalStateException("无法写入所选位置")
                }
                _state.update { it.copy(message = "纪要已导出为 Markdown") }
            } catch (e: Exception) {
                _state.update { it.copy(message = "导出失败：${e.message}") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
