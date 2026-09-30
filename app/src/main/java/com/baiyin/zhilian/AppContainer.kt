package com.baiyin.zhilian

import android.content.Context
import com.baiyin.zhilian.data.SettingsRepository
import com.baiyin.zhilian.data.batch.BatchImportService
import com.baiyin.zhilian.data.db.ZhilianDatabase
import com.baiyin.zhilian.data.practice.PracticeRepository
import com.baiyin.zhilian.data.question.QuestionBank

/**
 * 手动依赖容器（ADR：不引入 DI 框架）。应用级单例，MainActivity 持有。
 *
 * [database] 刻意不对外公开：UI 只能经仓（[practiceRepository] / [questionBank] /
 * [importService]）取数。曾把它公开，结果四个屏各自调 `database.<dao>()` 绕过仓，
 * Room 类型漏进 UI、屏无法被替身。需要新的读法就往对应仓上加方法。
 */
class AppContainer(context: Context) {
    private val database: ZhilianDatabase = ZhilianDatabase.get(context)
    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val importService: BatchImportService = BatchImportService(context, database)
    val practiceRepository: PracticeRepository = PracticeRepository(database)
    val questionBank: QuestionBank = QuestionBank(database)
}
