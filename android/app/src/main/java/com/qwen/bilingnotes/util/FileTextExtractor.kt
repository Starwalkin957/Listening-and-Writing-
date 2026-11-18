package com.qwen.bilingnotes.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.zip.ZipInputStream

/**
 * 附件文本提取：txt / md 直读；docx 解析 word/document.xml；pdf 用 PDFBox。
 * 提取出的纯文本随纪要一起入库，可参与 LLM 总结。
 */
object FileTextExtractor {

    data class Result(val name: String, val kind: String, val text: String)

    fun extract(context: Context, uri: Uri): Result {
        PDFBoxResourceLoader.init(context)
        val name = displayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取文件")

        return when (ext) {
            "txt", "md", "markdown", "csv", "json", "log" ->
                Result(name, ext, bytes.decodeToString())

            "docx" -> Result(name, "docx", extractDocx(bytes))

            "pdf" -> {
                val text = PDDocument.load(bytes.inputStream()).use { doc ->
                    PDFTextStripper().getText(doc)
                }
                Result(name, "pdf", text)
            }

            else -> throw IllegalArgumentException("暂不支持 .$ext 文件（支持 txt/md/docx/pdf）")
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun extractDocx(bytes: ByteArray): String {
        var xml: String? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    xml = zip.readBytes().decodeToString()
                    break
                }
                e = zip.nextEntry
            }
        }
        val doc = xml ?: throw IllegalArgumentException("不是有效的 docx 文件")
        val sb = StringBuilder()
        // 段落结束转换行；抽取 <w:t> 文本
        val paraSplit = doc.split("</w:p>")
        for (para in paraSplit) {
            val texts = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(para).map { it.groupValues[1] }.joinToString("")
            val decoded = texts
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
            if (decoded.isNotBlank()) sb.appendLine(decoded)
        }
        return sb.toString()
    }
}
