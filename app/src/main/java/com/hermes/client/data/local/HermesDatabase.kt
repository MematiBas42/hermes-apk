package com.hermes.client.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [SessionEntity::class, MessageEntity::class, RunEntity::class, JobEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun runDao(): RunDao
    abstract fun jobDao(): JobDao

    companion object {
        const val NAME = "hermes.db"
    }
}
