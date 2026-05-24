package dev.puklic.ui.screens.main

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.ui.screens.settings.SettingsCategory
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * RED tests for the Settings overlay state surface introduced by issue #8 (architect report
 * v2 §5). MainViewModel must expose `settingsOpen` + `selectedSettingsCategory` StateFlows
 * and three controls: openSettings, closeSettings, selectSettingsCategory.
 *
 * Until impl lands (Step 6), these tests fail with unresolved-reference for those symbols.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSettingsTest {

    @Test
    fun openSettings_with_ACCOUNT_updates_both_flows() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this)
        vm.openSettings(SettingsCategory.ACCOUNT)
        vm.settingsOpen.value shouldBe true
        vm.selectedSettingsCategory.value shouldBe SettingsCategory.ACCOUNT
    }

    @Test
    fun openSettings_without_arg_preserves_last_category() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this)
        vm.selectSettingsCategory(SettingsCategory.STORAGE)
        vm.openSettings(null)
        vm.settingsOpen.value shouldBe true
        vm.selectedSettingsCategory.value shouldBe SettingsCategory.STORAGE
    }

    @Test
    fun selectSettingsCategory_updates_flow_without_opening() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this)
        vm.selectSettingsCategory(SettingsCategory.APPEARANCE)
        vm.selectedSettingsCategory.value shouldBe SettingsCategory.APPEARANCE
        vm.settingsOpen.value shouldBe false
    }

    @Test
    fun closeSettings_clears_open_flag_but_preserves_category() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this)
        vm.openSettings(SettingsCategory.NOTIFICATIONS)
        vm.closeSettings()
        vm.settingsOpen.value shouldBe false
        vm.selectedSettingsCategory.value shouldBe SettingsCategory.NOTIFICATIONS
    }

    @Test
    fun default_category_on_first_open_is_ACCOUNT() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this)
        vm.openSettings(null)
        vm.selectedSettingsCategory.value shouldBe SettingsCategory.ACCOUNT
    }

    private fun newVm(scope: kotlinx.coroutines.test.TestScope): MainViewModel =
        MainViewModel(
            componentContext = newComponentContext(),
            orchestrators = null,
            sessionTransport = null,
            externalScope = scope,
        )

    private fun newComponentContext(): DefaultComponentContext {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return ctx
    }
}
