package dev.pocketshare.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ShareEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PocketShareDatabase : RoomDatabase() {
    abstract fun shareDao(): ShareDao
}
