package com.uscrooge.app.data.model

enum class BrokerHealthStatus {
    ONLINE,
    SLOW,
    OFFLINE
}

data class BrokerHealth(
    val brokerName: String,
    val status: BrokerHealthStatus,
    val latencyMs: Long,
    val lastError: String?,
    val lastChecked: Long
)

data class FearGreedHealth(
    val value: Int?,
    val status: BrokerHealthStatus,
    val latencyMs: Long,
    val lastError: String?,
    val lastChecked: Long
)

data class SystemHealth(
    val brokers: Map<String, BrokerHealth>,
    val fearGreed: FearGreedHealth?,
    val lastUpdated: Long
)
