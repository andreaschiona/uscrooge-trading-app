package com.uscrooge.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uscrooge.app.data.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TradeJournalDaoTest {

    private lateinit var db: TradingDatabase
    private lateinit var dao: TradeJournalDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.tradeJournalDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndGetAllEntries() = runBlocking {
        dao.insertEntry(createTestEntry())
        val entries = dao.getAllEntries().first()
        assertEquals(1, entries.size)
    }

    @Test
    fun getEntriesByPair() = runBlocking {
        dao.insertEntry(createTestEntry(pair = "BTC/EUR"))
        dao.insertEntry(createTestEntry(pair = "ETH/EUR"))
        val btc = dao.getEntriesByPair("BTC/EUR").first()
        assertEquals(1, btc.size)
    }

    @Test
    fun getEntriesBySide() = runBlocking {
        dao.insertEntry(createTestEntry(side = OrderSide.BUY))
        dao.insertEntry(createTestEntry(side = OrderSide.SELL))
        val buys = dao.getEntriesBySide(OrderSide.BUY).first()
        assertEquals(1, buys.size)
    }

    @Test
    fun getEntryById() = runBlocking {
        val id = dao.insertEntry(createTestEntry())
        val entry = dao.getEntryById(id)
        assertNotNull(entry)
        assertEquals(id, entry!!.id)
    }

    @Test
    fun updateEntry() = runBlocking {
        val id = dao.insertEntry(createTestEntry())
        val entry = dao.getEntryById(id)!!
        dao.updateEntry(entry.copy(profitLoss = 200.0))
        val updated = dao.getEntryById(id)
        assertEquals(200.0, updated!!.profitLoss, 0.001)
    }

    @Test
    fun deleteEntry() = runBlocking {
        val id = dao.insertEntry(createTestEntry())
        val entry = dao.getEntryById(id)!!
        dao.deleteEntry(entry)
        val entries = dao.getAllEntries().first()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun getEntryCountSince() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertEntry(createTestEntry(exitTime = now))
        dao.insertEntry(createTestEntry(pair = "ETH/EUR", exitTime = now - 100000))
        val count = dao.getEntryCountSince(now - 50000)
        assertEquals(1, count)
    }

    @Test
    fun getTotalPnLSince() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertEntry(createTestEntry(profitLoss = 100.0, exitTime = now))
        dao.insertEntry(createTestEntry(pair = "ETH/EUR", profitLoss = -50.0, exitTime = now))
        val pnl = dao.getTotalPnLSince(now - 10000)
        assertEquals(50.0, pnl!!, 0.001)
    }

    @Test
    fun getTopWinners() = runBlocking {
        dao.insertEntry(createTestEntry(profitLoss = 50.0))
        dao.insertEntry(createTestEntry(pair = "ETH/EUR", profitLoss = 200.0))
        val winners = dao.getTopWinners().first()
        assertEquals(2, winners.size)
        assertEquals(200.0, winners[0].profitLoss, 0.001)
    }

    @Test
    fun getTopLosers() = runBlocking {
        dao.insertEntry(createTestEntry(profitLoss = -50.0))
        dao.insertEntry(createTestEntry(pair = "ETH/EUR", profitLoss = -200.0))
        val losers = dao.getTopLosers().first()
        assertEquals(2, losers.size)
        assertEquals(-200.0, losers[0].profitLoss, 0.001)
    }

    @Test
    fun getPairStatistics() = runBlocking {
        dao.insertEntry(createTestEntry(pair = "BTC/EUR", profitLoss = 100.0, profitLossPercent = 2.0))
        dao.insertEntry(createTestEntry(pair = "BTC/EUR", profitLoss = 50.0, profitLossPercent = 1.0))
        dao.insertEntry(createTestEntry(pair = "ETH/EUR", profitLoss = -30.0, profitLossPercent = -1.5))
        val stats = dao.getPairStatistics().first()
        assertEquals(2, stats.size)
        val btcStats = stats.find { it.pair == "BTC/EUR" }
        assertNotNull(btcStats)
        assertEquals(2, btcStats!!.tradeCount)
    }

    private fun createTestEntry(
        pair: String = "BTC/EUR",
        side: OrderSide = OrderSide.BUY,
        profitLoss: Double = 100.0,
        profitLossPercent: Double = 2.0,
        exitTime: Long = System.currentTimeMillis()
    ): TradeJournalEntry = TradeJournalEntry(
        pair = pair,
        side = side,
        entryPrice = 50000.0,
        exitPrice = 51000.0,
        amount = 0.01,
        entryTime = exitTime - 3600000,
        exitTime = exitTime,
        profitLoss = profitLoss,
        profitLossPercent = profitLossPercent,
        fee = 1.3,
        exitReason = "Take profit",
        duration = 3600000,
        signalStrength = 0.8,
        signalReasons = "RSI oversold"
    )
}
