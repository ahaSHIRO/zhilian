package com.baiyin.zhilian.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baiyin.zhilian.R

/**
 * 题型代码 → 展示名（单选/多选/判断/填空），全库唯一一份。
 *
 * 三处消费：练习配置 chips（PracticeHomeScreen）、题库列表角标（BankScreen）、
 * 会话题卡左上标签（PracticeSessionScreen）。此前 PracticeHomeScreen 与 BankScreen
 * 各持一份私有实现且已漂移（一份 stringResource、一份硬编码中文），2026-10-01 收编。
 * 未知代码原样返回，防 Schema 扩题型后 UI 静默丢标签。
 */
@Composable
fun typeLabel(code: String): String = when (code) {
    "single_choice" -> stringResource(R.string.type_single_choice)
    "multiple_choice" -> stringResource(R.string.type_multiple_choice)
    "true_false" -> stringResource(R.string.type_true_false)
    "fill_in_blank" -> stringResource(R.string.type_fill_in_blank)
    else -> code
}
