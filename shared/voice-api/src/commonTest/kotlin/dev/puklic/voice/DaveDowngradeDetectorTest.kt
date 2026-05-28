package dev.puklic.voice

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DaveDowngradeDetectorTest {

    @Test
    fun `Active to Disabled is a downgrade`() {
        DaveDowngradeDetector.isDowngrade(
            DaveUiState.Active(epoch = 3),
            DaveUiState.Disabled("unsupported version"),
        ) shouldBe true
    }

    @Test
    fun `Active to Off is a downgrade`() {
        DaveDowngradeDetector.isDowngrade(
            DaveUiState.Active(epoch = 3),
            DaveUiState.Off,
        ) shouldBe true
    }

    @Test
    fun `Active to Active with new epoch is not a downgrade`() {
        DaveDowngradeDetector.isDowngrade(
            DaveUiState.Active(epoch = 3),
            DaveUiState.Active(epoch = 4),
        ) shouldBe false
    }

    @Test
    fun `Off to Active is not a downgrade`() {
        DaveDowngradeDetector.isDowngrade(
            DaveUiState.Off,
            DaveUiState.Active(epoch = 1),
        ) shouldBe false
    }

    @Test
    fun `Connecting to Active is not a downgrade`() {
        DaveDowngradeDetector.isDowngrade(
            DaveUiState.Connecting,
            DaveUiState.Active(epoch = 1),
        ) shouldBe false
    }

    @Test
    fun `Connecting to Disabled is not a downgrade - never reached E2EE`() {
        DaveDowngradeDetector.isDowngrade(
            DaveUiState.Connecting,
            DaveUiState.Disabled("unsupported"),
        ) shouldBe false
    }

    @Test
    fun `Active to Connecting is not a downgrade - handshake in progress`() {
        DaveDowngradeDetector.isDowngrade(
            DaveUiState.Active(epoch = 3),
            DaveUiState.Connecting,
        ) shouldBe false
    }
}
