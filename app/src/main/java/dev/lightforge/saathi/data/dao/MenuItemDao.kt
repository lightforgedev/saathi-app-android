package dev.lightforge.saathi.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.lightforge.saathi.data.entity.MenuItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuItemDao {

    @Query("SELECT * FROM menu_items ORDER BY category, name")
    fun getAll(): Flow<List<MenuItemEntity>>

    @Query("SELECT * FROM menu_items WHERE isAvailable = 1 ORDER BY category, name")
    fun getAvailable(): Flow<List<MenuItemEntity>>

    @Query("SELECT * FROM menu_items WHERE category = :category ORDER BY name")
    fun getByCategory(category: String): Flow<List<MenuItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MenuItemEntity>)

    @Query("DELETE FROM menu_items")
    suspend fun deleteAll()
}
