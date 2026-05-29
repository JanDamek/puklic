package dev.puklic.voice.codec.video

import dev.puklic.voice.transport.EncodedFrame
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Compile-only contract test: verifies the FP-2 KMP codec surface holds together —
 * [EncodedFrame] is constructible from commonMain, the encoder/decoder interfaces have
 * the agreed shape, and the factory `create` signatures compile against a fake impl.
 *
 * No real codec is exercised here; that's FP-5 (iOS VideoToolbox actuals + their tests).
 */
class H264InterfaceContractTest {

    @Test
    fun encodedFrame_isConstructibleFromCommon() {
        val frame = EncodedFrame(bytes = byteArrayOf(1, 2, 3), ts90k = 90, keyframe = true)
        frame.bytes.size shouldBe 3
        frame.ts90k shouldBe 90
        frame.keyframe shouldBe true
    }

    @Test
    fun encodedFrame_equalsAndHashCode_respectByteContent() {
        val a = EncodedFrame(byteArrayOf(1, 2, 3), ts90k = 7, keyframe = false)
        val b = EncodedFrame(byteArrayOf(1, 2, 3), ts90k = 7, keyframe = false)
        val c = EncodedFrame(byteArrayOf(1, 2, 4), ts90k = 7, keyframe = false)
        (a == b) shouldBe true
        a.hashCode() shouldBe b.hashCode()
        (a == c) shouldBe false
    }

    @Test
    fun encoderFactory_producesEncoderImplementingInterface() {
        val factory: H264EncoderFactory = FakeEncoderFactory
        val enc: H264Encoder = factory.create(width = 1280, height = 720, bitrateKbps = 2000, framerate = 30)
        val out = enc.encode(yuv420p = ByteArray(1280 * 720 * 3 / 2))
        out shouldBe null
        enc.close()
    }

    @Test
    fun decoderFactory_producesDecoderImplementingInterface() {
        val factory: H264DecoderFactory = FakeDecoderFactory
        val dec: H264Decoder = factory.create(width = 1280, height = 720)
        val pixels = dec.decode(annexBNalUnit = byteArrayOf(0x67.toByte(), 0x42))
        pixels shouldBe null
        dec.close()
    }

    private object FakeEncoderFactory : H264EncoderFactory {
        override fun create(width: Int, height: Int, bitrateKbps: Int, framerate: Int): H264Encoder =
            FakeEncoder
    }

    private object FakeEncoder : H264Encoder {
        override fun encode(yuv420p: ByteArray): EncodedFrame? = null
        override fun close() = Unit
    }

    private object FakeDecoderFactory : H264DecoderFactory {
        override fun create(width: Int, height: Int): H264Decoder = FakeDecoder
    }

    private object FakeDecoder : H264Decoder {
        override fun decode(annexBNalUnit: ByteArray): IntArray? = null
        override fun close() = Unit
    }
}
