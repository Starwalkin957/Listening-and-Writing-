package com.qwen.bilingnotes.llm

import android.content.Context
import org.json.JSONObject

/**
 * 计算机专业知识库（术语表）：
 * 1. 供 LLM 总结/纠错时注入相关术语释义，减少专业词误判；
 * 2. 与识别热词表（hotwords_cs.txt）配合，形成「识别端热词 + 理解端知识」双层术语保障。
 */
object KnowledgeBase {

    private var glossary: Map<String, String> = emptyMap()

    fun init(context: Context) {
        if (glossary.isNotEmpty()) return
        val text = context.assets.open("cs_glossary.json").bufferedReader().readText()
        val obj = JSONObject(text)
        val map = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            map[key] = obj.getString(key)
        }
        glossary = map
    }

    fun size(): Int = glossary.size

    /** 找出文本中出现的术语（英文不区分大小写） */
    fun relevantTerms(text: String): List<String> {
        val lower = text.lowercase()
        return glossary.keys.filter { term ->
            if (term.all { it.code < 128 }) lower.contains(term.lowercase()) else text.contains(term)
        }
    }

    /** 生成注入 LLM 提示词的术语上下文（最多 25 条，控制 token） */
    fun contextFor(text: String): String {
        val terms = relevantTerms(text).take(25)
        if (terms.isEmpty()) return ""
        return "相关计算机术语表：\n" + terms.joinToString("\n") { "- $it：${glossary[it]}" }
    }
}
