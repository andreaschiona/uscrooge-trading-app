package com.uscrooge.app

import com.uscrooge.app.data.model.TradingPair
import org.junit.Assert.*
import org.junit.Test

class TradingPairModelTest {

    @Test
    fun `fromString parses valid pair`() {
        val pair = TradingPair.fromString("BTC/EUR")
        assertEquals("BTC", pair.base)
        assertEquals("EUR", pair.quote)
        assertEquals("BTC/EUR", pair.symbol)
    }

    @Test
    fun `fromString handles whitespace`() {
        val pair = TradingPair.fromString("  SOL / EUR  ")
        assertEquals("SOL", pair.base)
        assertEquals("EUR", pair.quote)
    }

    @Test
    fun `fromString throws on missing slash`() {
        assertThrows(IllegalArgumentException::class.java) {
            TradingPair.fromString("BTC")
        }
    }

    @Test
    fun `fromString throws on too many parts`() {
        assertThrows(IllegalArgumentException::class.java) {
            TradingPair.fromString("BTC/EUR/USD")
        }
    }

    @Test
    fun `toKrakenSymbol maps BTC correctly`() {
        val pair = TradingPair.fromString("BTC/EUR")
        assertEquals("XXBTZEUR", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol maps ETH correctly`() {
        val pair = TradingPair.fromString("ETH/EUR")
        assertEquals("XETHZEUR", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol maps XRP correctly`() {
        val pair = TradingPair.fromString("XRP/EUR")
        assertEquals("XXRPZEUR", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol uses simple format for new assets`() {
        val pair = TradingPair.fromString("SOL/EUR")
        assertEquals("SOLEUR", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol maps USD quote correctly`() {
        val pair = TradingPair.fromString("BTC/USD")
        assertEquals("XXBTZUSD", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol handles non-EUR quote`() {
        val pair = TradingPair.fromString("SOL/USD")
        assertEquals("SOLUSD", pair.toKrakenSymbol())
    }
}
