package com.baiyin.zhilian.data.practice

/**
 * 练习筛选条件的选择态（CONTEXT.md「练习筛选条件」）。
 *
 * 级联规则在此：分类挂在科目下、标签挂在分类下，上层取值变化后已选的下层值
 * 必须收窄，否则会留下既看不见也关不掉的暗筛选。原先前者散在配置页的三个
 * LaunchedEffect 里，与 UI 状态交织、无从单测。
 */
data class PracticeSelection(
    val subjects: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val onlyWrong: Boolean = false,
    val onlyFavorite: Boolean = false,
    val sequential: Boolean = true,
    val limit: Int = DEFAULT_LIMIT,
) {
    /** 单选科目时的下钻键；多选科目取并集，分类/标签不限定于单一科目 */
    val onlySubject: String? get() = subjects.singleOrNull()

    /** 科目变化后收窄分类：丢掉已不在新取值里的选择 */
    fun withCategories(available: List<String>): PracticeSelection =
        copy(categories = categories.intersect(available.toSet()))

    /** 分类变化后收窄标签：同上 */
    fun withTags(available: List<String>): PracticeSelection =
        copy(tags = tags.intersect(available.toSet()))

    /** 组装成出题条件（维度间交集、维度内并集、两个范围开关取交集） */
    fun toFilter(): PracticeFilter = PracticeFilter(
        subjects = subjects,
        categories = categories,
        types = types,
        tags = tags,
        onlyWrong = onlyWrong,
        onlyFavorite = onlyFavorite,
        sequential = sequential,
        limit = limit,
    )

    companion object {
        /** 题量默认值（定案：10–100、步长 5、默认 20） */
        const val DEFAULT_LIMIT = 20
    }
}
