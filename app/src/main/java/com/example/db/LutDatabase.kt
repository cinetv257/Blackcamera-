package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LutEntity::class], version = 2, exportSchema = false)
abstract class LutDatabase : RoomDatabase() {
    abstract fun lutDao(): LutDao

    companion object {
        @Volatile
        private var INSTANCE: LutDatabase? = null

        fun getDatabase(context: Context): LutDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LutDatabase::class.java,
                    "lut_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
