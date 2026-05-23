package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource
import org.bytedeco.ffmpeg.avdevice.AVDeviceInfoList
import org.bytedeco.ffmpeg.global.avdevice.avdevice_free_list_devices
import org.bytedeco.ffmpeg.global.avdevice.avdevice_list_input_sources
import org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all
import org.bytedeco.ffmpeg.global.avformat.av_find_input_format
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer

/**
 * In-process avfoundation monitor enumeration via libavdevice's
 * `avdevice_list_input_sources`. Replaces the subprocess `ffmpeg -list_devices true` parser
 * (Phase 2 of self-contained Linux refactor — see
 * `docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md` §3).
 *
 * Some libavdevice inputs (notably older avfoundation builds) return -ENOSYS for this API.
 * Callers should treat an empty result as "use fallback" and try the legacy subprocess parser.
 */
internal object LibavMonitorEnumerator {

    /** Best-effort listing. Returns empty list if libavdevice declines (-ENOSYS, etc.). */
    fun listMonitors(inputFormat: String): List<ScreenSource.Monitor> {
        avdevice_register_all()
        val ifmt = av_find_input_format(inputFormat) ?: return emptyList()

        val listHolder = PointerPointer<AVDeviceInfoList>(1L)
        val rc = avdevice_list_input_sources(ifmt, null as BytePointer?, null, listHolder)
        if (rc < 0) {
            listHolder.deallocate()
            return emptyList()
        }
        val list = AVDeviceInfoList(listHolder.get(AVDeviceInfoList::class.java, 0))
        return try {
            val sources = mutableListOf<ScreenSource.Monitor>()
            for (i in 0 until list.nb_devices()) {
                val dev = list.devices(i)
                val name = dev.device_description()?.string ?: continue
                if (name.contains(SCREEN_KEYWORD, ignoreCase = true)) {
                    sources += ScreenSource.Monitor(
                        id = i.toString(),
                        displayName = name,
                        widthPx = UNKNOWN_DIMENSION,
                        heightPx = UNKNOWN_DIMENSION,
                    )
                }
            }
            sources
        } finally {
            avdevice_free_list_devices(list)
            listHolder.deallocate()
        }
    }

    private const val SCREEN_KEYWORD = "screen"
    private const val UNKNOWN_DIMENSION = 0
}
