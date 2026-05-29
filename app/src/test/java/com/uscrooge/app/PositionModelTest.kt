package com.uscrooge.app

import com.uscrooge.app.data.model.Position
import org.junit.Assert.*
import org.junit.Test

class PositionModelTest {

    @Test
    fun `calculateCurrentValue updates price and PnL`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 100.0)
        val updated = position.calculateCurrentValue(110.0)
        assertEquals(110.0, updated.currentPrice, 0.001)
        assertEquals(110.0, updated.currentValue, 0.001)
        assertEquals(10.0, updated.unrealizedPnL, 0.001)
    }

    @Test
    fun `calculateCurrentValue updates percent PnL`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 100.0)
        val updated = position.calculateCurrentValue(120.0)
        assertEquals(20.0, updated.unrealizedPnLPercent, 0.001)
    }

    @Test
    fun `calculateCurrentValue updates peak price`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 100.0, peakPrice = 105.0)
        val updated = position.calculateCurrentValue(110.0)
        assertEquals(110.0, updated.peakPrice, 0.001)
    }

    @Test
    fun `calculateCurrentValue does not decrease peak price`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 105.0, peakPrice = 105.0)
        val updated = position.calculateCurrentValue(102.0)
        assertEquals(105.0, updated.peakPrice, 0.001)
    }

    @Test
    fun `calculateCurrentValue negative PnL`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 100.0)
        val updated = position.calculateCurrentValue(90.0)
        assertEquals(-10.0, updated.unrealizedPnL, 0.001)
        assertEquals(-10.0, updated.unrealizedPnLPercent, 0.001)
    }

    @Test
    fun `calculateCurrentValue with zero investment`() {
        val position = createPosition(averageEntryPrice = 100.0, currentPrice = 100.0, amount = 0.0)
        val updated = position.calculateCurrentValue(110.0)
        assertEquals(0.0, updated.unrealizedPnLPercent, 0.001)
    }

    @Test
    fun `calculateCurrentValue updates timestamp`() {
        val position = createPosition()
        val before = position.updatedAt
        Thread.sleep(10)
        val updated = position.calculateCurrentValue(position.currentPrice)
        assertTrue(updated.updatedAt > before)
    }

    @Test
    fun `copy with modified fields`() {
        val position = createPosition()
        val closed = position.copy(isOpen = false, closedAt = System.currentTimeMillis())
        assertFalse(closed.isOpen)
        assertNotNull(closed.closedAt)
    }

    private fun createPosition(
        amount: Double = 1.0,
        averageEntryPrice: Double = 100.0,
        currentPrice: Double = 100.0,
        peakPrice: Double = 100.0
    ): Position = Position(
        pair = "BTC/EUR",
        amount = amount,
        averageEntryPrice = averageEntryPrice,
        currentPrice = currentPrice,
        peakPrice = peakPrice,
        totalInvested = amount * averageEntryPrice,
        currentValue = amount * currentPrice,
        unrealizedPnL = amount * (currentPrice - averageEntryPrice),
        unrealizedPnLPercent = ((currentPrice - averageEntryPrice) / averageEntryPrice) * 100,
        openedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        isOpen = true
    )
}
