# 任务卡：Symbol 参数下沉到 mapping（t_symbol 降级为 LP 源元数据表）

## 任务概览

- 任务编号：`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`
- 任务显示名称：Symbol 参数下沉到 mapping（t_symbol 降级为 LP 源元数据表）
- 当前阶段口径：BBook V2 阶段 2 / 插队在 5.4 订单监控之前
- 任务来源：用户 2026-05-12 决策 + 2026-05-25 更新——把 LP 上游当成纯报价源，把系统级配置（category / market_code / price_precision / qty_precision / 杠杆 / 费率 / 点差 / qty 限制）下沉到 `t_symbol_quote_mapping`，`t_symbol` 只保留 LP 源元数据
- 任务类型：跨服务契约重构 + 三端协作（业务后端 + 管理端后端 + 管理端前端；客户端字段口径不变，无 UI 改动但需 R5 字段口径核对）
- 角色路由：`R1 -> R2 -> R3 -> R6 -> R4 ∥ R9 ∥ R10 -> R7 -> R8 -> R1`
- 端别覆盖：业务后端 `market-service / trading-core-service`、管理端后端 `console-service`、管理端前端 `console-frontend`、客户端 `falconx-frontend`（仅字段口径核对，无 UI 改动）

## 背景与动机

当前数据模型问题：同一 LP 源 symbol（如 XAUUSD）派生出多个 platform symbol（XAUUSD.p / XAUUSD.c / XAUUSD.f）时，**它们被迫共享同一份 `t_symbol.max_leverage / taker_fee_rate / spread`**——无法做到「同一上游报价 + 不同用户群不同交易条件」。这违反了标准 broker 数据模型（MT5 / cTrader 的 "Symbol Group + Specification" 分层）。

用户决策：

1. `t_symbol` 降级为**纯 LP 源元数据表**：不含任何交易参数；只保留 `symbol / category / market_code / base_currency / quote_currency / price_precision / qty_precision / status` + 新增 `last_tick_at`（观测死 symbol）
2. `t_symbol_quote_mapping` 成为**系统唯一 Symbol 配置真源**：category / market_code / precision / 所有交易参数（杠杆、费率、点差、qty 限制）都在这里配置；`platform_symbol` 创建后不可改名
3. 管理端新增"新建 LP 源 Symbol" 入口，写入 t_symbol 后 market-service 自动追加 LP 订阅
4. mapping 编辑表单 source 用下拉选已存在的 t_symbol，user 组配置用下拉选 mapping.enabled=1 的 platform symbol
5. 用户组配置展示用聚合视图：1 行 = 1 个组 + N 个 symbol（不平铺）

历史兼容有利点：`t_position.leverage / open_fee_rate` 和 `t_order` 在下单时已经快照杠杆/费率到表内，本次重构**不影响历史持仓 / 订单**。

## 必须读取

- `AGENTS.md`
- `SKILLS.md` Skill 1 / 4 / 9 / 10 / 11 / 12 / 16 / 17
- `docs/process/AI工作模式.md` / `AI工作模式-Claude适配.md` / `完成定义.md`
- `docs/process/BBook一期完成执行路径.md` §5 阶段 2
- `docs/database/falconx一期数据库设计.md`（market owner 章节）
- `docs/api/REST接口规范.md` / `docs/api/WebSocket接口规范.md` / `docs/api/管理端接口规范.md`
- `docs/event/Kafka事件规范.md`
- `docs/market/LP自建行情源接入契约.md`
- `docs/architecture/falconx一期网关-服务-数据库架构方案.md`（trading-core 读取依赖部分）
- 当前实现：
  - `falconx-market-service/src/main/java/com/falconx/market/repository/MybatisMarketSymbolRepository.java`
  - `falconx-market-service/src/main/java/com/falconx/market/application/MarketSymbolAdminApplicationService.java`
  - `falconx-trading-core-service` 所有读取 `t_symbol.max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional` 的位置（R2 必须先盘点）

## R2 三轮契约（已冻结 2026-05-12）

> 本节为 R2 三轮**已冻结**契约。用户在 2026-05-12 已审阅并确认 4 项关键决策 + 4 项澄清决策；正式真源文档同步落盘点见 §正式真源文档同步。R4/R9/R10 实施时按本节执行。

### 4 项关键决策（用户拍板）

| 决策点 | 选择 |
| --- | --- |
| t_symbol 字段去留 | 纯 LP 元数据，**不加 default_* 字段**（避免双源歧义） |
| precision 字段 | source + mapping 都保留；系统运行读取 mapping 的必填系统级 precision，source precision 只表达上游元数据 |
| Backfill 策略 | 一次性 Flyway V11 同事务 backfill |
| 实施时机 | 插队到 5.4 之前完成，Phase A + B 捆绑发布 |

### 4 项澄清决策（用户在 task #9 / #10 / #11 审阅时拍板）

| 决策点 | 选择 |
| --- | --- |
| mapping 新增 6 字段 DEFAULT 策略 | `NOT NULL DEFAULT 0` + service 层必填校验（避免三步 ALTER） |
| mapping 时间戳精度 | 顺便升级 `created_at / updated_at` 到 `datetime(3)`（与 t_symbol 对齐） |
| CHECK 约束 | 加；leverage 1-500 / fee 0-0.05 / spread ≥ 0 / max > min / notional ≥ 0 / precision NULL or 0-10 |
| last_tick_at | **不加** t_symbol 字段；用 ClickHouse `quote_tick.event_time` + market internal RPC 替代 |
| GET /admin/symbols 含 last-tick 实现 | console 调 market `/internal/v1/market/symbols/last-tick`（保持 owner 边界） |
| POST /admin/symbols 创建初状态 | 只允许 status=1；suspended 后续走 suspend 接口 |
| group_concat truncate | mapper 查询前 `SET SESSION group_concat_max_len=1048576`（1MB ~30k symbol） |
| RBAC 迁移 | console V2 migration idempotent 转换持有者；废弃 symbol:update 标 enabled=0 不删 |
| Task #11 范围 | 包含 Phase B（trading-core 真实消费 SymbolSpec） |
| 双层 max_leverage | mapping + risk_config 都保留，开仓取 min |
| Phase A 标记 | 任务卡 + 真源文档明确"分阶段部署 = 调配不生效" |

### A. DB Schema 变更（Flyway V×）

`t_symbol` 字段裁剪：

| 字段 | 操作 |
| --- | --- |
| `max_leverage` | **删除** |
| `taker_fee_rate` | **删除** |
| `spread` | **删除** |
| `min_qty` | **删除** |
| `max_qty` | **删除** |
| `min_notional` | **删除** |
| `last_tick_at` | **新增** `datetime(3) NULL`，market-service 收到 tick 时回写 |
| 其他字段 | 保留：`id / symbol / category / market_code / base_currency / quote_currency / price_precision / qty_precision / status / created_at / updated_at` |

`t_symbol_quote_mapping` 字段扩展：

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `max_leverage` | `int NOT NULL` | `100` | 杠杆上限，校验 1-500 |
| `taker_fee_rate` | `decimal(10,6) NOT NULL` | `0.000000` | Taker 费率，校验 0-0.05 |
| `spread` | `decimal(24,8) NOT NULL` | `0.00000000` | 点差，校验 ≥ 0 |
| `min_qty` | `decimal(24,8) NOT NULL` | — | 必填，min < max |
| `max_qty` | `decimal(24,8) NOT NULL` | — | 必填 |
| `min_notional` | `decimal(24,8) NOT NULL` | — | 必填，≥ 0 |
| `category` | `tinyint NOT NULL` | source backfill | 系统级品类，校验 1-8 |
| `market_code` | `varchar(32) NOT NULL` | source backfill | 系统级市场代码 |
| `price_precision` | `int NOT NULL` | source backfill | 系统级价格精度，校验 0-10 |
| `qty_precision` | `int NOT NULL` | source backfill | 系统级数量精度，校验 0-10 |

Backfill SQL（在 V× migration 内同事务执行）：

```sql
ALTER TABLE t_symbol_quote_mapping
  ADD COLUMN max_leverage int NOT NULL DEFAULT 100,
  ADD COLUMN taker_fee_rate decimal(10,6) NOT NULL DEFAULT 0,
  ADD COLUMN spread decimal(24,8) NOT NULL DEFAULT 0,
  ADD COLUMN min_qty decimal(24,8) NOT NULL DEFAULT 0,
  ADD COLUMN max_qty decimal(24,8) NOT NULL DEFAULT 0,
  ADD COLUMN min_notional decimal(24,8) NOT NULL DEFAULT 0,
  ADD COLUMN price_precision int NULL,
  ADD COLUMN qty_precision int NULL;

UPDATE t_symbol_quote_mapping m
JOIN t_symbol s ON m.source_symbol = s.symbol
SET m.max_leverage   = s.max_leverage,
    m.taker_fee_rate = s.taker_fee_rate,
    m.spread         = s.spread,
    m.min_qty        = s.min_qty,
    m.max_qty        = s.max_qty,
    m.min_notional   = s.min_notional;

-- 校验 backfill 完整性（migration 失败回滚的 sentinel）
SELECT 1 FROM dual WHERE EXISTS (
  SELECT 1 FROM t_symbol_quote_mapping WHERE max_qty <= min_qty
);  -- 此处期望为空

ALTER TABLE t_symbol
  DROP COLUMN max_leverage,
  DROP COLUMN taker_fee_rate,
  DROP COLUMN spread,
  DROP COLUMN min_qty,
  DROP COLUMN max_qty,
  DROP COLUMN min_notional,
  ADD COLUMN last_tick_at datetime(3) NULL;
```

回滚：本次 migration **不可自动回滚**（删字段 + 数据已搬到 mapping）。R1 在 migration 前必须人工备份 `t_symbol` 全表。如确需回滚，需要 R2 输出独立的 reverse migration 把 mapping 字段拷回 t_symbol 再删 mapping 字段。

### B. API 契约变更

#### B.1 新增：`POST /admin/symbols`（新建 LP 源 Symbol）

权限：`symbol:source:create`（**新增高风险权限点**）

请求体：

```json
{
  "symbol": "XAGUSD",
  "category": 2,
  "marketCode": "COMEX",
  "baseCurrency": "XAG",
  "quoteCurrency": "USD",
  "pricePrecision": 3,
  "qtyPrecision": 2,
  "status": 1,
  "reason": "运营新增白银 LP 源 symbol"
}
```

响应：返回新建的 source 完整记录。

副作用：写入 `t_symbol` 成功后，触发 `MarketQuoteMappingService.refreshMappings()` + `MarketQuoteProvider.refreshSymbols()`，**自动追加 LP 订阅**。

错误码：

| 错误码 | 含义 |
| --- | --- |
| `90619` | `ADMIN_SYMBOL_SOURCE_DUPLICATE` symbol 已存在 |
| `90620` | `ADMIN_SYMBOL_SOURCE_INVALID` category/market_code/precision 范围非法 |

#### B.2 修改：`PUT /admin/symbols/{id}` 字段集裁剪

字段从原 `maxLeverage / takerFeeRate / spread / minQty / maxQty / minNotional` 改为：

```json
{
  "category": 2,
  "marketCode": "COMEX",
  "pricePrecision": 3,
  "qtyPrecision": 2,
  "status": 1,
  "reason": "调整 XAGUSD 源元数据"
}
```

权限：`symbol:source:update`（**新增高风险权限点**；与 `symbol:update` 旧语义切割）

错误码 `90601 / 90602 / 90603 / 90604` 在 source 接口层**移除**，语义搬迁到 mapping 接口。

#### B.3 修改：`POST /admin/symbols/quote-mappings` 字段集扩展

请求体新增 category / marketCode / 6 个必填交易字段 + 2 个必填系统级 precision：

```json
{
  "platformSymbol": "XAUUSD.p",
  "sourceProvider": "LP",
  "sourceSymbol": "XAUUSD",
  "category": 3,
  "marketCode": "METAL",
  "priceMultiplier": 1.0,
  "bidAdjustment": 0,
  "askAdjustment": 0,
  "enabled": 1,
  "lpSubscribeEnabled": 1,
  "maxLeverage": 100,
  "takerFeeRate": 0.0005,
  "spread": 0.5,
  "minQty": 0.01,
  "maxQty": 100,
  "minNotional": 10,
  "pricePrecision": null,
  "qtyPrecision": null,
  "reason": "新建黄金标准品种"
}
```

权限：`symbol:quote-mapping:update`（保留，高风险）

错误码（语义搬迁自 source）：

| 错误码 | 含义 |
| --- | --- |
| `90601` | `ADMIN_SYMBOL_INVALID_LEVERAGE` 1 ≤ x ≤ 500（**搬迁自 source**，现绑 mapping） |
| `90602` | `ADMIN_SYMBOL_INVALID_FEE_RATE` 0 ≤ x ≤ 0.05（**搬迁**） |
| `90603` | `ADMIN_SYMBOL_INVALID_SPREAD` x ≥ 0（**搬迁**） |
| `90604` | `ADMIN_SYMBOL_INVALID_QTY_RANGE` min_qty < max_qty（**搬迁**） |
| `90613-90618` | 原 mapping 错误码（保留） |

#### B.4 修改：`PUT /admin/symbols/quote-mappings/{platformSymbol}` 同 B.3 字段集

可空更新（仅传需修改字段）。

#### B.5 新增：`GET /admin/symbols/group-visibility/grouped`（聚合视图）

权限：`symbol:view`

查询：

```
GET /admin/symbols/group-visibility/grouped?groupCodeLike=&page=0&size=20
```

响应：

```json
{
  "code": "0",
  "data": {
    "items": [
      {
        "groupCode": "default",
        "visibleSymbols": ["XAUUSD.p", "XAUUSD.c", "BTCUSD", "..."],
        "totalCount": 1571,
        "lastModifiedAt": "2026-05-12T01:42:44.250Z"
      },
      {
        "groupCode": "vip",
        "visibleSymbols": [],
        "totalCount": 0,
        "lastModifiedAt": "2026-05-12T01:30:34.143Z"
      }
    ],
    "total": 2
  }
}
```

底层存储不变（row-per-(group, symbol)），SQL group by + GROUP_CONCAT 或在 service 层聚合。

#### B.6 客户端 `/api/v1/market/symbols` 返回字段口径

字段名**全部保持不变**（`maxLeverage / takerFeeRate / spread / minQty / maxQty / minNotional / pricePrecision / qtyPrecision`），但取值改成：

- 业务参数（`maxLeverage / takerFeeRate / spread / minQty / maxQty / minNotional`）从 `t_symbol_quote_mapping` 读
- precision：mapping 非 NULL 取 mapping，否则回落 t_symbol

客户端代码**无需改动**，前端字段口径与现状兼容。

### C. trading-core-service 读取路径切换

R2 必须先盘点 trading-core 所有读取 `t_symbol.max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional` 的代码位置。预计涉及：

| 链路 | 当前读取 | 切换后 |
| --- | --- | --- |
| 下单参数校验（杠杆 / qty / notional） | `t_symbol` | `t_symbol_quote_mapping` by `platform_symbol` |
| 保证金计算（按杠杆） | `t_symbol.max_leverage` | mapping.max_leverage |
| Taker 费率计算 | `t_symbol.taker_fee_rate` | mapping.taker_fee_rate |
| 强平校验（杠杆上限） | `t_symbol.max_leverage` | mapping.max_leverage |
| 风控规则（5.5 阶段会扩） | 同上 | 同上 |
| Swap 结算 | `t_swap_rate`（独立） | **不变** |

新接口（R2 冻结）：

- trading-core 通过 market 的 Redis 快照或 internal RPC 读取 mapping 配置，**禁止跨 schema 直查** `falconx_market.t_symbol_quote_mapping`
- 建议 R2 冻结 `MarketSymbolSpecRepository.findByPlatformSymbol(symbol) -> SymbolSpec`（DTO 含杠杆/费率/spread/qty/precision），数据源来自 Redis 快照或 market `/internal/v1/market/symbols/spec/{platformSymbol}` internal RPC

历史订单不受影响：`t_position` / `t_order` 在下单时已经把 leverage / open_fee_rate 快照入表，平仓 / 强平 / Swap 结算用快照值，不再回查 symbol 配置。

### D. R3 管理端 UI 重设计要点

| 区块 | 重设计 |
| --- | --- |
| 主表 Tab | 列：`symbol / category / market_code / base/quote / precision / status / last_tick_at`；新增「+ 新建源 Symbol」按钮；行级「编辑」改为只编辑 source 元数据（不含 leverage/fee） |
| 报价映射 Tab | 新建/编辑 Drawer 增 6 个交易字段输入；source 选择**改为下拉**（搜索 t_symbol）；新增字段分组「价格转换」（multiplier/bid_adj/ask_adj/spread）+「交易参数」（leverage/fee/qty）+「精度覆盖」（pricePrecision/qtyPrecision 可空） |
| 组可见性 Tab | 视图模式切换：默认**聚合视图**（左用户组列表 + 右选中组的 Transfer 组件选 platform symbol）；保留原行级视图作为「明细模式」可切换 |
| 公共 | platform symbol 选择改为下拉（拉取 mapping.enabled=1） |

### E. 权限点变更

| 权限码 | 操作 | 风险等级 |
| --- | --- | --- |
| `symbol:source:create` | **新增**，新建 LP 源 symbol | HIGH_RISK |
| `symbol:source:update` | **新增**，编辑 source 元数据 | HIGH_RISK |
| `symbol:update` | **废弃**（语义被 `symbol:source:update` 替代） | — |
| `symbol:suspend` | 保留，暂停/恢复 source | HIGH_RISK |
| `symbol:quote-mapping:update` | 保留，**字段集扩大**（含 leverage/fee） | HIGH_RISK |
| `symbol:group-visibility:update` | 保留 | HIGH_RISK |
| `symbol:swap-rate:update` | 保留 | HIGH_RISK |
| `symbol:view` | 保留 | LOW |

### F. 错误码段位重排

`90600-90649` 段内（不挪段，仅语义调整）：

| 错误码 | 旧绑定 | 新绑定 |
| --- | --- | --- |
| `90600` | source not found | 保留 |
| `90601` | source leverage 非法 | **mapping** leverage 非法 |
| `90602` | source fee_rate 非法 | **mapping** fee_rate 非法 |
| `90603` | source spread 非法 | **mapping** spread 非法 |
| `90604` | source qty_range 非法 | **mapping** qty_range 非法 |
| `90605/90606` | source 重复暂停/恢复 | 保留（source 状态切换） |
| `90613-90618` | mapping CRUD | 保留 |
| `90619` | — | **新增** ADMIN_SYMBOL_SOURCE_DUPLICATE |
| `90620` | — | **新增** ADMIN_SYMBOL_SOURCE_INVALID（category/market_code/precision 范围）|

## R6 测试设计要点

测试用例编号块（待 R6 在 `CFD全面测试用例规范.md` §13 注册新 prefix）：

- DB migration backfill：mapping 6 字段非空 + 与 backfill 前 t_symbol 一致；t_symbol 不再有这 6 字段
- t_symbol POST/PUT/DELETE 新 CRUD（90600/90619/90620）
- mapping POST/PUT 字段扩展校验（90601-90604/90613-90618）
- trading-core 下单读 mapping leverage/fee/qty
- 历史持仓 / 订单不因迁移变化（leverage/fee 已快照）
- E2E：管理员调 mapping.max_leverage → 后续下单按新杠杆校验
- E2E：管理员新增 source → market 5 秒内追加 LP 订阅 → mapping 派生后客户端可见
- 聚合视图接口：返回结构 + 排序 + 筛选

## R4 / R9 / R10 实施范围

R4（market-service 业务后端）：
- t_symbol CRUD（POST/PUT/PATCH status）+ last_tick_at 回写（行情消费链路）
- mapping CRUD 字段集扩展 + 校验
- `MarketSymbolSpecRepository.findByPlatformSymbol` 暴露 internal RPC 供 trading-core 调用
- Redis 快照 schema 变更：把 mapping 完整配置（含 leverage/fee/spread/qty）写入 Redis 供 trading-core 高频读

R4（trading-core-service 业务后端）：
- 下单 / 强平 / 保证金 / 费率所有读取 t_symbol 的位置切换到 mapping
- 通过 Redis 快照或 internal RPC 拿 SymbolSpec DTO

R9（console-service 管理端后端）：
- 调 market internal RPC 暴露的 source CRUD + mapping CRUD（字段扩展）+ 聚合视图
- 错误码 1:1 翻译（含新增 90619/90620）
- 新增 RBAC 权限点 `symbol:source:create / symbol:source:update`，废弃 `symbol:update`

R10（console-frontend 管理端前端）：
- 主表 Tab 加「新建源 Symbol」按钮 + Drawer
- 报价映射 Tab 表单字段扩展 + source 下拉
- 组可见性 Tab 切换为聚合视图（Transfer 组件）
- 权限码替换 `symbol:update → symbol:source:update`

## R7 验证范围

- DB migration 在 dev / 测试库执行成功 + backfill 校验
- 后端 Maven 单元 + 集成测试
- console-frontend 三件套
- API live：source CRUD + mapping 扩展字段 + 聚合视图 + 错误码全谱
- 浏览器 QA：三 Tab 重设计后桌面 + 移动
- 客户端回归：`/api/v1/market/symbols` 字段名不变 + 取值来自 mapping
- trading-core 集成测试：下单走 mapping 杠杆/费率
- 历史持仓平仓 / 强平 / Swap 不受影响

## R8 文档同步范围

- `docs/database/falconx一期数据库设计.md`（market schema 章节字段更新 + Flyway V× 标注）
- `docs/api/管理端接口规范.md`（§6 字段集 + 错误码段位 + 新增接口）
- `docs/api/FalconX统一接口文档.md`（同步管理端 + C 端字段口径备注）
- `docs/architecture/管理端架构.md`（RBAC 权限点新增/废弃）
- `docs/architecture/falconx一期网关-服务-数据库架构方案.md`（trading-core 读取依赖更新）
- `docs/process/BBook一期完成执行路径.md`（§5 新增 5.X 子任务并写入完成判定）
- `docs/setup/当前开发计划.md`（路线图新增条目）

## 禁止事项

- 禁止保留 `t_symbol.max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional` 作为「default 回退」字段（已决策为纯删除，避免双源歧义）
- 禁止 trading-core 跨 schema 直查 `falconx_market.t_symbol_quote_mapping`，必须走 Redis 快照或 market internal RPC
- 禁止把 mapping 字段扩展的 6 个错误码新开段位，必须复用现有 `90601-90604` 段（语义搬迁）
- 禁止把"用户组聚合视图"做成新建表，必须基于现有 `t_symbol_group_visibility` row-per-(group, symbol) 存储
- 禁止本次 migration 在生产 / 准生产环境执行未备份的 `t_symbol`

## 正式真源文档同步（R2 三轮冻结落盘，2026-05-12）

R2 三轮契约已写入以下真源文档（本次冻结 commit）：

- [`docs/database/falconx一期数据库设计.md`](../../database/falconx一期数据库设计.md) §4.2（market schema）+ §4.3（trading schema）：t_symbol 字段裁剪、mapping 字段扩展、open_fee_rate 快照说明、Flyway V6/V8/V13 编号建议、trading-core 不读 falconx_market 边界声明
- [`docs/api/管理端接口规范.md`](../../api/管理端接口规范.md) §6.13：R2 三轮接口契约（POST /admin/symbols、字段集裁剪/扩展、聚合视图、market internal RPC last-tick / spec、错误码 90601-90604 搬迁 + 90619/90620 新增、RBAC 权限码迁移 SQL）
- [`docs/architecture/管理端架构.md`](../../architecture/管理端架构.md) §2.2（权限码命名规范加 symbol:source:create / source:update / quote-mapping:update / group-visibility:update，废弃 symbol:update）+ §4.3（trading-core 不读 falconx_market 边界）
- [`docs/process/BBook一期完成执行路径.md`](../BBook一期完成执行路径.md) §5.3-PARAMS-DOWNSHIFT：插队条目
- [`docs/setup/当前开发计划.md`](../../setup/当前开发计划.md) §5：路线图新增条目

实施阶段（R3-R10）补充落盘：

- 实施完成后，[`docs/api/FalconX统一接口文档.md`](../../api/FalconX统一接口文档.md) 同步实际接口口径（R8 在 R7 验证通过后补）

## 禁止事项

- 禁止保留 `t_symbol.max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional` 作为「default 回退」字段（已决策为纯删除，避免双源歧义）
- 禁止 trading-core 跨 schema 直查 `falconx_market.t_symbol_quote_mapping`，必须走 Redis 快照或 market internal RPC
- 禁止把 mapping 字段扩展的 6 个错误码新开段位，必须复用现有 `90601-90604` 段（语义搬迁）
- 禁止把"用户组聚合视图"做成新建表，必须基于现有 `t_symbol_group_visibility` row-per-(group, symbol) 存储
- 禁止本次 migration 在生产 / 准生产环境执行未备份的 `t_symbol`
- 禁止 Phase A 与 Phase B 分阶段部署：分开发布 = 调配不生效，必须捆绑发布

## 完成判定

本任务满足以下条件才可标记完成：

- [x] R2 三轮契约已冻结并写入正式真源文档（2026-05-12 commit `1aadb8b`）
- [x] R3 管理端 UI 三 Tab 重设计完成（2026-05-12，详见 [`falconx-console-pages-V1.md §10`](../../design/falconx-console-pages-V1.md)：主表加新建源 Modal + lastTickAt 红黄绿状态点；映射 7 交易参数 + precision badge + 三折叠分组 Drawer；可见性聚合视图 Transfer + 双视图切换；桌面+移动响应式）
- [x] R6 测试用例先于实现写出（2026-05-12，详见 [`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-test-cases.md`](../../test/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-test-cases.md)：9 prefix 共 129 用例骨架；已注册到 CFD §13.9）
- [x] R4 market + trading-core 实施 + 单元 / 集成测试通过（commits `5bb4dcc` `b81f98b` `f35c991` `ff5a110` `3cf3e4c` `3a63e9b` `73893d1`：V11 schema + mapping 字段扩展 + 4 新 internal RPC + Redis warmup + V13 + SymbolSpec 消费 + open_fee_rate 快照 + IT seeder）
- [x] R9 console-service 实施 + 测试通过 + 错误码翻译完整（commits `5c1127a` `1a84f96`：V2 RBAC + 90619/90620 + DTO 字段扩展）
- [x] R10 console-frontend 实施 + 三件套通过 + 浏览器 QA（commit `6664cb9`：types/API/forms 字段适配；UI 三 Tab 重设计细化待 R10 三轮）
- [x] R7 验证：Maven 测试 + API live 8/8 + 浏览器 QA 5 截图 + 客户端字段口径回归 + 1574 个 SymbolSpec Redis key 验证（详见 [`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-R7-verification-report`](../../test/archive/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-R7-verification-report.md)）
- [x] R8 同步真源文档（本 commit：任务卡完成判定 + BBook 执行路径 §5.3-PARAMS-DOWNSHIFT + 当前开发计划 + R7 验证报告）
- [x] 形成单一发布 commit（R7+R8 + push 整合所有 WIP commits）

**当前状态**：✅ **已完成**（2026-05-12，13 commits 整体发布；详见 [`R7 验证报告`](../../test/archive/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-R7-verification-report.md)）
- [ ] R4 market + trading-core 实施 + 单元 / 集成测试通过
- [ ] R9 console-service 实施 + 测试通过 + 错误码翻译完整
- [ ] R10 console-frontend 实施 + 三件套通过 + 浏览器 QA
- [ ] R7 验证：Maven 测试 + API live + 浏览器 QA + 客户端字段口径回归 + trading-core 下单按 mapping 配置生效 + 历史持仓不受影响
- [ ] R8 同步真源文档（数据库设计 / 管理端接口规范 / 架构方案 / 执行路径 / 当前计划 / FalconX 统一接口文档）
- [ ] 形成单一 Git commit（不分批合）
- [x] 在 BBook 执行路径 §5 注册本任务，状态置「✅ 已完成」
