package com.uscrooge.app.executor

import com.uscrooge.app.TestDataFactory
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.integration.GitHubIssueReporter
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrderExecutorTest {

    private val krakenApiClient: KrakenApiClient = mockk()
    private val alpacaApiClient: AlpacaApiClient = mockk()
    private val brokerRegistry: BrokerRegistry = mockk()
    private val signalDao: TradingSignalDao = mockk()
    private val orderDao: OrderDao = mockk()
    private val positionDao: PositionDao = mockk()
    private val circuitBreaker: CircuitBreaker = mockk()
    private val gitHubIssueReporter: GitHubIssueReporter = mockk()

    private lateinit var executor: OrderExecutor

    @Before
    fun setup() {
        executor = OrderExecutor(
            krakenApiClient = krakenApiClient,
            alpacaApiClient = alpacaApiClient,
            brokerRegistry = brokerRegistry,
            signalDao = signalDao,
            orderDao = orderDao,
            positionDao = positionDao,
            circuitBreaker = circuitBreaker,
            gitHubIssueReporter = gitHubIssueReporter
        )
        executor.updateConfig(TradingConfig())
    }

    @Test
    fun `checkTradingAllowed returns circuit breaker reason`() = runTest {
        coEvery { circuitBreaker.checkTradingAllowed(any()) } returns "Circuit breaker active"
        val result = executor.checkTradingAllowed()
        assertEquals("Circuit breaker active", result)
    }

    @Test
    fun `checkTradingAllowed returns null when allowed`() = runTest {
        coEvery { circuitBreaker.checkTradingAllowed(any()) } returns null
        assertNull(executor.checkTradingAllowed())
    }

    @Test
    fun `executeSignal fails when circuit breaker blocks`() = runTest {
        coEvery { circuitBreaker.checkTradingAllowed(any()) } returns "Blocked"
        coEvery { signalDao.updateSignal(any()) } returns Unit
        val signal = TestDataFactory.createTradingSignal()
        val result = executor.executeSignal(signal)
        assertTrue(result.isFailure)
    }

    @Test
    fun `ignoreSignal marks signal as ignored`() = runTest {
        coEvery { signalDao.updateSignal(any()) } returns Unit
        val signal = TestDataFactory.createTradingSignal()
        executor.ignoreSignal(signal)
    }
}
