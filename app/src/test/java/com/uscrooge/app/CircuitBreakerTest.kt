package com.uscrooge.app

import com.uscrooge.app.executor.CircuitBreaker
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.model.TradingConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CircuitBreakerTest {

    private lateinit var circuitBreaker: CircuitBreaker
    private val orderDao: OrderDao = mockk()
    private val positionDao: PositionDao = mockk()
    private val tradeJournalDao: TradeJournalDao = mockk()

    @Before
    fun setup() {
        circuitBreaker = CircuitBreaker(orderDao, positionDao, tradeJournalDao)
        coEvery { orderDao.getTradeCountSince(any()) } returns 0
        coEvery { positionDao.getOpenPositions() } returns flowOf(emptyList())
        coEvery { tradeJournalDao.getTotalPnLSince(any()) } returns 0.0
    }

    @Test
    fun `trading allowed when circuit breaker disabled`() = runTest {
        val config = TradingConfig(circuitBreakerEnabled = false)
        assertNull(circuitBreaker.checkTradingAllowed(config))
    }

    @Test
    fun `trading allowed under normal conditions`() = runTest {
        val config = TradingConfig()
        assertNull(circuitBreaker.checkTradingAllowed(config))
    }

    @Test
    fun `blocks trading when max daily trades reached`() = runTest {
        coEvery { orderDao.getTradeCountSince(any()) } returns 10
        val config = TradingConfig(maxDailyTrades = 5)
        assertNotNull(circuitBreaker.checkTradingAllowed(config))
    }

    @Test
    fun `blocks trading when consecutive failures exceed limit`() = runTest {
        val config = TradingConfig(maxConsecutiveFailures = 3)
        repeat(3) { circuitBreaker.recordFailure() }
        assertNotNull(circuitBreaker.checkTradingAllowed(config))
    }

    @Test
    fun `resets consecutive failures on success`() = runTest {
        val config = TradingConfig(maxConsecutiveFailures = 3)
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        circuitBreaker.recordSuccess()
        circuitBreaker.recordFailure()
        assertNull(circuitBreaker.checkTradingAllowed(config))
    }

    @Test
    fun `blocks trading on daily drawdown`() = runTest {
        val position = TestDataFactory.createPosition(currentPrice = 90.0)
        coEvery { positionDao.getOpenPositions() } returns flowOf(listOf(position))
        val config = TradingConfig(maxDailyDrawdownPercent = 5.0)
        assertNotNull(circuitBreaker.checkTradingAllowed(config))
    }

    @Test
    fun `reset clears tripped state`() = runTest {
        coEvery { orderDao.getTradeCountSince(any()) } returns 10
        val config = TradingConfig(maxDailyTrades = 5)
        assertNotNull(circuitBreaker.checkTradingAllowed(config))
        assertTrue(circuitBreaker.isTripped())
        circuitBreaker.reset()
        assertFalse(circuitBreaker.isTripped())
    }

    @Test
    fun `recordFailure increments consecutive counter`() {
        assertFalse(circuitBreaker.isTripped())
        circuitBreaker.recordFailure()
        circuitBreaker.recordFailure()
        assertNull(circuitBreaker.getTripReason())
    }
}
