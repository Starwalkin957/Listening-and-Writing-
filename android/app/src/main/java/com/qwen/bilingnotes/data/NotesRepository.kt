package com.qwen.bilingnotes.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 纪要库：JSON 文件持久化（个人/小团队规模足够），原子写入。
 * 备份导出为 zip（内含 db.json），导入支持 zip 或裸 json，按合并策略入库。
 */
class NotesRepository(private val context: Context) {

    private val dbFile = File(context.filesDir, "notes/db.json")
    private val lock = Any()

    var nextId: Long = 1
        private set
    val series = mutableListOf<Series>()
    val sessions = mutableListOf<Session>()
    val speakers = mutableListOf<SpeakerInfo>()

    init {
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) {
            try {
                parse(JSONObject(dbFile.readText()))
            } catch (_: Exception) {
                // 损坏时不覆盖原文件，先备份再重建
                dbFile.renameTo(File(dbFile.parentFile, "db.corrupt.${System.currentTimeMillis()}.json"))
            }
        }
        if (series.isEmpty()) {
            series += Series(newId(), "默认系列")
            save()
        }
    }

    private fun newId(): Long = nextId++

    private fun parse(root: JSONObject) {
        nextId = root.optLong("nextId", 1)
        series.clear(); sessions.clear(); speakers.clear()
        val ss = root.optJSONArray("series") ?: JSONArray()
        for (i in 0 until ss.length()) {
            val o = ss.getJSONObject(i)
            series += Series(o.getLong("id"), o.getString("name"))
        }
        val sp = root.optJSONArray("speakers") ?: JSONArray()
        for (i in 0 until sp.length()) {
            val o = sp.getJSONObject(i)
            speakers += SpeakerInfo(o.getString("id"), o.getString("name"))
        }
        val se = root.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until se.length()) {
            val o = se.getJSONObject(i)
            val s = Session(
                id = o.getLong("id"),
                title = o.getString("title"),
                seriesId = o.optLong("seriesId", 0),
                createdAt = o.getLong("createdAt"),
                summary = o.optString("summary", ""),
                corrected = o.optString("corrected", ""),
            )
            val us = o.optJSONArray("utterances") ?: JSONArray()
            for (j in 0 until us.length()) {
                val u = us.getJSONObject(j)
                s.utterances += Utterance(
                    u.getLong("id"), u.optString("speaker", ""),
                    u.getString("text"), u.getLong("ts"), u.optString("translation", ""),
                )
            }
            val at = o.optJSONArray("attachments") ?: JSONArray()
            for (j in 0 until at.length()) {
                val a = at.getJSONObject(j)
                s.attachments += Attachment(
                    a.getLong("id"), a.getString("name"), a.getString("kind"), a.getString("text"),
                )
            }
            sessions += s
        }
    }

    private fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("nextId", nextId)
        root.put("series", JSONArray().apply {
            series.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        root.put("speakers", JSONArray().apply {
            speakers.forEach { put(JSONObject().put("id", it.id).put("name", it.displayName)) }
        })
        root.put("sessions", JSONArray().apply {
            sessions.forEach { s ->
                put(JSONObject()
                    .put("id", s.id).put("title", s.title).put("seriesId", s.seriesId)
                    .put("createdAt", s.createdAt).put("summary", s.summary).put("corrected", s.corrected)
                    .put("utterances", JSONArray().apply {
                        s.utterances.forEach { u ->
                            put(JSONObject().put("id", u.id).put("speaker", u.speaker)
                                .put("text", u.text).put("ts", u.ts).put("translation", u.translation))
                        }
                    })
                    .put("attachments", JSONArray().apply {
                        s.attachments.forEach { a ->
                            put(JSONObject().put("id", a.id).put("name", a.name)
                                .put("kind", a.kind).put("text", a.text))
                        }
                    }))
            }
        })
        return root
    }

    fun save() = synchronized(lock) {
        val tmp = File(dbFile.parentFile, "db.json.tmp")
        tmp.writeText(toJson().toString())
        tmp.renameTo(dbFile)
    }

    fun createSession(seriesId: Long): Session {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val s = Session(newId(), "纪要 ${fmt.format(Date())}", seriesId, System.currentTimeMillis())
        sessions += s
        save()
        return s
    }

    fun session(id: Long): Session? = sessions.find { it.id == id }

    fun deleteSession(id: Long) {
        sessions.removeAll { it.id == id }
        save()
    }

    fun addSeries(name: String): Series {
        val s = Series(newId(), name)
        series += s
        save()
        return s
    }

    fun seriesName(id: Long): String = series.find { it.id == id }?.name ?: "未分组"

    fun addUtterance(sessionId: Long, speaker: String, text: String): Utterance? {
        val s = session(sessionId) ?: return null
        val u = Utterance(newId(), speaker, text, System.currentTimeMillis())
        s.utterances += u
        save()
        return u
    }

    fun updateUtterance(sessionId: Long, utteranceId: Long, block: (Utterance) -> Unit) {
        val u = session(sessionId)?.utterances?.find { it.id == utteranceId } ?: return
        block(u)
        save()
    }

    fun addAttachment(sessionId: Long, name: String, kind: String, text: String) {
        val s = session(sessionId) ?: return
        s.attachments += Attachment(newId(), name, kind, text)
        save()
    }

    fun speakerDisplayName(id: String): String {
        if (id.isEmpty()) return ""
        return speakers.find { it.id == id }?.displayName ?: id
    }

    fun setSpeakerName(id: String, name: String) {
        val existing = speakers.find { it.id == id }
        if (existing != null) existing.displayName = name else speakers += SpeakerInfo(id, name)
        save()
    }

    fun encounteredSpeakers(): List<String> =
        sessions.flatMap { it.utterances.map { u -> u.speaker } }.filter { it.isNotEmpty() }.distinct()

    // ---------- 备份 ----------

    fun exportBackup(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("db.json"))
            zip.write(toJson().toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("双语语音纪要备份文件。在应用「设置-导入备份」中恢复。".toByteArray())
            zip.closeEntry()
        }
    }

    /** 返回导入的会话数 */
    fun importBackup(input: InputStream): Int {
        val bytes = input.readBytes()
        val jsonText = if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            var text: String? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == "db.json") {
                        text = zip.readBytes().decodeToString()
                        break
                    }
                    e = zip.nextEntry
                }
            }
            text ?: throw IllegalArgumentException("备份包中没有 db.json")
        } else {
            bytes.decodeToString()
        }

        val imported = JSONObject(jsonText)
        val se = imported.optJSONArray("sessions") ?: JSONArray()
        val importedSeries = imported.optJSONArray("series") ?: JSONArray()
        val seriesIdMap = mutableMapOf<Long, Long>()
        for (i in 0 until importedSeries.length()) {
            val o = importedSeries.getJSONObject(i)
            val oldId = o.getLong("id")
            val name = o.getString("name")
            val existing = series.find { it.name == name }
            seriesIdMap[oldId] = existing?.id ?: addSeries(name).id
        }
        val importedSpeakers = imported.optJSONArray("speakers") ?: JSONArray()
        for (i in 0 until importedSpeakers.length()) {
            val o = importedSpeakers.getJSONObject(i)
            val id = o.getString("id")
            if (speakers.none { it.id == id }) speakers += SpeakerInfo(id, o.getString("name"))
        }

        var count = 0
        for (i in 0 until se.length()) {
            val o = se.getJSONObject(i)
            val s = Session(
                newId(), o.getString("title"),
                seriesIdMap[o.optLong("seriesId", 0)] ?: series.first().id,
                o.getLong("createdAt"),
                summary = o.optString("summary", ""), corrected = o.optString("corrected", ""),
            )
            val us = o.optJSONArray("utterances") ?: JSONArray()
            for (j in 0 until us.length()) {
                val u = us.getJSONObject(j)
                s.utterances += Utterance(
                    newId(), u.optString("speaker", ""), u.getString("text"),
                    u.getLong("ts"), u.optString("translation", ""),
                )
            }
            val at = o.optJSONArray("attachments") ?: JSONArray()
            for (j in 0 until at.length()) {
                val a = at.getJSONObject(j)
                s.attachments += Attachment(newId(), a.getString("name"), a.getString("kind"), a.getString("text"))
            }
            sessions += s
            count++
        }
        save()
        return count
    }

    fun sessionMarkdown(id: Long): String {
        val s = session(id) ?: return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("# ${s.title}")
        sb.appendLine()
        sb.appendLine("- 系列：${seriesName(s.seriesId)}")
        sb.appendLine("- 时间：${fmt.format(Date(s.createdAt))}")
        sb.appendLine()
        if (s.summary.isNotEmpty()) {
            sb.appendLine("## 内容总结")
            sb.appendLine()
            sb.appendLine(s.summary)
            sb.appendLine()
        }
        sb.appendLine("## 转写记录")
        sb.appendLine()
        for (u in s.utterances) {
            val sp = speakerDisplayName(u.speaker)
            sb.appendLine(if (sp.isNotEmpty()) "**$sp**：${u.text}" else u.text)
            if (u.translation.isNotEmpty()) sb.appendLine("> ${u.translation}")
            sb.appendLine()
        }
        if (s.corrected.isNotEmpty()) {
            sb.appendLine("## 术语纠错稿")
            sb.appendLine()
            sb.appendLine(s.corrected)
            sb.appendLine()
        }
        for (a in s.attachments) {
            sb.appendLine("## 附件：${a.name}")
            sb.appendLine()
            sb.appendLine(a.text)
            sb.appendLine()
        }
        return sb.toString()
    }
}
