package com.uscrooge.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.uscrooge.app.MockBrokerApi
import com.uscrooge.app.TestDataFactory
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.analysis.FearGreedService
import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.strategy.PositionSelectionStrategy
import com.uscrooge.app.strategy.SignalResult
import com.uscrooge.app.strategy.TradingStrategy
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TradingRepositoryTest {

    private val context: Context = mockk()
    private val krakenApiClient: KrakenApiClient = mockk()
    private val alpacaApiClient: AlpacaApiClient = mockk()
    private val brokerRegistry: BrokerRegistry = mockk()
    private val signalDao: TradingSignalDao = mockk()
    private val orderDao: OrderDao = mockk()
    private val positionDao: PositionDao = mockk()
    private val strategy: TradingStrategy = mockk()
    private val gson = Gson()
    private val fearGreedService: FearGreedService = mockk()
    private val sentimentAnalyzer: SentimentAnalyzer = mockk()
    private val gitHubIssueReporter: GitHubIssueReporter = mockk()
    private val positionSelectionStrategy: PositionSelectionStrategy = mockk()

    private lateinit var repository: TradingRepository

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { context.getSharedPreferences(any(), any()) } returns mockk {
            every { getString(any(), any()) } returns null
            every { edit() } returns mockk {
                every { putString(any(), any()) } returns this
                every { apply() } returns Unit
            }
        }
        every { context.applicationContext } returns context

        repository = TradingRepository(
            context = context,
            krakenApiClient = krakenApiClient,
            alpacaApiClient = alpacaApiClient,
            brokerRegistry = brokerRegistry,
            signalDao = signalDao,
            orderDao = orderDao,
            positionDao = positionDao,
            strategy = strategy,
            gson = gson,
            fearGreedService = fearGreedService,
            sentimentAnalyzer = sentimentAnalyzer,
            gitHubIssueReporter = gitHubIssueReporter,
            positionSelectionStrategy = positionSelectionStrategy
        )
    }

    @Test
    fun `getAllSignals delegates to signalDao`() {
        val expected = flowOf(emptyList<TradingSignal>())
        every { signalDao.getAllSignals() } returns expected
        val result = repository.getAllSignals()
        assertEquals(expected, result)
    }

    @Test
    fun `getPendingSignals delegates to signalDao`() {
        val expected = flowOf(emptyList<TradingSignal>())
        every { signalDao.getSignalsByStatus(SignalStatus.PENDING) } returns expected
        val result = repository.getPendingSignals()
        assertEquals(expected, result)
    }

    @Test
    fun `getOpenPositions delegates to positionDao`() {
        val expected = flowOf(emptyList<Position>())
        every { positionDao.getOpenPositions() } returns expected
        val result = repository.getOpenPositions()
        assertEquals(expected, result)
    }

    @Test
    fun `insertSignal delegates to signalDao and returns id`() = runTest {
        val signal = TestDataFactory.createTradingSignal()
        coEvery { signalDao.insertSignal(signal) } returns 42L
        val id = repository.insertSignal(signal)
        assertEquals(42L, id)
    }

    @Test
    fun `updateSignal delegates to signalDao`() = runTest {
        val signal = TestDataFactory.createTradingSignal()
        coEvery { signalDao.updateSignal(signal) } returns Unit
        repository.updateSignal(signal)
        coVerify { signalDao.updateSignal(signal) }
    }
}
