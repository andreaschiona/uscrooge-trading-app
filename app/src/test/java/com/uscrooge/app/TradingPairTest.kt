package com.uscrooge.app

import com.uscrooge.app.data.model.TradingPair
import org.junit.Assert.*
import org.junit.Test

class TradingPairTest {

    @Test
    fun `fromString parses valid pair`() {
        val pair = TradingPair.fromString("BTC/EUR")
        assertEquals("BTC", pair.base)
        assertEquals("EUR", pair.quote)
        assertEquals("BTC/EUR", pair.symbol)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromString throws on invalid format`() {
        TradingPair.fromString("INVALID")
    }

    @Test
    fun `toKrakenSymbol converts BTC correctly`() {
        val pair = TradingPair.fromString("BTC/EUR")
        assertEquals("XXBTZEUR", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol converts ETH correctly`() {
        val pair = TradingPair.fromString("ETH/EUR")
        assertEquals("XETHZEUR", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol converts SOL correctly`() {
        val pair = TradingPair.fromString("SOL/EUR")
        assertEquals("SOLZEUR", pair.toKrakenSymbol())
    }

    @Test
    fun `toKrakenSymbol converts XRP correctly`() {
        val pair = TradingPair.fromString("XRP/EUR")
        assertEquals("XXRPZEUR", pair.toKrakenSymbol())
    }
}
