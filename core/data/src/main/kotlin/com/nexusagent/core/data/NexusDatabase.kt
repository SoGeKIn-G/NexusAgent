package com.nexusagent.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RunEntity::class, StepEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NexusDatabase : RoomDatabase() {

    abstract fun runDao(): RunDao

    companion object {
        fun create(context: Context): NexusDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NexusDatabase::class.java,
                "nexus.db",
            )
                // History is diagnostic, not user data - losing it on a schema change is
                // an acceptable trade for not shipping migrations at this stage. Revisit
                // if history ever becomes something a user would miss.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
