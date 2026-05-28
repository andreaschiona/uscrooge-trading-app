package com.uscrooge.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_log",
    indices = [
        Index("timestamp"),
        Index("category"),
        Index("category", "timestamp")
    ]
)
data class AuditLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val category: String,
    val action: String,
    val details: String,
    val severity: String = "INFO"
)
