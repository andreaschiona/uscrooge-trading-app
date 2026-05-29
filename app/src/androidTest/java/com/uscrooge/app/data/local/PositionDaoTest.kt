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
class PositionDaoTest {

    private lateinit var db: TradingDatabase
    private lateinit var dao: PositionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.positionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndGetOpenPositions() = runBlocking {
        dao.insertPosition(createTestPosition())
        val positions = dao.getOpenPositions().first()
        assertEquals(1, positions.size)
        assertTrue(positions[0].isOpen)
    }

    @Test
    fun getOpenPositionsExcludesClosed() = runBlocking {
        dao.insertPosition(createTestPosition(isOpen = true))
        dao.insertPosition(createTestPosition(pair = "ETH/EUR", isOpen = false))
        val open = dao.getOpenPositions().first()
        assertEquals(1, open.size)
    }

    @Test
    fun getAllPositions() = runBlocking {
        dao.insertPosition(createTestPosition())
        dao.insertPosition(createTestPosition(pair = "ETH/EUR", isOpen = false))
        val all = dao.getAllPositions().first()
        assertEquals(2, all.size)
    }

    @Test
    fun getOpenPositionByPair() = runBlocking {
        dao.insertPosition(createTestPosition(pair = "BTC/EUR"))
        val result = dao.getOpenPositionByPair("BTC/EUR")
        assertNotNull(result)
        assertEquals("BTC/EUR", result!!.pair)
    }

    @Test
    fun getPositionById() = runBlocking {
        val id = dao.insertPosition(createTestPosition())
        val result = dao.getPositionById(id)
        assertNotNull(result)
        assertEquals(id, result!!.id)
    }

    @Test
    fun updatePosition() = runBlocking {
        val id = dao.insertPosition(createTestPosition())
        val original = dao.getPositionById(id)!!
        dao.updatePosition(original.copy(currentPrice = 55000.0, isOpen = false))
        val updated = dao.getPositionById(id)
        assertEquals(55000.0, updated!!.currentPrice, 0.001)
        assertFalse(updated.isOpen)
    }

    @Test
    fun deletePosition() = runBlocking {
        val id = dao.insertPosition(createTestPosition())
        val position = dao.getPositionById(id)!!
        dao.deletePosition(position)
        val positions = dao.getOpenPositions().first()
        assertTrue(positions.isEmpty())
    }

    @Test
    fun getOpenPositionsCount() = runBlocking {
        dao.insertPosition(createTestPosition())
        dao.insertPosition(createTestPosition(pair = "ETH/EUR"))
        val count = dao.getOpenPositionsCount()
        assertEquals(2, count)
    }

    @Test
    fun closePosition() = runBlocking {
        val id = dao.insertPosition(createTestPosition())
        val position = dao.getPositionById(id)!!
        dao.updatePosition(position.copy(isOpen = false, closedAt = System.currentTimeMillis()))
        val open = dao.getOpenPositions().first()
        assertTrue(open.isEmpty())
    }

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
}
