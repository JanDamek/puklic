package dev.puklic.ui.text

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * User-visible text produced outside of a composable (view models, state classes).
 *
 * Producers pass a typed [Resource] reference plus format arguments; the text is resolved against
 * the active locale only at the render site, so state stays locale-independent.
 */
public sealed interface UiText {
    /** A localized string resource with optional positional format arguments. */
    public data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : UiText

    /** Text that must never be translated, such as a server-supplied or user-supplied message. */
    public data class Verbatim(val value: String) : UiText

    public companion object {
        public fun of(resource: StringResource, vararg args: Any): UiText = Resource(resource, args.toList())
    }
}

/** Resolves this text for the current composition locale. */
@Composable
public fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(resource, *args.toTypedArray())
    is UiText.Verbatim -> value
}

/** Resolves this text for the current default locale outside of composition. */
public suspend fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> getString(resource, *args.toTypedArray())
    is UiText.Verbatim -> value
}
