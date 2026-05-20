package com.uscrooge.app.data.model

/**
 * Represents the result of a full market analysis run.
 */
data class AnalysisLog(
    val timestamp: Long = System.currentTimeMillis(),
    val entries: List<AnalysisLogEntry> = emptyList()
) {
    val successCount: Int get() = entries.count { it.isSuccess }
    val errorCount: Int get() = entries.count { !it.isSuccess }
    val totalCount: Int get() = entries.size
}

data class AnalysisLogEntry(
    val pair: String,
    val isSuccess: Boolean,
    val signalType: SignalType? = null,
    val strength: Double? = null,
    val errorMessage: String? = null
)
