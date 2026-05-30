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
class TradingSignalDaoTest {

    private lateinit var db: TradingDatabase
    private lateinit var dao: TradingSignalDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.signalDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndGetAllSignals() = runBlocking {
        val signal = createTestSignal()
        dao.insertSignal(signal)
        val signals = dao.getAllSignals().first()
        assertEquals(1, signals.size)
        assertEquals("BTC/EUR", signals[0].pair)
    }

    @Test
    fun getSignalsByStatus() = runBlocking {
        dao.insertSignal(createTestSignal(status = SignalStatus.PENDING))
        dao.insertSignal(createTestSignal(pair = "ETH/EUR", status = SignalStatus.EXECUTED))
        val pending = dao.getSignalsByStatus(SignalStatus.PENDING).first()
        assertEquals(1, pending.size)
        assertEquals(SignalStatus.PENDING, pending[0].status)
    }

    @Test
    fun getPendingSignalByPair() = runBlocking {
        dao.insertSignal(createTestSignal(pair = "BTC/EUR", status = SignalStatus.PENDING))
        val result = dao.getPendingSignalByPair("BTC/EUR", SignalStatus.PENDING)
        assertNotNull(result)
        assertEquals("BTC/EUR", result!!.pair)
    }

    @Test
    fun getSignalsByStatusList() = runBlocking {
        dao.insertSignal(createTestSignal(status = SignalStatus.PENDING))
        val list = dao.getSignalsByStatusList(SignalStatus.PENDING)
        assertEquals(1, list.size)
    }

    @Test
    fun getSignalById() = runBlocking {
        val id = dao.insertSignal(createTestSignal())
        val result = dao.getSignalById(id)
        assertNotNull(result)
        assertEquals(id, result!!.id)
    }

    @Test
    fun updateSignal() = runBlocking {
        val id = dao.insertSignal(createTestSignal())
        val original = dao.getSignalById(id)!!
        dao.updateSignal(original.copy(strength = 0.95))
        val updated = dao.getSignalById(id)
        assertEquals(0.95, updated!!.strength, 0.001)
    }

    @Test
    fun deleteSignal() = runBlocking {
        val id = dao.insertSignal(createTestSignal())
        val signal = dao.getSignalById(id)!!
        dao.deleteSignal(signal)
        val signals = dao.getAllSignals().first()
        assertTrue(signals.isEmpty())
    }

    @Test
    fun deleteOldSignals() = runBlocking {
        val oldSignal = createTestSignal(timestamp = 1000L, status = SignalStatus.EXECUTED)
        val newSignal = createTestSignal(pair = "ETH/EUR", timestamp = System.currentTimeMillis(), status = SignalStatus.PENDING)
        dao.insertSignal(oldSignal)
        dao.insertSignal(newSignal)
        dao.deleteOldSignals(50000L)
        val signals = dao.getAllSignals().first()
        assertEquals(1, signals.size)
        assertEquals("ETH/EUR", signals[0].pair)
    }

    @Test
    fun insertMultipleSignalsAndQueryByPair() = runBlocking {
        dao.insertSignal(createTestSignal(pair = "BTC/EUR"))
        dao.insertSignal(createTestSignal(pair = "ETH/EUR"))
        dao.insertSignal(createTestSignal(pair = "SOL/EUR"))
        val btcSignal = dao.getPendingSignalByPair("BTC/EUR", SignalStatus.PENDING)
        assertNotNull(btcSignal)
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
}
