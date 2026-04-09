package dev.lightforge.saathi.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Recent caller log for the HomeScreen stats display.
 * Stored locally for quick access; full call history is on the backend.
 */
@Entity(tableName = "recent_callers")
data class RecentCallerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callerNumber: String,
    val callerName: String?,
    val callDirection: String, // "incoming" or "outgoing"
    val durationMs: Long,
    val toolCallCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)
