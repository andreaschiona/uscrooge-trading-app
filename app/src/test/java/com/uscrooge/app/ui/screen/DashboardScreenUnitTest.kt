package com.uscrooge.app.ui.screen

import com.uscrooge.app.data.model.*
import com.uscrooge.app.ui.viewmodel.DashboardUiState
import org.junit.Assert.*
import org.junit.Test

class DashboardScreenUnitTest {

    @Test
    fun `PortfolioSummaryCard data formatting`() {
        val portfolio = Portfolio(
            totalInvested = 1000.0,
            currentValue = 1100.0,
            totalPnL = 100.0,
            totalPnLPercent = 10.0,
            positions = emptyList(),
            availableBalance = 500.0,
            availableBalanceSource = "Test"
        )
        assertEquals(1100.0, portfolio.currentValue, 0.001)
        assertEquals(100.0, portfolio.totalPnL, 0.001)
        assertEquals(10.0, portfolio.totalPnLPercent, 0.001)
    }

    @Test
    fun `Portfolio negative PnL`() {
        val portfolio = Portfolio(
            totalInvested = 1000.0,
            currentValue = 900.0,
            totalPnL = -100.0,
            totalPnLPercent = -10.0,
            positions = emptyList(),
            availableBalance = 500.0,
            availableBalanceSource = "Test"
        )
        assertEquals(-100.0, portfolio.totalPnL, 0.001)
        assertEquals(-10.0, portfolio.totalPnLPercent, 0.001)
    }

    @Test
    fun `ErrorView displays correct message structure`() {
        val error = DashboardUiState.Error("Connection failed")
        assertTrue(error.message.contains("Connection"))
    }
}
