package com.baiyin.zhilian

import android.content.Context
import com.baiyin.zhilian.data.SettingsRepository
import com.baiyin.zhilian.data.batch.BatchImportService
import com.baiyin.zhilian.data.db.ZhilianDatabase
import com.baiyin.zhilian.data.practice.PracticeRepository

/**
 * 手动依赖容器（ADR：不引入 DI 框架）。应用级单例，MainActivity 持有。
 */
class AppContainer(context: Context) {
    val database: ZhilianDatabase = ZhilianDatabase.get(context)
    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val importService: BatchImportService = BatchImportService(context, database)
    val practiceRepository: PracticeRepository = PracticeRepository(database)
}
