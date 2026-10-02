# 错题规则单一来源：共享阈值 + SQL 条件单一入口，守卫测试防漂移

错题判定（CONTEXT.md「错题」）此前有两份表达：`SQL_WRONG`（SQL 片段，选题/统计用）与 `QuestionEntity.isWrong`（Kotlin 派生，题库页过滤用）。两者是同一规则的 SQL/Kotlin 双表达（列名 vs 属性名是必然差异），但**阈值「连续两次全对消解」在两处硬编码 `2`**——改阈值要同步改两处，且无机制保证不漂移。另 `PracticeStats` 的 `wrongQuestionCount` / `topWrongCategories` 手写 SQL 条件（`inactive = 0 AND $SQL_WRONG ...`），与 `PracticeSql.where` 的条件结构重复——`wrongQuestionCount` 的 WHERE 其实是 `PracticeSql.where(PracticeFilter(onlyWrong = true))` 的特例。

对此本决策为：

1. **`WRONG_THRESHOLD` 常量单一来源**（放 `QuestionEntity.kt`，db 层）：`SQL_WRONG` 用 `consecutive_perfect < ${QuestionEntity.WRONG_THRESHOLD}`、`isWrong` 用 `consecutivePerfect < WRONG_THRESHOLD`——阈值一处改、两处同步。
2. **`PracticeSql.wrongClause()` 单一入口**：返回 `inactive = 0 AND $SQL_WRONG`，`PracticeStats` 的两条 SQL 用它拼条件，不再手写。
3. **守卫测试**：`MasteryTest` 钉 `isWrong` 四象限（`hasEverWrong × consecutivePerfect<2`）+ `SQL_WRONG` 阈值同步（动态拼接断言，防阈值漂移与字符串意外改）；`PracticeSqlTest` 钉 `wrongClause()` 结构。

## 理由

- **阈值是规则里唯一可共享的语义**：`has_ever_wrong = 1` 列名 vs `hasEverWrong` 属性名是 SQL/Kotlin 的必然差异，无法共享；但阈值 `2` 是纯语义，可单一来源。
- **守卫测试比注释硬**：原靠「勿两处漂移」注释（候选 3 报告点名的隐患），守卫测试改坏任一表达即红。
- **消除「同一条件两种拼法」**：`wrongQuestionCount` 的 WHERE 与 `PracticeSql.where(onlyWrong)` 的特例重复，统一后改停用语义（`inactive = 0`）只改一处。

## 代价与边界

- **两份表达仍存在**（SQL 片段 vs Kotlin 派生）：这是 SQL/Kotlin 的必然差异，无法合并为一份；靠守卫测试同步。
- **`WRONG_THRESHOLD` 放 db 层**（`QuestionEntity.kt`）：`isWrong` 在 db 层、`SQL_WRONG` 在 practice 层引它——依赖方向 practice→db 正常；若放 practice 层则 db 层的 `isWrong` 要反向引 practice（层依赖倒置）。
- **挂起重议条件**：若错题规则扩展（如阈值可配、多条件组合、按科目差异化），重审单一常量是否足够。

## 关键签名

```kotlin
// QuestionEntity.kt（db 层）
companion object {
    /** 错题消解阈值（CONTEXT.md「连续两次全对消解」）：SQL 与 Kotlin 两份表达共享此值 */
    const val WRONG_THRESHOLD = 2
}
val isWrong: Boolean get() = hasEverWrong && consecutivePerfect < WRONG_THRESHOLD

// QuestionPicker.kt
internal const val SQL_WRONG = "has_ever_wrong = 1 AND consecutive_perfect < ${QuestionEntity.WRONG_THRESHOLD}"

internal object PracticeSql {
    fun wrongClause(): String = "inactive = 0 AND $SQL_WRONG"
}
```
