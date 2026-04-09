package dev.lightforge.saathi.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached restaurant configuration (name, address, hours, etc.).
 * Single row — there is only one restaurant per device.
 */
@Entity(tableName = "restaurant_config")
data class RestaurantConfigEntity(
    @PrimaryKey val id: Int = 1, // Singleton row
    val name: String,
    val phone: String,
    val address: String,
    val hoursJson: String, // JSON map of day -> "HH:MM-HH:MM"
    val languagesJson: String, // JSON array of language codes
    val lastSyncedAt: Long = System.currentTimeMillis()
)
