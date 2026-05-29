package dev.puklic.screencast.windows

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.ptr.PointerByReference

/**
 * JNA + COM bridge for the DXGI 1.2 Desktop Duplication API and the slice of D3D11 we need
 * to drive it. Used by [WindowsScreenCapture] (FP-9) to produce BGRA8 framebuffers off
 * the GPU at framerate.
 *
 * ## COM vtable dispatch pattern
 *
 * JNA does not bind COM vtables natively. Each COM interface pointer is a `Pointer` whose
 * dereferenced value is the address of the vtable; each vtable slot holds a function
 * pointer at `slot * pointerSize` bytes from the vtable base. We compute the function
 * address per call (cheap — two pointer reads) and dispatch through `Function.getFunction`,
 * passing the COM `this` pointer as the first argument.
 *
 * Slot indices come from the MSDN-published vtable layout. Every COM interface inherits
 * the three `IUnknown` slots (`QueryInterface`=0, `AddRef`=1, `Release`=2). Derived
 * interfaces append their declared methods after the inherited slots.
 *
 * This is the same idiom JNA's internal `com.sun.jna.platform.win32.COM.Unknown` uses;
 * we don't subclass that because we'd reach into the vtable for every non-`IUnknown`
 * method anyway, and one-class-per-interface boilerplate would 3× the LOC for no runtime
 * benefit.
 *
 * ## Loaded native libraries
 *
 * - `Dxgi.dll` — `CreateDXGIFactory1`.
 * - `D3d11.dll` — `D3D11CreateDevice`.
 *
 * `User32.dll`, `Ole32.dll`, and `Kernel32.dll` are accessed via the bundled
 * jna-platform wrappers ([com.sun.jna.platform.win32.User32], [com.sun.jna.platform.win32.Ole32],
 * [com.sun.jna.platform.win32.Kernel32]) from the calling code.
 *
 * ## GUIDs
 *
 * Every COM IID below is documented with its C header constant for traceability against
 * the official MSDN definitions. Bytes are passed in the standard Win32 little-endian
 * mixed-endian layout that [GUID] uses on the wire.
 */
@Suppress("MagicNumber", "TooManyFunctions")
public object WindowsDxgiBridge {

    private val dxgi: NativeLibrary by lazy { NativeLibrary.getInstance("Dxgi") }
    private val d3d11: NativeLibrary by lazy { NativeLibrary.getInstance("D3d11") }

    // --- IIDs ---
    // From dxgi.h: DEFINE_GUID(IID_IDXGIFactory1, 0x770aae78, 0xf26f, 0x4dba, ...)
    public val IID_IDXGIFactory1: GUID =
        GUID("{770AAE78-F26F-4DBA-A829-253C83D1B387}")

    // From dxgi.h: DEFINE_GUID(IID_IDXGIOutput, 0xae02eedb, 0xc735, 0x4690, ...)
    public val IID_IDXGIOutput: GUID =
        GUID("{AE02EEDB-C735-4690-8D52-5A8DC20213AA}")

    // From dxgi1_2.h: DEFINE_GUID(IID_IDXGIOutput1, ...)
    public val IID_IDXGIOutput1: GUID =
        GUID("{00CDDEA8-939B-4B83-A340-A685226666CC}")

    // From dxgi1_6.h: DEFINE_GUID(IID_IDXGIOutput6, ...)
    public val IID_IDXGIOutput6: GUID =
        GUID("{068346E8-AAEC-4B84-ADD7-137F513F77A1}")

    // From d3d11.h: DEFINE_GUID(IID_ID3D11Texture2D, ...)
    public val IID_ID3D11Texture2D: GUID =
        GUID("{6F15AAF2-D208-4E89-9AB4-489535D34F9C}")

    // --- HRESULT / DXGI return codes (selected) ---
    public const val S_OK: Int = 0
    public const val E_NOINTERFACE: Int = 0x80004002.toInt()
    public const val E_ACCESSDENIED: Int = 0x80070005.toInt()
    public const val DXGI_ERROR_NOT_FOUND: Int = 0x887A0002.toInt()
    public const val DXGI_ERROR_ACCESS_LOST: Int = 0x887A0026.toInt()
    public const val DXGI_ERROR_WAIT_TIMEOUT: Int = 0x887A0027.toInt()
    public const val DXGI_ERROR_UNSUPPORTED: Int = 0x887A0004.toInt()
    public const val DXGI_ERROR_INVALID_CALL: Int = 0x887A0001.toInt()

    // --- D3D11 enums (selected) ---
    public const val D3D11_SDK_VERSION: Int = 7
    public const val D3D_DRIVER_TYPE_HARDWARE: Int = 1
    public const val D3D_FEATURE_LEVEL_11_0: Int = 0xB000
    public const val D3D11_USAGE_STAGING: Int = 3
    public const val D3D11_CPU_ACCESS_READ: Int = 0x20000
    public const val D3D11_MAP_READ: Int = 1
    public const val DXGI_FORMAT_B8G8R8A8_UNORM: Int = 87

    private val POINTER_SIZE: Int = Native.POINTER_SIZE

    // --- Top-level entry points ---

    /** Calls `CreateDXGIFactory1(IID, ppFactory)` → returns the factory pointer or throws. */
    public fun createFactory1(): Pointer {
        val out = PointerByReference()
        val iid = guidToMemory(IID_IDXGIFactory1)
        val fn = dxgi.getFunction("CreateDXGIFactory1")
        val hr = fn.invokeInt(arrayOf<Any>(iid, out))
        if (hr != S_OK) throwHr("CreateDXGIFactory1", hr)
        return out.value
            ?: throw IllegalStateException("CreateDXGIFactory1 returned S_OK with null pointer")
    }

    /**
     * Calls `D3D11CreateDevice(adapter, HARDWARE, NULL, 0, [11_0], 1, SDK_VERSION,
     *   ppDevice, &featureLevel, ppContext)`. Returns the `(device, immediateContext)` pair.
     * `adapter` may be `Pointer.NULL` to let the OS pick — Windows picks the first
     * hardware adapter. For multi-GPU we pass an explicit `IDXGIAdapter1` pointer.
     */
    public fun createD3D11Device(adapter: Pointer?): Pair<Pointer, Pointer> {
        val deviceOut = PointerByReference()
        val ctxOut = PointerByReference()
        val featureLevelsArr = Memory(Integer.BYTES.toLong()).apply { setInt(0L, D3D_FEATURE_LEVEL_11_0) }
        val featureLevelOut = Memory(Integer.BYTES.toLong())
        val fn = d3d11.getFunction("D3D11CreateDevice")
        // Driver type must be UNKNOWN (0) when adapter is non-null; HARDWARE only when adapter is NULL.
        val driverType = if (adapter == null || adapter == Pointer.NULL) D3D_DRIVER_TYPE_HARDWARE else 0
        val hr = fn.invokeInt(
            arrayOf<Any?>(
                adapter ?: Pointer.NULL,
                driverType,
                Pointer.NULL, // software module
                0, // flags
                featureLevelsArr,
                1, // feature level count
                D3D11_SDK_VERSION,
                deviceOut,
                featureLevelOut,
                ctxOut,
            ),
        )
        if (hr != S_OK) throwHr("D3D11CreateDevice", hr)
        val device = deviceOut.value ?: throw IllegalStateException("D3D11CreateDevice returned null device")
        val ctx = ctxOut.value ?: run {
            release(device)
            throw IllegalStateException("D3D11CreateDevice returned null context")
        }
        return device to ctx
    }

    // --- COM vtable dispatch primitives ---

    /** Read the vtable slot at [slot] in the COM object at [thisPtr]. */
    private fun vtableSlot(thisPtr: Pointer, slot: Int): Function {
        val vtable = thisPtr.getPointer(0L)
        val fnPtr = vtable.getPointer((slot.toLong() * POINTER_SIZE))
        return Function.getFunction(fnPtr)
    }

    // --- IUnknown ---

    public fun queryInterface(thisPtr: Pointer, iid: GUID): Pointer? {
        val out = PointerByReference()
        val hr = vtableSlot(thisPtr, 0).invokeInt(arrayOf<Any>(thisPtr, guidToMemory(iid), out))
        return if (hr == S_OK) out.value else null
    }

    public fun addRef(thisPtr: Pointer): Int =
        vtableSlot(thisPtr, 1).invokeInt(arrayOf<Any>(thisPtr))

    public fun release(thisPtr: Pointer): Int =
        vtableSlot(thisPtr, 2).invokeInt(arrayOf<Any>(thisPtr))

    // --- IDXGIFactory1 (slot 12 = EnumAdapters1, after IDXGIObject + IDXGIFactory inherited slots) ---
    // IDXGIObject: 3 (IUnknown) + 4 (SetPrivateData, SetPrivateDataInterface, GetPrivateData, GetParent) = 7.
    // IDXGIFactory adds 5: EnumAdapters, MakeWindowAssociation, GetWindowAssociation, CreateSwapChain, CreateSoftwareAdapter = slots 7..11.
    // IDXGIFactory1 adds 2: EnumAdapters1=12, IsCurrent=13.

    /** Returns the `IDXGIAdapter1` pointer or `null` when `DXGI_ERROR_NOT_FOUND` is reached. */
    public fun enumAdapters1(factory: Pointer, index: Int): Pointer? {
        val out = PointerByReference()
        val hr = vtableSlot(factory, SLOT_FACTORY1_ENUM_ADAPTERS1).invokeInt(
            arrayOf<Any>(factory, index, out),
        )
        return when (hr) {
            S_OK -> out.value
            DXGI_ERROR_NOT_FOUND -> null
            else -> throwHr("IDXGIFactory1::EnumAdapters1", hr)
        }
    }

    // --- IDXGIAdapter1: IDXGIObject (7) + IDXGIAdapter (3: EnumOutputs=7, GetDesc=8, CheckInterfaceSupport=9) ---

    /** Returns the `IDXGIOutput` pointer or `null` when `DXGI_ERROR_NOT_FOUND` is reached. */
    public fun enumOutputs(adapter: Pointer, index: Int): Pointer? {
        val out = PointerByReference()
        val hr = vtableSlot(adapter, SLOT_ADAPTER_ENUM_OUTPUTS).invokeInt(
            arrayOf<Any>(adapter, index, out),
        )
        return when (hr) {
            S_OK -> out.value
            DXGI_ERROR_NOT_FOUND -> null
            else -> throwHr("IDXGIAdapter1::EnumOutputs", hr)
        }
    }

    // --- IDXGIOutput6: IDXGIObject (7) + IDXGIOutput (10) + Output1 (4) + Output2 (1) + Output3 (1)
    //                  + Output4 (1) + Output5 (1) + Output6 (2: GetDesc1=37, CheckHardwareCompositionSupport=38).
    //                  DuplicateOutput is on Output1 — slot 22. DuplicateOutput1 is on Output5 — slot 35.

    /**
     * Calls `IDXGIOutput1::DuplicateOutput(device, &dupl)`. Works on any Output1+
     * (Output6 is binary-compatible because COM derived interfaces append slots, never
     * reorder).
     *
     * Failure modes per MSDN: `E_ACCESSDENIED` (secure desktop active),
     * `DXGI_ERROR_UNSUPPORTED` (unsupported display mode).
     */
    public fun duplicateOutput(output: Pointer, device: Pointer): DuplicateOutputResult {
        val out = PointerByReference()
        val hr = vtableSlot(output, SLOT_OUTPUT1_DUPLICATE_OUTPUT).invokeInt(
            arrayOf<Any>(output, device, out),
        )
        return when (hr) {
            S_OK -> DuplicateOutputResult.Ok(
                out.value ?: throw IllegalStateException("DuplicateOutput returned null pointer"),
            )
            E_ACCESSDENIED -> DuplicateOutputResult.AccessDenied
            DXGI_ERROR_UNSUPPORTED -> DuplicateOutputResult.Unsupported
            else -> throwHr("IDXGIOutput1::DuplicateOutput", hr)
        }
    }

    // --- IDXGIOutputDuplication: IDXGIObject (7) + Duplication (7 methods):
    //                            GetDesc=7, AcquireNextFrame=8, GetFrameDirtyRects=9,
    //                            GetFrameMoveRects=10, GetFramePointerShape=11,
    //                            MapDesktopSurface=12, UnMapDesktopSurface=13, ReleaseFrame=14.

    /**
     * Calls `AcquireNextFrame(timeoutMs, &frameInfo, &desktopResource)`. Returns
     * the desktop `IDXGIResource` pointer + the frame info struct, or a sentinel for
     * timeout / access-lost.
     */
    public fun acquireNextFrame(
        dupl: Pointer,
        timeoutMs: Int,
    ): AcquireNextFrameResult {
        val frameInfo = Memory(SIZEOF_DXGI_OUTDUPL_FRAME_INFO.toLong())
        val resOut = PointerByReference()
        val hr = vtableSlot(dupl, SLOT_DUPLICATION_ACQUIRE_NEXT_FRAME).invokeInt(
            arrayOf<Any>(dupl, timeoutMs, frameInfo, resOut),
        )
        return when (hr) {
            S_OK -> AcquireNextFrameResult.Ok(
                resOut.value
                    ?: throw IllegalStateException("AcquireNextFrame returned null resource"),
                frameInfo,
            )
            DXGI_ERROR_WAIT_TIMEOUT -> AcquireNextFrameResult.Timeout
            DXGI_ERROR_ACCESS_LOST -> AcquireNextFrameResult.AccessLost
            else -> throwHr("IDXGIOutputDuplication::AcquireNextFrame", hr)
        }
    }

    public fun releaseFrame(dupl: Pointer) {
        val hr = vtableSlot(dupl, SLOT_DUPLICATION_RELEASE_FRAME).invokeInt(arrayOf<Any>(dupl))
        if (hr != S_OK && hr != DXGI_ERROR_INVALID_CALL) {
            // INVALID_CALL = no frame was acquired; benign on close path.
            throwHr("IDXGIOutputDuplication::ReleaseFrame", hr)
        }
    }

    // --- ID3D11Device: IUnknown (3) + CreateBuffer=3, CreateTexture1D=4, CreateTexture2D=5, ... ---

    /**
     * Calls `ID3D11Device::CreateTexture2D(&desc, NULL, &tex)` to allocate a staging
     * texture sized [width]×[height] BGRA8 with CPU read access.
     */
    public fun createStagingTexture2D(device: Pointer, width: Int, height: Int): Pointer {
        val desc = Memory(SIZEOF_D3D11_TEXTURE2D_DESC.toLong())
        desc.clear(SIZEOF_D3D11_TEXTURE2D_DESC.toLong())
        // Layout matches d3d11.h struct D3D11_TEXTURE2D_DESC:
        // UINT Width; UINT Height; UINT MipLevels; UINT ArraySize; DXGI_FORMAT Format;
        // DXGI_SAMPLE_DESC{Count,Quality}; D3D11_USAGE Usage; UINT BindFlags;
        // UINT CPUAccessFlags; UINT MiscFlags;
        var off = 0L
        desc.setInt(off, width); off += 4
        desc.setInt(off, height); off += 4
        desc.setInt(off, 1); off += 4 // MipLevels
        desc.setInt(off, 1); off += 4 // ArraySize
        desc.setInt(off, DXGI_FORMAT_B8G8R8A8_UNORM); off += 4
        desc.setInt(off, 1); off += 4 // SampleDesc.Count
        desc.setInt(off, 0); off += 4 // SampleDesc.Quality
        desc.setInt(off, D3D11_USAGE_STAGING); off += 4
        desc.setInt(off, 0); off += 4 // BindFlags
        desc.setInt(off, D3D11_CPU_ACCESS_READ); off += 4
        desc.setInt(off, 0) // MiscFlags
        val out = PointerByReference()
        val hr = vtableSlot(device, SLOT_DEVICE_CREATE_TEXTURE_2D).invokeInt(
            arrayOf<Any>(device, desc, Pointer.NULL, out),
        )
        if (hr != S_OK) throwHr("ID3D11Device::CreateTexture2D(staging)", hr)
        return out.value ?: throw IllegalStateException("CreateTexture2D returned S_OK with null")
    }

    // --- ID3D11DeviceContext: IUnknown(3) + ID3D11DeviceChild(4) + then a long list.
    //                          CopyResource is slot 47, Map is slot 14, Unmap is slot 15
    //                          (per d3d11.h vtable order).

    public fun copyResource(context: Pointer, dst: Pointer, src: Pointer) {
        vtableSlot(context, SLOT_CONTEXT_COPY_RESOURCE).invoke(
            arrayOf<Any>(context, dst, src),
        )
    }

    /**
     * Calls `Map(resource, 0, D3D11_MAP_READ, 0, &mapped)`. Returns the mapped struct
     * (data pointer + row pitch + depth pitch). Caller MUST call [unmap] symmetrically.
     */
    public fun map(context: Pointer, resource: Pointer): MappedSubresource {
        val mapped = Memory(SIZEOF_D3D11_MAPPED_SUBRESOURCE.toLong())
        mapped.clear(SIZEOF_D3D11_MAPPED_SUBRESOURCE.toLong())
        val hr = vtableSlot(context, SLOT_CONTEXT_MAP).invokeInt(
            arrayOf<Any>(context, resource, 0, D3D11_MAP_READ, 0, mapped),
        )
        if (hr != S_OK) throwHr("ID3D11DeviceContext::Map", hr)
        // struct D3D11_MAPPED_SUBRESOURCE { void* pData; UINT RowPitch; UINT DepthPitch; }
        val data = mapped.getPointer(0L)
            ?: throw IllegalStateException("Map returned null pData")
        val rowPitch = mapped.getInt(POINTER_SIZE.toLong())
        val depthPitch = mapped.getInt(POINTER_SIZE.toLong() + 4)
        return MappedSubresource(data, rowPitch, depthPitch)
    }

    public fun unmap(context: Pointer, resource: Pointer) {
        vtableSlot(context, SLOT_CONTEXT_UNMAP).invoke(
            arrayOf<Any>(context, resource, 0),
        )
    }

    /**
     * `IDXGIOutput::GetDesc` — returns the monitor description (HMONITOR + DesktopCoordinates).
     * Available on every Output revision; we use the base slot to stay compatible with
     * Output1 callers.
     */
    public fun getOutputDesc(output: Pointer): OutputDesc {
        val buf = Memory(SIZEOF_DXGI_OUTPUT_DESC.toLong())
        buf.clear(SIZEOF_DXGI_OUTPUT_DESC.toLong())
        val hr = vtableSlot(output, SLOT_OUTPUT_GET_DESC).invokeInt(arrayOf<Any>(output, buf))
        if (hr != S_OK) throwHr("IDXGIOutput::GetDesc", hr)
        // struct DXGI_OUTPUT_DESC {
        //   WCHAR DeviceName[32];           // 64 bytes
        //   RECT DesktopCoordinates;         // 16 bytes (4 LONGs)
        //   BOOL AttachedToDesktop;          // 4 bytes
        //   DXGI_MODE_ROTATION Rotation;     // 4 bytes
        //   HMONITOR Monitor;                // pointer
        // }
        val nameBytes = ByteArray(DEVICE_NAME_WCHARS * 2)
        buf.read(0L, nameBytes, 0, nameBytes.size)
        val deviceName = decodeUtf16(nameBytes)
        var off = DEVICE_NAME_WCHARS.toLong() * 2
        val left = buf.getInt(off); off += 4
        val top = buf.getInt(off); off += 4
        val right = buf.getInt(off); off += 4
        val bottom = buf.getInt(off); off += 4
        val widthPx = right - left
        val heightPx = bottom - top
        return OutputDesc(deviceName = deviceName, widthPx = widthPx, heightPx = heightPx)
    }

    // --- Helpers ---

    /** Convert a [GUID] into a 16-byte [Memory] block ready to pass to a COM call. */
    public fun guidToMemory(guid: GUID): Memory {
        val mem = Memory(GUID_SIZE.toLong())
        val bytes = guid.toByteArray()
        mem.write(0L, bytes, 0, bytes.size)
        return mem
    }

    /** Decode a UTF-16LE null-terminated WCHAR array into a Kotlin String. */
    private fun decodeUtf16(bytes: ByteArray): String {
        // Find first NUL char (two zero bytes on a WCHAR boundary).
        var end = bytes.size
        var i = 0
        while (i + 1 < bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                end = i
                break
            }
            i += 2
        }
        return String(bytes, 0, end, Charsets.UTF_16LE)
    }

    private fun throwHr(method: String, hr: Int): Nothing =
        throw IllegalStateException("$method failed with HRESULT=0x${hr.toUInt().toString(HEX_RADIX)}")

    // --- Result types ---

    public sealed interface DuplicateOutputResult {
        public data class Ok(val duplication: Pointer) : DuplicateOutputResult
        public data object AccessDenied : DuplicateOutputResult
        public data object Unsupported : DuplicateOutputResult
    }

    public sealed interface AcquireNextFrameResult {
        public data class Ok(val resource: Pointer, val frameInfo: Memory) : AcquireNextFrameResult
        public data object Timeout : AcquireNextFrameResult
        public data object AccessLost : AcquireNextFrameResult
    }

    public data class MappedSubresource(
        val data: Pointer,
        val rowPitch: Int,
        val depthPitch: Int,
    )

    public data class OutputDesc(
        val deviceName: String,
        val widthPx: Int,
        val heightPx: Int,
    )

    // --- Constants ---

    public const val HEX_RADIX: Int = 16
    public const val GUID_SIZE: Int = 16
    public const val DEVICE_NAME_WCHARS: Int = 32

    // DXGI_OUTPUT_DESC = 32 WCHARs name (64) + RECT (16) + BOOL (4) + DXGI_MODE_ROTATION (4)
    //                    + HMONITOR (pointer-sized).
    public val SIZEOF_DXGI_OUTPUT_DESC: Int = 64 + 16 + 4 + 4 + POINTER_SIZE
    // DXGI_OUTDUPL_FRAME_INFO is:
    //   LARGE_INTEGER LastPresentTime (8) + LastMouseUpdateTime (8) + UINT AccumulatedFrames (4)
    //   + BOOL RectsCoalesced (4) + BOOL ProtectedContentMaskedOut (4)
    //   + DXGI_OUTDUPL_POINTER_POSITION (Position{x,y}=8 + Visible=4 = 16 with padding)
    //   + UINT TotalMetadataBufferSize (4) + UINT PointerShapeBufferSize (4)
    public const val SIZEOF_DXGI_OUTDUPL_FRAME_INFO: Int = 8 + 8 + 4 + 4 + 4 + 16 + 4 + 4
    // D3D11_TEXTURE2D_DESC = 11 UINTs = 44 bytes (no padding when 4-aligned).
    public const val SIZEOF_D3D11_TEXTURE2D_DESC: Int = 11 * 4
    // D3D11_MAPPED_SUBRESOURCE = void* pData + UINT RowPitch + UINT DepthPitch.
    public val SIZEOF_D3D11_MAPPED_SUBRESOURCE: Int = POINTER_SIZE + 4 + 4

    // --- Vtable slot indices ---
    // IUnknown: 0=QueryInterface, 1=AddRef, 2=Release.
    // IDXGIObject (inherits IUnknown): +4 → SetPrivateData=3, SetPrivateDataInterface=4,
    //   GetPrivateData=5, GetParent=6.
    // IDXGIFactory (inherits IDXGIObject): +5 → EnumAdapters=7, MakeWindowAssociation=8,
    //   GetWindowAssociation=9, CreateSwapChain=10, CreateSoftwareAdapter=11.
    // IDXGIFactory1: +2 → EnumAdapters1=12, IsCurrent=13.
    public const val SLOT_FACTORY1_ENUM_ADAPTERS1: Int = 12

    // IDXGIAdapter (inherits IDXGIObject): EnumOutputs=7, GetDesc=8, CheckInterfaceSupport=9.
    // IDXGIAdapter1: +1 → GetDesc1=10.
    public const val SLOT_ADAPTER_ENUM_OUTPUTS: Int = 7

    // IDXGIOutput (inherits IDXGIObject): GetDesc=7, GetDisplayModeList=8, FindClosestMatchingMode=9,
    //   WaitForVBlank=10, TakeOwnership=11, ReleaseOwnership=12, GetGammaControlCapabilities=13,
    //   SetGammaControl=14, GetGammaControl=15, SetDisplaySurface=16, GetDisplaySurfaceData=17,
    //   GetFrameStatistics=18.
    public const val SLOT_OUTPUT_GET_DESC: Int = 7

    // IDXGIOutput1 (inherits Output): GetDisplayModeList1=19, FindClosestMatchingMode1=20,
    //   GetDisplaySurfaceData1=21, DuplicateOutput=22.
    public const val SLOT_OUTPUT1_DUPLICATE_OUTPUT: Int = 22

    // IDXGIOutputDuplication (inherits IDXGIObject):
    //   GetDesc=7, AcquireNextFrame=8, GetFrameDirtyRects=9, GetFrameMoveRects=10,
    //   GetFramePointerShape=11, MapDesktopSurface=12, UnMapDesktopSurface=13, ReleaseFrame=14.
    public const val SLOT_DUPLICATION_ACQUIRE_NEXT_FRAME: Int = 8
    public const val SLOT_DUPLICATION_RELEASE_FRAME: Int = 14

    // ID3D11Device (inherits IUnknown): CreateBuffer=3, CreateTexture1D=4, CreateTexture2D=5, ...
    public const val SLOT_DEVICE_CREATE_TEXTURE_2D: Int = 5

    // ID3D11DeviceContext (inherits ID3D11DeviceChild=4): VSSetConstantBuffers=7, ..., Map=14,
    //   Unmap=15, ..., CopyResource=47. Slots verified against d3d11.h vtable order.
    public const val SLOT_CONTEXT_MAP: Int = 14
    public const val SLOT_CONTEXT_UNMAP: Int = 15
    public const val SLOT_CONTEXT_COPY_RESOURCE: Int = 47
}
