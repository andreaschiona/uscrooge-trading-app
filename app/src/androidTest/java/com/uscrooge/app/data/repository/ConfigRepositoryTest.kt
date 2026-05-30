package com.uscrooge.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uscrooge.app.data.model.TradingConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigRepositoryTest {

    private lateinit var repository: ConfigRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = ConfigRepository(context)
    }

    @Test
    fun defaultConfigHasCorrectValues() = runBlocking {
        val config = repository.configFlow.first()
        assertEquals(listOf("BTC/EUR", "ETH/EUR", "SOL/EUR", "XRP/EUR"), config.tradingPairs)
        assertEquals(0.25, config.riskPerTrade, 0.001)
        assertEquals(3, config.maxOpenPositions)
        assertEquals(5, config.maxDailyTrades)
        assertEquals(0.65, config.minSignalStrength, 0.001)
        assertFalse(config.automaticTrading)
        assertEquals(300, config.checkIntervalSeconds)
        assertTrue(config.useLimitOrders)
        assertEquals(30.0, config.rsiOversold, 0.001)
        assertEquals(70.0, config.rsiOverbought, 0.001)
    }

    @Test
    fun updateConfigPersistsValues() = runBlocking {
        val modified = TradingConfig(
            tradingPairs = listOf("BTC/EUR"),
            riskPerTrade = 0.5,
            maxOpenPositions = 5,
            maxDailyTrades = 10,
            minSignalStrength = 0.7,
            checkIntervalSeconds = 600
        )
        repository.updateConfig(modified)
        val loaded = repository.configFlow.first()
        assertEquals(listOf("BTC/EUR"), loaded.tradingPairs)
        assertEquals(0.5, loaded.riskPerTrade, 0.001)
        assertEquals(5, loaded.maxOpenPositions)
        assertEquals(10, loaded.maxDailyTrades)
        assertEquals(0.7, loaded.minSignalStrength, 0.001)
        assertEquals(600, loaded.checkIntervalSeconds)
    }

    @Test
    fun resetToDefaultsProducesDefaultConfig() = runBlocking {
        val modified = TradingConfig(
            tradingPairs = listOf("BTC/EUR"),
            riskPerTrade = 0.5,
            maxOpenPositions = 10
        )
        repository.updateConfig(modified)
        repository.resetToDefaults()
        val config = repository.configFlow.first()
        assertEquals(listOf("BTC/EUR", "ETH/EUR", "SOL/EUR", "XRP/EUR"), config.tradingPairs)
        assertEquals(0.25, config.riskPerTrade, 0.001)
        assertEquals(3, config.maxOpenPositions)
    }

    @Test
    fun emptyApiKeysAreNotEncrypted() = runBlocking {
        val config = repository.configFlow.first()
        assertTrue(config.krakenApiKey.isEmpty())
        assertTrue(config.krakenApiSecret.isEmpty())
        assertTrue(config.alpacaApiKey.isEmpty())
        assertTrue(config.alpacaApiSecret.isEmpty())
    }

    @Test
    fun encryptedApiKeysAreRoundTripped() = runBlocking {
        val configWithKeys = TradingConfig(
            krakenApiKey = "test-key-12345",
            krakenApiSecret = "test-secret-abcde",
            alpacaApiKey = "alpaca-key-67890",
            alpacaApiSecret = "alpaca-secret-fghij"
        )
        repository.updateConfig(configWithKeys)
        val loaded = repository.configFlow.first()
        assertEquals("test-key-12345", loaded.krakenApiKey)
        assertEquals("test-secret-abcde", loaded.krakenApiSecret)
        assertEquals("alpaca-key-67890", loaded.alpacaApiKey)
        assertEquals("alpaca-secret-fghij", loaded.alpacaApiSecret)
    }
}
