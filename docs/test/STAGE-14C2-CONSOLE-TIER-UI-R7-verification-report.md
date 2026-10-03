# STAGE-14C2-CONSOLE-TIER-UI R7 验证报告

> 验证日期：2026-05-29
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14C2-CONSOLE-TIER-UI` console tier 配置三端 UI（CRUD 透传 + RBAC + 审计）+ FX_PAUSED 按品种类目行为接线（master spec §0.3 C 阶段剩余切片 C2，跨 market/trading/console 三服务 + console-frontend）

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本阶段 C2（tier 三端 UI + FX_PAUSED 按类目行为）在三端代码侧已实现并通过 IT/vitest 验证，但存在一个生产/演示库部署阻断项（沿 STAGE-14B/C1 叠加），部署前必须先处理，不得让生产/演示库裸跑 `flyway migrate`。**

**阻断项：本地开发库 `falconx_trading`（及远程演示库）存在 STAGE-14B 遗留 schema 漂移（V28/V29），C1 V30-V32 叠加其上，C2 不再向 trading 库新增 migration。**

- 根因（沿 14B）：14B 的 V28（`t_ledger` 三列）与 V29（`t_position.entry_fx_rate`）migration 在 root bug 期间误写 `USE falconx_trading;`（已由 `1031a9ce` 修复），导致这两列在 `falconx_trading` 库**已物理存在且已回填**，但 `flyway_schema_history` **没有 V28/V29 行**。
- C2 新增的 migration：**仅 console `V12__seed_tier_permissions_and_menu.sql`（干净，无 USE，作用于 `falconx_console` 库）**；**market 无新 migration**（`category` 落在既有 `t_symbol`，由 owner 填充 Redis 快照）；**trading 无新 migration**（C2 复用 C1 的 V30-V32 + Task 6 仅消费 V31 已建的 `t_fx_pause_behavior`）。
- 风险：被 14B `USE` 污染过的既有 trading 库下次 `flyway migrate` 仍会因 V28/V29 列已存在而先撞 `Duplicate column` 失败，trading-core 启动/部署受阻；该失败连带 C1 V30-V32 无法应用。**C2 本身不引入新的 trading 库阻断，但不解除 14B/C1 的既有阻断。**

**修复指引（部署前由 DBA / 部署执行，沿 C1 §0）：**

1. **先修 14B V28/V29 漂移**（二选一）：
   - 手动向 `flyway_schema_history` 插入 V28/V29 成功行（`success=1`，checksum 用删 USE 后 SQL 计算，因列与回填已就位不再 ALTER）；
   - 或 `flyway repair` + 人工核对目标库 `t_ledger` 三列 + `t_position.entry_fx_rate` 确已存在且回填正确，再对齐/跳过 V28/V29。
2. **再正常 migrate**：trading 库 V30-V32（C1）、console 库 V12（C2）为干净顺序 migration，正常应用即可。

执行须按授权进行。**禁止在未核对前对生产/演示库直接 `flyway migrate`。**

> 全新部署的干净库无此问题（trading 库从 V27 顺序到 V32、console 库到 V12 均干净）。本阻断项仅影响 root bug 期间被 14B `USE` 污染过的既有 trading 库。

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 C 阶段的**剩余切片 C2**（console tier 配置三端 UI + FX_PAUSED 按类目行为），补齐 C1 推迟的 master §8.3 C 阶段「admin 改 tier 30s 生效」「tier CRUD UI」两条验收硬约束：

| 子能力 | C2 交付 | 关键 commit |
|---|---|---|
| `SymbolSpec` 加 `category`（FX_PAUSED 类目来源） | market owner `RedisMarketSymbolSpecRepository.toSpec` 填充 + trading 消费就绪 + 向后兼容 null（无新 migration，category 在 `t_symbol`） | `645fd76c` |
| 错误码 90930-90932 ADMIN_TIER_* + 30087 GLOBAL_PAUSE_ACTIVE | trading 错误码 + console 翻译映射 | `7471cb22` |
| `SymbolLeverageTier` CRUD 写扩展 | insert/updateById/软删 enabled=0/selectPage/countBy/selectById | `bf81242b` |
| tier CRUD internal RPC | `/internal/v1/trading/console/tier` GET/POST/PUT/DELETE + 区间重叠 90932/CHECK 90931/notFound 90930 校验 + `LeverageTierResolver` 写后 invalidate | `f7ed9641` |
| `FxPauseBehavior` 读模型 + 缓存 | Mapper/Repository + 本地缓存（TTL/刷新/降级） | `c733e5d6` |
| FX_PAUSED 按类目接线 | 开仓按 category 查 `t_fx_pause_behavior` → `allow_open=false` 拒 30087 / true 放行；被动强平 `allow_liquidation=false` skip；降级（开仓缺信息保守全拒 / 强平缺信息继续） | `711a198d` |
| console V12 tier 权限 + 菜单 | `tier:view`/`tier:edit` seed + 角色关联 + 「杠杆档位配置」菜单 | `3baf6ffc` |
| console AdminTier 透传 | `/admin/trading/tiers` GET/POST/PUT/DELETE + RBAC + 审计 + 错误翻译 | `a6e97941` |
| console-frontend tier 配置页 | 展开行多档位 + CRUD Modal + 高危二次确认/reason + RBAC 按钮 + `maxLev×mmRate` 校验 | `be284f6a` |
| 三端测试汇总 + 闭环 IT | `it017` CRUD→开仓风控生效闭环（同进程真 DB）| `e1bd2541` |
| R8 文档同步 + R7 报告 + 计划录入 | 本 commit | 本 commit |

**不在 C2 范围**（划 D / E）：

- **D**：CROSS/ISOLATED 用户级 toggle + 切换闸门（OPEN/挂单/冷静期/同模式）+ 5min 冷静期 + CROSS 强平排序「浮亏最大优先」+ 实时 MM 精化（master §3.2 D2，**放开 C1 latent 耦合 `closePositionByTrigger` 二次价格校验**）+ FX_PAUSED 8 类目×3 开关完整验收 + 升级窗口 `isolated_margin` 回填 + supplement-margin。
- **E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换。

---

## §2. 角色

C2 为**三端**（market / trading-core / console-service 后端 + console-frontend 管理端前端），按 [`AI工作模式 §2`](../process/AI工作模式.md) 角色路由：

- **R2 Contract Designer**：tier CRUD REST/RPC 路径 + 错误码 90930-90932/30087 + `SymbolSpec.category` 契约。
- **R4 业务后端**：market category 填充 / trading CRUD 写 + RPC + FX_PAUSED 接线 / FxPauseBehavior 读模型。
- **R9 管理端后端**：console V12 权限 + AdminTier 透传 + RBAC + 审计 + 错误翻译。
- **R10 管理端前端**：console-frontend tier 配置页。
- **R6 Test**：trading IT/UT + console 透传 IT + 前端 vitest + it017 闭环。
- **R7 QA**：本报告。
- **R8 Doc**：R8 文档同步。

> **三端硬约束**：本阶段为管理端配置能力（运营改 tier），客户端无新可见行为（tier 影响通过既有开仓/强平路径间接体现）；管理端后端 + 管理端前端 + 涉及的业务后端（market/trading）均已实现并验证，满足 [`AI工作模式 §5.1`](../process/AI工作模式.md) 管理端配置类三端交付口径。各 task 均经实施 → spec 合规评审 → 代码质量评审 → 收口完整门禁。

---

## §3. 各 Task 证据

| Task | 内容 | 关键 commit | 证据 |
|---|---|---|---|
| 1 | `SymbolSpec` 加 `category`（market 填充 + trading 消费就绪 + 兼容 null） | `645fd76c` | market `RedisMarketSymbolSpecRepositoryToSpec` UT；trading `SymbolSpecBackwardCompatDeserialization` UT（旧快照 null）；无新 migration |
| 2 | 错误码 90930-90932 ADMIN_TIER_* + 30087 GLOBAL_PAUSE_ACTIVE + console 翻译 | `7471cb22` | trading `TradingErrorCode` + console `AdminErrorCode` + `AdminGlobalExceptionHandler` 翻译 |
| 3 | `SymbolLeverageTier` CRUD 写扩展（insert/update/软删/分页/countBy/selectById） | `bf81242b` | UT/IT |
| 4 | tier CRUD internal RPC + 重叠/CHECK/notFound 校验 + 缓存 invalidate | `f7ed9641` | `AdminInternalTradingTierControllerIntegrationTests` IT + `TradingTierAdminApplicationServiceTests` UT；写后 `LeverageTierResolver.invalidate` |
| 5 | `FxPauseBehavior` 读模型/Mapper/Repository + 本地缓存（TTL/刷新/降级） | `c733e5d6` | UT/IT |
| 6 | FX_PAUSED 按类目控制开仓(30087)/被动强平 + 降级 | `711a198d` | `DefaultTradingRiskServiceRiskControlTests` + `QuoteDrivenEngineMarginLevelTriggerTests` UT/IT（forex 拒 30087 / crypto 放行 / 强平 skip / category null 降级） |
| 7 | console V12 tier 权限 + 角色关联 + 菜单 | `3baf6ffc` | V12 seed（`tier:view`/`tier:edit` + 关联 risk-config 角色 + 「杠杆档位配置」菜单） |
| 8 | console AdminTier 透传 + RBAC + 审计 + 错误翻译 | `a6e97941` | `AdminTierEndpointIntegrationTests` 11 IT（WireMock for trading） |
| 9 | console-frontend tier 配置页（展开行 + CRUD + 二次确认/reason + RBAC + 校验） | `be284f6a` | `TierConfigListPage.test.tsx` vitest（tier 页 10） |
| 10 | 三端测试汇总 + it017 CRUD→开仓生效闭环 IT | `e1bd2541` | `it017`（同进程真 DB，admin 改 tier 后开仓风控生效闭环） |
| 11 | R8 文档同步 + R7 收口报告 + 当前开发计划录入（本 commit） | 本 commit | 本报告 + R8 同步清单（§8）+ 计划 §1 条目 |

---

## §4. 测试统计

| 测试 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| trading-core C2 相关 tests | UT+IT | 308 全绿 | tier CRUD / RPC（重叠/CHECK/notFound）/ FxPause 读模型 / RiskControl FX_PAUSED / QuoteDrivenEngine 被动强平 skip / StopOut IT / it017 闭环 |
| console `AdminTierEndpointIntegrationTests` | IT | 11 全绿 | `/admin/trading/tiers` GET/POST/PUT/DELETE 透传 + RBAC（tier:view/tier:edit）+ 审计 + 错误翻译 90930-90932（WireMock for trading-core） |
| console-frontend vitest | 前端 | 74 全绿（tier 页 10） | tier 配置页展开行 / CRUD Modal / 二次确认/reason / RBAC 按钮 / `maxLev×mmRate` 校验 |
| 三件套 | 前端 | — | lint 0（改动文件）/ build 退出 0 |

### admin 改 tier 30s 生效证据链（master §8.3 C 验收硬约束）

| 环节 | 证据 |
|---|---|
| 写后立即失效本进程缓存 | Task 4 `LeverageTierResolver.invalidate` IT：改后 resolve 立即反映新值 |
| CRUD→开仓风控生效闭环 | Task 10 `it017`（同进程真 DB：改 tier 后开仓按新档位校验生效） |
| console→trading 透传 | Task 8 console 透传 IT 11 |
| 30s 兜底语义 | `LeverageTierResolver` 30s 惰性 TTL（多实例其余节点兜底对齐） |
| 真三端跨服务 HTTP E2E | console→gateway→trading **= WSL 受限手动项**，靠上述三段语义拼接（见 §5） |

### FX_PAUSED 按类目行为覆盖（master §6.5）

| 场景 | 行为 | 证据 |
|---|---|---|
| forex（allow_open=false） | 开仓拒 30087 GLOBAL_PAUSE_ACTIVE | Task 6 UT/IT |
| crypto（allow_open=true） | 开仓放行（继续品种级风控） | Task 6 UT/IT |
| forex/metal（allow_liquidation=false） | 被动强平 skip（continue + WARN） | Task 6 IT |
| category==null / behavior 缺失（开仓） | **保守全拒**（回退 BBOOK_RISK_GLOBAL_PAUSE） | Task 6 UT |
| category==null / behavior 缺失（强平） | **继续强平**（防穿仓，与开仓相反方向） | Task 6 UT |

---

## §5. 已知不阻断项 / 边界

> 部署阻断项（生产/演示库 schema 漂移，沿 14B/C1 叠加）见 **§0**，为最高优先级，单独提级，不在本节"不阻断"列表内。

1. **【部署阻断项，提级到 §0】** 14B V28/V29 `USE` 污染遗留 + C1 V30-V32 叠加；C2 新增仅 console V12（干净）、market/trading 无新 migration。被污染 trading 库部署前仍须先 repair V28/V29 再 migrate（详见 §0）。
2. **真三端跨服务 HTTP E2E（console→gateway→trading）= WSL 受限手动项**：证据靠 console 透传 IT + trading 同进程 CRUD→开仓闭环 IT（it017）+ resolver 30s 语义三段拼接，未跑真网关链路。
3. **`SymbolSpec` category 过渡期 null**：market 重发布前旧 Redis 快照无 category；FX_PAUSED 开仓缺信息保守全拒 / 强平继续；market 重发布后补齐（同 STAGE-14B currency 过渡口径）。
4. **tier 缓存多实例**：单进程 invalidate + 30s 惰性 TTL 兜底（master §8.3 容许）；跨实例事件驱动失效（`tier.changed`）未引入。
5. **`tier:edit` 注册为高危**：审计 risk_level + 前端二次确认；写操作 `OperationAuditAspect` 写 `t_admin_operation_log`。
6. **DELETE 不带 reason 入参**：前端送 reason 仅用于二次确认提示，后端软删未接收，审计有 target_id 无 reason（如需 reason 落 DELETE 审计须后端加参）。
7. **分页按扁平档位条数**：同 symbol 多档位可能跨页（与 risk 页同口径）。
8. **浏览器 QA WSL chromium 受限**：vitest + build 覆盖页面行为，未跑真机浏览器截图。
9. **icon `SlidersOutlined`** 已补前端 ICON_MAP。
10. **pre-existing flake `TradingKafkaWalletDepositIntegrationTests`**（Kafka consumer group 异步计数竞态，非本阶段，建议后续单独修，不阻断本阶段）。
11. **D 阶段前置注意（沿 C1 Finding 2 / latent 耦合）**：ISOLATED 单仓下 MarginLevel-30% 与 liqPrice 差集为空，30% StopOut 真正价值在 CROSS（D）；`closePositionByTrigger` 二次价格校验须在 D 引入实时 MM/CROSS（ML 与 liqPrice 解耦）前放开，否则 StopOut-without-liqPrice 被静默吞掉。
12. **后续阶段**（独立 plan）：D（CROSS/ISOLATED 切换 + 实时 MM + isolated_margin + supplement-margin）/ E（三端 UI + 多币聚合 + WS break 切换）。

---

## §6. tier CRUD 校验证据

| 验证项 | 证据 |
|---|---|
| 区间重叠拒绝 90932 | 同 `symbol + group_code` 档位区间 `[notional_lower, notional_upper)` 重叠 → 90932（Task 4 IT） |
| CHECK 违例 90931 | `max_leverage × mm_rate ≤ 1.0` DB CHECK + 区间非法 → 90931（Task 4 IT，前端 `maxLev×mmRate` 同款前置校验 Task 9） |
| not found 90930 | PUT/DELETE 目标档位不存在 → 90930（Task 4 IT） |
| 软删 enabled=0 | DELETE 软删，列表含软删行供 admin 查看（Task 3/4） |
| 写后缓存失效 | `LeverageTierResolver.invalidate` 改后 resolve 立即反映（Task 4 IT）+ it017 闭环（Task 10） |

---

## §7. master §8.3 C 阶段验收硬约束对照（C2 补齐部分逐条标注）

| 硬约束 | 状态 | 验证证据 |
|---|---|---|
| admin 改 tier 在 30s 内生效 | ✅（真跨服务 E2E 手动） | Task 4 invalidate IT + Task 10 it017 闭环（同进程真 DB）+ Task 8 console 透传 IT + 30s TTL 兜底 |
| tier CRUD UI | ✅ | Task 9 console-frontend tier 配置页（展开行 CRUD + RBAC + 二次确认）+ Task 8 透传 |
| 全 10 模板边界 tier 切换 IT 各覆盖 1 次 | ✅（C1 已覆盖） | C1 IT-003（T4 空映射据实记录） |
| 200x 大单落高档拒单 30070 + 错误清晰 | ✅（C1 已覆盖） | C1 IT-004 |
| StopOut 整链 < 500ms | ✅（C1 已覆盖） | C1 IT-008 实测 74ms |
| CHECK max_lev × mm_rate ≤ 1.0 DB 层生效 | ✅（C1 已覆盖） | C1 IT-001 |
| 全 symbol tier seed 抽查 + 自动校验 | ✅（C1 已覆盖） | C1 IT-002 + Task 1/11（6311 行 / 1572 symbol） |

> 补充：FX_PAUSED 8 类目 × 3 开关完整验收属 master §8.3 **D 阶段**；C2 接线了开仓(allow_open)/被动强平(allow_liquidation)两个开关并验证降级方向，完整 8×3 矩阵随 D 收口。

#### 通用硬约束

| 通用硬约束 | 状态 | 备注 |
|---|---|---|
| mvn compile + test-compile BUILD SUCCESS | ✅ | market/trading/console 各 task 实施门禁覆盖 |
| 涉及服务 mvn test 全过 | ✅ | trading C2 相关 308 全绿 + console AdminTier 透传 IT 11 |
| 前端 npm 三件套 | ✅ | vitest 74（tier 页 10）/ lint 0（改动文件）/ build 退出 0 |
| 文档同步完成 | ✅ | R8 同步（本 commit，见 §8） |
| Git 回滚点 push 到 main | ⏳ | 本地 main 领先 origin/main（按约定由控制者统一执行） |
| 当前开发计划 §1 阶段收口条目录入 | ✅ | 本 commit（STAGE-14C 整体完成，下一步 D） |

---

## §8. 文档同步清单（R8）

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/api/FalconX统一接口文档.md` | ✅ | §3.30 console tier CRUD REST + trading internal RPC + RBAC + 错误码 90930-90932；§3.6 开仓拒单补 30087 FX_PAUSED 按类目 |
| `docs/api/管理端接口规范.md` | ✅ | §18 tier 配置接口（console 端点 + internal RPC + 错误码 + RBAC/菜单/审计 V12 + 测试结论）+ §16 占位表登记 |
| `docs/domain/状态机规范.md` | ✅ | §6.5 FX_PAUSED/GLOBAL_PAUSE 按类目行为（allow_open/close/liquidation + 降级方向 + 过渡期 null） |
| `docs/event/Kafka事件规范.md` | ✅ | §11.2 确认 `tier.changed` C2 仍未引入（缓存 invalidate + 30s TTL，无 Kafka）+ 多实例口径 |
| `docs/process/BBook一期完成执行路径.md` | ✅ | §15D STAGE-14C2 收口条目（三端范围 + 30s 生效证据链 + commits + 部署阻断 + 边界） |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14C2 收口条目 + STAGE-14C（C1+C2）整体完成标注 + 下一步指向 D/E |

> 数据库设计：C2 仅 console V12 权限/菜单 seed（作用于 `falconx_console`）；`docs/database/falconx一期数据库设计.md` 为业务库（identity/market/trading/wallet）设计，不含 console RBAC 表，故 C2 无该文档增补点（与既有阶段口径一致）。

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**C2（console tier 配置三端 UI + FX_PAUSED 按品种类目行为）在 market / trading-core / console-service 后端 + console-frontend 管理端前端代码侧已完整实现，并通过 trading C2 相关 308 tests 全绿（tier CRUD/RPC/FxPause/RiskControl/QuoteDrivenEngine/StopOut + it017 闭环）+ console AdminTier 透传 IT 11 + 前端 vitest 74（tier 页 10）+ 三件套（lint 0 改动文件 / build 退出 0）验证。补齐了 master §8.3 C 阶段「admin 改 tier 30s 生效」（Task 4 invalidate + Task 10 it017 闭环 + console 透传 IT + 30s TTL 兜底）与「tier CRUD UI」（Task 9 配置页 + Task 8 透传）两条此前 C1 推迟的验收硬约束。FX_PAUSED 按类目接线了 allow_open（开仓 30087）/ allow_liquidation（被动强平 skip）两个开关并验证了缺信息时的双向降级口径。三端交付完整。**

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项（部署前必须处理，沿 14B/C1 叠加）**：生产/演示库 `falconx_trading` 因 14B root bug 期间 `USE` 污染存在 V28/V29 schema 漂移，下次 `flyway migrate` 将先撞 `Duplicate column` 阻断启动；C2 本身只新增干净的 console V12、market/trading 无新 migration，不引入新阻断但也不解除既有阻断。**部署前必须先按 §0 修复 14B V28/V29 漂移再 migrate（trading V30-V32 + console V12），禁止裸跑 migrate。**
- **已验证范围边界**：tier 三端 CRUD + RBAC + 审计 + 缓存失效 + FX_PAUSED 按类目开仓/强平在代码侧完整且通过测试；干净库全新部署无 schema 阻断；真三端跨服务 HTTP E2E（console→gateway→trading）为 WSL 受限手动项，由 console 透传 IT + trading 同进程闭环 IT + 30s 语义三段拼接代证。
- **范围边界（非阻断，按 §5）**：CROSS/ISOLATED 切换 + 实时 MM + isolated_margin + supplement-margin + FX_PAUSED 8×3 完整验收 + 放开 `closePositionByTrigger` 二次校验留 D；三端 UI + 多币聚合 + WS break 切换留 E；SymbolSpec category 过渡期 null（market 重发布补齐）；DELETE 审计无 reason；多实例 tier 缓存靠 30s TTL 兜底。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md) 末条，当前系统不得表述为"生产可用"或"可安全对外公测"。

**使用说明（按 §8.1.3）：**

- **使用入口**：运营经 console-frontend「交易监控 → 杠杆档位配置」页（权限 `tier:view` 可见、`tier:edit` 可写）按 `symbol + group_code` 维护多档位；写操作走 console `/admin/trading/tiers` POST/PUT/DELETE（高危 + 二次确认/reason）→ trading-core internal RPC `/internal/v1/trading/console/tier` → 写 `t_symbol_leverage_tier` 并失效 `LeverageTierResolver` 缓存。FX_PAUSED 按类目行为由活跃 `GLOBAL_PAUSE` 触发，下单/被动强平按 `SymbolSpec.category` 查 `t_fx_pause_behavior`。
- **前置条件**：目标库已按 §0 完成 14B V28/V29 漂移修复 + trading V30-V32 + console V12 migrate；market-service 重发布以填充 `SymbolSpec.category`（FX_PAUSED 精确生效前提，过渡期保守降级）；console-service 加载 V12 权限/菜单；gateway internal token 配置就绪。
- **执行步骤**：按 §0 修复目标库 → migrate（trading V30-V32 + console V12）→ 重发布 market（填 category）→ 启动 trading-core（加载 tier/risk_config/fx_pause 缓存）→ 启动 console-service / console-frontend → 运营进 tier 配置页 CRUD → 改后 30s 内（本进程 invalidate 即时 / 多实例 TTL 兜底）开仓按新档位生效；活跃 GLOBAL_PAUSE 下按类目控制开仓/被动强平。
- **预期结果**：tier CRUD 区间重叠拒 90932 / CHECK 违例拒 90931 / not found 拒 90930；admin 改 tier 后开仓按新 maxLeverage/mmRate 校验生效；写操作落 `t_admin_operation_log` 审计；FX_PAUSED 下 forex 开仓拒 30087、crypto 放行、forex/metal 被动强平 skip。
- **已知限制 / 禁用场景**：真三端跨服务 HTTP E2E 为手动项（代码侧已三段证据）；SymbolSpec category 过渡期 null 时 FX_PAUSED 开仓保守全拒 / 强平继续；DELETE 审计无 reason；多实例 tier 缓存靠 30s TTL 兜底（无事件驱动失效）；CROSS/实时 MM/8×3 完整 FX_PAUSED 验收/放开二次校验留 D；禁止对未按 §0 修复的污染库直接 migrate。
