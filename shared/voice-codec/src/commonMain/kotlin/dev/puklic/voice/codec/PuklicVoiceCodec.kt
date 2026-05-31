package dev.puklic.voice.codec

/**
 * Marks types that are part of [:shared:voice-codec]'s public-but-in-tree-only API surface.
 *
 * These types are deliberately `public` (so that consumers in [:shared:voice] /
 * Apple-native voice clients can compose them across Kotlin module boundaries) but their
 * contract may evolve without notice across slices of the voice / codec roadmap. External
 * consumers (i.e. anything outside `dev.puklic.*`) must NOT depend on them.
 *
 * See `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-1-v2-voice-gateway-redesign.md`
 * §2 for the rationale (Option A: promote to `public` + opt-in marker, chosen over moving
 * `DefaultVoiceClient` into voice-codec or introducing a 3rd `:shared:voice-internal` module).
 */
@RequiresOptIn(
    message = "This API is part of :shared:voice-codec's in-tree composition surface " +
        "for :shared:voice / :shared:voice-codec / Apple-native voice clients only. " +
        "External consumers must not depend on it.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class PuklicVoiceCodec
