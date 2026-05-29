package dev.puklic.screencast.windows

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference

/**
 * JNA + COM bridge for the slice of WASAPI we use to capture system audio in loopback mode.
 * Used by [WindowsLoopbackAudioReader] (FP-9). See [WindowsDxgiBridge] for the vtable
 * dispatch pattern this file mirrors.
 *
 * Loaded native libraries:
 * - `Ole32.dll` — `CoCreateInstance` (via [Ole32]).
 * - `Kernel32.dll` — `CreateEventW`, `WaitForSingleObject`, `CloseHandle` (via
 *   [com.sun.jna.platform.win32.Kernel32]).
 *
 * GUIDs are taken from `mmdeviceapi.h` and `audioclient.h`; each is annotated with its C
 * macro name for traceability.
 */
@Suppress("MagicNumber", "TooManyFunctions")
public object WindowsWasapiBridge {

    // --- CLSIDs / IIDs ---
    // CLSID_MMDeviceEnumerator (mmdeviceapi.h)
    public val CLSID_MMDeviceEnumerator: GUID =
        GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")

    // IID_IMMDeviceEnumerator
    public val IID_IMMDeviceEnumerator: GUID =
        GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")

    // IID_IAudioClient (audioclient.h)
    public val IID_IAudioClient: GUID =
        GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")

    // IID_IAudioCaptureClient
    public val IID_IAudioCaptureClient: GUID =
        GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")

    // KSDATAFORMAT_SUBTYPE_IEEE_FLOAT (ksmedia.h)
    public val KSDATAFORMAT_SUBTYPE_IEEE_FLOAT: GUID =
        GUID("{00000003-0000-0010-8000-00AA00389B71}")

    // --- Enums / constants ---
    public const val CLSCTX_ALL: Int = 0x17
    public const val E_DATA_RENDER: Int = 0 // EDataFlow::eRender
    public const val E_ROLE_CONSOLE: Int = 0 // ERole::eConsole

    public const val AUDCLNT_SHAREMODE_SHARED: Int = 0
    public const val AUDCLNT_STREAMFLAGS_LOOPBACK: Int = 0x00020000
    public const val AUDCLNT_STREAMFLAGS_EVENTCALLBACK: Int = 0x00040000
    public const val AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY: Int = 0x1
    public const val AUDCLNT_BUFFERFLAGS_SILENT: Int = 0x2
    public const val AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR: Int = 0x4

    public const val WAVE_FORMAT_EXTENSIBLE: Int = 0xFFFE
    public const val INFINITE: Int = -1

    // --- HRESULT helpers ---
    public const val S_OK: Int = 0
    public const val AUDCLNT_S_BUFFER_EMPTY: Int = 0x08890001
    public const val AUDCLNT_E_DEVICE_INVALIDATED: Int = 0x88890004.toInt()

    private val POINTER_SIZE: Int = Native.POINTER_SIZE

    // --- Top-level entry points ---

    /** Wrap [Ole32.CoCreateInstance] returning the `IMMDeviceEnumerator` pointer. */
    public fun createMMDeviceEnumerator(): Pointer {
        val out = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            CLSID_MMDeviceEnumerator,
            null,
            CLSCTX_ALL,
            IID_IMMDeviceEnumerator,
            out,
        )
        val hrInt = hr.toInt()
        if (hrInt != S_OK) throwHr("CoCreateInstance(MMDeviceEnumerator)", hrInt)
        return out.value ?: throw IllegalStateException("CoCreateInstance returned null")
    }

    // --- COM vtable primitives ---

    private fun vtableSlot(thisPtr: Pointer, slot: Int): Function {
        val vtable = thisPtr.getPointer(0L)
        val fnPtr = vtable.getPointer(slot.toLong() * POINTER_SIZE)
        return Function.getFunction(fnPtr)
    }

    public fun release(thisPtr: Pointer): Int =
        vtableSlot(thisPtr, 2).invokeInt(arrayOf<Any>(thisPtr))

    // --- IMMDeviceEnumerator slots (mmdeviceapi.h):
    //   3=EnumAudioEndpoints, 4=GetDefaultAudioEndpoint, 5=GetDevice, 6=RegisterEndpointNotificationCallback,
    //   7=UnregisterEndpointNotificationCallback.

    public fun getDefaultAudioEndpoint(enumerator: Pointer): Pointer {
        val out = PointerByReference()
        val hr = vtableSlot(enumerator, SLOT_ENUMERATOR_GET_DEFAULT_ENDPOINT).invokeInt(
            arrayOf<Any>(enumerator, E_DATA_RENDER, E_ROLE_CONSOLE, out),
        )
        if (hr != S_OK) throwHr("IMMDeviceEnumerator::GetDefaultAudioEndpoint", hr)
        return out.value ?: throw IllegalStateException("GetDefaultAudioEndpoint returned null")
    }

    // --- IMMDevice slots:
    //   3=Activate, 4=OpenPropertyStore, 5=GetId, 6=GetState.

    public fun activateAudioClient(device: Pointer): Pointer {
        val out = PointerByReference()
        val iid = WindowsDxgiBridge.guidToMemory(IID_IAudioClient)
        val hr = vtableSlot(device, SLOT_DEVICE_ACTIVATE).invokeInt(
            arrayOf<Any>(device, iid, CLSCTX_ALL, Pointer.NULL, out),
        )
        if (hr != S_OK) throwHr("IMMDevice::Activate(IAudioClient)", hr)
        return out.value ?: throw IllegalStateException("Activate(IAudioClient) returned null")
    }

    // --- IAudioClient slots:
    //   3=Initialize, 4=GetBufferSize, 5=GetStreamLatency, 6=GetCurrentPadding,
    //   7=IsFormatSupported, 8=GetMixFormat, 9=GetDevicePeriod, 10=Start, 11=Stop,
    //   12=Reset, 13=SetEventHandle, 14=GetService.

    /**
     * Returns a CoTaskMemAlloc'd `WAVEFORMATEX*` describing the engine's mix format. The
     * caller MUST call [Ole32.CoTaskMemFree] on the pointer after extracting the fields it
     * needs.
     */
    public fun getMixFormat(client: Pointer): Pointer {
        val out = PointerByReference()
        val hr = vtableSlot(client, SLOT_CLIENT_GET_MIX_FORMAT).invokeInt(
            arrayOf<Any>(client, out),
        )
        if (hr != S_OK) throwHr("IAudioClient::GetMixFormat", hr)
        return out.value ?: throw IllegalStateException("GetMixFormat returned null")
    }

    /**
     * Calls `Initialize(shareMode, streamFlags, hnsBufferDuration, 0, pwfx, NULL)`.
     * `hnsBufferDuration` is in 100-nanosecond units (Win32 REFERENCE_TIME).
     */
    public fun initialize(
        client: Pointer,
        shareMode: Int,
        streamFlags: Int,
        hnsBufferDuration: Long,
        pwfx: Pointer,
    ) {
        val hr = vtableSlot(client, SLOT_CLIENT_INITIALIZE).invokeInt(
            arrayOf<Any>(client, shareMode, streamFlags, hnsBufferDuration, 0L, pwfx, Pointer.NULL),
        )
        if (hr != S_OK) throwHr("IAudioClient::Initialize", hr)
    }

    public fun setEventHandle(client: Pointer, hEvent: WinNT.HANDLE) {
        val hr = vtableSlot(client, SLOT_CLIENT_SET_EVENT_HANDLE).invokeInt(
            arrayOf<Any>(client, hEvent.pointer),
        )
        if (hr != S_OK) throwHr("IAudioClient::SetEventHandle", hr)
    }

    public fun getService(client: Pointer, iid: GUID): Pointer {
        val out = PointerByReference()
        val hr = vtableSlot(client, SLOT_CLIENT_GET_SERVICE).invokeInt(
            arrayOf<Any>(client, WindowsDxgiBridge.guidToMemory(iid), out),
        )
        if (hr != S_OK) throwHr("IAudioClient::GetService", hr)
        return out.value ?: throw IllegalStateException("GetService returned null")
    }

    public fun start(client: Pointer) {
        val hr = vtableSlot(client, SLOT_CLIENT_START).invokeInt(arrayOf<Any>(client))
        if (hr != S_OK) throwHr("IAudioClient::Start", hr)
    }

    public fun stop(client: Pointer) {
        val hr = vtableSlot(client, SLOT_CLIENT_STOP).invokeInt(arrayOf<Any>(client))
        if (hr != S_OK) throwHr("IAudioClient::Stop", hr)
    }

    public fun getBufferSize(client: Pointer): Int {
        val out = Memory(Integer.BYTES.toLong())
        val hr = vtableSlot(client, SLOT_CLIENT_GET_BUFFER_SIZE).invokeInt(arrayOf<Any>(client, out))
        if (hr != S_OK) throwHr("IAudioClient::GetBufferSize", hr)
        return out.getInt(0L)
    }

    // --- IAudioCaptureClient slots:
    //   3=GetBuffer, 4=ReleaseBuffer, 5=GetNextPacketSize.

    /**
     * Wraps `GetBuffer(&pData, &numFrames, &flags, &devicePosition, &qpcPosition)`. Returns
     * `null` when no data is available (`AUDCLNT_S_BUFFER_EMPTY`). Throws on any other
     * non-zero HRESULT.
     */
    public fun getBuffer(cap: Pointer): CaptureBuffer? {
        val dataOut = PointerByReference()
        val framesOut = Memory(Integer.BYTES.toLong())
        val flagsOut = Memory(Integer.BYTES.toLong())
        val devPosOut = Memory(java.lang.Long.BYTES.toLong())
        val qpcOut = Memory(java.lang.Long.BYTES.toLong())
        val hr = vtableSlot(cap, SLOT_CAPTURE_GET_BUFFER).invokeInt(
            arrayOf<Any>(cap, dataOut, framesOut, flagsOut, devPosOut, qpcOut),
        )
        return when (hr) {
            S_OK -> CaptureBuffer(
                data = dataOut.value ?: throw IllegalStateException("GetBuffer returned null pData"),
                numFrames = framesOut.getInt(0L),
                flags = flagsOut.getInt(0L),
            )
            AUDCLNT_S_BUFFER_EMPTY -> null
            else -> throwHr("IAudioCaptureClient::GetBuffer", hr)
        }
    }

    public fun releaseBuffer(cap: Pointer, numFrames: Int) {
        val hr = vtableSlot(cap, SLOT_CAPTURE_RELEASE_BUFFER).invokeInt(
            arrayOf<Any>(cap, numFrames),
        )
        if (hr != S_OK) throwHr("IAudioCaptureClient::ReleaseBuffer", hr)
    }

    public fun getNextPacketSize(cap: Pointer): Int {
        val out = Memory(Integer.BYTES.toLong())
        val hr = vtableSlot(cap, SLOT_CAPTURE_GET_NEXT_PACKET_SIZE).invokeInt(arrayOf<Any>(cap, out))
        if (hr != S_OK) throwHr("IAudioCaptureClient::GetNextPacketSize", hr)
        return out.getInt(0L)
    }

    // --- WAVEFORMATEX helpers ---
    // WAVEFORMATEX layout:
    //   WORD wFormatTag (2), WORD nChannels (2), DWORD nSamplesPerSec (4),
    //   DWORD nAvgBytesPerSec (4), WORD nBlockAlign (2), WORD wBitsPerSample (2),
    //   WORD cbSize (2). Total 18 bytes; WAVEFORMATEXTENSIBLE appends 22 bytes.

    public data class WaveFormatEx(
        val formatTag: Int,
        val channels: Int,
        val samplesPerSec: Int,
        val avgBytesPerSec: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val cbSize: Int,
    )

    public fun readWaveFormat(pwfx: Pointer): WaveFormatEx = WaveFormatEx(
        formatTag = pwfx.getShort(0L).toInt() and 0xFFFF,
        channels = pwfx.getShort(2L).toInt() and 0xFFFF,
        samplesPerSec = pwfx.getInt(4L),
        avgBytesPerSec = pwfx.getInt(8L),
        blockAlign = pwfx.getShort(12L).toInt() and 0xFFFF,
        bitsPerSample = pwfx.getShort(14L).toInt() and 0xFFFF,
        cbSize = pwfx.getShort(16L).toInt() and 0xFFFF,
    )

    // --- Result types ---

    public data class CaptureBuffer(
        val data: Pointer,
        val numFrames: Int,
        val flags: Int,
    ) {
        public val isSilent: Boolean get() = (flags and AUDCLNT_BUFFERFLAGS_SILENT) != 0
    }

    private fun throwHr(method: String, hr: Int): Nothing =
        throw IllegalStateException("$method failed with HRESULT=0x${hr.toUInt().toString(WindowsDxgiBridge.HEX_RADIX)}")

    // --- Vtable slot indices ---
    public const val SLOT_ENUMERATOR_GET_DEFAULT_ENDPOINT: Int = 4
    public const val SLOT_DEVICE_ACTIVATE: Int = 3
    public const val SLOT_CLIENT_INITIALIZE: Int = 3
    public const val SLOT_CLIENT_GET_BUFFER_SIZE: Int = 4
    public const val SLOT_CLIENT_GET_MIX_FORMAT: Int = 8
    public const val SLOT_CLIENT_START: Int = 10
    public const val SLOT_CLIENT_STOP: Int = 11
    public const val SLOT_CLIENT_SET_EVENT_HANDLE: Int = 13
    public const val SLOT_CLIENT_GET_SERVICE: Int = 14
    public const val SLOT_CAPTURE_GET_BUFFER: Int = 3
    public const val SLOT_CAPTURE_RELEASE_BUFFER: Int = 4
    public const val SLOT_CAPTURE_GET_NEXT_PACKET_SIZE: Int = 5
}
