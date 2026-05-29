package com.uscrooge.app.ui.screen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uscrooge.app.data.model.*
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.HealthCheckRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.ui.viewmodel.DashboardUiState
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

    private lateinit var viewModel: DashboardViewModel
    private val repository: TradingRepository = mockk(relaxed = true)
    private val configRepository: ConfigRepository = mockk(relaxed = true)
    private val healthCheckRepository: HealthCheckRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createEmptyPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createEmptyHealth())
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun loadingStateShowsCircularProgressIndicator() {
        viewModel = TestDashboardViewModel(repository, configRepository, healthCheckRepository)
        viewModel._uiState.value = DashboardUiState.Loading
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithTag("CircularProgressIndicator").assertExists()
    }

    @Test
    fun successStateShowsPortfolioTitle() {
        viewModel = TestDashboardViewModel(repository, configRepository, healthCheckRepository)
        val portfolio = createEmptyPortfolio()
        viewModel._uiState.value = DashboardUiState.Success(portfolio, emptyList())
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Portfolio").assertExists()
    }

    @Test
    fun successStateShowsRefreshButton() {
        viewModel = TestDashboardViewModel(repository, configRepository, healthCheckRepository)
        val portfolio = createEmptyPortfolio()
        viewModel._uiState.value = DashboardUiState.Success(portfolio, emptyList())
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Refresh").assertExists()
    }

    @Test
    fun errorStateShowsErrorMessage() {
        viewModel = TestDashboardViewModel(repository, configRepository, healthCheckRepository)
        viewModel._uiState.value = DashboardUiState.Error("Test error message")
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Error: Test error message").assertExists()
    }

    @Test
    fun errorStateShowsRetryButton() {
        viewModel = TestDashboardViewModel(repository, configRepository, healthCheckRepository)
        viewModel._uiState.value = DashboardUiState.Error("Something broke")
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Retry").assertExists()
    }

    @Test
    fun emptyPositionsShowsNoOpenPositionsText() {
        viewModel = TestDashboardViewModel(repository, configRepository, healthCheckRepository)
        val portfolio = createEmptyPortfolio()
        viewModel._uiState.value = DashboardUiState.Success(portfolio, emptyList())
        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }
        composeTestRule.onText("No open positions").assertExists()
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

class TestDashboardViewModel(
    repository: TradingRepository,
    configRepository: ConfigRepository,
    healthCheckRepository: HealthCheckRepository
) : DashboardViewModel(repository, configRepository, healthCheckRepository) {
    val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    override val uiState = _uiState
}
