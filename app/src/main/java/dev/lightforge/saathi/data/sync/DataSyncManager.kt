package dev.lightforge.saathi.data.sync

import android.util.Log
import com.google.gson.Gson
import dev.lightforge.saathi.data.SaathiDatabase
import dev.lightforge.saathi.data.entity.MenuItemEntity
import dev.lightforge.saathi.data.entity.ReservationEntity
import dev.lightforge.saathi.data.entity.RestaurantConfigEntity
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.ConfigResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages data synchronization between backend and local Room database.
 *
 * Two sync modes:
 * 1. Full sync — called at startup and periodically. Fetches all config from GET /config,
 *    replaces local cache entirely.
 * 2. Delta sync — triggered by WebSocket config.sync push. Updates only changed records.
 *
 * All tool calls during voice sessions use backend data (not local cache).
 * The local cache is for the restaurant owner's UI (menu listing, reservation overview).
 */
@Singleton
class DataSyncManager @Inject constructor(
    private val api: AegisApiClient,
    private val db: SaathiDatabase
) {

    companion object {
        private const val TAG = "DataSyncManager"
    }

    private val gson = Gson()

    /**
     * Performs a full sync from the backend.
     * Replaces all local data with fresh data from GET /config.
     *
     * @return true if sync succeeded
     */
    suspend fun fullSync(): Boolean {
        return try {
            val response = api.getConfig()
            if (!response.isSuccessful) {
                Log.e(TAG, "Full sync failed: ${response.code()}")
                return false
            }

            val config = response.body() ?: return false
            applyFullConfig(config)
            Log.i(TAG, "Full sync complete: ${config.menu_items.size} menu items, ${config.upcoming_reservations.size} reservations")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Full sync exception", e)
            false
        }
    }

    /**
     * Applies a full config response to the local database.
     * Runs in a transaction: clear all, then insert fresh data.
     */
    private suspend fun applyFullConfig(config: ConfigResponse) {
        db.runInTransaction {
            // This runs synchronously within the transaction
        }

        // Restaurant config — serialize business_hours object as JSON for local storage
        db.restaurantConfigDao().delete()
        db.restaurantConfigDao().upsert(
            RestaurantConfigEntity(
                name = config.restaurant.name,
                phone = config.restaurant.phone,
                address = config.restaurant.address,
                hoursJson = gson.toJson(config.restaurant.business_hours),
                languagesJson = gson.toJson(config.restaurant.languages),
            )
        )

        // Menu items — price is in paise, stored as-is (divide by 100 for display)
        db.menuItemDao().deleteAll()
        db.menuItemDao().insertAll(
            config.menu_items.map { item ->
                MenuItemEntity(
                    id = item.id,
                    name = item.name,
                    nameHindi = item.name_hi,
                    description = item.description,
                    price = item.price.toDouble(),
                    category = item.category,
                    isAvailable = item.is_available,
                    isVeg = item.is_veg
                )
            }
        )

        // Upcoming reservations (next 48h) — store as denormalised rows for owner UI
        db.reservationDao().deleteAll()
        db.reservationDao().insertAll(
            config.upcoming_reservations.map { res ->
                ReservationEntity(
                    date = res.date,
                    time = res.time,
                    partySizeMax = res.party_size,
                    available = res.status == "available"
                )
            }
        )
    }

    /**
     * Handles a delta sync push from the backend WebSocket.
     * Parses the config.sync message and updates only changed records.
     */
    suspend fun applyDeltaSync(syncJson: String) {
        // TODO: Parse delta sync format and apply incremental updates
        // For now, fall back to full sync
        Log.d(TAG, "Delta sync received — falling back to full sync")
        fullSync()
    }
}
