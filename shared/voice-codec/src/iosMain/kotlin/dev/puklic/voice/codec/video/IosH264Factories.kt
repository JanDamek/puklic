package dev.puklic.voice.codec.video

/**
 * iOS `actual` factory for [H264Encoder] via VideoToolbox `VTCompressionSession`.
 *
 * See [IosH264Encoder] for behaviour, threading and Annex-B output format details.
 */
public object IosH264EncoderFactory : H264EncoderFactory {
    override fun create(
        width: Int,
        height: Int,
        bitrateKbps: Int,
        framerate: Int,
    ): H264Encoder = IosH264Encoder(
        width = width,
        height = height,
        bitrateKbps = bitrateKbps,
        framerate = framerate,
    )
}

/**
 * iOS `actual` factory for [H264Decoder] via VideoToolbox `VTDecompressionSession`.
 *
 * See [IosH264Decoder] for behaviour, SPS/PPS handshake and BGRA → ARGB output details.
 */
public object IosH264DecoderFactory : H264DecoderFactory {
    override fun create(width: Int, height: Int): H264Decoder = IosH264Decoder(
        width = width,
        height = height,
    )
}
