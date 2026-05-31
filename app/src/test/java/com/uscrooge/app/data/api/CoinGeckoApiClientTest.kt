package com.uscrooge.app.data.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CoinGeckoApiClientTest {

    private lateinit var mockService: CoinGeckoApiService
    private lateinit var client: CoinGeckoApiClient

    @Before
    fun setup() {
        mockService = mockk()
        client = spyk(CoinGeckoApiClient())
        every { client.service } returns mockService
    }

    @Test
    fun `getTopCoinsByVolume returns success with market list`() = runTest {
        val expected = listOf(
            CoinGeckoMarket(id = "bitcoin", symbol = "btc", name = "Bitcoin", current_price = 50000.0)
        )
        coEvery { mockService.getMarkets(vsCurrency = "usd", order = "volume_desc", perPage = 50) } returns expected

        val result = client.getTopCoinsByVolume()

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify { mockService.getMarkets(vsCurrency = "usd", order = "volume_desc", perPage = 50) }
    }

    @Test
    fun `getTopCoinsByVolume returns failure on exception`() = runTest {
        coEvery { mockService.getMarkets(any(), any(), any()) } throws RuntimeException("Network error")

        val result = client.getTopCoinsByVolume()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getTopCoinsByMarketCap returns success with market list`() = runTest {
        val expected = listOf(
            CoinGeckoMarket(id = "ethereum", symbol = "eth", name = "Ethereum", current_price = 3000.0)
        )
        coEvery { mockService.getMarkets(vsCurrency = "usd", order = "market_cap_desc", perPage = 50) } returns expected

        val result = client.getTopCoinsByMarketCap()

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify { mockService.getMarkets(vsCurrency = "usd", order = "market_cap_desc", perPage = 50) }
    }

    @Test
    fun `getTopCoinsByMarketCap returns failure on exception`() = runTest {
        coEvery { mockService.getMarkets(any(), any(), any()) } throws RuntimeException("Timeout")

        val result = client.getTopCoinsByMarketCap()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getOHLC returns success with OHLC data`() = runTest {
        val expected = listOf(listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        coEvery { mockService.getOHLC("bitcoin", "usd", 7) } returns expected

        val result = client.getOHLC("bitcoin")

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
    }

    @Test
    fun `getOHLC retries on failure and returns failure after max retries`() = runTest {
        coEvery { mockService.getOHLC(any(), any(), any()) } throws RuntimeException("API error")

        val result = client.getOHLC("bitcoin")

        assertTrue(result.isFailure)
        coVerify(exactly = 2) { mockService.getOHLC(any(), any(), any()) }
    }

    @Test
    fun `getOHLC succeeds on retry after initial failure`() = runTest {
        val expected = listOf(listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        coEvery { mockService.getOHLC("bitcoin", "usd", 7) } throws RuntimeException("First fail") andThen expected

        val result = client.getOHLC("bitcoin")

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify(exactly = 2) { mockService.getOHLC(any(), any(), any()) }
    }

    @Test
    fun `getSimplePrice returns mapped prices`() = runTest {
        val ids = listOf("bitcoin", "ethereum")
        val rawResponse = mapOf(
            "bitcoin" to mapOf("usd" to 50000.0, "usd_24h_vol" to 1e9, "usd_24h_change" to 2.5),
            "ethereum" to mapOf("usd" to 3000.0, "usd_24h_vol" to 5e8, "usd_24h_change" to 1.5)
        )
        coEvery { mockService.getSimplePrice(ids = "bitcoin,ethereum", vsCurrencies = "usd") } returns rawResponse

        val result = client.getSimplePrice(ids)

        assertTrue(result.isSuccess)
        val prices = result.getOrNull()!!
        assertEquals(50000.0, prices["bitcoin"]?.price ?: 0.0, 0.01)
        assertEquals(1e9, prices["bitcoin"]?.volume24h ?: 0.0, 0.01)
        assertEquals(2.5, prices["bitcoin"]?.change24h ?: 0.0, 0.01)
        assertEquals(3000.0, prices["ethereum"]?.price ?: 0.0, 0.01)
    }

    @Test
    fun `getSimplePrice uses defaults for missing fields`() = runTest {
        val ids = listOf("unknown")
        val rawResponse = mapOf("unknown" to emptyMap<String, Double>())
        coEvery { mockService.getSimplePrice(ids = "unknown", vsCurrencies = "usd") } returns rawResponse

        val result = client.getSimplePrice(ids)

        assertTrue(result.isSuccess)
        val prices = result.getOrNull()!!
        assertEquals(0.0, prices["unknown"]?.price ?: -1.0, 0.01)
        assertEquals(0.0, prices["unknown"]?.volume24h ?: -1.0, 0.01)
        assertEquals(0.0, prices["unknown"]?.change24h ?: -1.0, 0.01)
    }

    @Test
    fun `getSimplePrice returns failure on exception`() = runTest {
        coEvery { mockService.getSimplePrice(any(), any()) } throws RuntimeException("Rate limited")

        val result = client.getSimplePrice(listOf("bitcoin"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `getTopCoinsByVolume limits perPage to 250`() = runTest {
        coEvery { mockService.getMarkets(any(), any(), perPage = 250) } returns emptyList()

        client.getTopCoinsByVolume(limit = 500)

        coVerify { mockService.getMarkets(any(), any(), perPage = 250) }
    }

    @Test
    fun `getTopCoinsByVolume minimum perPage is 1`() = runTest {
        coEvery { mockService.getMarkets(any(), any(), perPage = 1) } returns emptyList()

        client.getTopCoinsByVolume(limit = 0)

        coVerify { mockService.getMarkets(any(), any(), perPage = 1) }
    }
}
