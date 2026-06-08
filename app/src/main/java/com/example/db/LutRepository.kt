package com.example.db

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "camera_settings")

class LutRepository(private val context: Context) {
    private val database = LutDatabase.getDatabase(context)
    private val lutDao = database.lutDao()

    val allLuts: Flow<List<LutEntity>> = lutDao.getAllLuts()
    val recentLuts: Flow<List<LutEntity>> = lutDao.getRecentLuts()

    private object PreferencesKeys {
        val LAST_LUT_PATH = stringPreferencesKey("last_lut_path")
        val LUT_ENABLED = booleanPreferencesKey("lut_enabled")
        val LUT_INTENSITY = floatPreferencesKey("lut_intensity")
        val HIGH_QUALITY_INTERPOLATION = booleanPreferencesKey("high_quality_interpolation")
    }

    val lastLutPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_LUT_PATH]
    }

    val lutEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LUT_ENABLED] ?: true
    }

    val lutIntensity: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LUT_INTENSITY] ?: 1.0f
    }

    val highQualityInterpolation: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.HIGH_QUALITY_INTERPOLATION] ?: true
    }

    suspend fun saveLastLutPath(path: String?) {
        context.dataStore.edit { preferences ->
            if (path == null) {
                preferences.remove(PreferencesKeys.LAST_LUT_PATH)
            } else {
                preferences[PreferencesKeys.LAST_LUT_PATH] = path
            }
        }
    }

    suspend fun saveLutEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LUT_ENABLED] = enabled
        }
    }

    suspend fun saveLutIntensity(intensity: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LUT_INTENSITY] = intensity
        }
    }

    suspend fun saveHighQualityInterpolation(high: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HIGH_QUALITY_INTERPOLATION] = high
        }
    }

    suspend fun insertLut(lut: LutEntity) = lutDao.insertLut(lut)

    suspend fun deleteLut(lut: LutEntity) {
        lutDao.deleteLut(lut)
        try {
            val file = File(lut.filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setDefaultLut(lutId: Int) {
        lutDao.setDefaultLut(lutId)
        val defaultLut = lutDao.getLutById(lutId)
        if (defaultLut != null) {
            saveLastLutPath(defaultLut.filePath)
        }
    }

    suspend fun getDefaultLut(): LutEntity? = lutDao.getDefaultLut()
    suspend fun getLutById(id: Int): LutEntity? = lutDao.getLutById(id)
    suspend fun getLutByPath(filePath: String): LutEntity? = lutDao.getLutByPath(filePath)
}
