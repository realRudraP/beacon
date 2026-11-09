package dev.realrudrap.beacon.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppMigrations {
    /**
     * Adds the background location check queue. This is a real migration on
     * purpose: StorageModule still enables fallbackToDestructiveMigration, and
     * bumping the version without it would wipe unsynced attendance and cash.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `location_checks` (
                    `id` TEXT NOT NULL,
                    `attendanceRecordId` TEXT NOT NULL,
                    `capturedAt` TEXT NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `accuracy` REAL,
                    `sequenceNumber` INTEGER NOT NULL,
                    `syncStatus` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_location_checks_attendanceRecordId_sequenceNumber` " +
                        "ON `location_checks` (`attendanceRecordId`, `sequenceNumber`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_location_checks_syncStatus` " +
                        "ON `location_checks` (`syncStatus`)"
            )
        }
    }
}
