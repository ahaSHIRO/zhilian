package com.baiyin.zhilian

import android.content.Context
import com.baiyin.zhilian.data.SettingsRepository
import com.baiyin.zhilian.data.batch.BatchImportService
import com.baiyin.zhilian.data.batch.BatchReconcileService
import com.baiyin.zhilian.data.batch.BatchReconciler
import com.baiyin.zhilian.data.batch.BatchSchemaValidator
import com.baiyin.zhilian.data.batch.RoomImportStore
import com.baiyin.zhilian.data.batch.RoomReconcileStore
import com.baiyin.zhilian.data.db.ZhilianDatabase
import com.baiyin.zhilian.data.practice.PracticeRepository
import com.baiyin.zhilian.data.practice.PracticeSessionLoader
import com.baiyin.zhilian.data.practice.PracticeStats
import com.baiyin.zhilian.data.practice.QuestionPicker
import com.baiyin.zhilian.data.practice.RoomPracticeStore
import com.baiyin.zhilian.data.question.QuestionBank

/**
 * 手动依赖容器（ADR：不引入 DI 框架）。应用级单例，MainActivity 持有。
 *
 * [database] 刻意不对外公开：UI 只能经仓（[practiceRepository] / [questionBank] /
 * [importService] / [practiceStats]）取数。曾把它公开，结果四个屏各自调 `database.<dao>()` 绕过仓，
 * Room 类型漏进 UI、屏无法被替身。需要新的读法就往对应仓上加方法。
 *
 * 编排依赖收在窄接口（[RoomPracticeStore] / [RoomImportStore]，ADR-0013）：生产接 Room，
 * 测试接内存替身——`PracticeRepository` / `BatchImportService` 的编排因此可 JVM 测。
 */
class AppContainer(context: Context) {
    private val database: ZhilianDatabase = ZhilianDatabase.get(context)

    /** 批次 Schema（assets 里由构建期从 docs/schema 复制）；导入与对账共用一份 */
    private val schemaValidator = BatchSchemaValidator(
        context.assets.open("batch-v1.schema.json").bufferedReader().use { it.readText() }
    )

    val settingsRepository: SettingsRepository = SettingsRepository(context)

    /** 手动兜底导入（保留旧「逐题加入」语义，只对新批次生效） */
    val importService: BatchImportService = BatchImportService(
        importStore = RoomImportStore(database),
        schemaValidator = schemaValidator,
        db = database,
    )

    /** 题库对账（ADR-0017）：目录为权威源，自动对账与撤销批次导入 */
    val reconcileService: BatchReconcileService = BatchReconcileService(
        store = RoomReconcileStore(database),
        schemaValidator = schemaValidator,
    )

    /** 前台自动对账的薄壳；自动与手动两条触发路径由此统一入口 */
    val reconciler: BatchReconciler = BatchReconciler(
        context = context.applicationContext,
        settings = settingsRepository,
        service = reconcileService,
    )
    val practiceRepository: PracticeRepository = PracticeRepository(RoomPracticeStore(database))
    val practiceStats: PracticeStats = PracticeStats(database)
    val questionBank: QuestionBank = QuestionBank(database)

    /** 选题（C7）：条件拼装 + 排序 + 四个投影收在一处；作答在 [practiceRepository]、统计在 [practiceStats] */
    val questionPicker: QuestionPicker = QuestionPicker(database.questionDao())

    /** 会话载入器（C1）：依赖只有「按 ID 取题」一件，故按函数注入，单测可给内存实现 */
    val practiceSessionLoader: PracticeSessionLoader = PracticeSessionLoader(questionBank::get)
}
