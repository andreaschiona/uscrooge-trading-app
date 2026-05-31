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
            riskPerTrade = 0.25
        )
        assertEquals(250.0, config.getMaxAmountPerTrade(1000.0), 0.01)
    }

    @Test
    fun `check interval minimum is enforced`() {
        val config = TradingConfig(checkIntervalSeconds = 30)
        val result = config.validate()
        assertTrue("Check interval < 60 should fail", result.isFailure)
    }

    @Test
    fun `position selection is disabled by default`() {
        val config = TradingConfig()
        assertFalse("Position selection should be disabled by default", config.enablePositionSelection)
    }

    @Test
    fun `position selection min market cap has correct default`() {
        val config = TradingConfig()
        assertEquals(10_000_000.0, config.positionSelectionMinMarketCap, 0.01)
    }

    @Test
    fun `position selection min volume has correct default`() {
        val config = TradingConfig()
        assertEquals(1_000_000.0, config.positionSelectionMinVolume, 0.01)
    }

    @Test
    fun `position selection scan limit has correct default`() {
        val config = TradingConfig()
        assertEquals(100, config.positionSelectionScanLimit)
    }

    @Test
    fun `position selection max results has correct default`() {
        val config = TradingConfig()
        assertEquals(20, config.positionSelectionMaxResults)
    }

    @Test
    fun `position selection default weights sum to 1`() {
        val config = TradingConfig()
        val sum = config.positionSelectionMomentumWeight +
            config.positionSelectionLiquidityWeight +
            config.positionSelectionVolatilityWeight +
            config.positionSelectionVolumeWeight
        assertEquals(1.0, sum, 0.01)
    }

    @Test
    fun `position selection can be enabled`() {
        val config = TradingConfig(enablePositionSelection = true)
        assertTrue(config.enablePositionSelection)
    }

    @Test
    fun `position selection validation passes with enabled config`() {
        val config = TradingConfig(
            enablePositionSelection = true,
            positionSelectionMinMarketCap = 5_000_000.0,
            positionSelectionMinVolume = 500_000.0,
            positionSelectionScanLimit = 50,
            positionSelectionMaxResults = 10
        )
        val result = config.validate()
        assertTrue("Config with position selection enabled should still be valid", result.isSuccess)
    }
}
