package com.uscrooge.app.data.model

data class TradingPair(
    val base: String,      // e.g., "BTC"
    val quote: String,     // e.g., "EUR"
    val symbol: String     // e.g., "BTC/EUR"
) {
    companion object {
        fun fromString(pair: String): TradingPair {
            val parts = pair.split("/")
            require(parts.size == 2) { "Invalid trading pair format: $pair" }
            return TradingPair(
                base = parts[0].trim(),
                quote = parts[1].trim(),
                symbol = pair
            )
        }
    }

    fun toKrakenSymbol(): String {
        // Kraken uses format like XXBTZEUR
        val baseMap = mapOf(
            "BTC" to "XXBT",
            "ETH" to "XETH",
            "SOL" to "SOL",
            "XRP" to "XXRP"
        )
        val quoteMap = mapOf("EUR" to "ZEUR", "USD" to "ZUSD")
        return (baseMap[base] ?: base) + (quoteMap[quote] ?: quote)
    }
}
