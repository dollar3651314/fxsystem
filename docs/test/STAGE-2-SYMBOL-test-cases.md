# 阶段 2.3 行情品种管理测试用例清单（V1，2026-05-09）

> R6 三轮交付，覆盖 [`管理端接口规范`](../api/管理端接口规范.md) §6 行情品种管理 8 个 console REST + §6.10 market internal RPC + RBAC 新模块 `symbol`。
>
> R10 二轮 / R9 二轮 / R4.4 实施前由本清单作为成功标准；实施完成后 R6 把 TC 落地为真实 `@Test`。
>
> R7 收口状态（2026-05-11）：live API `53 pass / 0 fail` + 浏览器 QA 7 截图 + 目标回归测试已通过，详见 [`STAGE-2-SYMBOL-R7-verification-report`](STAGE-2-SYMBOL-R7-verification-report.md)。完整 81 TC 转 CI 自动化仍作为测试债务保留。

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 350-379 | 30 | 阶段 2.3（console 服务侧）|
| `TC-MARKET-` | 200-219 | 20 | 阶段 2.3（market 服务接收 internal RPC 侧）|
| `TC-E2E-CONSOLE-` | 003 | 1 | 阶段 2.3 E2E（运营调 swap-rate 立即生效）|
| `FE-CONSOLE-` | 250-279 | 30 | 阶段 2.3 前端 |

合计 **81** 个用例。

---

## §2. 文档结构

| 节 | 范围 | TC 数 |
| --- | --- | --- |
| §3 console 列表 / 详情 | TC-CONSOLE-350 ~ 358 | 9 |
| §4 console 编辑配置 | TC-CONSOLE-359 ~ 365 | 7 |
| §5 console 暂停 / 恢复 | TC-CONSOLE-366 ~ 369 | 4 |
| §6 console swap-rate | TC-CONSOLE-370 ~ 376 | 7 |
| §7 console trading-hours | TC-CONSOLE-377 ~ 379 | 3 |
| §8 market internal RPC | TC-MARKET-200 ~ 219 | 20 |
| §9 E2E | TC-E2E-CONSOLE-003 | 1 |
| §10 前端 | FE-CONSOLE-250 ~ 279 | 30 |

---

## §3. console 列表 / 详情（TC-CONSOLE-350 ~ 358）

### §3.1 GET /admin/symbols 列表 — 5 TC

- **TC-CONSOLE-350**：默认参数返回 page=0/size=20，含必填字段（id String / symbol / category / marketCode / baseCurrency / quoteCurrency / pricePrecision / qtyPrecision / minQty / maxQty / minNotional / maxLeverage / takerFeeRate / spread / status / createdAt）
- **TC-CONSOLE-351**：`category=1` 仅返回 crypto；`category=2` 仅返回 forex
- **TC-CONSOLE-352**：`marketCode=CRYPTO` 筛选
- **TC-CONSOLE-353**：`status=SUSPENDED` 仅返回 status=2
- **TC-CONSOLE-354**：`symbolLike=BTC` 模糊匹配（LIKE %BTC%）

### §3.2 GET /admin/symbols/{id} 详情 — 4 TC

- **TC-CONSOLE-355**：返回 symbol 基础字段 + currentSwapRate 嵌套（最近一条生效 swap rate，或 null）+ tradingHours[]（按 day_of_week 聚合）
- **TC-CONSOLE-356**：currentSwapRate 是 effective_from ≤ 今天的最大日期记录（验 SQL ORDER BY effective_from DESC LIMIT 1）
- **TC-CONSOLE-357**：tradingHours[].sessions 按 open_time_utc ASC 排序
- **TC-CONSOLE-358**：不存在 id → 90600 ADMIN_SYMBOL_NOT_FOUND（404）

---

## §4. console 编辑配置（TC-CONSOLE-359 ~ 365）— 7 TC

- **TC-CONSOLE-359**：正常编辑（maxLeverage=50 / takerFeeRate=0.0008 / spread=2.0 + reason ≥10）→ 200，t_symbol UPDATE，t_admin_operation_log risk_level=HIGH_RISK 写入
- **TC-CONSOLE-360**：maxLeverage=0 → 90601 ADMIN_SYMBOL_INVALID_LEVERAGE
- **TC-CONSOLE-361**：maxLeverage=1000（超过上限 500）→ 90601
- **TC-CONSOLE-362**：takerFeeRate=0.10（超 5%）→ 90602 ADMIN_SYMBOL_INVALID_FEE_RATE
- **TC-CONSOLE-363**：spread=-1（负数）→ 90603 ADMIN_SYMBOL_INVALID_SPREAD
- **TC-CONSOLE-364**：minQty=10, maxQty=5（min > max）→ 90604 ADMIN_SYMBOL_INVALID_QTY_RANGE
- **TC-CONSOLE-365**：reason=8 字符 → 99004 INVALID_REQUEST_PAYLOAD（jakarta validation）

---

## §5. console 暂停 / 恢复（TC-CONSOLE-366 ~ 369）— 4 TC

- **TC-CONSOLE-366**：suspend TRADING symbol → 200，t_symbol.status=2，trading-core 校验生效（次单创建拒绝）
- **TC-CONSOLE-367**：suspend 已 SUSPENDED symbol → 90605 ADMIN_SYMBOL_ALREADY_SUSPENDED
- **TC-CONSOLE-368**：resume SUSPENDED symbol → 200，status=1
- **TC-CONSOLE-369**：resume TRADING symbol → 90606 ADMIN_SYMBOL_ALREADY_TRADING

---

## §6. console swap-rate（TC-CONSOLE-370 ~ 376）— 7 TC

### §6.1 GET swap-rate

- **TC-CONSOLE-370**：返回当前生效（effective_from ≤ 今天的最新一条）+ history 最近 5 条
- **TC-CONSOLE-371**：从未配置过 swap-rate 的 symbol → currentSwapRate=null + history=[]

### §6.2 PUT swap-rate（高风险）

- **TC-CONSOLE-372**：正常写入新 effective_from 一行（昨天 + 今天 + 明天三种情况）→ 仅 effective_from ≥ 今天才允许；过去 → 90611
- **TC-CONSOLE-373**：同 symbol + effective_from 已存在 → 90610 ADMIN_SYMBOL_SWAP_RATE_OVERLAP_DATE
- **TC-CONSOLE-374**：longRate=0.02（超 1% 上限）→ 90612 ADMIN_SYMBOL_SWAP_RATE_OUT_OF_RANGE
- **TC-CONSOLE-375**：longRate=-0.02 → 90612
- **TC-CONSOLE-376**：reason=5 字符 → 99004

---

## §7. console trading-hours（TC-CONSOLE-377 ~ 379）— 3 TC

- **TC-CONSOLE-377**：CRYPTO symbol 返回 dayOfWeek=0..6 全 7 天 + 每天 sessions=[{00:00, 24:00}]
- **TC-CONSOLE-378**：FX symbol 返回周一-周五 + 周末 sessions=[]
- **TC-CONSOLE-379**：含 trading_holiday 节假日的 symbol 返回 exceptions[]（如 2026 圣诞）

---

## §8. market internal RPC（TC-MARKET-200 ~ 219）— 20 TC

### §8.1 X-Internal-Token 鉴权（与 identity / trading 共用）— 3 TC

- **TC-MARKET-200**：缺失 X-Internal-Token → 401 + 90702 INTERNAL_TOKEN_MISSING
- **TC-MARKET-201**：错误 token → 401 + 90701 INTERNAL_TOKEN_INVALID
- **TC-MARKET-202**：缺失 X-Admin-User-Id → 400 + 90703 INTERNAL_ADMIN_USER_ID_MISSING

### §8.2 list / detail RPC — 5 TC

- **TC-MARKET-203**：GET /internal/v1/market/symbols 分页 + 筛选返回完整字段
- **TC-MARKET-204**：GET /internal/v1/market/symbols/{id} 详情含 currentSwapRate 嵌套
- **TC-MARKET-205**：详情 trading-hours JOIN（按 market_code）
- **TC-MARKET-206**：不存在 id → `90600` business code（direct market internal RPC 当前由 `MarketGlobalExceptionHandler` 返回 HTTP 200；console 对外入口翻译为 HTTP 404 + `90600`）
- **TC-MARKET-207**：list 默认按 created_at DESC

### §8.3 update RPC — 5 TC

- **TC-MARKET-208**：PUT 编辑成功，仅修改请求体内字段，其余保持
- **TC-MARKET-209**：maxLeverage 不在 [1,500] → 90601
- **TC-MARKET-210**：takerFeeRate 不在 [0,0.05] → 90602
- **TC-MARKET-211**：尝试改 symbol / category / marketCode 字段 → 后端忽略（不报错，只 UPDATE 允许字段）
- **TC-MARKET-212**：UPDATE 后 t_symbol.updated_at 自动刷新

### §8.4 status RPC — 3 TC

- **TC-MARKET-213**：suspend 后 status=2
- **TC-MARKET-214**：resume 后 status=1
- **TC-MARKET-215**：状态无效（如传 BANNED）→ 99004

### §8.5 swap-rate RPC — 4 TC

- **TC-MARKET-216**：GET 当前生效（含 history 5 条）
- **TC-MARKET-217**：PUT 写新行（symbol + effective_from UNIQUE 索引校验）
- **TC-MARKET-218**：PUT 重复 (symbol, effective_from) → DataIntegrityViolation 翻译为 90610
- **TC-MARKET-219**：effective_from 在过去 → 90611（service 层校验，不依赖 DB 约束）

---

## §9. E2E（TC-E2E-CONSOLE-003）— 1 TC

- **TC-E2E-CONSOLE-003**：运营调 swap-rate（PUT effective_from=明天）→ 立即写入 t_swap_rate →
  执行 trading-core 模拟 rollover（当日 22:00 UTC）→ 生效新 longRate 影响隔夜利息计算 →
  验证账户 t_ledger biz_type=swap_settlement 金额计算用了新 rate（端到端 ~3 秒，跳过等待用 SQL 直接验证）

---

## §10. 前端（FE-CONSOLE-250 ~ 279）— 30 TC

### §10.1 列表页 P6（FE-CONSOLE-250 ~ 261）— 12 TC

- **FE-CONSOLE-250**：默认 20/page，分页 + 翻页
- **FE-CONSOLE-251**：4 个筛选（category Select / marketCode Input / status Select / symbolLike Input）
- **FE-CONSOLE-252**：列定义 7 列（symbol / category / marketCode / status Tag / leverage / fee / spread / 操作）
- **FE-CONSOLE-253**：status TRADING 绿 Tag / SUSPENDED 红 Tag
- **FE-CONSOLE-254**：行点击进详情或在列表 Drawer 显示
- **FE-CONSOLE-255**：操作列 [编辑] [暂停/恢复] [Swap Rate] 按权限码 RequiresPermission 渲染
- **FE-CONSOLE-256~261**：响应式 / loading / 错误 / 空集（行为 with customer 列表对齐）

### §10.2 编辑 Drawer（FE-CONSOLE-262 ~ 268）— 7 TC

- **FE-CONSOLE-262**：Drawer 宽 520，预填 symbol 当前配置
- **FE-CONSOLE-263**：仅可改 maxLeverage / takerFeeRate / spread / minQty / maxQty / minNotional + reason
- **FE-CONSOLE-264**：reason < 10 字符按钮 disabled
- **FE-CONSOLE-265**：jakarta validation 触发表单内字段红框
- **FE-CONSOLE-266**：90601-90604 业务错误以 toast 显示
- **FE-CONSOLE-267**：保存成功后表格行更新（refetch）
- **FE-CONSOLE-268**：高风险 toast：「保存生效后影响所有此 symbol 新订单的杠杆 / 费率」

### §10.3 暂停 / 恢复 Popconfirm（FE-CONSOLE-269 ~ 271）— 3 TC

- **FE-CONSOLE-269**：Popconfirm 含 reason 输入（≥10 字符）+ 立即生效说明
- **FE-CONSOLE-270**：暂停后状态 Tag 变 SUSPENDED，按钮变「恢复」
- **FE-CONSOLE-271**：90605/90606 toast 提示

### §10.4 Swap Rate Drawer（FE-CONSOLE-272 ~ 277）— 6 TC

- **FE-CONSOLE-272**：Drawer 显示 currentSwapRate（large 显示）+ history 表（5 行）
- **FE-CONSOLE-273**：「新增费率」表单 longRate / shortRate / rolloverTime / effectiveFrom（日期不允许选过去）
- **FE-CONSOLE-274**：90610/90611/90612 业务错误 toast
- **FE-CONSOLE-275**：保存后 history 表刷新，新行高亮
- **FE-CONSOLE-276**：长 / 短率 ±1% 上限前端 InputNumber min/max 提示
- **FE-CONSOLE-277**：高风险 reason TextArea ≥10 字符

### §10.5 Trading Hours 弹窗（FE-CONSOLE-278 ~ 279）— 2 TC

- **FE-CONSOLE-278**：周一到周日表格展示 sessions（24h CRYPTO 一行，FX 5 行 + 周末空）
- **FE-CONSOLE-279**：exceptions 节假日列表展示

---

## §11. 落地映射表（TC → @Test）

| TC 区间 | 实施类（建议）| 状态 |
| --- | --- | --- |
| TC-CONSOLE-350 ~ 379 | _`AdminSymbolControllerIntegrationTests`_ | ✅ R7 live API 核心路径通过；完整 `@Test` 待补 |
| TC-MARKET-200 ~ 219 | _`MarketSymbolAdminInternalControllerIntegrationTests`_ | ✅ R7 live API 核心路径通过；完整 `@Test` 待补 |
| TC-E2E-CONSOLE-003 | _`AdminSymbolE2ETests`_ | ✅ Redis 快照即时刷新 + `TradingSwapSettlementIntegrationTests` 通过；完整 admin E2E `@Test` 待补 |
| FE-CONSOLE-250 ~ 279 | _`SymbolListPage.test.tsx` + `SymbolEditDrawer.test.tsx` 等_ | ✅ 浏览器 QA 7 截图通过；组件测试待补 |

R6 三轮交付仅写骨架；R7 已通过 live API / 浏览器 QA 收口。后续若要求 81 TC 全量进入 CI，需要单独排自动化测试债务任务。

---

## §12. 三表补充管理（STAGE-2-SYMBOL-THREE-TABLE-ADMIN）

补充用例清单见 [`STAGE-2-SYMBOL-THREE-TABLE-ADMIN-test-cases`](./STAGE-2-SYMBOL-THREE-TABLE-ADMIN-test-cases.md)。

当前已落地自动化覆盖：

- `MarketSymbolAdminApplicationServiceMappingTests`：
  - 允许 `XAUUSD.p` 这类 platform symbol 写入 mapping，不按后缀做特殊拒绝。
  - `source_symbol` 不存在返回 `90615`。
  - group visibility upsert 前校验 `platform_symbol` 已存在于 mapping。
  - mapping 修改后刷新报价映射快照和 provider 订阅白名单。
- 管理端页面补充组可见性批量设置入口；后续浏览器 QA 需覆盖批量最多 500 个 platform symbol 的校验与成功反馈。
- `HighRiskPermissionRegistryTests`：
  - `symbol:quote-mapping:update` / `symbol:group-visibility:update` 均为 `HIGH_RISK`。

待补 live / 浏览器证据：

- 管理端 quote mapping 新建、编辑、停用/启用操作截图。
- 管理端 group visibility 新建/更新操作截图。
- C 端 `/api/v1/market/symbols` 与 WebSocket 订阅在 group/mapping 变更后的回归截图或日志。
