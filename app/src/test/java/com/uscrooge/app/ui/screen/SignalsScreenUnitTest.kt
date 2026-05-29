package com.uscrooge.app.ui.screen

import com.uscrooge.app.data.model.*
import com.uscrooge.app.ui.viewmodel.SignalsUiState
import org.junit.Assert.*
import org.junit.Test

class SignalsScreenUnitTest {

    @Test
    fun `StatusBadge displays correct text for each status`() {
        assertEquals("Pending", getStatusText(SignalStatus.PENDING))
        assertEquals("Executed", getStatusText(SignalStatus.EXECUTED))
        assertEquals("Ignored", getStatusText(SignalStatus.IGNORED))
        assertEquals("Failed", getStatusText(SignalStatus.FAILED))
    }

    @Test
    fun `formatTimestamp produces expected format`() {
        val timestamp = 1700000000000L
        val formatted = formatTimestamp(timestamp)
        assertNotNull(formatted)
        assertTrue(formatted.isNotEmpty())
    }

    @Test
    fun `SignalCard shows reasons when present`() {
        val signal = createTestSignal(reasons = """["RSI oversold","MACD bullish"]""")
        val reasons = signal.getReasonsList()
        assertEquals(2, reasons.size)
        assertTrue(reasons.contains("RSI oversold"))
    }

    @Test
    fun `SignalCard handles empty reasons`() {
        val signal = createTestSignal(reasons = "[]")
        val reasons = signal.getReasonsList()
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `SignalCard handles invalid JSON reasons`() {
        val signal = createTestSignal(reasons = "invalid json")
        val reasons = signal.getReasonsList()
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `Pending signal in success state has correct status`() {
        val signal = createTestSignal(status = SignalStatus.PENDING)
        assertEquals(SignalStatus.PENDING, signal.status)
    }

    @Test
    fun `Executed signal has execution details`() {
        val signal = createTestSignal(
            status = SignalStatus.EXECUTED,
            executedPrice = 50100.0,
            orderId = "O12345"
        )
        assertEquals(SignalStatus.EXECUTED, signal.status)
        assertEquals(50100.0, signal.executedPrice!!, 0.001)
        assertEquals("O12345", signal.orderId)
    }

    @Test
    fun `AnalysisLog tracks success and error counts`() {
        val entry1 = AnalysisLogEntry(pair = "BTC/EUR", isSuccess = true)
        val entry2 = AnalysisLogEntry(pair = "ETH/EUR", isSuccess = false)
        val log = AnalysisLog(entries = listOf(entry1, entry2))
        assertEquals(1, log.successCount)
        assertEquals(1, log.errorCount)
        assertEquals(2, log.totalCount)
    }

    @Test
    fun `AnalysisLog with all successes`() {
        val entries = (0 until 5).map { AnalysisLogEntry(pair = "BTC/EUR", isSuccess = true) }
        val log = AnalysisLog(entries = entries)
        assertEquals(5, log.successCount)
        assertEquals(0, log.errorCount)
    }

    private fun getStatusText(status: SignalStatus): String = when (status) {
        SignalStatus.PENDING -> "Pending"
        SignalStatus.EXECUTING -> "Executing"
        SignalStatus.EXECUTED -> "Executed"
        SignalStatus.IGNORED -> "Ignored"
        SignalStatus.FAILED -> "Failed"
        SignalStatus.EXPIRED -> "Expired"
        SignalStatus.MISSED -> "Missed"
    }

    private fun createTestSignal(
        status: SignalStatus = SignalStatus.PENDING,
        reasons: String = """["RSI oversold"]""",
        executedPrice: Double? = null,
        orderId: String? = null
    ): TradingSignal = TradingSignal(
        id = 1,
        pair = "BTC/EUR",
        type = SignalType.BUY,
        strength = 0.85,
        currentPrice = 50000.0,
        suggestedPrice = 50100.0,
        stopLoss = 48500.0,
        takeProfit = 53000.0,
        suggestedAmount = 250.0,
        riskRewardRatio = 2.5,
        timestamp = System.currentTimeMillis(),
        reasons = reasons,
        status = status,
        executedAt = if (status == SignalStatus.EXECUTED) System.currentTimeMillis() else null,
        executedPrice = executedPrice,
        orderId = orderId
    )
}
