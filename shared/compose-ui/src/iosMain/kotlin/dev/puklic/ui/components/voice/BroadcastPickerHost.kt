@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.puklic.ui.components.voice

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.CoreGraphics.CGRectMake
import platform.ReplayKit.RPSystemBroadcastPickerView
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.objc.sel_registerName

/**
 * Bundle id of the Puklic broadcast extension. Must match `PRODUCT_BUNDLE_IDENTIFIER` of the
 * `PuklicBroadcastUpload` Xcode target (see FP-11 architect report §2).
 */
private const val BROADCAST_EXTENSION_BUNDLE_ID: String = "cz.damek.puklic.app.broadcast"

/**
 * Compose-iOS host for `RPSystemBroadcastPickerView` styled to LOOK like a Material3
 * `FilledButton` (filled primary background, white label, rounded corners) so it visually
 * matches the user-approved [IosShareScreenConfirmDialog] design (HARD RULE #3 2026-05-29).
 *
 * Why we restyle the system view: Apple requires the broadcast to be started from
 * `RPSystemBroadcastPickerView` — no app-side code can present the picker sheet manually.
 * The view ships with its own private `UIButton` styling that does not match Material3. We
 * traverse subviews to find the button and override its appearance. Apple discourages but
 * does not forbid this; if a future iOS release changes the hierarchy the styling silently
 * falls back to the default (the button still works — only cosmetics differ).
 *
 * The [onUserTapped] callback fires on any tap on the system view so the caller can capture
 * the current `shareAudio` toggle value before the broadcast picker sheet appears.
 */
@Composable
internal fun BroadcastPickerHost(
    primaryContainerColor: Color,
    primaryContentColor: Color,
    cornerRadiusDp: Int,
    onUserTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundArgb = primaryContainerColor.toArgb()
    val foregroundArgb = primaryContentColor.toArgb()

    UIKitView(
        factory = {
            val picker = RPSystemBroadcastPickerView(
                frame = CGRectMake(0.0, 0.0, PICKER_DEFAULT_WIDTH, PICKER_DEFAULT_HEIGHT),
            ).apply {
                preferredExtension = BROADCAST_EXTENSION_BUNDLE_ID
                showsMicrophoneButton = false
                backgroundColor = uiColorFromArgb(backgroundArgb)
                layer.cornerRadius = cornerRadiusDp.toDouble()
                layer.masksToBounds = true
            }
            stylePickerSubviews(
                root = picker,
                background = uiColorFromArgb(backgroundArgb),
                foreground = uiColorFromArgb(foregroundArgb),
                cornerRadius = cornerRadiusDp.toDouble(),
            )
            attachTapForwarder(picker, onUserTapped)
            picker
        },
        modifier = modifier
            .widthIn(min = PICKER_MIN_WIDTH_DP.dp)
            .height(PICKER_HEIGHT_DP.dp),
        update = { view ->
            view.backgroundColor = uiColorFromArgb(backgroundArgb)
            view.layer.cornerRadius = cornerRadiusDp.toDouble()
            stylePickerSubviews(
                root = view,
                background = uiColorFromArgb(backgroundArgb),
                foreground = uiColorFromArgb(foregroundArgb),
                cornerRadius = cornerRadiusDp.toDouble(),
            )
        },
    )
}

private fun stylePickerSubviews(
    root: UIView,
    background: UIColor,
    foreground: UIColor,
    cornerRadius: Double,
) {
    // RPSystemBroadcastPickerView's hierarchy contains exactly one UIButton in current iOS
    // releases. We restyle whatever buttons we encounter and recurse for safety.
    for (subview in root.subviews) {
        val v = subview as? UIView ?: continue
        if (v is UIButton) {
            v.backgroundColor = background
            v.tintColor = foreground
            v.setTitleColor(foreground, forState = UIControlStateNormal)
            v.layer.cornerRadius = cornerRadius
            v.layer.masksToBounds = true
            v.imageView?.tintColor = foreground
        } else {
            v.backgroundColor = background
            v.layer.cornerRadius = cornerRadius
        }
        stylePickerSubviews(v, background, foreground, cornerRadius)
    }
}

private fun attachTapForwarder(view: UIView, onUserTapped: () -> Unit) {
    val target = TapForwarderTarget(onUserTapped)
    val recognizer = UITapGestureRecognizer(
        target = target,
        action = sel_registerName("handleTap"),
    )
    recognizer.cancelsTouchesInView = false
    view.addGestureRecognizer(recognizer)
}

/**
 * Obj-C target wired via NSObject — kept alive by the gesture recognizer's strong reference.
 */
private class TapForwarderTarget(
    private val onTap: () -> Unit,
) : NSObject() {
    @ObjCAction
    fun handleTap() {
        onTap()
    }
}

private fun uiColorFromArgb(argb: Int): UIColor {
    val a = ((argb ushr ARGB_A_SHIFT) and ARGB_BYTE_MASK) / ARGB_DENOM
    val r = ((argb ushr ARGB_R_SHIFT) and ARGB_BYTE_MASK) / ARGB_DENOM
    val g = ((argb ushr ARGB_G_SHIFT) and ARGB_BYTE_MASK) / ARGB_DENOM
    val b = (argb and ARGB_BYTE_MASK) / ARGB_DENOM
    return UIColor.colorWithRed(red = r, green = g, blue = b, alpha = a)
}

private const val PICKER_DEFAULT_WIDTH = 240.0
private const val PICKER_DEFAULT_HEIGHT = 40.0
private const val PICKER_MIN_WIDTH_DP = 160
private const val PICKER_HEIGHT_DP = 40
private const val ARGB_A_SHIFT = 24
private const val ARGB_R_SHIFT = 16
private const val ARGB_G_SHIFT = 8
private const val ARGB_BYTE_MASK = 0xFF
private const val ARGB_DENOM = 255.0
