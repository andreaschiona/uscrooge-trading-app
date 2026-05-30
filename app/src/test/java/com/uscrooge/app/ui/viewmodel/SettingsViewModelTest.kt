package com.uscrooge.app.ui.viewmodel

import android.content.Context
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.update.UpdateChecker
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val context: Context = mockk()
    private val configRepository: ConfigRepository = mockk()
    private val gitHubIssueReporter: GitHubIssueReporter = mockk()
    private val updateChecker: UpdateChecker = mockk()

    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        clearAllMocks()
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads config from repository`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())

        viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter, updateChecker)

        assertNotNull(viewModel.config.value)
        assertEquals(0.25, viewModel.config.value!!.riskPerTrade, 0.001)
    }

    @Test
    fun `save state defaults to idle`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())

        viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter, updateChecker)

        assertTrue(viewModel.saveState.value is SaveState.Idle)
    }

    @Test
    fun `updateConfig valid config saves successfully`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { configRepository.updateConfig(any()) } returns Unit
        every { gitHubIssueReporter.configureToken(any()) } returns Unit

        viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter, updateChecker)

        val newConfig = TradingConfig(riskPerTrade = 0.5, tradingPairs = listOf("BTC/EUR"))
        viewModel.updateConfig(newConfig)

        coVerify { configRepository.updateConfig(any()) }
    }

    @Test
    fun `resetToDefaults calls repository`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { configRepository.resetToDefaults() } returns Unit

        viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter, updateChecker)
        viewModel.resetToDefaults()

        coVerify { configRepository.resetToDefaults() }
    }

    @Test
    fun `config flow is collected and state is updated`() = runTest {
        val testConfig = TradingConfig(riskPerTrade = 0.5, maxOpenPositions = 5)
        coEvery { configRepository.configFlow } returns flowOf(testConfig)

        viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter, updateChecker)

        assertEquals(0.5, viewModel.config.value!!.riskPerTrade, 0.001)
        assertEquals(5, viewModel.config.value!!.maxOpenPositions)
    }
}
