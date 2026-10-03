# 阶段 2.3 Symbol 三表管理端验证用例

任务卡：[`STAGE-2-SYMBOL-THREE-TABLE-ADMIN`](../process/task-cards/STAGE-2-SYMBOL-THREE-TABLE-ADMIN.md)

## R6 测试目标

- `t_symbol` 继续作为上游 LP 源 symbol 主表，不新增运行时代码白名单。
- `t_symbol_quote_mapping.platform_symbol` 是系统展示 symbol，`source_symbol` 是实际上游订阅 symbol。
- `t_symbol_group_visibility` 按用户组控制 platform symbol 可见性。
- 管理端接口不按 `.p / .c / .f` 或其他后缀做强制拒绝；三表数据与状态是唯一准入依据。

## 后端用例

| 用例 | 断言 |
| --- | --- |
| `TC-SYMBOL-3TABLE-001` | 新建 mapping 时，`platform_symbol=XAUUSD.p` 且 `source_symbol=XAUUSD` 存在时必须允许写入，不得触发后缀拒绝 |
| `TC-SYMBOL-3TABLE-002` | 新建 mapping 时，`source_symbol` 不存在于 `t_symbol` 必须返回 `90615 ADMIN_SYMBOL_MAPPING_SOURCE_NOT_FOUND` |
| `TC-SYMBOL-3TABLE-003` | 更新 mapping 的 `enabled/lpSubscribeEnabled` 后必须刷新 LP 订阅白名单 |
| `TC-SYMBOL-3TABLE-004` | upsert 用户组可见性前，`symbol` 必须存在于 `t_symbol_quote_mapping.platform_symbol` |
| `TC-SYMBOL-3TABLE-005` | `symbol:quote-mapping:update` 与 `symbol:group-visibility:update` 必须进入高风险权限集合 |
| `TC-SYMBOL-3TABLE-006` | 批量 upsert 用户组可见性时，所有 platform symbol 均存在于 mapping 后才写入，单次最多 500 个 |

## 前端用例

| 用例 | 断言 |
| --- | --- |
| `FE-SYMBOL-3TABLE-001` | Symbol 管理页提供 `主表 / 报价映射 / 组可见性` 三个工作区 |
| `FE-SYMBOL-3TABLE-002` | mapping 与 visibility 写操作按钮受对应权限码控制 |
| `FE-SYMBOL-3TABLE-003` | mapping/visibility 写操作必须填写 10-500 字符操作原因 |
| `FE-SYMBOL-3TABLE-004` | mapping 表展示 `platformSymbol/sourceSymbol/enabled/lpSubscribeEnabled/sourceStatus` |
| `FE-SYMBOL-3TABLE-005` | 组可见性提供单项 upsert 与批量设置入口 |

## 客户端回归

| 用例 | 断言 |
| --- | --- |
| `C-SYMBOL-3TABLE-001` | C 端 `/market/symbols` 只返回当前用户组可见且 mapping 启用、源 symbol TRADING 的 platform symbol |
| `C-SYMBOL-3TABLE-002` | WebSocket 订阅 platform symbol 后，后端使用 mapping 解析源报价并按 platform symbol 推送 |
