package com.uscrooge.app.ui.screen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uscrooge.app.data.model.*
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.HealthCheckRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.ui.viewmodel.DashboardViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val repository: TradingRepository = mockk(relaxed = true)
    private val configRepository: ConfigRepository = mockk(relaxed = true)
    private val healthCheckRepository: HealthCheckRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createEmptyPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createEmptyHealth())
    }

    @Test
    fun successStateShowsPortfolioTitle() {
        val viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Portfolio").assertExists()
    }

    @Test
    fun successStateShowsRefreshButton() {
        val viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository)
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Refresh").assertExists()
    }

    @Test
    fun emptyPositionsShowsNoOpenPositionsText() {
        val viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository)
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("No open positions").assertExists()
    }

    @Test
    fun errorStateShowsErrorMessage() {
        coEvery { repository.syncOpenPositionsFromKraken(any()) } throws RuntimeException("Test error message")
        val viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository)
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Error: Test error message").assertExists()
    }

    @Test
    fun errorStateShowsRetryButton() {
        coEvery { repository.syncOpenPositionsFromKraken(any()) } throws RuntimeException("Something broke")
        val viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository)
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Retry").assertExists()
    }

    private fun createEmptyPortfolio() = Portfolio(
        totalInvested = 0.0,
        currentValue = 0.0,
        totalPnL = 0.0,
        totalPnLPercent = 0.0,
        positions = emptyList(),
        availableBalance = 1000.0,
        availableBalanceSource = "Test"
    )

    private fun createEmptyHealth() = SystemHealth(
        brokers = emptyMap(),
        fearGreed = null,
        lastUpdated = System.currentTimeMillis()
    )
}
