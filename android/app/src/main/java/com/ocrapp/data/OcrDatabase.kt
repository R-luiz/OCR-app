package com.ocrapp.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScanEntity::class],
    version = 3,
    exportSchema = true,
    // Declared here rather than on the database builder, so every builder (the app's
    // and the tests') applies it.
    autoMigrations = [AutoMigration(from = 2, to = 3, spec = OcrDatabase.Migration2To3::class)],
)
abstract class OcrDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    /**
     * Adds [ScanEntity.preview], [ScanEntity.searchTitle], [ScanEntity.searchText],
     * [ScanEntity.draftText] and the history list's index. Room writes the `ALTER TABLE`
     * and `CREATE INDEX` statements from the two exported schemas; this fills the new
     * columns of the scans that already exist, in the same transaction.
     */
    class Migration2To3 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE scans SET preview = substr(ltrim(plainText, char(9, 10, 13, 32)), 1, $PREVIEW_LENGTH)")

            // The folding (see searchKey) has no SQL equivalent, so it runs here, a row
            // at a time. Rewriting columns the cursor does not order by keeps its
            // rows stable while it pages through them.
            db.compileStatement("UPDATE scans SET searchTitle = ?, searchText = ? WHERE id = ?").use { update ->
                db.query("SELECT id, title, plainText FROM scans ORDER BY id").use { rows ->
                    while (rows.moveToNext()) {
                        update.bindString(1, searchKey(rows.getString(1)))
                        update.bindString(2, searchKey(rows.getString(2)))
                        update.bindLong(3, rows.getLong(0))
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
            }
        }
    }

    companion object {
        const val NAME = "ocr.db"

        /** Adds [ScanEntity.notice]. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN notice TEXT")
            }
        }
    }
}
