# 题库可选值列表归位 QuestionBank + SQL 转义单一来源

`QuestionPicker` 此前 8 方法混两关注点：**选题执行**（`pick`/`countMatching`/`wrongCount`/`favoriteCount`，走 `PracticeSql` 拼 WHERE）与**题库可选值列表**（`subjects`/`categories`/`tags`/`types`，各自手写 SQL）。后者语义上是「题库有什么」（科目/分类/标签/题型的取值域），与 `QuestionBank.observeCategories` 同族，不是「出哪些题」。另 `categories()`/`tags()` 两处手写 subject 转义（`subject.replace("'", "''")`），而 `PracticeSql.quote` 是 IN 列表格式（`'a','b'`）不能直接用于单值等值（`subject = 'a'`）——转义规则在三处各写一遍。

对此本决策为：

1. **可选值列表归位 `QuestionBank`**（question 层）：`subjects`/`categories`/`tags`/`types` 移入，`QuestionPicker` 收窄到选题执行（`pick` + 3 个 count + 私有 `tagRows`）。
2. **`QuestionTags` 移到 `data.question`**：题目标签（`matches`/`distinct`）本属题库，随可选值列表归位；practice→question 依赖方向正常（`Submission` 已用 `QuestionContent`）。
3. **`SqlEscape` 独立工具**（`data.db.SqlEscape`）：`sqlEscape(value)`（单值转义）+ `sqlEscapeAll(values)`（IN 列表）；`QuestionBank.categories`/`tags` 用它，`PracticeSql.quote` 复用它——转义逻辑一处。

## 理由

- **语义归位**：可选值是「题库有什么」，归 `QuestionBank` 后调用方语义清晰（`questionBank.categories(subject)` 而非 `questionPicker.categories(subject)`）；`QuestionPicker` 接口从 8 方法收窄到 5，深度提升。
- **转义单一来源**：消除 `categories`/`tags` 两处手写 + `PracticeSql.quote` 内联转义，共三处归一；`SqlEscape` 放 db 层（与 `SimpleSQLiteQuery` 同族），practice/question 都可引，依赖方向干净。
- **层依赖不倒置**：`sqlEscape` 若放 `PracticeSql`（practice 层），`QuestionBank`（question）引它会倒置——放 `data.db` 避免。

## 代价与边界

- **`QuestionTags` 移包**（`data.practice` → `data.question`）：文件移动 + 包名改，`QuestionPicker` 加 import 引它（practice→question，方向正常）。
- **`QuestionBank.categories`/`tags` 的 DAO 调用不测**：需 mock DAO，候选 4 不引入新测试基建（ADR-0013 的取向）；只测 `sqlEscape`/`sqlEscapeAll` 纯函数（3 用例进 `PracticeSqlTest`）。
- **挂起重议条件**：若可选值列表扩展（按难度/来源等筛选），或 `QuestionBank` 接口膨胀到与 `QuestionPicker` 当初类似的混居程度，重审归位边界。

## 关键签名

```kotlin
// data/db/SqlEscape.kt（独立工具）
internal fun sqlEscape(value: String): String = "'" + value.replace("'", "''") + "'"
internal fun sqlEscapeAll(values: Set<String>): String = values.joinToString(",") { sqlEscape(it) }

// QuestionBank.kt——题库可选值（语义归位）
suspend fun subjects(): List<String>
suspend fun categories(subject: String?): List<String>
suspend fun tags(subject: String?, categories: Set<String>): List<String>
suspend fun types(): List<String>

// QuestionPicker.kt——收窄到选题执行（5 方法）
// pick / countMatching / wrongCount / favoriteCount / tagRows（私有）
```
