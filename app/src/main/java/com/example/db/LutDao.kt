package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LutDao {
    @Query("SELECT * FROM luts ORDER BY timestamp DESC")
    fun getAllLuts(): Flow<List<LutEntity>>

    @Query("SELECT * FROM luts ORDER BY timestamp DESC LIMIT 5")
    fun getRecentLuts(): Flow<List<LutEntity>>

    @Query("SELECT * FROM luts WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultLut(): LutEntity?

    @Query("SELECT * FROM luts WHERE id = :id LIMIT 1")
    suspend fun getLutById(id: Int): LutEntity?

    @Query("SELECT * FROM luts WHERE filePath = :filePath LIMIT 1")
    suspend fun getLutByPath(filePath: String): LutEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLut(lut: LutEntity)

    @Update
    suspend fun updateLut(lut: LutEntity)

    @Delete
    suspend fun deleteLut(lut: LutEntity)

    @Query("UPDATE luts SET isDefault = 0")
    suspend fun resetDefaults()

    @Query("UPDATE luts SET isDefault = :isDefault WHERE id = :id")
    suspend fun updateDefaultStatus(id: Int, isDefault: Boolean)

    @Transaction
    suspend fun setDefaultLut(lutId: Int) {
        resetDefaults()
        updateDefaultStatus(lutId, true)
    }
}
