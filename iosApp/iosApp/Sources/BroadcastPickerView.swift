// SwiftUI / UIViewRepresentable wrapper for `RPSystemBroadcastPickerView`.
//
// This is the only UI surface that can start a ReplayKit broadcast: it renders
// a system-styled button which, when tapped, displays Apple's broadcast picker
// sheet with our extension (bundle id `cz.damek.puklic.app.broadcast`)
// pre-selected. The extension then runs as a separate process and pipes frames
// into the main app via the App Group ring buffer (`SharedRingBuffer.swift` /
// `BroadcastExtensionBridge.kt`).
//
// FP-11 ships the type but does NOT mount it in any visible Compose / UIKit
// view hierarchy. FP-12 owns the placement under HARD RULE #3 (UX approval
// required for any visible UI surface change).
//
// References:
//   docs/03_infrastructure/architect-reports/2026-05-29-fp11-ios-broadcast-extension.md
//   https://developer.apple.com/documentation/replaykit/rpsystembroadcastpickerview

import SwiftUI
import UIKit
import ReplayKit

/// SwiftUI-friendly wrapper around `RPSystemBroadcastPickerView`. The view
/// renders a system button; when the user taps it, ReplayKit shows the
/// broadcast picker with our extension pre-selected (via `preferredExtension`).
///
/// `onUserTapped` fires on every tap so callers can record analytics or update
/// local UI state (e.g. show a "Starting broadcast…" status). It does NOT
/// fire when the extension actually starts — that signal is observed via the
/// Darwin lifecycle notification consumed by `BroadcastExtensionBridge`.
public struct BroadcastPickerView: UIViewRepresentable {

    /// Bundle id of the Puklic broadcast extension. MUST match
    /// `PRODUCT_BUNDLE_IDENTIFIER` of the `PuklicBroadcastUpload` target.
    public static let extensionBundleId = "cz.damek.puklic.app.broadcast"

    private let onUserTapped: () -> Void

    public init(onUserTapped: @escaping () -> Void = {}) {
        self.onUserTapped = onUserTapped
    }

    public func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let view = RPSystemBroadcastPickerView(frame: .zero)
        view.preferredExtension = Self.extensionBundleId
        // ReplayKit hides the mic button by default on this picker; we leave
        // it hidden because Discord screen-share spec ships mic audio over the
        // existing voice channel, not the screen-share channel.
        view.showsMicrophoneButton = false
        view.translatesAutoresizingMaskIntoConstraints = false

        // Forward taps. RPSystemBroadcastPickerView is a UIView containing a
        // single private UIButton; we attach a tap gesture to the wrapper so
        // we observe taps without depending on the private subview.
        let recogniser = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap)
        )
        // cancelsTouchesInView = false so the underlying button still receives
        // its touch and presents the picker.
        recogniser.cancelsTouchesInView = false
        view.addGestureRecognizer(recogniser)
        return view
    }

    public func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {
        uiView.preferredExtension = Self.extensionBundleId
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(onUserTapped: onUserTapped)
    }

    public final class Coordinator: NSObject {
        private let onUserTapped: () -> Void
        init(onUserTapped: @escaping () -> Void) {
            self.onUserTapped = onUserTapped
        }
        @objc func handleTap() {
            onUserTapped()
        }
    }
}
