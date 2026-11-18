package com.qwen.bilingnotes.data

data class Utterance(
    val id: Long,
    val speaker: String,
    val text: String,
    val ts: Long,
    var translation: String = "",
)

data class Attachment(
    val id: Long,
    val name: String,
    val kind: String,
    val text: String,
)

data class Session(
    val id: Long,
    var title: String,
    var seriesId: Long,
    val createdAt: Long,
    val utterances: MutableList<Utterance> = mutableListOf(),
    val attachments: MutableList<Attachment> = mutableListOf(),
    var summary: String = "",
    var corrected: String = "",
)

data class Series(val id: Long, var name: String)

data class SpeakerInfo(val id: String, var displayName: String)
