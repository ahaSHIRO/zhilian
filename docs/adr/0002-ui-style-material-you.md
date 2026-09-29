# UI 采用 Material You 动态取色与 M3 组件（动态取色已被取代）

> **部分取代**：
> - 本文「Material You 动态取色」决策已被 [ADR-0005](./0005-fixed-mist-blue-palette-and-unified-cards.md) 取代——动态取色随壁纸不可控（实测出现整屏偏粉），改为内置固定雾蓝配色。
> - 本文「提交后从底部弹出解析」这一表述已过时：ADR-0003 曾废弃弹层改内联，[ADR-0007](./0007-explanation-half-modal-panel.md) 又恢复为**半模态面板**（教材式解析使卡片被撑到整页）。现形态见 ADR-0007。
> - 本文其余内容（M3 组件、提交后就地高亮选项、统计页数字卡片、下方图标决策）仍然有效。

UI 使用 Material3（配色改为固定雾蓝，见上）并跟随系统深浅色，App 内提供深色模式手动覆盖开关；练习页提交答案后就地高亮选项（对/错），解析另行承载；统计页首版用数字卡片与进度条呈现首次/全部正确率、多选全对率、平均得分率，不引入图表库。由此避免自建设计系统和重型图表依赖，个人自用场景的设计维护成本最低；间距/圆角/字体 token 规范见 [docs/conventions/design-tokens.md](../conventions/design-tokens.md)；统计趋势图等需求出现后仍可引入 Compose 原生图表库（如 Vico）。

## 图标决策（2026-09-27 修订）

初版计划使用 androidx material-icons-extended；核查发现该库 1.7.8 起已停止维护且官方明确不推荐（见 developer.android.com 图标文档），改用 **Material Symbols 矢量 XML 资源**：从 google/material-design-icons 仓库（symbols/web/<图标>/materialsymbolsoutlined/）取 SVG，转为 VectorDrawable 放入 res/drawable，选中态用 fill1 变体、常态用 outlined 变体。零运行时依赖、跟随主题 tint、可按需增量添加。

