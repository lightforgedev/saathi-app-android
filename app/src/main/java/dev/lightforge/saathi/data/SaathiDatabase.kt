package dev.lightforge.saathi.data

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.lightforge.saathi.data.dao.MenuItemDao
import dev.lightforge.saathi.data.dao.ReservationDao
import dev.lightforge.saathi.data.dao.RestaurantConfigDao
import dev.lightforge.saathi.data.entity.MenuItemEntity
import dev.lightforge.saathi.data.entity.RecentCallerEntity
import dev.lightforge.saathi.data.entity.ReservationEntity
import dev.lightforge.saathi.data.entity.RestaurantConfigEntity

/**
 * Room database for local cache of restaurant data.
 *
 * This is a read-through cache — all writes come from backend sync.
 * The database is cleared and re-populated on full sync.
 * Delta syncs update individual records.
 */
@Database(
    entities = [
        MenuItemEntity::class,
        ReservationEntity::class,
        RestaurantConfigEntity::class,
        RecentCallerEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SaathiDatabase : RoomDatabase() {
    abstract fun menuItemDao(): MenuItemDao
    abstract fun reservationDao(): ReservationDao
    abstract fun restaurantConfigDao(): RestaurantConfigDao
}
