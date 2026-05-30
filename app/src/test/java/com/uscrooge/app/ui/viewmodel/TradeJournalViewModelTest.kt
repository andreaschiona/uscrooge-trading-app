package com.uscrooge.app.ui.viewmodel

import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.model.*
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
class TradeJournalViewModelTest {

    private val tradeJournalDao: TradeJournalDao = mockk()

    private lateinit var viewModel: TradeJournalViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        clearAllMocks()
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads entries from dao`() = runTest {
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(emptyList())
        viewModel = TradeJournalViewModel(tradeJournalDao)
        assertTrue(viewModel.entries.value.isEmpty())
    }

    @Test
    fun `entries are populated from dao`() = runTest {
        val entries = listOf(
            createTestEntry(pair = "BTC/EUR", profitLoss = 100.0),
            createTestEntry(pair = "ETH/EUR", profitLoss = -50.0)
        )
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(entries)

        viewModel = TradeJournalViewModel(tradeJournalDao)

        assertEquals(2, viewModel.entries.value.size)
        assertNotNull(viewModel.stats.value)
    }

    @Test
    fun `filterByPair filters entries`() = runTest {
        val entries = listOf(
            createTestEntry(pair = "BTC/EUR", profitLoss = 100.0),
            createTestEntry(pair = "ETH/EUR", profitLoss = -50.0),
            createTestEntry(pair = "BTC/EUR", profitLoss = 50.0)
        )
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(entries)

        viewModel = TradeJournalViewModel(tradeJournalDao)
        viewModel.filterByPair("BTC/EUR")

        assertEquals(2, viewModel.entries.value.size)
        assertTrue(viewModel.entries.value.all { it.pair == "BTC/EUR" })
    }

    @Test
    fun `filterByPair null shows all entries`() = runTest {
        val entries = listOf(
            createTestEntry(pair = "BTC/EUR"),
            createTestEntry(pair = "ETH/EUR")
        )
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(entries)

        viewModel = TradeJournalViewModel(tradeJournalDao)
        viewModel.filterByPair(null)

        assertEquals(2, viewModel.entries.value.size)
    }

    @Test
    fun `stats are computed correctly`() = runTest {
        val entries = listOf(
            createTestEntry(pair = "BTC/EUR", profitLoss = 100.0, duration = 3600000),
            createTestEntry(pair = "ETH/EUR", profitLoss = -50.0, duration = 7200000),
            createTestEntry(pair = "SOL/EUR", profitLoss = 200.0, duration = 1800000)
        )
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(entries)

        viewModel = TradeJournalViewModel(tradeJournalDao)

        val stats = viewModel.stats.value
        assertNotNull(stats)
        assertEquals(3, stats!!.tradeCount)
        assertEquals(250.0, stats.totalPnL, 0.001)
        assertEquals(2, stats.winCount)
        assertEquals(1, stats.lossCount)
        assertEquals(2.0 / 3.0, stats.winRate, 0.001)
    }

    @Test
    fun `stats are null when entries are empty`() = runTest {
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(emptyList())

        viewModel = TradeJournalViewModel(tradeJournalDao)

        assertNull(viewModel.stats.value)
    }

    @Test
    fun `selectedPair is exposed as state flow`() = runTest {
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(emptyList())

        viewModel = TradeJournalViewModel(tradeJournalDao)
        viewModel.filterByPair("BTC/EUR")

        assertEquals("BTC/EUR", viewModel.selectedPair.value)
    }

    @Test
    fun `stats includes distinct pairs`() = runTest {
        val entries = listOf(
            createTestEntry(pair = "BTC/EUR"),
            createTestEntry(pair = "ETH/EUR")
        )
        coEvery { tradeJournalDao.getAllEntries() } returns flowOf(entries)

        viewModel = TradeJournalViewModel(tradeJournalDao)

        val stats = viewModel.stats.value
        assertNotNull(stats)
        assertTrue(stats!!.pairs.containsAll(listOf("BTC/EUR", "ETH/EUR")))
    }

    private fun createTestEntry(
        pair: String = "BTC/EUR",
        profitLoss: Double = 100.0,
        duration: Long = 3600000
    ): TradeJournalEntry = TradeJournalEntry(
        pair = pair,
        side = OrderSide.BUY,
        entryPrice = 50000.0,
        exitPrice = 51000.0,
        amount = 0.01,
        entryTime = System.currentTimeMillis() - duration,
        exitTime = System.currentTimeMillis(),
        profitLoss = profitLoss,
        profitLossPercent = (profitLoss / 500.0) * 100,
        fee = 1.3,
        exitReason = "Take profit",
        duration = duration,
        signalStrength = 0.8,
        signalReasons = "RSI oversold"
    )
}
