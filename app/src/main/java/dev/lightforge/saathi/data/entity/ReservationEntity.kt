package dev.lightforge.saathi.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached reservation slot.
 * Synced from backend for offline availability checking.
 */
@Entity(tableName = "reservations")
data class ReservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: String,
    val partySizeMax: Int,
    val available: Boolean,
    val lastSyncedAt: Long = System.currentTimeMillis()
)
