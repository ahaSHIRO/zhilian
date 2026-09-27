# UI 采用 Material You 动态取色与 M3 组件

UI 使用 Material3 动态取色（dynamicColorScheme）并跟随系统，App 内提供深色模式手动覆盖开关；练习页提交答案后就地高亮选项（对/错）并从底部弹出解析，不打断做题节奏；统计页首版用数字卡片与进度条呈现首次/全部正确率、多选全对率、平均得分率，不引入图表库。由此避免自建设计系统和重型图表依赖，深浅色对比度与组件语义色由 M3 token 保证，个人自用场景的设计维护成本最低；统计趋势图等需求出现后仍可引入 Compose 原生图表库（如 Vico）。

## 图标决策（2026-09-27 修订）

初版计划使用 androidx material-icons-extended；核查发现该库 1.7.8 起已停止维护且官方明确不推荐（见 developer.android.com 图标文档），改用 **Material Symbols 矢量 XML 资源**：从 google/material-design-icons 仓库（symbols/web/<图标>/materialsymbolsoutlined/）取 SVG，转为 VectorDrawable 放入 res/drawable，选中态用 fill1 变体、常态用 outlined 变体。零运行时依赖、跟随主题 tint、可按需增量添加。

