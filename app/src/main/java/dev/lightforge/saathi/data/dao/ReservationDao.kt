package dev.lightforge.saathi.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.lightforge.saathi.data.entity.ReservationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReservationDao {

    @Query("SELECT * FROM reservations WHERE date >= :fromDate ORDER BY date, time")
    fun getUpcoming(fromDate: String): Flow<List<ReservationEntity>>

    @Query("SELECT * FROM reservations WHERE date = :date AND available = 1 ORDER BY time")
    fun getAvailableForDate(date: String): Flow<List<ReservationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reservations: List<ReservationEntity>)

    @Query("DELETE FROM reservations")
    suspend fun deleteAll()
}
