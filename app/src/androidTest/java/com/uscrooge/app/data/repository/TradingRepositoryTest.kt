package com.uscrooge.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.uscrooge.app.analysis.FearGreedService
import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.BrokerApi
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.api.TradeBalance
import com.uscrooge.app.data.local.TradingDatabase
import com.uscrooge.app.data.model.*
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TradingRepositoryTest {

    private lateinit var db: TradingDatabase
    private lateinit var context: Context
    private val krakenApiClient: KrakenApiClient = mockk()
    private val alpacaApiClient: AlpacaApiClient = mockk()
    private val brokerRegistry: BrokerRegistry = mockk()
    private val fearGreedService: FearGreedService = mockk()
    private val gitHubIssueReporter: GitHubIssueReporter = mockk()

    private lateinit var repository: TradingRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val strategy = TradingStrategy(TechnicalAnalyzer(), SentimentAnalyzer())
        val gson = Gson()

        repository = TradingRepository(
            context = context,
            krakenApiClient = krakenApiClient,
            alpacaApiClient = alpacaApiClient,
            brokerRegistry = brokerRegistry,
            signalDao = db.signalDao(),
            orderDao = db.orderDao(),
            positionDao = db.positionDao(),
            strategy = strategy,
            gson = gson,
            fearGreedService = fearGreedService,
            sentimentAnalyzer = SentimentAnalyzer(),
            gitHubIssueReporter = gitHubIssueReporter
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertSignalAndRetrieve() = runBlocking {
        val signal = createTestSignal()
        val id = repository.insertSignal(signal)
        assertTrue(id > 0)

        val signals = repository.getAllSignals().first()
        assertEquals(1, signals.size)
        assertEquals("BTC/EUR", signals[0].pair)
    }

    @Test
    fun insertAndUpdateSignal() = runBlocking {
        val id = repository.insertSignal(createTestSignal())
        val signal = repository.getSignalById(id)!!
        repository.updateSignal(signal.copy(strength = 0.95))
        val updated = repository.getSignalById(id)
        assertEquals(0.95, updated!!.strength, 0.001)
    }

    @Test
    fun getPendingSignalsReturnsOnlyPending() = runBlocking {
        repository.insertSignal(createTestSignal(pair = "BTC/EUR", status = SignalStatus.PENDING))
        repository.insertSignal(createTestSignal(pair = "ETH/EUR", status = SignalStatus.EXECUTED))
        val pending = repository.getPendingSignalsList()
        assertEquals(1, pending.size)
        assertEquals(SignalStatus.PENDING, pending[0].status)
    }

    @Test
    fun insertAndGetPosition() = runBlocking {
        val pos = createTestPosition()
        val id = repository.insertPosition(pos)
        assertTrue(id > 0)

        val positions = repository.getOpenPositions().first()
        assertEquals(1, positions.size)
        assertEquals("BTC/EUR", positions[0].pair)
    }

    @Test
    fun updatePositionAndVerify() = runBlocking {
        repository.insertPosition(createTestPosition())
        val pos = repository.getOpenPositionByPair("BTC/EUR")!!
        repository.updatePosition(pos.copy(currentPrice = 55000.0, isOpen = false))
        val updated = repository.getOpenPositionByPair("BTC/EUR")
        assertNull(updated)
        val allPositions = repository.getAllPositions().first()
        val closedPos = allPositions.find { it.pair == "BTC/EUR" }
        assertNotNull(closedPos)
        assertEquals(55000.0, closedPos!!.currentPrice, 0.001)
        assertFalse(closedPos.isOpen)
    }

    @Test
    fun closePositionMarksAsClosed() = runBlocking {
        repository.insertPosition(createTestPosition())
        val pos = repository.getOpenPositionByPair("BTC/EUR")!!
        repository.updatePosition(pos.copy(isOpen = false, closedAt = System.currentTimeMillis()))
        val open = repository.getOpenPositions().first()
        assertTrue(open.isEmpty())
    }

    @Test
    fun insertAndGetOrder() = runBlocking {
        val order = createTestOrder()
        repository.insertOrder(order)
        val orders = repository.getAllOrders().first()
        assertEquals(1, orders.size)
    }

    @Test
    fun getOrdersByStatus() = runBlocking {
        repository.insertOrder(createTestOrder(orderId = "o1", status = OrderStatus.OPEN))
        repository.insertOrder(createTestOrder(orderId = "o2", status = OrderStatus.CLOSED))
        val open = repository.getOrdersByStatus(OrderStatus.OPEN).first()
        assertEquals(1, open.size)
    }

    @Test
    fun getPendingSignalByPair() = runBlocking {
        repository.insertSignal(createTestSignal(pair = "BTC/EUR", status = SignalStatus.PENDING))
        val result = repository.getPendingSignalByPair("BTC/EUR")
        assertNotNull(result)
        assertEquals("BTC/EUR", result!!.pair)
    }

    @Test
    fun analyzePairWithoutApiFailsGracefully() = runBlocking {
        val config = TradingConfig(tradingPairs = listOf("BTC/EUR"))
        val broker: BrokerApi = mockk()
        coEvery { broker.getTicker(any()) } returns Result.failure(Exception("API not available"))
        coEvery { broker.getOHLC(any(), any()) } returns Result.failure(Exception("API not available"))

        val result = repository.analyzePairAndGenerateSignal(
            pair = "BTC/EUR",
            config = config,
            availableBalance = 1000.0,
            broker = broker
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun getPortfolioWithEmptyPositions() = runBlocking {
        val config = TradingConfig()
        coEvery { krakenApiClient.getAccountBalance() } returns Result.success(emptyMap())
        coEvery { krakenApiClient.getTradeBalance(any()) } returns Result.success(
            TradeBalance(eb = "0", tb = "1000.0", m = "0", n = "0", c = "0", v = "0", e = "1000", mf = "1000", ml = null)
        )
        coEvery { krakenApiClient.updateCredentials(any(), any(), any()) } returns Unit
        coEvery { alpacaApiClient.updateCredentials(any(), any(), any()) } returns Unit

        val portfolio = repository.getPortfolio(config)
        assertEquals(0.0, portfolio.totalInvested, 0.001)
        assertEquals(0.0, portfolio.currentValue, 0.001)
        assertTrue(portfolio.availableBalance > 0)
    }

    @Test
    fun cleanupOldDataRemovesOldSignals() = runBlocking {
        val oldSignal = createTestSignal(
            pair = "BTC/EUR",
            timestamp = 1000L,
            status = SignalStatus.EXECUTED
        )
        val newSignal = createTestSignal(
            pair = "ETH/EUR",
            timestamp = System.currentTimeMillis(),
            status = SignalStatus.PENDING
        )
        repository.insertSignal(oldSignal)
        repository.insertSignal(newSignal)
        repository.cleanupOldData()
        val signals = repository.getAllSignals().first()
        assertEquals(1, signals.size)
        assertEquals("ETH/EUR", signals[0].pair)
    }

    private fun createTestSignal(
        pair: String = "BTC/EUR",
        type: SignalType = SignalType.BUY,
        strength: Double = 0.8,
        status: SignalStatus = SignalStatus.PENDING,
        timestamp: Long = System.currentTimeMillis()
    ): TradingSignal = TradingSignal(
        pair = pair,
        type = type,
        strength = strength,
        currentPrice = 50000.0,
        suggestedPrice = 50100.0,
        stopLoss = 48500.0,
        takeProfit = 53000.0,
        suggestedAmount = 250.0,
        riskRewardRatio = 2.5,
        timestamp = timestamp,
        reasons = """["RSI oversold","MACD bullish"]""",
        status = status
    )

    private fun createTestPosition(
        pair: String = "BTC/EUR",
        isOpen: Boolean = true
    ): Position = Position(
        pair = pair,
        amount = 0.01,
        averageEntryPrice = 50000.0,
        currentPrice = 51000.0,
        peakPrice = 51000.0,
        totalInvested = 500.0,
        currentValue = 510.0,
        unrealizedPnL = 10.0,
        unrealizedPnLPercent = 2.0,
        openedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        isOpen = isOpen
    )

    private fun createTestOrder(
        orderId: String = "order-1",
        pair: String = "BTC/EUR",
        status: OrderStatus = OrderStatus.OPEN
    ): Order = Order(
        orderId = orderId,
        pair = pair,
        type = OrderType.MARKET,
        side = OrderSide.BUY,
        price = 50000.0,
        amount = 0.01,
        cost = 500.0,
        fee = 1.3,
        status = status,
        createdAt = System.currentTimeMillis()
    )
}
