# STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 测试用例骨架（R6 三轮，2026-05-12）

任务卡：[`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`](../process/task-cards/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT.md)

R2 三轮契约真源：
- [数据库设计 §4.2 / §4.3](../database/falconx一期数据库设计.md)
- [管理端接口规范 §6.13](../api/管理端接口规范.md)
- [管理端架构 §2.2 / §4.3](../architecture/管理端架构.md)

R3 三轮设计真源：
- [`falconx-console-pages-V1.md` §10](../design/falconx-console-pages-V1.md)

## R6 测试目标

1. **零回归保障**：schema 变更（删 6 字段 + 加 6 字段）不破坏 trading-core 现有开仓/平仓/强平/Swap 链路
2. **Phase B 生效证明**：trading-core 真实消费 `mapping.taker_fee_rate` 与 `mapping.max_leverage`，按 platform symbol 差异化生效
3. **历史持仓保护**：开仓时 `open_fee_rate` 快照入 t_order/t_position；运营改 mapping 后历史持仓平仓 / 强平 / Swap 仍按快照值
4. **Owner 边界**：trading-core 不跨 schema 直查 `falconx_market`，必须通过 Redis Hash `falconx:market:symbol-spec:{platformSymbol}` 消费
5. **新增源 Symbol 链路**：管理员 POST → market 自动追加 LP 订阅 → ClickHouse 收到 tick → 客户端可见
6. **聚合视图与 group_concat truncate**：5000+ symbol 大组场景 mapper SET SESSION 后正确返回
7. **RBAC 迁移**：现有持有 symbol:update 的 admin 自动获得 symbol:source:update

## §1 后端测试用例（共 99 用例）

### §1.1 TC-PARAMS-SOURCE-001 ~ 015（market source CRUD）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-PARAMS-SOURCE-001` | POST /admin/symbols 成功创建 XAGUSD | 200 + t_symbol 行入库 + status=1 + market warmup 触发 |
| `TC-PARAMS-SOURCE-002` | POST 字段集校验（缺 symbol） | 99004 |
| `TC-PARAMS-SOURCE-003` | POST symbol 重复 | 90619 ADMIN_SYMBOL_SOURCE_DUPLICATE |
| `TC-PARAMS-SOURCE-004` | POST category 越界（如 99） | 90620 |
| `TC-PARAMS-SOURCE-005` | POST marketCode 长度超 32 | 99004 |
| `TC-PARAMS-SOURCE-006` | POST pricePrecision = 15（越界） | 90620 |
| `TC-PARAMS-SOURCE-007` | POST status = 2（不允许创建即 suspended） | 90620 |
| `TC-PARAMS-SOURCE-008` | POST reason < 10 字符 | 99004 |
| `TC-PARAMS-SOURCE-009` | POST 成功后 `t_admin_operation_log` HIGH_RISK 落库 | risk_level=HIGH_RISK, permission=symbol:source:create |
| `TC-PARAMS-SOURCE-010` | POST 无权限角色 | 90004 |
| `TC-PARAMS-SOURCE-011` | PUT /admin/symbols/{id} 编辑 category | 200 + t_symbol 更新 |
| `TC-PARAMS-SOURCE-012` | PUT 尝试包含 maxLeverage 字段 | 字段被忽略（字段集裁剪后不接受） |
| `TC-PARAMS-SOURCE-013` | PUT 不存在的 id | 90600 |
| `TC-PARAMS-SOURCE-014` | GET /admin/symbols 返回字段不含 maxLeverage/takerFeeRate/spread/minQty/maxQty/minNotional | 字段裁剪验证 |
| `TC-PARAMS-SOURCE-015` | GET /admin/symbols 返回字段含 lastTickAt | 与 ClickHouse 数据一致 |

### §1.2 TC-PARAMS-MAPPING-001 ~ 025（market mapping CRUD 字段扩展）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-PARAMS-MAPPING-001` | POST 新建 mapping 带完整 6 交易字段 | 200 + DB 字段全部正确 |
| `TC-PARAMS-MAPPING-002` | POST maxLeverage = 600 | 90601 |
| `TC-PARAMS-MAPPING-003` | POST maxLeverage = 0 | 90601 |
| `TC-PARAMS-MAPPING-004` | POST takerFeeRate = 0.06 | 90602 |
| `TC-PARAMS-MAPPING-005` | POST takerFeeRate = -0.01 | 90602 |
| `TC-PARAMS-MAPPING-006` | POST spread = -0.1 | 90603 |
| `TC-PARAMS-MAPPING-007` | POST minQty = 100, maxQty = 100（min ≥ max） | 90604 |
| `TC-PARAMS-MAPPING-008` | POST minNotional = -1 | 90604 |
| `TC-PARAMS-MAPPING-009` | POST pricePrecision = 11 | 90620 |
| `TC-PARAMS-MAPPING-010` | POST pricePrecision = NULL（继承 source） | 200 + DB 字段 NULL |
| `TC-PARAMS-MAPPING-011` | POST sourceSymbol 不存在于 t_symbol | 90615 |
| `TC-PARAMS-MAPPING-012` | POST platformSymbol 重复 | 90614 |
| `TC-PARAMS-MAPPING-013` | PUT 编辑 takerFeeRate 从 0.0005 改 0.001 | 200 + DB 更新 + Redis spec 立即刷新 |
| `TC-PARAMS-MAPPING-014` | PUT mapping 不存在 | 90613 |
| `TC-PARAMS-MAPPING-015` | DB CHECK 约束生效：直接 INSERT max_leverage=600 抛 SQL 异常 | constraint chk_mapping_leverage 触发 |
| `TC-PARAMS-MAPPING-016` | DB CHECK 约束：min_qty >= max_qty 抛异常 | chk_mapping_qty |
| `TC-PARAMS-MAPPING-017` | DB CHECK 约束：precision = 11 抛异常 | chk_mapping_price_precision |
| `TC-PARAMS-MAPPING-018` | GET list 返回字段含 7 交易参数 + 2 precision | 与 R2 契约 6.13 对齐 |
| `TC-PARAMS-MAPPING-019` | 时间戳 created_at / updated_at 类型为 datetime(3) | INFORMATION_SCHEMA.COLUMNS 验证 |
| `TC-PARAMS-MAPPING-020` | 后缀 .p / .c / .f 不被特殊拒绝 | 与现有 STAGE-2-SYMBOL-THREE-TABLE-ADMIN 测试一致 |
| `TC-PARAMS-MAPPING-021` | mapping CRUD 触发 SymbolSpec Redis warmup（afterCommit） | Redis Hash falconx:market:symbol-spec:{platformSymbol} 含新值 |
| `TC-PARAMS-MAPPING-022` | mapping 删除场景（任务卡未要求 DELETE，复测当前 POST/PUT 是唯一写路径） | DELETE 路径不存在 405 |
| `TC-PARAMS-MAPPING-023` | reason < 10 字符 | 99004 |
| `TC-PARAMS-MAPPING-024` | 高风险审计落库 | risk_level=HIGH_RISK, target_id=platformSymbol |
| `TC-PARAMS-MAPPING-025` | 无权限角色 | 90004 |

### §1.3 TC-PARAMS-SPEC-001 ~ 010（market SymbolSpec Redis warmup）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-PARAMS-SPEC-001` | market 启动 warmup：所有 mapping 写入 Redis Hash | Redis key 数量 = mapping.count |
| `TC-PARAMS-SPEC-002` | mapping CRUD afterCommit 触发 refresh | 单个 key 立即更新 |
| `TC-PARAMS-SPEC-003` | Hash 字段完整：含 maxLeverage / takerFeeRate / spread / minQty / maxQty / minNotional / pricePrecision / qtyPrecision | 8 字段全 |
| `TC-PARAMS-SPEC-004` | pricePrecision NULL 时 Hash 字段值 = "null"（或省略，决定于 R2 + R4） | Hash 字段处理 NULL 的语义一致 |
| `TC-PARAMS-SPEC-005` | TTL 验证：Hash 永不过期（TTL = -1） | redisson `getRemainTimeToLive` |
| `TC-PARAMS-SPEC-006` | warmup 后 Redis 与 DB mapping 字段值一致（抽样验证 100 个） | bit-by-bit 一致 |
| `TC-PARAMS-SPEC-007` | mapping update 后 Redis Hash 立即反映 | 写后立即读，新值 |
| `TC-PARAMS-SPEC-008` | Redis 故障时 trading-core 拒单 SYMBOL_SPEC_NOT_FOUND | mock Redis 不可达 |
| `TC-PARAMS-SPEC-009` | 内部 RPC `GET /internal/v1/market/symbols/spec/{platformSymbol}` 返回值与 Redis 一致 | 双源一致 |
| `TC-PARAMS-SPEC-010` | 内部 RPC 不存在 platform symbol | 404 + 90613 |

### §1.4 TC-PARAMS-TICK-001 ~ 006（ClickHouse last-tick RPC）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-PARAMS-TICK-001` | GET /internal/v1/market/symbols/last-tick?symbols=XAUUSD,BTCUSD | 200 + map 含两个 symbol 的最新 event_time |
| `TC-PARAMS-TICK-002` | 单次请求 symbols 数量 > 100 | 400 + 错误码 |
| `TC-PARAMS-TICK-003` | 请求中含未在 quote_tick 表的 symbol（如 XAGUSD-NEW） | 返回 map 不含该 symbol（前端展示「从未收到」） |
| `TC-PARAMS-TICK-004` | symbols 参数为空 | 400 |
| `TC-PARAMS-TICK-005` | ClickHouse 不可达时 | 503 或快速失败（不阻塞 console 主流程） |
| `TC-PARAMS-TICK-006` | 性能：100 symbols 查询 < 200ms | latency 断言 |

### §1.5 TC-PARAMS-VIS-001 ~ 008（group visibility 聚合视图）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-PARAMS-VIS-001` | GET /admin/symbols/group-visibility/grouped 返回 default + vip + ... | items 按 groupCode 聚合 |
| `TC-PARAMS-VIS-002` | 单组超过 5000 symbol（大组 truncate 场景）| group_concat_max_len 调高后完整返回 |
| `TC-PARAMS-VIS-003` | 单组 0 symbol（vip 现状） | visibleCount=0, visibleSymbols=[] |
| `TC-PARAMS-VIS-004` | 筛选 groupCodeLike=de | 命中 default |
| `TC-PARAMS-VIS-005` | 分页 size=2 | total 正确，items 2 个 |
| `TC-PARAMS-VIS-006` | mapper 自动 SET SESSION group_concat_max_len=1048576 | 验证连接级 SQL 执行 |
| `TC-PARAMS-VIS-007` | lastModifiedAt 来自 MAX(updated_at) | 与 DB 一致 |
| `TC-PARAMS-VIS-008` | 权限 symbol:view 缺失 | 90004 |

### §1.6 TC-PARAMS-TRADING-001 ~ 020（trading-core SymbolSpec 消费）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-PARAMS-TRADING-001` | 开仓时通过 Redis 查 SymbolSpec | 缓存命中（10s 本地） |
| `TC-PARAMS-TRADING-002` | SYMBOL_SPEC_NOT_FOUND 场景：Redis 缺失 | reject SYMBOL_SPEC_NOT_FOUND |
| `TC-PARAMS-TRADING-003` | effective max leverage = min(mapping.max_leverage=50, risk_config.max_leverage=200) = 50 | 60 倍杠杆下单 → LEVERAGE_EXCEEDED |
| `TC-PARAMS-TRADING-004` | mapping.max_leverage 缺失 risk_config（risk_config = null） | effective = mapping.max_leverage |
| `TC-PARAMS-TRADING-005` | fee 计算用 mapping.takerFeeRate | t_order.fee = fillPrice × qty × mapping.taker_fee_rate |
| `TC-PARAMS-TRADING-006` | 开仓时 open_fee_rate 落 t_order | t_order.open_fee_rate = mapping.taker_fee_rate |
| `TC-PARAMS-TRADING-007` | 开仓时 open_fee_rate 落 t_position | t_position.open_fee_rate 同 t_order |
| `TC-PARAMS-TRADING-008` | qty < mapping.minQty | reject QTY_BELOW_MIN |
| `TC-PARAMS-TRADING-009` | qty > mapping.maxQty | reject QTY_ABOVE_MAX |
| `TC-PARAMS-TRADING-010` | notional < mapping.minNotional | reject NOTIONAL_BELOW_MIN |
| `TC-PARAMS-TRADING-011` | qty = mapping.minQty（边界 inclusive） | 通过 |
| `TC-PARAMS-TRADING-012` | qty = mapping.maxQty（边界 inclusive） | 通过 |
| `TC-PARAMS-TRADING-013` | 平仓按 position.open_fee_rate 计算 fee（**历史保护**）| 平仓 fee = closePrice × qty × position.open_fee_rate（不查 mapping）|
| `TC-PARAMS-TRADING-014` | 强平按 position.open_fee_rate 计算 fee | 同上 |
| `TC-PARAMS-TRADING-015` | Swap 结算用 position.open_fee_rate（如适用）或独立 swap_rate | 与 t_swap_rate 流程一致 |
| `TC-PARAMS-TRADING-016` | 历史持仓（open_fee_rate=0.0005）→ 管理员改 mapping 到 0.001 → 平仓仍按 0.0005 | t_trade.fee 反映 0.0005 |
| `TC-PARAMS-TRADING-017` | open_fee_rate 字段不影响 quotaResultRepository / queryService 返回 | t_order/position 现有 ORM record 含新字段 |
| `TC-PARAMS-TRADING-018` | Spec 本地缓存 10s TTL：mapping 更新后第 11s 反映 | 缓存失效后查 Redis 拿新值 |
| `TC-PARAMS-TRADING-019` | trading-core 不读 falconx_market schema | grep + 集成测试断言 |
| `TC-PARAMS-TRADING-020` | t_order / t_position 字段 open_fee_rate 类型为 decimal(10,6) | INFORMATION_SCHEMA.COLUMNS |

### §1.7 TC-PARAMS-CONSOLE-001 ~ 015（console-service REST + RBAC 迁移）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-PARAMS-CONSOLE-001` | console 调 market internal RPC last-tick 合并返回 | GET /admin/symbols 含 lastTickAt |
| `TC-PARAMS-CONSOLE-002` | console 错误码翻译：market 90601 → console 90601（透传，不改语义） | 1:1 |
| `TC-PARAMS-CONSOLE-003` | 同上 90602 / 90603 / 90604 / 90615 / 90616 / 90619 / 90620 | 透传 |
| `TC-PARAMS-CONSOLE-004` | RBAC 启动扫描注册 `symbol:source:create / symbol:source:update` | t_admin_permission 含新条目 |
| `TC-PARAMS-CONSOLE-005` | RBAC V8 migration idempotent：再跑一次无副作用 | INSERT NOT EXISTS 命中 |
| `TC-PARAMS-CONSOLE-006` | V8 后 symbol:update 持有者获得 symbol:source:update | 跨表 JOIN 验证 |
| `TC-PARAMS-CONSOLE-007` | V8 后 symbol:update 仍在 t_admin_permission 但 enabled=0 | 不删，保留审计 |
| `TC-PARAMS-CONSOLE-008` | 高风险审计 target_id 含正确 symbol | 创建 source 时 target_id=symbol |
| `TC-PARAMS-CONSOLE-009` | console 调 market internal RPC 失败时 | console 返回 502 或 90701-90703 |
| `TC-PARAMS-CONSOLE-010` | console 调 ClickHouse 不可达时（last-tick RPC 失败）| GET /admin/symbols 仍返回主表数据，lastTickAt 字段为 null + 警告日志 |
| `TC-PARAMS-CONSOLE-011` | PUT mapping bulk visibility 参数验证（symbols 长度 0 → 99004）| 与 STAGE-2-SYMBOL-THREE-TABLE-ADMIN 一致 |
| `TC-PARAMS-CONSOLE-012` | POST /admin/symbols 雪花 id 字符串序列化（避免 JS 精度丢失） | 与 FX-071 修复模式一致 |
| `TC-PARAMS-CONSOLE-013` | GET /admin/symbols/group-visibility/grouped 含 visibleSymbols 列表 | 见 §1.5 用例覆盖 |
| `TC-PARAMS-CONSOLE-014` | 错误码 90619 / 90620 HTTP 状态 400 vs 90600 状态 404 | 与 §6.13 表对齐 |
| `TC-PARAMS-CONSOLE-015` | console 启动后 SymbolSpec Redis 不依赖（console 不读 spec） | console 不应直接读 falconx:market:symbol-spec |

## §2 前端测试用例（共 25 用例）

### §2.1 FE-PARAMS-001 ~ 025（console-frontend 三 Tab 重设计）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `FE-PARAMS-001` | Tab 主表渲染：lastTickAt 红黄绿状态点 < 5min 绿 | 状态点 className=green |
| `FE-PARAMS-002` | 主表 lastTickAt < 30min 黄 | className=yellow |
| `FE-PARAMS-003` | 主表 lastTickAt ≥ 30min 或 NULL 红 | className=red + tooltip「死 symbol」 |
| `FE-PARAMS-004` | 主表「+ 新建源 Symbol」按钮 RBAC：无 `symbol:source:create` 隐藏 | RequiresPermission 组件 |
| `FE-PARAMS-005` | 主表 6 个字段（maxLev/takerFee/spread/minQty/maxQty/minNotional）不再展示 | 字段已移除 |
| `FE-PARAMS-006` | 新建源 Modal：reason < 10 字符 确认按钮 disabled | disabled=true |
| `FE-PARAMS-007` | 新建源 Modal：90619 错误时 symbol 输入框红框 + toast | error 渲染 |
| `FE-PARAMS-008` | 新建源 Modal：90620 错误时对应字段红框 | error 渲染 |
| `FE-PARAMS-009` | 编辑 source Drawer：字段集裁剪（不含 leverage 等） | 表单字段验证 |
| `FE-PARAMS-010` | Tab 映射列表：7 交易参数 + precision badge 全展示 | 9 列渲染 |
| `FE-PARAMS-011` | 映射列表 precision badge：mapping pricePrecision=null 显示「继承 (3/2)」灰色 Tag | className=tag-gray |
| `FE-PARAMS-012` | 映射列表 precision badge：mapping 覆盖时显示「覆盖 (5/4)」蓝色 Tag | className=tag-blue |
| `FE-PARAMS-013` | 编辑 mapping Drawer：分三个折叠分组「上游配置 / 交易参数 / 精度覆盖」 | Collapse 组件 |
| `FE-PARAMS-014` | source 下拉：从 t_symbol.status=1 拉取 + 搜索功能 | 数据源验证 |
| `FE-PARAMS-015` | source 下拉：不允许手填 | mode='filterable' but combobox=false |
| `FE-PARAMS-016` | 编辑 mapping：90601-90604 错误时对应字段红框 | 错误码绑定字段 |
| `FE-PARAMS-017` | Tab 可见性 Segmented 切换：默认「聚合视图」 | activeKey=aggregated |
| `FE-PARAMS-018` | 聚合视图左侧组列表：显示 `{groupCode} ({visibleCount})` | 双值渲染 |
| `FE-PARAMS-019` | 聚合视图右侧 Transfer：左可选所有 enabled=1 的 platform symbol | 数据源验证 |
| `FE-PARAMS-020` | Transfer Δ 计算：+N 新增 / -M 移除 实时显示 | computed |
| `FE-PARAMS-021` | Transfer 保存按钮 Δ=0 时 disabled | disabled |
| `FE-PARAMS-022` | 大组（5000+ symbol） Transfer 虚拟滚动 | virtualScroll=true |
| `FE-PARAMS-023` | 移动 ≤ 768：聚合视图降级 Drawer | breakpoint media query |
| `FE-PARAMS-024` | 明细视图保留：可切换 | activeKey=detailed |
| `FE-PARAMS-025` | 三 Tab 切换 URL hash 同步 + 刷新保留 | router state |

## §3 E2E 测试用例（共 5 用例）

### §3.1 TC-E2E-PARAMS-001 ~ 005（端到端整链）

| 编号 | 场景 | 期望断言 |
| --- | --- | --- |
| `TC-E2E-PARAMS-001` | 管理员把 mapping.takerFeeRate 从 0.0005 改 0.001 → 用户开仓 → 后端按 0.001 收 fee → t_order.fee 反映 | 整链生效 |
| `TC-E2E-PARAMS-002` | **历史持仓保护**：已有持仓 open_fee_rate=0.0005 → 管理员改 mapping 到 0.001 → 平仓 fee 按 0.0005 | t_trade.fee = closePrice × qty × position.open_fee_rate |
| `TC-E2E-PARAMS-003` | 管理员调 mapping.maxLeverage 100 → 50；effective max = min(50, 200) = 50；用户下 60 倍杠杆 → LEVERAGE_EXCEEDED | trading-core 拒单 |
| `TC-E2E-PARAMS-004` | 管理员 POST /admin/symbols 新建 XAGUSD → market 5s 内追加 LP 订阅 → ClickHouse 收 tick → mapping 派生后 客户端 /api/v1/market/symbols 看到 XAGUSD 的 platform symbol | 整链 < 30s |
| `TC-E2E-PARAMS-005` | 管理员把 mapping.minQty 改大 → 用户下小单 → QTY_BELOW_MIN 拒单；改回小值 → 通过 | 双向生效 |

## §4 测试基础设施要求

- MySQL 8.4 测试 schema 独立（不复用生产）
- Redis 8.2 独立 DB 编号；测试隔离 Hash key
- ClickHouse 25.x 独立测试库；快照插入测试 tick
- Kafka Testcontainers（订阅 / 推送链路）
- 测试用 admin 账号已通过 V8 migration（持有 symbol:source:create / source:update）

## §5 CFD §13.9 注册

新增 TC 编号块（待 CFD `docs/test/CFD全面测试用例规范.md` §13.9 同步落盘）：

| Prefix | 编号区间 | 数量 | 说明 |
| --- | --- | --- | --- |
| `TC-PARAMS-SOURCE-` | 001-015 | 15 | market source CRUD 后端集成测试 |
| `TC-PARAMS-MAPPING-` | 001-025 | 25 | market mapping CRUD 字段扩展 |
| `TC-PARAMS-SPEC-` | 001-010 | 10 | SymbolSpec Redis warmup |
| `TC-PARAMS-TICK-` | 001-006 | 6 | ClickHouse last-tick RPC |
| `TC-PARAMS-VIS-` | 001-008 | 8 | group visibility 聚合视图 |
| `TC-PARAMS-TRADING-` | 001-020 | 20 | trading-core SymbolSpec 消费 + 历史保护 |
| `TC-PARAMS-CONSOLE-` | 001-015 | 15 | console-service REST + RBAC 迁移 |
| `FE-PARAMS-` | 001-025 | 25 | console-frontend 三 Tab 重设计组件测试 |
| `TC-E2E-PARAMS-` | 001-005 | 5 | 端到端整链 |

合计 **129 用例**。

## §6 验收硬约束（阶段 5.X 完成判定）

- [ ] `TC-PARAMS-SOURCE-001~015` 全部通过（source CRUD 15 用例）
- [ ] `TC-PARAMS-MAPPING-001~025` 全部通过（mapping CRUD 25 用例）
- [ ] `TC-PARAMS-SPEC-001~010` 全部通过（SymbolSpec Redis 10 用例）
- [ ] `TC-PARAMS-TICK-001~006` 全部通过（last-tick RPC 6 用例）
- [ ] `TC-PARAMS-VIS-001~008` 全部通过（聚合视图 8 用例）
- [ ] `TC-PARAMS-TRADING-001~020` 全部通过（trading-core 消费 + 历史保护 20 用例）
- [ ] `TC-PARAMS-CONSOLE-001~015` 全部通过（console + RBAC 迁移 15 用例）
- [ ] `FE-PARAMS-001~025` 全部通过（前端 25 用例）
- [ ] `TC-E2E-PARAMS-001~005` 全部通过（E2E 5 用例）
- [ ] R7 浏览器 QA：三 Tab 重设计桌面 + 移动截图齐全
- [ ] R7 客户端回归：C 端 /api/v1/market/symbols 字段名不变 + 取值来自 mapping
- [ ] V6 + V8 + V13 Flyway migration 顺序部署不回滚
- [ ] Phase A + Phase B 必须同一发布窗口（任务卡强制约束）
