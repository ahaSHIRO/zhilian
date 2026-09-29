package com.baiyin.zhilian.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 知练字体规范（design-tokens.md §四）。
 *
 * 只规范被引用的 11 档；未用的 displayLarge/Medium/Small 留 M3 默认不指定。
 * 字重三档对比：body=Normal、title/headline/label=Medium；
 * 强调场景（统计数字/会话得分/错题标记）由调用方在对应档上覆写 FontWeight.Bold。
 *
 * 行高为建议初值（正文约 1.4–1.5×、标题约 1.2–1.3×），实现后按真机语感微调。
 */
val ZhilianTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
)
