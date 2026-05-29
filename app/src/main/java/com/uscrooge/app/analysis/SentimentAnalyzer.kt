package com.uscrooge.app.analysis

import com.uscrooge.app.data.model.FearGreedIndex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SentimentAnalyzer @Inject constructor() {

    fun calculateSentimentModifier(sentiment: FearGreedIndex): Double {
        return when (sentiment.level) {
            FearGreedIndex.SentimentLevel.EXTREME_FEAR -> 0.8
            FearGreedIndex.SentimentLevel.FEAR -> 0.3
            FearGreedIndex.SentimentLevel.NEUTRAL -> 0.0
            FearGreedIndex.SentimentLevel.GREED -> -0.3
            FearGreedIndex.SentimentLevel.EXTREME_GREED -> -0.8
        }
    }

    fun describeSentiment(sentiment: FearGreedIndex): String {
        val modifier = calculateSentimentModifier(sentiment)
        val direction = when {
            modifier > 0.5 -> "Strong buy opportunity"
            modifier > 0 -> "Slight buy opportunity"
            modifier == 0.0 -> "Neutral"
            modifier < -0.5 -> "Strong sell signal"
            else -> "Slight sell signal"
        }
        return "Fear & Greed: ${sentiment.value}/100 (${sentiment.classification}) - $direction"
    }
}
