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
        // Kraken uses format like XXBTZEUR for legacy pairs,
        // but newer assets like SOL use simple format (SOLEUR)
        val baseMap = mapOf(
            "BTC" to "XXBT",
            "ETH" to "XETH",
            "XRP" to "XXRP"
        )
        val quoteMap = mapOf("EUR" to "ZEUR", "USD" to "ZUSD")

        val mappedBase = baseMap[base]
        return if (mappedBase != null) {
            // Legacy X-prefixed asset: use Z-prefixed quote
            mappedBase + (quoteMap[quote] ?: quote)
        } else {
            // Newer asset (SOL, DOT, etc.): use simple format
            base + quote
        }
    }
}
