package dev.lightforge.saathi.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.lightforge.saathi.data.entity.RestaurantConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RestaurantConfigDao {

    @Query("SELECT * FROM restaurant_config WHERE id = 1")
    fun get(): Flow<RestaurantConfigEntity?>

    @Query("SELECT * FROM restaurant_config WHERE id = 1")
    suspend fun getSync(): RestaurantConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: RestaurantConfigEntity)

    @Query("DELETE FROM restaurant_config")
    suspend fun delete()
}
