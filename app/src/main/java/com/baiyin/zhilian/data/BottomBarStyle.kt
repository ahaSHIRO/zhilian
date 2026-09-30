package com.baiyin.zhilian.data

/**
 * 底栏效果三档（ADR-0010，设置项，DataStore 持久化）。
 * 三档共用同一套「悬浮胶囊」版式，切换只换材质、不重排版式。
 *
 * 与 [ThemeMode] 同样下沉到数据层；渲染能力相关的解析（[isLiquidGlassSupported]
 * 等）留在 UI 层——那是表示层的事，不是设置值本身。
 */
enum class BottomBarStyle {
    /** 不透明明快表面：无着色器、无透明，交给 M3 原生涟漪反馈。低版本设备的安全档。 */
    STANDARD,

    /** 液态玻璃：折射页面内容（vibrancy + blur + lens），按压时选中胶囊膨胀并带高光/内阴影/色散。 */
    LIQUID_GLASS,

    /** 磨砂：半透明面 + 缩放反馈，不涉着色器（AGSAL 不可用时的降级档）。 */
    FROSTED,
}
