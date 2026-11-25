package dev.puklic.ui.screens.main

import androidx.compose.runtime.Composable

/**
 * Android renders no voice dock: there is no voice media engine behind the Android `VoiceClient` yet,
 * and the dock's visual design needs explicit approval before it is built for mobile.
 */
@Composable
internal actual fun VoiceDock(viewModel: MainViewModel) = Unit
