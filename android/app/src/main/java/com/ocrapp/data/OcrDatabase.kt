package com.ocrapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ScanEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OcrDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        const val NAME = "ocr.db"
    }
}
