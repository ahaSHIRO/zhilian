package com.baiyin.zhilian.data.practice

import androidx.room.withTransaction
import com.baiyin.zhilian.data.db.AnswerRecordEntity
import com.baiyin.zhilian.data.db.ZhilianDatabase

/**
 * 作答落库的数据存取面（ADR-0013）。
 *
 * 把 [PracticeRepository] 依赖的 DAO 方法收成窄接口：生产接 Room（[RoomPracticeStore]），
 * 测试接内存替身（有限可变状态：写入能读，**不模拟事务原子性与并发**）。事务边界
 * （[inTransaction]）也收在此——生产用 `db.withTransaction`，测试直接执行。
 */
internal interface PracticeStore {

    /** 事务边界：生产用 Room 事务，测试直接执行（不模拟事务语义） */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    /** 判首答：该题已有作答记录数（0 = 本次是首答） */
    suspend fun countByQuestion(questionId: String): Int

    /** 读库内当前掌握度；null = 库内无此题（兜底用传入快照，见 [currentMasteryOf]） */
    suspend fun getMastery(questionId: String): Mastery?

    /** 写作答记录 */
    suspend fun insertRecord(record: AnswerRecordEntity)

    /** 写回掌握度 */
    suspend fun updateMastery(questionId: String, mastery: Mastery)

    /** 清空全部作答记录 */
    suspend fun deleteAllRecords()

    /** 复位全部题目掌握度 */
    suspend fun resetAllMastery()
}

/** [PracticeStore] 的 Room 实现（生产用） */
internal class RoomPracticeStore(private val db: ZhilianDatabase) : PracticeStore {
    private val questionDao = db.questionDao()
    private val answerRecordDao = db.answerRecordDao()

    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun countByQuestion(questionId: String): Int =
        answerRecordDao.countByQuestion(questionId)

    override suspend fun getMastery(questionId: String): Mastery? =
        questionDao.getById(questionId)?.let { Mastery(it.consecutivePerfect, it.hasEverWrong) }

    override suspend fun insertRecord(record: AnswerRecordEntity) = answerRecordDao.insert(record)

    override suspend fun updateMastery(questionId: String, mastery: Mastery) =
        questionDao.updateMastery(questionId, mastery.consecutivePerfect, mastery.hasEverWrong)

    override suspend fun deleteAllRecords() = answerRecordDao.deleteAll()

    override suspend fun resetAllMastery() = questionDao.resetAllMastery()
}
