package com.qwen.bilingnotes.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM 接入：OpenAI 兼容 Chat Completions 协议。
 * 可指向云端（如阿里百炼兼容模式）、局域网自建服务（ollama / llama.cpp server / vLLM）等。
 * 未配置时翻译/总结/纠错功能自动禁用，识别本身不受影响（识别始终离线）。
 */
data class LlmSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "qwen-plus",
    val autoTranslate: Boolean = false,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

object LlmPrefs {
    private const val FILE = "llm_settings"

    fun load(context: Context): LlmSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return LlmSettings(
            baseUrl = p.getString("baseUrl", "") ?: "",
            apiKey = p.getString("apiKey", "") ?: "",
            model = p.getString("model", "qwen-plus") ?: "qwen-plus",
            autoTranslate = p.getBoolean("autoTranslate", false),
        )
    }

    fun save(context: Context, s: LlmSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("baseUrl", s.baseUrl.trim().trimEnd('/'))
            .putString("apiKey", s.apiKey.trim())
            .putString("model", s.model.trim())
            .putBoolean("autoTranslate", s.autoTranslate)
            .apply()
    }
}

class LlmClient(private val settings: LlmSettings) {

    /** 阻塞调用，请在 IO 线程使用 */
    fun chat(system: String, user: String, maxTokens: Int = 2048): String {
        require(settings.configured) { "未配置 LLM 服务" }
        val body = JSONObject()
            .put("model", settings.model)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))

        val conn = URL(settings.baseUrl.trimEnd('/') + "/chat/completions")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (settings.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("LLM HTTP $code: ${text.take(300)}")
            }
            val root = JSONObject(text)
            return root.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } finally {
            conn.disconnect()
        }
    }

    fun translate(text: String): String = chat(
        system = "你是专业的同声传译引擎，精通中英互译与计算机专业术语。" +
            "判断输入语言：以中文为主则译为英文，以英文为主则译为中文。" +
            "只输出译文，不要解释，不要添加任何前后缀。",
        user = text,
        maxTokens = 1024,
    )

    fun summarize(transcript: String, glossaryContext: String): String = chat(
        system = "你是会议纪要助手。根据转写文本输出结构化纪要，包含：会议要点、关键结论、待办事项（含负责人若可推断）。" +
            "忠实于原文，不编造。转写可能含语音识别错误，请结合术语表理解。\n$glossaryContext",
        user = transcript,
        maxTokens = 2048,
    )

    fun correct(transcript: String, glossaryContext: String): String = chat(
        system = "你是语音转写校对助手，精通计算机专业术语。请修正转写文本中的同音错字与术语错误" +
            "（如把「康威定律」误识别之类），保持原意与说话人标记不变，输出修正后的完整文本。" +
            "不要总结、不要解释。\n$glossaryContext",
        user = transcript,
        maxTokens = 4096,
    )

    fun ping(): String = chat("你是连接测试助手。", "请只回复：OK", maxTokens = 8)
}
