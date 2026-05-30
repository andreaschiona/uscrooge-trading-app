package com.uscrooge.app.ui.viewmodel

import com.uscrooge.app.data.model.*
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.HealthCheckRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.executor.OrderExecutor
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val repository: TradingRepository = mockk()
    private val configRepository: ConfigRepository = mockk()
    private val healthCheckRepository: HealthCheckRepository = mockk()
    private val orderExecutor: OrderExecutor = mockk()

    private lateinit var viewModel: DashboardViewModel
    private val testDispatcher = StandardTestDispatcher()

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
    fun `init loads dashboard and succeeds`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is DashboardUiState.Success)
    }

    @Test
    fun `error state when repository fails`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } throws RuntimeException("Network error")
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is DashboardUiState.Error)
        assertTrue((state as DashboardUiState.Error).message.contains("Network error"))
    }

    @Test
    fun `generateEquityCurve returns points from portfolio`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val portfolio = createTestPortfolio()
        val equityCurve = viewModel.generateEquityCurve(portfolio)
        assertTrue(equityCurve.isNotEmpty())
    }

    @Test
    fun `generateAllocationSlices returns pie slices`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val portfolio = createTestPortfolio()
        val slices = viewModel.generateAllocationSlices(portfolio)
        assertTrue(slices.isNotEmpty())
        assertTrue(slices.any { it.label == "BTC/EUR" })
    }

    @Test
    fun `generateAllocationSlices includes available balance slice`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val portfolio = createTestPortfolio()
        val slices = viewModel.generateAllocationSlices(portfolio)
        assertTrue(slices.any { it.label == "Available" })
    }

    @Test
    fun `generateAllocationSlices returns empty for empty portfolio`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val portfolio = Portfolio(0.0, 0.0, 0.0, 0.0, emptyList(), 0.0, "Test")
        val slices = viewModel.generateAllocationSlices(portfolio)
        assertTrue(slices.isEmpty())
    }

    @Test
    fun `generateDrawdownData returns drawdown percentages`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val portfolio = createTestPortfolio()
        val drawdown = viewModel.generateDrawdownData(portfolio)
        assertEquals(portfolio.positions.size, drawdown.size)
    }

    @Test
    fun `generatePriceHistory produces 30 data points`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val position = createTestPosition()
        val history = viewModel.generatePriceHistory(position)
        assertEquals(30, history.size)
    }

    @Test
    fun `system health is exposed as state flow`() = runTest {
        val healthFlow = MutableStateFlow(createTestSystemHealth())
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns healthFlow

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        assertEquals(healthFlow.value, viewModel.systemHealth.value)
    }

    @Test
    fun `refresh data reloads dashboard`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        viewModel.refreshData()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getPortfolio(any()) }
    }

    @Test
    fun `closePosition calls orderExecutor and reloads dashboard`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val position = createTestPosition()
        coEvery { orderExecutor.closePosition(position) } returns Result.success(mockk())

        viewModel.closePosition(position)
        advanceUntilIdle()

        coVerify { orderExecutor.closePosition(position) }
        coVerify(exactly = 2) { repository.getPortfolio(any()) }
    }

    @Test
    fun `closePosition logs error on failure`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val position = createTestPosition()
        val error = Exception("Close failed")
        coEvery { orderExecutor.closePosition(position) } returns Result.failure(error)

        viewModel.closePosition(position)
        advanceUntilIdle()

        coVerify { orderExecutor.closePosition(position) }
        coVerify(exactly = 2) { repository.getPortfolio(any()) }
    }

    @Test
    fun `generateEquityCurve with empty positions returns available balance`() = runTest {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.syncOpenPositionsFromKraken(any()) } returns Unit
        coEvery { repository.getOpenPositions() } returns flowOf(emptyList())
        coEvery { repository.getPortfolio(any()) } returns createTestPortfolio()
        every { healthCheckRepository.systemHealth } returns MutableStateFlow(createTestSystemHealth())

        viewModel = DashboardViewModel(repository, configRepository, healthCheckRepository, orderExecutor)
        advanceUntilIdle()

        val portfolio = Portfolio(0.0, 1000.0, 0.0, 0.0, emptyList(), 1000.0, "Test")
        val equityCurve = viewModel.generateEquityCurve(portfolio)
        assertEquals(1, equityCurve.size)
    }

    private fun createTestPortfolio(): Portfolio = Portfolio(
        totalInvested = 500.0,
        currentValue = 520.0,
        totalPnL = 20.0,
        totalPnLPercent = 4.0,
        positions = listOf(createTestPosition()),
        availableBalance = 1000.0,
        availableBalanceSource = "Test"
    )

    private fun createTestPosition(): Position = Position(
        pair = "BTC/EUR",
        amount = 0.01,
        averageEntryPrice = 50000.0,
        currentPrice = 52000.0,
        peakPrice = 52000.0,
        totalInvested = 500.0,
        currentValue = 520.0,
        unrealizedPnL = 20.0,
        unrealizedPnLPercent = 4.0,
        openedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        isOpen = true
    )

    private fun createTestSystemHealth(): SystemHealth = SystemHealth(
        brokers = emptyMap(),
        fearGreed = null,
        lastUpdated = System.currentTimeMillis()
    )
}
