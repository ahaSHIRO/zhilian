package com.baiyin.zhilian.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        QuestionEntity::class,
        AnswerRecordEntity::class,
        ProcessedBatchEntity::class,
        PendingDuplicateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ZhilianDatabase : RoomDatabase() {

    abstract fun questionDao(): QuestionDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun processedBatchDao(): ProcessedBatchDao
    abstract fun pendingDuplicateDao(): PendingDuplicateDao

    companion object {
        @Volatile
        private var instance: ZhilianDatabase? = null

        fun get(context: Context): ZhilianDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ZhilianDatabase::class.java,
                    "zhilian.db",
                ).build().also { instance = it }
            }
    }
}

