package dev.puklic.domain

import dev.puklic.ids.AttachmentId

data class Attachment(
    val id: AttachmentId,
    val filename: String,
    val size: Long,
    val url: String,
    val proxyUrl: String,
    val contentType: String?,
    val width: Int?,
    val height: Int?,
    val durationSecs: Float?,
    val description: String?,
)
