package com.uscrooge.app

import com.uscrooge.app.data.model.TradingConfig
import org.junit.Assert.*
import org.junit.Test

class TradingConfigTest {

    @Test
    fun `default config is valid`() {
        val config = TradingConfig()
        val result = config.validate()
        assertTrue("Default config should be valid", result.isSuccess)
    }

    @Test
    fun `invalid budget fails validation`() {
        val config = TradingConfig(budgetEur = -100.0)
        val result = config.validate()
        assertTrue("Negative budget should fail", result.isFailure)
    }

    @Test
    fun `invalid risk per trade fails validation`() {
        val config = TradingConfig(riskPerTrade = 1.5)
        val result = config.validate()
        assertTrue("Risk > 1.0 should fail", result.isFailure)
    }

    @Test
    fun `empty trading pairs fails validation`() {
        val config = TradingConfig(tradingPairs = emptyList())
        val result = config.validate()
        assertTrue("Empty trading pairs should fail", result.isFailure)
    }

    @Test
    fun `getMaxAmountPerTrade calculates correctly`() {
        val config = TradingConfig(
            budgetEur = 1000.0,
            riskPerTrade = 0.25
        )
        assertEquals(250.0, config.getMaxAmountPerTrade(), 0.01)
    }

    @Test
    fun `check interval minimum is enforced`() {
        val config = TradingConfig(checkIntervalSeconds = 30)
        val result = config.validate()
        assertTrue("Check interval < 60 should fail", result.isFailure)
    }
}
