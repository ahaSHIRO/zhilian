# 作答提交原子性与掌握度转移纯函数化

作答提交（`PracticeRepository.submitAnswer`）收口为单一深接口 `suspend fun submitAnswer(question, answer): SubmitSummary`：repo 内部在单个 Room 事务里完成"判首答 → 写作答记录 → 读当前掌握度 → 转移 → 写回"四步，消除原先三次裸 DAO 调用（注释谎称"同一事务"实则无事务包裹）与读改写使用陈旧实体快照的隐患。掌握度转移抽为纯函数 `masteryTransition(current, outcome)`，事务内 `getById` 读库内当前值后计算，规则留 Kotlin 可单测；不下推到原子 SQL（业务规则埋进 SQL 不可测）。作答结果枚举 `AnswerOutcome` 由 `Scoring.outcomeOf(rate, perfect)` 产出，`masteryTransition` 只依赖枚举不依赖 `Double`，接口最窄。调用方不再自算评分传 4 参，UI 用返回的 `SubmitSummary(rate, perfect)` 做反馈横幅；提交按钮以 `submitting` 态防重入，重复点击不再落重复记录。

部分得分（多选 0<rate<1）的转移语义经裁决为**保留 `consecutivePerfect` 当前计数不清零**——与零分错误的"归零"区分：错误作答归零、跳过不变、部分得分保留。错题定义里"部分得分仍算未对"指 `has_ever_wrong` 维度（Partial 仍置 true 计入错题），但不抹除已积累的连续全对次数。此裁决消除 `CONTEXT.md`"连续全对次数"原句"部分得分不增加"的歧义（已就地补注"保留当前计数不清零"）。

理由：作答提交是练习闭环里正确性最敏感的一步，事务保证原子性、纯函数保证可测性，两层各司其职；部分得分保留语义使错题消解难度与规格一致（一次部分得分不抹杀此前积累的连续全对）。首个 JVM 单测（`MasteryTest`）覆盖转移规则表四象限，破全仓零测试基线。`submitAnswer` 整体原子性靠真机端到端验证：构造一道多选部分得分题 → 提交 → 查 `consecutive_perfect` 保留 + 记录落库 + 重启不丢。
