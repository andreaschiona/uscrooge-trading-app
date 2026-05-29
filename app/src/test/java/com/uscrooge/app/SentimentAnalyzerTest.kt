package com.uscrooge.app

import com.uscrooge.app.analysis.SentimentAnalyzer
import com.uscrooge.app.data.model.FearGreedIndex
import org.junit.Assert.*
import org.junit.Test

class SentimentAnalyzerTest {

    private val analyzer = SentimentAnalyzer()

    @Test
    fun `extreme fear returns highest buy modifier`() {
        val sentiment = FearGreedIndex(10, "Extreme Fear", System.currentTimeMillis())
        val modifier = analyzer.calculateSentimentModifier(sentiment)
        assertEquals(0.8, modifier, 0.001)
    }

    @Test
    fun `fear returns moderate buy modifier`() {
        val sentiment = FearGreedIndex(35, "Fear", System.currentTimeMillis())
        val modifier = analyzer.calculateSentimentModifier(sentiment)
        assertEquals(0.3, modifier, 0.001)
    }

    @Test
    fun `neutral returns zero modifier`() {
        val sentiment = FearGreedIndex(50, "Neutral", System.currentTimeMillis())
        val modifier = analyzer.calculateSentimentModifier(sentiment)
        assertEquals(0.0, modifier, 0.001)
    }

    @Test
    fun `greed returns slight sell modifier`() {
        val sentiment = FearGreedIndex(65, "Greed", System.currentTimeMillis())
        val modifier = analyzer.calculateSentimentModifier(sentiment)
        assertEquals(-0.3, modifier, 0.001)
    }

    @Test
    fun `extreme greed returns strong sell modifier`() {
        val sentiment = FearGreedIndex(90, "Extreme Greed", System.currentTimeMillis())
        val modifier = analyzer.calculateSentimentModifier(sentiment)
        assertEquals(-0.8, modifier, 0.001)
    }

    @Test
    fun `describeSentiment for extreme fear`() {
        val sentiment = FearGreedIndex(10, "Extreme Fear", System.currentTimeMillis())
        val description = analyzer.describeSentiment(sentiment)
        assertTrue(description.contains("Fear"))
        assertTrue(description.contains("10"))
    }

    @Test
    fun `describeSentiment for extreme greed`() {
        val sentiment = FearGreedIndex(90, "Extreme Greed", System.currentTimeMillis())
        val description = analyzer.describeSentiment(sentiment)
        assertTrue(description.contains("Greed"))
        assertTrue(description.contains("90"))
    }
}
