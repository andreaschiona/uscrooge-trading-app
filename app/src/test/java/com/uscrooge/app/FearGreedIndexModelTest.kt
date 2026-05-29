package com.uscrooge.app

import com.uscrooge.app.data.model.FearGreedIndex
import org.junit.Assert.*
import org.junit.Test

class FearGreedIndexModelTest {

    @Test
    fun `extreme fear at 24 or below`() {
        assertEquals(FearGreedIndex.SentimentLevel.EXTREME_FEAR, FearGreedIndex(0, "", 0L).level)
        assertEquals(FearGreedIndex.SentimentLevel.EXTREME_FEAR, FearGreedIndex(24, "", 0L).level)
    }

    @Test
    fun `fear between 25 and 44`() {
        assertEquals(FearGreedIndex.SentimentLevel.FEAR, FearGreedIndex(25, "", 0L).level)
        assertEquals(FearGreedIndex.SentimentLevel.FEAR, FearGreedIndex(44, "", 0L).level)
    }

    @Test
    fun `neutral between 45 and 55`() {
        assertEquals(FearGreedIndex.SentimentLevel.NEUTRAL, FearGreedIndex(45, "", 0L).level)
        assertEquals(FearGreedIndex.SentimentLevel.NEUTRAL, FearGreedIndex(55, "", 0L).level)
    }

    @Test
    fun `greed between 56 and 75`() {
        assertEquals(FearGreedIndex.SentimentLevel.GREED, FearGreedIndex(56, "", 0L).level)
        assertEquals(FearGreedIndex.SentimentLevel.GREED, FearGreedIndex(75, "", 0L).level)
    }

    @Test
    fun `extreme greed at 76 or above`() {
        assertEquals(FearGreedIndex.SentimentLevel.EXTREME_GREED, FearGreedIndex(76, "", 0L).level)
        assertEquals(FearGreedIndex.SentimentLevel.EXTREME_GREED, FearGreedIndex(100, "", 0L).level)
    }
}
