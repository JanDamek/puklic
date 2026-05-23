package dev.puklic.ui.components

import dev.puklic.domain.Attachment
import dev.puklic.ids.AttachmentId
import io.kotest.matchers.shouldBe
import kotlin.test.Test

private fun att(
    filename: String = "x.bin",
    contentType: String? = null,
): Attachment = Attachment(
    id = AttachmentId(1L),
    filename = filename,
    size = 100L,
    url = "https://cdn.example/x",
    proxyUrl = "https://media.example/x",
    contentType = contentType,
    width = null,
    height = null,
    durationSecs = null,
    description = null,
)

class AttachmentRendererTest {

    @Test
    fun `image content type classifies as image`() {
        att(contentType = "image/png").kind() shouldBe AttachmentKind.Image
        att(contentType = "image/gif").kind() shouldBe AttachmentKind.Image
        att(contentType = "image/jpeg").kind() shouldBe AttachmentKind.Image
    }

    @Test
    fun `video content type classifies as video`() {
        att(contentType = "video/mp4").kind() shouldBe AttachmentKind.Video
        att(contentType = "video/webm").kind() shouldBe AttachmentKind.Video
    }

    @Test
    fun `image extension without content type classifies as image`() {
        att(filename = "photo.jpg").kind() shouldBe AttachmentKind.Image
        att(filename = "PIC.PNG").kind() shouldBe AttachmentKind.Image
        att(filename = "anim.gif").kind() shouldBe AttachmentKind.Image
        att(filename = "x.webp").kind() shouldBe AttachmentKind.Image
    }

    @Test
    fun `video extension without content type classifies as video`() {
        att(filename = "clip.webm").kind() shouldBe AttachmentKind.Video
        att(filename = "movie.mp4").kind() shouldBe AttachmentKind.Video
        att(filename = "x.mov").kind() shouldBe AttachmentKind.Video
        att(filename = "x.mkv").kind() shouldBe AttachmentKind.Video
    }

    @Test
    fun `audio classifies as file in v1`() {
        att(contentType = "audio/mpeg", filename = "song.mp3").kind() shouldBe AttachmentKind.File
    }

    @Test
    fun `pdf classifies as file`() {
        att(contentType = "application/pdf", filename = "x.pdf").kind() shouldBe AttachmentKind.File
    }

    @Test
    fun `unknown extension and unknown content type classifies as file`() {
        att(filename = "data.xyz").kind() shouldBe AttachmentKind.File
        att(filename = "noext").kind() shouldBe AttachmentKind.File
        att(contentType = "application/octet-stream", filename = "x.bin").kind() shouldBe AttachmentKind.File
    }

    @Test
    fun `formatFileSize handles boundaries`() {
        formatFileSize(0L) shouldBe "0 B"
        formatFileSize(1023L) shouldBe "1023 B"
        formatFileSize(1024L) shouldBe "1.0 KB"
        formatFileSize(1024L * 1024) shouldBe "1.0 MB"
        formatFileSize(1024L * 1024 * 1024) shouldBe "1.0 GB"
    }
}
