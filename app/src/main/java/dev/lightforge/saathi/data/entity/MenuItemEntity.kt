package dev.lightforge.saathi.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached menu item from the restaurant.
 * Synced from backend via GET /config.
 */
@Entity(tableName = "menu_items")
data class MenuItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameHindi: String?,
    val description: String?,
    val price: Double,
    val category: String,
    val isAvailable: Boolean,
    val isVeg: Boolean,
    val lastSyncedAt: Long = System.currentTimeMillis()
)
