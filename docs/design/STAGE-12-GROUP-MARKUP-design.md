# STAGE-12-GROUP-MARKUP 设计稿（R2 契约 + R3 设计）

> **任务编号**：`STAGE-12-GROUP-MARKUP`
> **状态**：R2/R3 设计稿（待用户审定）
> **建档日期**：2026-05-21
> **范围**：用户组级别的双向加点（bid_extra / ask_extra），全链路按组（推送 + REST + 撮合 + PnL + 强平 + 挂单触发）一次到位
> **owner**：market-service（配置真源） + trading-core-service（撮合 / 持仓 / PnL / 强平 / 挂单消费方）+ console（管理端 CRUD）+ falconx-frontend（客户端无感）+ console-frontend（管理端配置 UI）

---

## 1. 目标与边界

### 1.1 目标

让每个用户组（`t_user.group_code`）在已有「平台对 LP 统一加点」（`t_symbol_quote_mapping`）的基础上，附加一层「组级 bid/ask 双向加点」：

- **双向**：`bid_extra ∈ ℝ`，`ask_extra ∈ ℝ`（可正可负，给 VIP 让利或扩点差均可，用 CHECK 约束兜底）
- **全链路按组**：
  - **客户端 REST**：返回带组加点的 bid/ask/mid
  - **WebSocket 推送**：按 session 的 groupCode 应用加点后推送
  - **撮合开仓 fillPrice**：按用户当前 groupCode 应用加点
  - **平仓 exitPrice**：按 position 开仓时冻结的 groupCode 应用加点
  - **未实现 PnL**：按 position 冻结的 groupCode 算 effectiveMark
  - **强平触发**：按 position 冻结的 groupCode 比对 liquidation_price
  - **挂单（LIMIT/STOP/STOP_LIMIT/SL/TP）触发**：按订单冻结的 groupCode 应用加点后比对触发条件

### 1.2 严格不在范围内（写明免歧义）

- **K 线**：继续用 mapping 后的「平台基准 bid」推 OHLC，**不应用组加点**（避免按组分裂 ClickHouse `kline` 表）
- **ClickHouse `quote_tick`**：继续存平台基准价
- **Kafka `falconx.market.price.tick`**：继续发平台基准价（不按组分流 topic）
- **Kafka `falconx.market.kline.update`**：继续基准价
- **管理端报价快照 / 风控敞口**：管理员视角看到的是平台基准价（运营内部口径），不应用任何用户组加点

### 1.3 兼容性

- `t_symbol_group_markup` 缺行或全 0 的组与 symbol，对外行为完全等同于现状（向后兼容）
- 现有 `t_user.group_code='default'` 兜底，未配置加点的默认组即基准价

---

## 2. 整体架构

### 2.1 加点的两层语义

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: t_symbol_quote_mapping (现状，不动)                 │
│   price_multiplier + bid_adjustment + ask_adjustment        │
│   ← 平台 vs LP 的统一映射，影响 K 线 / 落盘 / Kafka          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼ 平台基准 StandardQuote
                            │
        ┌───────────────────┼────────────────────┐
        ▼                   ▼                    ▼
   K 线 / 落盘 /         全平台运营            按用户视角应用 Layer 2
   Kafka 基准价           看板（基准价）              │
                                                  ▼
                                  ┌──────────────────────────────────┐
                                  │ Layer 2: t_symbol_group_markup    │
                                  │ (新增)                            │
                                  │   bid_extra + ask_extra           │
                                  │   按 (groupCode, platformSymbol)  │
                                  └──────────────────────────────────┘
                                                  │
                ┌─────────────────────────────────┼──────────────────────────┐
                ▼                                 ▼                          ▼
        market-service：                  trading-core：                  trading-core：
        REST + WS 按 session                撮合 fillPrice 加点              开仓时冻结
        的 groupCode 加点推送              （新建 position 写入            (group_code_at_open
                                          group_code_at_open）             /bid_extra_at_open
                                                                            /ask_extra_at_open)
                                                                                 ▼
                                                                  平仓 / PnL / 强平 / 挂单触发
                                                                  按"冻结值"算（不查实时配置）
```

### 2.2 关键设计决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| **配置 owner** | `market-service`（与 `t_symbol_quote_mapping` / `t_symbol_group_visibility` 同源） | 价格相关配置统一在市场域，符合服务边界 |
| **Kafka 是否分流多 topic** | ❌ 不分流，继续发基准价 | 避免 N×topic 数据放大；trading-core 自己拿配置算 |
| **trading-core 怎么拿配置** | 启动加载全量 + 30s 定时刷新 + market-service 提供 internal RPC | 复刻 `DefaultMarketQuoteMappingService` 已验证模式 |
| **加点是否冻结到 position** | ✅ 冻结到 `t_position.bid_extra_at_open / ask_extra_at_open / group_code_at_open` | 用户后续换组、运营改加点都不影响存量持仓口径；强平价、PnL 口径稳定 |
| **挂单是否冻结** | ✅ 冻结到 `t_pending_order_trigger`（与 position 一致） | 用户挂单后改组不影响触发条件 |
| **强平价计算** | 用 `position.bid_extra_at_open / ask_extra_at_open` 算 effectiveMark，触发时也按这套 | 公平性 + 一致性 |
| **管理员视角** | 不加点，看基准价 | 运营观测口径稳定 |

---

## 3. 数据模型（SQL 迁移）

### 3.1 market-service 新增 V14

**文件**：`falconx-market-service/src/main/resources/db/migration/V14__symbol_group_markup.sql`

```sql
-- =============================================================
-- V14: 用户组对每个平台 symbol 的额外加点配置（双向）
-- 任务卡：STAGE-12-GROUP-MARKUP
--
-- 与 V7 平行的第二张组级关系表：
--   - V7  t_symbol_group_visibility (group_code, symbol) → 该组能看哪些 symbol
--   - V14 t_symbol_group_markup     (group_code, platform_symbol) → 该组在每个 symbol 上的加点
--
-- bid_extra / ask_extra 均允许负值（用于 VIP 让利）；CHECK 兜底防止极端值。
-- =============================================================

CREATE TABLE t_symbol_group_markup (
    group_code      VARCHAR(64)    NOT NULL COMMENT '用户组代码，对应 t_user.group_code',
    platform_symbol VARCHAR(32)    NOT NULL COMMENT '平台symbol，对应 t_symbol_quote_mapping.platform_symbol',
    bid_extra       DECIMAL(24,8)  NOT NULL DEFAULT 0 COMMENT 'Bid 组级额外加点（可负）',
    ask_extra       DECIMAL(24,8)  NOT NULL DEFAULT 0 COMMENT 'Ask 组级额外加点（可负）',
    enabled         TINYINT        NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用（停用等价于 0 加点）',
    created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (group_code, platform_symbol),
    INDEX idx_group_enabled (group_code, enabled),
    INDEX idx_platform (platform_symbol),
    CONSTRAINT chk_group_markup_range CHECK (
        bid_extra > -1000000 AND bid_extra < 1000000 AND
        ask_extra > -1000000 AND ask_extra < 1000000
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户组在平台基准价之上的额外加点配置';

-- 默认组兜底：为 default 组、所有当前启用的 platform symbol 写一行 0 加点
-- 保证应用层 lookup 必有命中，无需对 cache miss 做额外分支
INSERT IGNORE INTO t_symbol_group_markup (group_code, platform_symbol, bid_extra, ask_extra, enabled)
SELECT 'default', platform_symbol, 0, 0, 1
FROM t_symbol_quote_mapping
WHERE enabled = 1;
```

**约束说明**：

- 主键 `(group_code, platform_symbol)`：保证一组+一个 symbol 只有一条配置
- 不外键约束（与现有 `t_symbol_group_visibility` 一致，依赖应用层维护）
- `CHECK` 限制 ±1,000,000，防止误录入毁掉报价（业务上加点不应超过价格本身的数量级）

### 3.2 trading-core-service 新增 V21

**文件**：`falconx-trading-core-service/src/main/resources/db/migration/V27__position_pending_order_group_markup_freeze.sql`

```sql
-- =============================================================
-- V21: position / pending_order_trigger 冻结开仓时的组加点
-- 任务卡：STAGE-12-GROUP-MARKUP
--
-- 把开仓 / 挂单时刻的 (groupCode, bid_extra, ask_extra) 冗余冻结到
-- t_position / t_pending_order_trigger，保证后续 PnL / 强平 / 触发口径稳定，
-- 不受用户后续换组、运营改加点影响。
--
-- 现存数据兼容：DEFAULT 0（基准价），可选 NULL→0 backfill。
-- =============================================================

ALTER TABLE t_position
    ADD COLUMN group_code_at_open VARCHAR(64)    NOT NULL DEFAULT 'default'
        COMMENT '开仓时用户所属组（冗余冻结，用于稳定 PnL / 强平口径）' AFTER user_id,
    ADD COLUMN bid_extra_at_open  DECIMAL(24,8)  NOT NULL DEFAULT 0
        COMMENT '开仓时该组的 bid_extra（冗余冻结）' AFTER group_code_at_open,
    ADD COLUMN ask_extra_at_open  DECIMAL(24,8)  NOT NULL DEFAULT 0
        COMMENT '开仓时该组的 ask_extra（冗余冻结）' AFTER bid_extra_at_open;

ALTER TABLE t_pending_order_trigger
    ADD COLUMN group_code_at_create VARCHAR(64)   NOT NULL DEFAULT 'default'
        COMMENT '挂单创建时用户所属组（冗余冻结）' AFTER user_id,
    ADD COLUMN bid_extra_at_create  DECIMAL(24,8) NOT NULL DEFAULT 0
        COMMENT '挂单创建时该组的 bid_extra（冗余冻结）' AFTER group_code_at_create,
    ADD COLUMN ask_extra_at_create  DECIMAL(24,8) NOT NULL DEFAULT 0
        COMMENT '挂单创建时该组的 ask_extra（冗余冻结）' AFTER bid_extra_at_create;

-- 兼容性说明：
-- 1. 存量 position / pending_order_trigger 默认 group_code_at_open='default' + 0 加点，行为等同于现状。
-- 2. 应用层在新建 position / pending_order_trigger 时必须显式写入冻结值（不依赖 default）。
```

### 3.3 数据库设计文档同步

`docs/database/falconx一期数据库设计.md` 需要追加：

- `market`：新增 `t_symbol_group_markup`
- `trading`：`t_position` 新增 3 列、`t_pending_order_trigger` 新增 3 列
- 字段语义说明：双向、可负、冻结策略

---

## 4. 契约设计（REST 接口 + 内部 RPC）

### 4.1 客户端 REST 接口（行为变更）

**变更**：所有返回 bid/ask/mid 的客户端接口，在 controller 层应用组加点。**接口路径、入参、字段名、HTTP 状态码全部不变**。

涉及接口（grep 出 4 个）：

| 接口 | 现状 | 改造后 |
|---|---|---|
| `GET /api/v1/market/symbols` | 返回 bid/ask（基准价） | 按 `X-User-Group-Code` 应用加点 |
| `GET /api/v1/market/quote/{symbol}` | 返回 bid/ask/mid | 按 `X-User-Group-Code` 应用加点 |
| `GET /api/v1/market/snapshot` (批量快照) | 返回 list | 按 `X-User-Group-Code` 应用加点 |
| 任何在响应 DTO 中暴露 `bid/ask/mid` 的端点 | 现状 | 改造后 |

**实现位置**：`market-service` 在 `MarketQueryController` 出参 mapper 处统一应用。**禁止**散落在多个 service 里。

### 4.2 WebSocket（行为变更，协议不变）

| WS frame | 现状字段 | 改造后字段 |
|---|---|---|
| `price.tick` | `bid / ask / mid / mark` 全平台一致 | **每个 session 按 groupCode 各自计算后下发** |
| `kline.*` | 不变 | 不变（基准价） |

握手时 gateway 已经把 `X-User-Group-Code` 注入到 WS attribute（已确认存在），无需新增协议字段。

### 4.3 管理端 REST 接口（新增 §6.10 用户组加点管理）

补充到 `docs/api/管理端接口规范.md`，紧跟现有 §6.8 报价映射管理、§6.9 用户组可见性管理。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| `GET`  | `/admin/symbols/group-markup` | `symbol:view` | 列表查询（`groupCode` / `symbol` 过滤 + 分页） |
| `GET`  | `/admin/symbols/group-markup/grouped` | `symbol:view` | 聚合视图：按 group_code 聚合 |
| `POST` | `/admin/symbols/group-markup` | `symbol:write` | 新增配置 |
| `PUT`  | `/admin/symbols/group-markup` | `symbol:write` | 批量更新（key=group+symbol） |
| `DELETE` | `/admin/symbols/group-markup` | `symbol:write` | 删除配置（删除后该组该 symbol 回退基准价） |

**入参校验**：
- `groupCode` 必须存在于 `t_symbol_group_visibility.group_code`（防止配置不可见的组）
- `platform_symbol` 必须存在于 `t_symbol_quote_mapping.platform_symbol`，否则 `90615` `ADMIN_SYMBOL_NOT_FOUND`
- `bid_extra` / `ask_extra`：DECIMAL(24,8)，CHECK 范围
- 新增错误码：`90640 ADMIN_GROUP_MARKUP_NOT_FOUND`、`90641 ADMIN_GROUP_MARKUP_INVALID_RANGE`、`90642 ADMIN_GROUP_MARKUP_DUPLICATE`

**审计**：标记为 `risk_level=MEDIUM`（影响所有该组用户的报价口径），写 `t_admin_operation_log`。

### 4.4 内部 RPC（market-service → trading-core）

新增到 market-service：

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `/internal/v1/market/group-markup` | gateway 内部网络 | 全量加载（启动时） |
| `GET` | `/internal/v1/market/group-markup/{groupCode}/{symbol}` | 同上 | 单点查询（备用） |
| `GET` | `/internal/v1/market/group-markup/changes?since={epochMs}` | 同上 | 增量刷新（30s 拉取） |

trading-core 启动时 `GET /internal/v1/market/group-markup` 加载到内存快照；30s `@Scheduled` 调 `changes?since=` 增量刷新。

**响应 DTO**（落到 `falconx-market-contract`）：

```java
public record MarketGroupMarkupItem(
    String groupCode,
    String platformSymbol,
    BigDecimal bidExtra,
    BigDecimal askExtra,
    boolean enabled,
    OffsetDateTime updatedAt
) {}

public record MarketGroupMarkupListResponse(
    List<MarketGroupMarkupItem> items,
    OffsetDateTime serverTime  // 用于下次 since 参数
) {}
```

---

## 5. Java 改造清单（按角色拆分）

### 5.1 market-service（R2 契约 + R4 实现 + R8 文档）

**新增**：

| 文件 | 类型 | 职责 |
|---|---|---|
| `db/migration/V14__symbol_group_markup.sql` | SQL | 见 §3.1 |
| `entity/MarketSymbolGroupMarkup.java` | record | (groupCode, platformSymbol, bidExtra, askExtra, enabled, updatedAt) |
| `repository/MarketSymbolGroupMarkupRepository.java` | interface | `findAll()` / `findByGroupCode` / `upsert` / `delete` / `findChangedSince` |
| `repository/MybatisMarketSymbolGroupMarkupRepository.java` | impl | MyBatis 实现 |
| `repository/mapper/MarketSymbolGroupMarkupMapper.java` + `.xml` | mapper | SQL |
| `repository/mapper/record/MarketSymbolGroupMarkupRecord.java` | record | MyBatis record |
| `service/MarketGroupMarkupService.java` | interface | `find(groupCode, symbol)` / `apply(quote, groupCode)` / `refresh()` |
| `service/impl/DefaultMarketGroupMarkupService.java` | impl | 内存快照 + 30s `@Scheduled refresh` + 启动 `refreshMappings`-style 加载 |
| `application/MarketGroupMarkupAdminApplicationService.java` | service | 管理端 CRUD 编排 |
| `controller/MarketGroupMarkupInternalController.java` | controller | 3 个 `/internal/v1/market/group-markup*` 端点 |
| `controller/MarketGroupMarkupAdminInternalController.java` | controller | 管理端 5 端点（被 console 透传调用） |
| `api/AdminGroupMarkupCreateRequest.java` / `UpdateRequest.java` / `ListResponse.java` | DTO | 管理端 API DTO |

**改造**：

| 文件 | 改动 |
|---|---|
| `websocket/MarketWebSocketSessionRegistry.publishQuote` | 推送前按 `session.groupCode` 查 markup + 应用到 bid/ask/mid |
| `websocket/MarketWebSocketSessionRegistry.publishCurrentPriceSnapshots` | 首屏快照也按组加点 |
| `controller/MarketQueryController.*` | REST 出参 mapper 统一按 `X-User-Group-Code` 应用加点 |
| `service/MarketQuoteApplicationService` 或类似 layer | 提供 `applyGroupMarkup(StandardQuote, groupCode)` 统一工具方法 |
| `entity/StandardQuote.java` | 加 `withExtraMarkup(BigDecimal bidExtra, BigDecimal askExtra)` 方法（返回新对象） |

**`falconx-market-contract` 新增**：

| 文件 | 职责 |
|---|---|
| `event/MarketGroupMarkupItem.java` | 跨服务 DTO（供 trading-core / console 反序列化） |
| `event/MarketGroupMarkupListResponse.java` | 同上 |

### 5.2 trading-core-service（R4 实现 + R6 测试）

**新增**：

| 文件 | 类型 | 职责 |
|---|---|---|
| `db/migration/V27__position_pending_order_group_markup_freeze.sql` | SQL | 见 §3.2 |
| `service/TradingGroupMarkupService.java` | interface | `find(groupCode, symbol)` / `refresh()` |
| `service/impl/DefaultTradingGroupMarkupService.java` | impl | 内存快照 + 30s `@Scheduled refresh` + 启动加载（仿 market `DefaultMarketQuoteMappingService`） |
| `client/MarketGroupMarkupClient.java` (RestClient) | client | 调 market-service `/internal/v1/market/group-markup*` |
| `support/TradingMarkupApplier.java` | util | 统一应用加点：`apply(quote, bidExtra, askExtra, side)` |

**改造**（核心 8 处）：

| 位置 | 改造目的 |
|---|---|
| `service/impl/DefaultTradingRiskService.java:163` | 开仓 `fillPrice` 加点：`side==BUY ? quote.ask()+askExtra : quote.bid()+bidExtra` |
| `service/impl/DefaultTradingRiskService.java:227` | exposureLimit 检查也用加点价 |
| `application/TradingOrderPlacementApplicationService.java` | 创建 position 时写入 `group_code_at_open / bid_extra_at_open / ask_extra_at_open`（从 command.user.groupCode 取） |
| `support/TradingPricingSupport.java:42/55/68` | 平仓 / unrealized PnL 计算用 `position.bidExtraAtOpen / askExtraAtOpen` |
| `engine/QuoteDrivenEngine.processTick / publishPositionPnlUpdates` | 每个 position 按其 `bid_extra_at_open / ask_extra_at_open` 算 `effectiveMark`（不再统一用 `snapshot.mark()`） |
| `engine/QuoteDrivenEngine` 强平扫描 | 用 `effectiveMark` 与 `liquidation_price` 比对 |
| `engine/PendingOrderTriggerEvaluator.java:40-48` | 触发判定按 `pendingOrder.bidExtraAtCreate / askExtraAtCreate` 应用加点后比对 |
| `application/TradingPendingOrderApplicationService` | 创建挂单时写入冻结值 |
| `repository/MybatisTradingPositionRepository.java` + `xml` | INSERT 写入 3 个冻结字段，SELECT 返回 |
| `repository/mapper/record/TradingPositionRecord.java` | 加 3 个字段 |
| `entity/TradingPosition.java` | 加 `groupCodeAtOpen / bidExtraAtOpen / askExtraAtOpen` |
| `repository/MybatisTradingPendingOrderTriggerRepository.java` + `xml` | INSERT 写入 3 字段 |
| `entity/TradingPendingOrderTrigger.java` | 加 3 字段 |
| `config/TradingCoreServiceProperties.java` + `application.yml` | 加 `falconx.trading.group-markup.refresh-interval=30s` + `falconx.trading.market.base-url` |
| `application/TradingPositionCloseApplicationService` | 平仓时按 position 冻结值算 exitPrice |
| `service/impl/DefaultTradingRiskObservabilityService` | 管理员视角不加点（保持基准） |

**入口拦截 user 的 groupCode**：

撮合开仓时 `PlaceMarketOrderCommand.userId` → 在 `TradingOrderPlacementApplicationService` 入口先查询用户当前 groupCode（来自 gateway 透传的 header 或 identity 同步缓存）。**当前 trading-core 已有 user groupCode 流转**（gateway 透传 `X-User-Group-Code`），需在 controller 层提取并放到 command。

### 5.3 console-service（R9 实现）

**新增**：

| 文件 | 职责 |
|---|---|
| `controller/AdminSymbolGroupMarkupController.java` | 5 端点透传到 market-service 管理端 RPC |
| `application/AdminSymbolGroupMarkupApplicationService.java` | InternalRpcClient 转发 + 错误码翻译 |
| `api/AdminGroupMarkupItem.java` / `AdminGroupMarkupCreateRequest.java` / 等 DTO | 管理端 API DTO |

**改造**：

| 文件 | 改动 |
|---|---|
| `error/AdminErrorCode.java` | 加 3 个错误码：`90640 / 90641 / 90642` |
| `error/AdminGlobalExceptionHandler.java` | 加错误码映射 |
| `security/HighRiskPermissionRegistry.java` | 注册 `symbol:write` → MEDIUM 风险（写审计） |

### 5.4 falconx-frontend（客户端，R5）

**期望**：**前端代码 0 改动**。

理由：所有报价口径已经在后端按组应用，前端拿到的 REST / WS 字段名、字段语义、数据流完全不变。只是数值变了，前端不需要感知。

**验证**：客户端在浏览器看到的报价就是带组加点的；开仓 / 平仓显示的成交价也是带组加点的。

### 5.5 console-frontend（管理端前端，R10）

**新增页面**（**必须先走 frontend-design skill 设计**）：

| 路由 | 页面 | 功能 |
|---|---|---|
| `/admin/symbols/group-markup` | `GroupMarkupListPage` | 列表 + 过滤（groupCode / symbol） + 分页 |
| `/admin/symbols/group-markup/grouped` | `GroupMarkupGroupedPage`（可选） | 按组聚合视图 |
| `/admin/symbols/group-markup/new` 弹窗或子页 | `GroupMarkupEditModal` | 新增/编辑表单 |

**侧栏菜单**：在 "市场配置" 分组下新增 "组加点配置"，沿用 §6.8 / §6.9 同款风格。

**UI/UX 关键点**：
- 加点字段强调"可负"，用 `+0.05` / `-0.02` 直观显示
- 表单提供"预览计算结果"：输入加点后实时显示 "若当前 LP bid=2300，则用户看到 bid=2300+0.05=2300.05"
- 删除二次确认（影响所有该组用户的报价）
- 配置变更后页面提示"配置最长 30 秒同步到撮合服务"

---

## 6. 改造影响面

### 6.1 数据流改造点（grep 锁定的 8 个位置）

| # | 路径 | 改造前 | 改造后 |
|---|---|---|---|
| ① | `DefaultTradingRiskService.java:163` 开仓 fillPrice | `side==BUY ? quote.ask() : quote.bid()` | `side==BUY ? quote.ask()+askExtra : quote.bid()+bidExtra` |
| ② | `TradingPricingSupport.java:42` 平仓 exitPrice | `side==BUY ? quote.bid() : quote.ask()` | 按 position 冻结值 |
| ③ | `TradingPricingSupport.java:55` netSettle | 同上 | 同上 |
| ④ | `TradingPricingSupport.java:68` markEffective | `netExposure<0 ? quote.ask() : quote.bid()` | 不变（运营敞口口径） |
| ⑤ | `QuoteDrivenEngine.java:247-249` publishPositionPnlUpdates | `snapshot.mark()` 全平台一致 | 按 position 冻结值 |
| ⑥ | `QuoteDrivenEngine.java:263` 强平扫描 | 同上 | 同上 |
| ⑦ | `PendingOrderTriggerEvaluator.java:40-48` 挂单触发 | `quote.bid() / quote.ask() / quote.mark()` | 按 pendingOrder 冻结值 |
| ⑧ | `MarketWebSocketSessionRegistry.publishQuote / publishCurrentPriceSnapshots` | 全部 session 同一 quote | 按 session.groupCode 各自计算 |

### 6.2 性能影响评估

| 链路 | 影响 |
|---|---|
| WS 推送 | 每条 tick × N 在线 session：原来 1 次序列化 → 改后按 group 分桶（实际 group 数远小于 session 数），分桶后 1 次/group 序列化。**预估开销 < 5%** |
| REST 报价接口 | 单次查一次 in-memory map：**+0.1ms 以内** |
| 撮合开仓 | 多查一次 in-memory map：**忽略不计** |
| QuoteDrivenEngine processTick | 现在已经按 position 循环，加 group 加点只是多算一次 BigDecimal 加法：**单 tick +5% 以内** |

**性能测试基线**（§9.3 性能测试章节）：
- WS 推送：1000 在线 session × 100 tick/s，p99 延迟 ≤ 100ms（与现状对比）
- 撮合开仓：单笔 < 200ms（与现状对比）
- 强平扫描：tick→强平触发 < 500ms（与现状对比）

### 6.3 兼容性 / 回滚

| 风险 | 控制策略 |
|---|---|
| 配置未刷新到 trading-core | 30s 自动刷新；启动时同步加载；启动失败时 fail-fast |
| 配置丢失（cache miss） | 降级为 0 加点（向后兼容） |
| 错误录入巨额加点 | DB CHECK 约束 ±1,000,000；前端 confirmation |
| 紧急回滚 | `UPDATE t_symbol_group_markup SET enabled=0 WHERE group_code='X'` → 30s 内全平台该组退回基准价 |

---

## 7. 工作量评估（按角色 / 阶段）

### 7.1 R2 契约（设计稿 + 接口规范）

| 任务 | 工作量 |
|---|---|
| 本设计稿 | 已交付（本文档） |
| 管理端接口规范 §6.10 落地 | 0.3 天 |
| 错误码补充（90640-90642） | 0.1 天 |
| falconx-market-contract DTO record | 0.2 天 |
| **R2 小计** | **0.6 天** |

### 7.2 R3 UI 设计（frontend-design skill）

| 任务 | 工作量 |
|---|---|
| GroupMarkupListPage 设计稿（Figma 或 ASCII） | 0.5 天 |
| GroupMarkupEditModal 设计稿 | 0.3 天 |
| **R3 小计** | **0.8 天** |

### 7.3 R4 业务后端（market + trading-core）

| 任务 | 工作量 |
|---|---|
| market-service V14 + entity / repository / mapper / xml | 0.5 天 |
| market-service `MarketGroupMarkupService` 内存快照 + 刷新 | 0.5 天 |
| market-service `MarketGroupMarkupInternalController` + DTO | 0.3 天 |
| market-service `MarketWebSocketSessionRegistry.publishQuote` 改造 | 0.5 天 |
| market-service `MarketQueryController` REST 出参改造 | 0.3 天 |
| trading-core V21 + `Trading{Position,PendingOrderTrigger}` entity/mapper 加字段 | 0.5 天 |
| trading-core `TradingGroupMarkupService` + `MarketGroupMarkupClient` | 0.5 天 |
| trading-core 8 个改造点（`DefaultTradingRiskService` / `TradingPricingSupport` / `QuoteDrivenEngine` / `PendingOrderTriggerEvaluator` / 入口冻结） | 1.5 天 |
| 联调 + 修 bug | 1.0 天 |
| **R4 小计** | **5.6 天** |

### 7.4 R6 单元测试 + 集成测试

| 任务 | 工作量 |
|---|---|
| market-service `DefaultMarketGroupMarkupServiceTests`（刷新 / find / apply） | 0.3 天 |
| market-service WebSocket 集成测试（多 group session 各自看到不同价） | 0.5 天 |
| market-service REST 测试（X-User-Group-Code 不同时返回不同价） | 0.3 天 |
| trading-core 开仓加点 IT（不同组同 symbol 不同 fillPrice） | 0.5 天 |
| trading-core 平仓 PnL IT（用冻结值算） | 0.5 天 |
| trading-core 强平触发 IT（按 effectiveMark） | 0.5 天 |
| trading-core 挂单触发 IT（按冻结值） | 0.5 天 |
| 配置变更后冻结值不变 IT | 0.2 天 |
| **R6 小计** | **3.3 天** |

### 7.5 R7 E2E QA + 性能

| 任务 | 工作量 |
|---|---|
| 全链路 E2E（admin 配置 → trading-core 30s 内拉到 → 用户开仓 → 平仓 PnL 含加点） | 0.5 天 |
| 浏览器截图（管理端配置 + 客户端报价 + 交易确认） | 0.3 天 |
| 性能测试（WS 推送压测 / 撮合开仓压测） | 0.5 天 |
| R7 验证报告 | 0.3 天 |
| **R7 小计** | **1.6 天** |

### 7.6 R8 文档同步

| 任务 | 工作量 |
|---|---|
| `docs/database/falconx一期数据库设计.md` 同步 | 0.2 天 |
| `docs/api/FalconX统一接口文档.md` 同步（新接口 + WS 行为变化） | 0.3 天 |
| `docs/api/REST接口规范.md` 报价接口字段说明补充 | 0.2 天 |
| `docs/api/WebSocket接口规范.md` 行为说明补充 | 0.2 天 |
| `docs/api/管理端接口规范.md` §6.10 落地 | 0.3 天 |
| `docs/setup/当前开发计划.md` 收口条目 | 0.2 天 |
| **R8 小计** | **1.4 天** |

### 7.7 R9 console-service 后端透传

| 任务 | 工作量 |
|---|---|
| `AdminSymbolGroupMarkupController` + `AdminSymbolGroupMarkupApplicationService` + DTO | 0.5 天 |
| 错误码翻译 + 审计 AOP | 0.2 天 |
| IT（5 端点） | 0.5 天 |
| **R9 小计** | **1.2 天** |

### 7.8 R10 console-frontend 管理端前端

| 任务 | 工作量 |
|---|---|
| `GroupMarkupListPage` + `GroupMarkupEditModal` + types + api | 1.0 天 |
| 侧栏菜单 + 路由 | 0.1 天 |
| Vitest（list / api / modal） | 0.5 天 |
| **R10 小计** | **1.6 天** |

### 7.9 总计

| 角色 | 工作量 |
|---|---|
| R2 | 0.6 天 |
| R3 | 0.8 天 |
| R4 | 5.6 天 |
| R6 | 3.3 天 |
| R7 | 1.6 天 |
| R8 | 1.4 天 |
| R9 | 1.2 天 |
| R10 | 1.6 天 |
| **合计** | **约 16 人天**（单人串行约 3 周；多角色并行约 1.5-2 周） |

---

## 8. 测试计划

### 8.1 单元测试（R6）

**market-service**：

| 用例 | 覆盖点 |
|---|---|
| `DefaultMarketGroupMarkupServiceTests.find` | 找到 / 找不到 / 禁用 |
| `DefaultMarketGroupMarkupServiceTests.refresh` | 全量加载 / 增量刷新 |
| `DefaultMarketGroupMarkupServiceTests.applyMarkup` | bid + bidExtra / ask + askExtra / 0 加点直返 / 负加点 |
| `MarketWebSocketSessionRegistryTests.publishQuoteByGroup` | 多 group session 同 symbol 各自不同价 |

**trading-core**：

| 用例 | 覆盖点 |
|---|---|
| `TradingGroupMarkupServiceTests` | 同上 |
| `TradingMarkupApplierTests` | 不同 side × 不同方向 |

### 8.2 集成测试（R6）

| TC 编号 | 名称 | 覆盖 |
|---|---|---|
| TC-GM-001 | admin 配置加点 → 30s 内 trading-core 拉到 | 配置传播 |
| TC-GM-002 | 默认组 / 配置组 同 symbol 同 LP 报价 → REST 返回不同 bid/ask | REST 按组 |
| TC-GM-003 | 多 group session 订阅同 symbol → WS 各自下发不同 price.tick | WS 按组 |
| TC-GM-004 | VIP 组开仓 fillPrice = base.ask + askExtra | 开仓加点 |
| TC-GM-005 | 用户开仓后改组 / 改加点 → position PnL 仍按原 group_code_at_open 算 | 冻结生效 |
| TC-GM-006 | 平仓 exitPrice = base.bid + bidExtra（用 position 冻结值） | 平仓加点 |
| TC-GM-007 | VIP 组持仓 PnL 与基准持仓 PnL 在同一 mark 下差异等于 bidExtra×qty | PnL 一致性 |
| TC-GM-008 | 强平触发：按 position 冻结值算 effectiveMark | 强平按组 |
| TC-GM-009 | 挂单 LIMIT/STOP 创建后改加点 → 触发仍按原冻结值 | 挂单冻结 |
| TC-GM-010 | 挂单触发 STOP_LIMIT 按冻结值比对 | 挂单加点 |
| TC-GM-011 | 删除 group_markup 配置 → 30s 后该组回退基准 | 删除路径 |
| TC-GM-012 | 配置 bid_extra=-0.5 (让利) → REST 返回 bid 缩小 | 负加点 |
| TC-GM-013 | 同时 ±1M 边界值 → CHECK 拦截 | 边界 |
| TC-GM-014 | admin 5 端点透传（list / create / update / delete / grouped） | 管理端 |
| TC-GM-015 | RBAC：无 `symbol:write` → 403 | 权限 |
| TC-GM-016 | 审计：写操作落 `t_admin_operation_log` | 审计 |
| TC-GM-017 | K 线表持续验证为基准价（多组开仓不影响 OHLC） | K 线不变 |
| TC-GM-018 | ClickHouse `quote_tick` 持续为基准价 | 落盘不变 |
| TC-GM-019 | trading-core 启动加载失败 → fail-fast | 容错 |
| TC-GM-020 | trading-core 增量刷新（since 参数）正常 | 刷新机制 |

### 8.3 性能测试（R7）

**基线对比**：在不启用任何组加点（all 0 / default 组）的情况下与现状对比。

| 用例 | 指标 | 验收 |
|---|---|---|
| PERF-GM-001 | WS 推送：1000 session × 4 组 × 100 tick/s | p99 延迟 ≤ 100ms |
| PERF-GM-002 | REST `/quote` 接口：QPS 1000 | p99 ≤ 50ms |
| PERF-GM-003 | 撮合开仓：单笔 e2e | p99 ≤ 200ms |
| PERF-GM-004 | QuoteDrivenEngine processTick：100 open position × 100 tick/s | 无积压 |

工具：`wrk` (REST) + 自研 WS 压测脚本 + JMeter（撮合）。

### 8.4 浏览器 QA（R7）

| 截图 | 内容 |
|---|---|
| screenshot-01 | 管理端 GroupMarkupListPage 列表页 |
| screenshot-02 | 管理端 GroupMarkupEditModal 编辑弹窗（含预览） |
| screenshot-03 | 客户端浏览器报价（default 组） |
| screenshot-04 | 客户端浏览器报价（VIP 组，与 default 组对比） |
| screenshot-05 | 客户端持仓 PnL 显示（含组加点） |
| screenshot-06 | 客户端平仓确认页（含组加点价） |

> 若 WSL 浏览器 QA 受 chromium 依赖限制，则按现有 STAGE-7/8 同样的方式降级到程序化 E2E + 数据库 trace。

---

## 9. 文档同步清单（R8）

| 文档 | 改动 |
|---|---|
| `docs/database/falconx一期数据库设计.md` | 新增 `t_symbol_group_markup`，`t_position` / `t_pending_order_trigger` 加 3 字段 |
| `docs/api/REST接口规范.md` | `/api/v1/market/symbols` / `quote` / `snapshot` 字段说明：bid/ask 含组加点 |
| `docs/api/WebSocket接口规范.md` | `price.tick` 行为说明：按 session.groupCode 计算 |
| `docs/api/管理端接口规范.md` | 新增 §6.10 用户组加点管理 + §15.x 错误码 90640-90642 |
| `docs/api/FalconX统一接口文档.md` | 同步新接口 + 行为变化 |
| `docs/architecture/falconx一期网关-服务-数据库架构方案.md` | 加点两层模型说明 |
| `docs/market/LP自建行情源接入契约.md` | 组加点不影响 LP 链路 |
| `docs/setup/当前开发计划.md` | 阶段 12 收口条目（按 STAGE-7 / STAGE-8 / STAGE-11 同款格式） |
| `docs/process/BBook一期完成执行路径.md` | 加阶段 12 段落 |
| `docs/test/STAGE-12-GROUP-MARKUP-test-cases.md` | 新建 R6 测试用例清单（TC-GM-001~020） |
| `docs/test/STAGE-12-GROUP-MARKUP-R7-verification-report.md` | R7 验证报告 |

---

## 10. 执行顺序（建议）

R1 派发后建议按此顺序推进（不可跳过依赖）：

| Step | 内容 | 角色 | 阻断关系 |
|---|---|---|---|
| 1 | 本设计稿用户审定 | R2 | — |
| 2 | 落地 R2 契约：管理端接口规范 §6.10 + 错误码 + market-contract DTO | R2 | blockedBy: 1 |
| 3 | R3 frontend-design 设计稿：GroupMarkupListPage + EditModal | R3 | blockedBy: 1 |
| 4 | R6 测试用例骨架（TC-GM-001~020 占位） | R6 | blockedBy: 2 |
| 5 | R4 market-service：V14 + entity / repository / service / WS / REST | R4 | blockedBy: 2 |
| 6 | R4 trading-core：V21 + entity / repository / service / 8 改造点 | R4 | blockedBy: 5 |
| 7 | R9 console-service：5 端点透传 | R9 | blockedBy: 5 |
| 8 | R6 IT 落地 + 单元测试 | R6 | blockedBy: 5, 6 |
| 9 | R10 console-frontend：列表 + 编辑弹窗 + Vitest | R10 | blockedBy: 3, 7 |
| 10 | R7 E2E + 浏览器 QA + 性能测试 | R7 | blockedBy: 6, 8, 9 |
| 11 | R8 文档同步 + 当前开发计划收口条目 | R8 | blockedBy: 10 |
| 12 | Git 回滚点 commit + push（用户授权） | R1 | blockedBy: 11 |

---

## 11. 风险与缓解

| 风险 | 缓解 |
|---|---|
| trading-core 启动时 market-service 未启动 → 加点配置加载失败 | fail-fast；market-service 必须先启动；运维顺序文档化 |
| 加点配置 30s 才同步到 trading-core，期间撮合用旧值 | 接受（文档化）；高敏感场景 admin 操作后人工通知运营 |
| 强平价已用冻结的 bidExtra/askExtra 计算并入库，后续配置变更不影响存量 | 冻结策略保证一致性 |
| 用户换组（identity 改 group_code）后未平仓持仓的 PnL 仍按原组算 | 这是有意为之，避免持仓口径中途突变 |
| WS 推送 group 维度循环开销 | 按 group 分桶后序列化 1 次/group，不增加序列化次数 |
| 双向加点录入错误（如 bid_extra=99999 误录） | CHECK 约束 + 前端 confirmation + 审计日志事后追责 |

---

## 12. 反问 / 待确认

设计稿在交付前需要用户确认的关键决策：

1. **管理员视角（运营敞口看板 / 风控页）是否需要按组加点？**
   - 默认：不加点（看基准价），运营口径稳定
   - 备选：按组分别显示（运营复杂度高，不推荐）

2. **30s 配置同步延迟是否可接受？**
   - 默认：可接受（与现有 `MarketQuoteMappingService` 一致）
   - 备选：改成事件驱动（market-service publishGroupMarkupChanged → Kafka → trading-core 订阅），延迟降到 < 1s，但实施复杂度 +30%

3. **挂单触发的 effectiveMark 计算口径**：
   - 默认：用 `pendingOrder.bidExtraAtCreate / askExtraAtCreate` 冻结值
   - 备选：实时按用户当前组取值（不冻结）
   - 已选默认（冻结）。

4. **是否需要审计加点变更前后对该组持仓 PnL 的"假设影响"分析报告？**
   - 默认：不需要（成本太高）
   - 备选：管理端配置变更前显示"影响 N 个用户、X USD 浮动盈亏假设变化"
   - 推荐默认。

---

## 13. 附录：grep 证据索引

设计稿涉及的所有现状代码位置（grep 命中行号）：

| 现象 | 文件:行 |
|---|---|
| K 线只用 bid | `falconx-market-service/.../DefaultKlineAggregationService.java:108` |
| mapping 加点计算 | `falconx-market-service/.../DefaultMarketQuoteMappingService.java:86-91` |
| WS 推送 publishQuote | `falconx-market-service/.../MarketWebSocketSessionRegistry.java:182-200` |
| 撮合 fillPrice | `falconx-trading-core-service/.../DefaultTradingRiskService.java:163` |
| 平仓 exitPrice | `falconx-trading-core-service/.../TradingPricingSupport.java:42,55,68` |
| PnL 推送 | `falconx-trading-core-service/.../QuoteDrivenEngine.java:247-249` |
| 强平扫描 | `falconx-trading-core-service/.../QuoteDrivenEngine.java:263` |
| 挂单触发 | `falconx-trading-core-service/.../PendingOrderTriggerEvaluator.java:40-48` |
| gateway 透传 X-User-Group-Code | gateway 已落地（详见现有架构方案） |
| `t_symbol_group_visibility` 同款 PK 模式 | `falconx-market-service/.../V7__symbol_group_visibility_and_quote_mapping.sql` |

---

**设计稿审定提示**：本稿为 R2 + R3 联合设计稿，包含 SQL 迁移、Java 改造清单、接口契约、测试计划、工作量估算、风险评估和文档同步清单。审定通过后由 R1 派发到各角色按 §10 执行顺序推进。

**审定决策点**：见 §12 反问列表。
