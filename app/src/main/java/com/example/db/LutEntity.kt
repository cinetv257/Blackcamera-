package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "luts")
data class LutEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val filePath: String,
    val size: Int,
    val timestamp: Long,
    val isDefault: Boolean = false
)
