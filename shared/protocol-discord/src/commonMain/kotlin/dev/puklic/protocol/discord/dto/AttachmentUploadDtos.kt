package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request to `POST /channels/{cid}/attachments` — Discord's pre-upload endpoint that
 * returns CDN URLs the client then PUTs raw bytes to. Verified against discord.js +
 * Discord-S.C.U.M reverse-engineering (issue #23).
 */
@Serializable
internal data class AttachmentUploadRequestDto(
    val files: List<AttachmentUploadFileDto>,
)

@Serializable
internal data class AttachmentUploadFileDto(
    val filename: String,
    @SerialName("file_size") val fileSize: Long,
    /** Client-assigned identifier so the upload response can be paired with the request entry. */
    val id: String,
)

/** Response from `POST /channels/{cid}/attachments`. */
@Serializable
internal data class AttachmentUploadResponseDto(
    val attachments: List<AttachmentUploadSlotDto>,
)

@Serializable
internal data class AttachmentUploadSlotDto(
    /** Echoed back from the request `id` field. */
    val id: Int,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("upload_filename") val uploadFilename: String,
)

/**
 * Entry placed into the final `POST /channels/{cid}/messages` body's `attachments` array.
 * `uploaded_filename` ties the message back to the CDN-staged file.
 */
@Serializable
internal data class FinalizedAttachmentDto(
    val id: String,
    val filename: String,
    @SerialName("uploaded_filename") val uploadedFilename: String,
)
