package com.qwen.bilingnotes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwen.bilingnotes.llm.KnowledgeBase
import com.qwen.bilingnotes.llm.LlmClient
import com.qwen.bilingnotes.llm.LlmPrefs
import com.qwen.bilingnotes.llm.LlmSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: LlmSettings = LlmSettings(),
    val glossarySize: Int = 0,
    val testing: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        KnowledgeBase.init(app)
        _state.update {
            it.copy(settings = LlmPrefs.load(app), glossarySize = KnowledgeBase.size())
        }
    }

    fun update(transform: (LlmSettings) -> LlmSettings) {
        _state.update { it.copy(settings = transform(it.settings)) }
    }

    fun save() {
        LlmPrefs.save(getApplication(), _state.value.settings)
        _state.update { it.copy(message = "已保存") }
    }

    fun testConnection() {
        val s = _state.value.settings
        if (!s.configured) {
            _state.update { it.copy(message = "请先填写服务地址与模型名") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testing = true, message = null) }
            try {
                val reply = withContext(Dispatchers.IO) { LlmClient(s).ping() }
                _state.update { it.copy(testing = false, message = "连接成功：$reply") }
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, message = "连接失败：${e.message}") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
