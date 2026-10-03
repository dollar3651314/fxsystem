# STAGE-14C2 console tier 配置三端 UI + FX_PAUSED 按类目行为 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development，每 task 实施→spec 评审→代码质量评审→收口。
>
> **FalconX R 角色映射**：R2 契约（Task 1-2）+ R4 业务后端 trading（Task 3-6）+ R9 管理端后端 console（Task 7-8）+ R10 管理端前端（Task 9）+ R6 测试（Task 10）+ R7/R8（Task 11）。这是**三端业务功能**（含 console-frontend），完成判定按 AI工作模式 §5.1 三端硬约束。

**Goal:** 管理员可通过 console 配置杠杆/MM tier（CRUD）；trading-core 在 FX_PAUSED/GLOBAL_PAUSE 下按品种类目（t_fx_pause_behavior）控制开仓/被动强平；补齐 C1 延后的 Task 10 与 master §9 C 阶段的 tier CRUD UI。

**Architecture:** SymbolSpec 加 category（market-contract，market 生产者填充，trading 消费）→ trading-core 扩 tier CRUD internal RPC + FxPauseBehavior 读模型 + FX_PAUSED 按类目接线 → console-service 透传 RPC（@RequiresPermission tier:view/tier:edit + 审计 + V12 权限/菜单）→ console-frontend tier 配置页（复用 RiskConfig 页模式 + AntD 展开行多档位）。tier 改动经 LeverageTierResolver 30s 惰性缓存生效（满足 master §8.3「admin 改 tier 30s 生效」）。

**Tech Stack:** Spring Boot 4.0.5 / MyBatis / Kafka / Redisson / Vite+React+TS+AntD+React Query。

**前置阅读：**
- master §7.4（tier/fx REST + internal RPC 接口签名）/§7.3（错误码 90930-90932 ADMIN_TIER_* / 30087 GLOBAL_PAUSE_ACTIVE）/§4.1（console V14 grant tier:view/tier:edit，本计划实查 console 实际版本顺延）/§6.5（FX_PAUSED 状态机：allow_open/allow_close/allow_liquidation 按类目）/§3.4.2（t_fx_pause_behavior）。
- C1 R7 报告 `docs/test/STAGE-14C1-MARGIN-LEVEL-TIER-R7-verification-report.md`（tier 表/resolver/MarginLevel 已就位；Task 10 延后边界）。
- AGENTS.md §3.13（前端/全栈）/§3.2（边界）/§3.8（跨服务兼容）；console RBAC/审计见各范例文件。

**关键现状（code-explorer 已实查，据此实施）：**
- **console 透传模式**：`AdminRiskController`(@RequiresPermission)→`AdminRiskApplicationService`(写前 `AuditSnapshotHolder.set(before,after)`)→`InternalRpcClient.put/post/delete`(自动注 X-Admin-User-Id/X-Trace-Id)→trading-core internal RPC。RBAC：`@RequiresPermission` + `PermissionGuardAspect`；权限点由 `AdminPermissionDictionaryInitializer` 启动扫描自动入库（**无需 migration seed 权限点本身**），但**角色-权限关联 + t_admin_menu 菜单需 migration**（参考 `V4__seed_risk_admin_permissions.sql`）。审计：`OperationAuditAspect` @AfterReturning 自动写 t_admin_operation_log。console Flyway 最新 **V11** → 本计划用 **V12**（实查复核）。
- **trading-core internal RPC**：`AdminInternalTradingRiskController`(@RequestMapping `/internal/v1/trading/console`)；鉴权 `TradingInternalApiTokenFilter`（X-Internal-Token + X-Admin-User-Id）；@Transactional 在 ApplicationService。写模式参考 `MybatisTradingRiskMarketConfigRepository`。
- **tier 写基础**：C1 仅 `SymbolLeverageTierRepository.findTiers`（只读）；需扩 insert/update/delete/selectPage。`DefaultLeverageTierResolver` 30s 惰性缓存无主动失效（**C2 接受 30s 延迟**）。
- **SymbolSpec**：`falconx-market-contract` record 现 11 字段（含 14B 加的 baseCurrency/quoteCurrency）。加 category 同口径：`MarketSymbolAdminRecord` 已有 `category` 字段；`RedisMarketSymbolSpecRepository.toSpec` 加第 12 参；**6 处 trading-core 测试构造点 + 1 生产构造点**同步（漏则编译报错，break 面可控）。Redis 过渡期旧快照无 category → Jackson null → 降级。market 无需新 migration（category 在 t_symbol）。
- **FX_PAUSED**：`t_fx_pause_behavior`（V31 seed 8 类目，forex/metal allow_open=0/allow_liquidation=0）；**Java 侧 Entity/Mapper/Repository 全空白**（Task 5 从头建）。GLOBAL_PAUSE 现一刀切（30009 BBOOK_RISK_GLOBAL_PAUSE）。接线点：开仓 `DefaultTradingRiskService.evaluateRiskControlRejection`（约 L347-362）、强平 `QuoteDrivenEngine.processTick`（约 L187 auto-liquidate 开关后）。category 编码 1-8（1crypto/2forex/3metal/4index/5energy/6stock/7etf/8other）。
- **前端**：`features/risk/RiskConfigListPage.tsx` + `RiskConfigFormModal.tsx` + `riskApi.ts` + `types.ts` 是最贴近范例；tier 多档位用 AntD Table `expandable` 展开行。RBAC 按钮 `useHasPermission`/`RequiresPermission`。路由 `App.tsx` lazy + 菜单 DB 动态（`/admin/me/menus`）。API client `lib/api/apiClient.ts` adminApi。无 E2E 框架（vitest only）。
- **测试**：console IT = MockMvc + @MockitoBean(InternalRpcClient) + 真 MySQL falconx_console_it + Redis（范例 `AdminWithdrawEndpointIntegrationTests`）；trading IT 真 DB（范例 `TradingLeverageTierStopOutIntegrationTests`）；frontend vitest mock adminApi。
- **Flyway 不写 USE**（14B root bug 教训），所有 migration 一律 schema 由连接绑定。

---

## File Structure（概要）
- market-contract：`SymbolSpec.java`（加 category）
- market-service：`RedisMarketSymbolSpecRepository.toSpec`（+category）+ toSpec 调用点
- trading-core：`error/TradingErrorCode.java`（+90930-90932/30087）；`SymbolLeverageTierMapper(.xml)/Repository`（+写）；`controller/AdminInternalTradingTierController.java`（新）+ `application/TradingTierAdminApplicationService.java`（新）；`entity/FxPauseBehavior.java`+`repository/FxPauseBehaviorRepository(+Mybatis)`+`mapper`（新）；`DefaultTradingRiskService`/`QuoteDrivenEngine`（FX_PAUSED 按类目接线）；6 测试构造点
- console-service：`controller/AdminTierController.java`+`tier/AdminTierApplicationService.java`（新）；`error/AdminErrorCode`（+tier 翻译）；`db/migration/V12__seed_tier_permissions_and_menu.sql`
- console-frontend：`features/tier/`（TierConfigListPage / TierFormModal / TierDeleteModal / tierApi.ts / types.ts）+ `App.tsx` 路由

---

## Tasks

### Task 1: SymbolSpec 加 category（跨服务契约）
**Files:** `falconx-market-contract/.../SymbolSpec.java`；market `RedisMarketSymbolSpecRepository.toSpec` + 调用点；6 trading-core 测试构造点 + 兼容性测试
- record 末尾追加 `Integer category`（javadoc 注明过渡期 null）。
- market `toSpec` 末尾加 `source != null ? source.category() : null`（source=MarketSymbolAdminRecord 已有 category()）。
- grep `new SymbolSpec(` 全仓（排除 worktrees）补全所有构造点（含全限定名写法），测试给合理 category（crypto symbol→1、FX→2 等）。
- 向后兼容：补/扩 `SymbolSpecBackwardCompatDeserializationTests` 验证旧 JSON 无 category→null 不抛。
- 验证：`mvn -pl falconx-market-contract,falconx-market-service,falconx-trading-core-service -am test-compile -q` + market toSpec UT + trading 既有 UT 无回归。
- **Commit:** `feat(market): STAGE-14C2 Task 1 SymbolSpec 加 category（market 生产者填充 + trading 消费就绪）+ 向后兼容 + 构造点同步`

### Task 2: 错误码追加（trading + console 翻译）
**Files:** `trading/error/TradingErrorCode.java` + console `AdminErrorCode`
- trading 加 `ADMIN_TIER_NOT_FOUND(90930)` / `ADMIN_TIER_VALIDATION_FAILED(90931)` / `ADMIN_TIER_OVERLAP(90932)` / `GLOBAL_PAUSE_ACTIVE(30087)`（master §7.3）。
- console AdminErrorCode 加对应翻译（对齐既有 translateRiskError 模式）。
- 验证：test-compile。
- **Commit:** `feat(trading): STAGE-14C2 Task 2 错误码 90930-90932 ADMIN_TIER_* + 30087 GLOBAL_PAUSE_ACTIVE + console 翻译`

### Task 3: trading-core tier CRUD Mapper/Repository 写扩展 + 校验
**Files:** `SymbolLeverageTierMapper(.java/.xml)` + `SymbolLeverageTierRepository` + `MybatisSymbolLeverageTierRepository` + UT
- Mapper/XML 加 `insert` / `update`（by id）/ `deleteById`（软删 enabled=0 或物理删，按 master §7.4「软删」用 enabled=0）/ `selectPage(symbol?, groupCode?, offset, limit)` + `countBy`。
- Repository 加写方法 + 分页查询。
- **校验逻辑（在 ApplicationService Task 4，这里只提供数据访问）**：CHECK 约束（DB 层 max_lev×mm_rate≤1.0 已有 V30）；档位不重叠 + tier_no 唯一（uk_symbol_group_tier 已有）。
- UT（mock mapper）+ 轻量 IT（真 DB insert/update/delete/page）。
- **Commit:** `feat(trading): STAGE-14C2 Task 3 SymbolLeverageTier CRUD 写扩展（insert/update/软删/分页）+ UT/IT`

### Task 4: trading-core tier CRUD internal RPC + 缓存失效
**Files:** `controller/AdminInternalTradingTierController.java`（新）+ `application/TradingTierAdminApplicationService.java`（新）+ UT/IT
- RPC（master §7.4，挂 `/internal/v1/trading/console`，经 TradingInternalApiTokenFilter）：
  - `GET /tier?symbol=&groupCode=&page=` 列表分页
  - `POST /tier` 新建（校验：notional 区间不与同 symbol+group 现有档重叠、max_lev×mm_rate≤1.0、tier_no 唯一；失败 90931/90932）
  - `PUT /tier/{id}` 编辑（90930 not found）
  - `DELETE /tier/{id}` 软删（enabled=0）
- @Transactional 在 ApplicationService。
- **缓存失效**：写成功后调 `LeverageTierResolver` 的本地 invalidate（新增 `invalidate(symbol, groupCode)` 方法清单机缓存）；多实例下其余实例 30s 惰性过期兜底（接受，master §8.3「30s 生效」）。注释说明。
- UT + IT（真 DB：建/改/删 tier → findTiers 反映 + 缓存失效）。
- **Commit:** `feat(trading): STAGE-14C2 Task 4 tier CRUD internal RPC（/internal/v1/trading/console/tier）+ 区间重叠/CHECK 校验 + 缓存 invalidate + UT/IT`

### Task 5: FxPauseBehavior 读模型 + Repository + 缓存
**Files:** `entity/FxPauseBehavior.java` + `repository/mapper/record/FxPauseBehaviorRecord.java` + `mapper/FxPauseBehaviorMapper(.xml)` + `repository/FxPauseBehaviorRepository(+Mybatis)` + UT
- 读 `t_fx_pause_behavior`（V31 已建，8 行）：`findByCategory(int) → Optional<FxPauseBehavior{category,allowOpen,allowClose,allowLiquidation}>` + `findAll`。
- 缓存：8 行小数据，启动加载或本地 30s TTL（明确 TTL/刷新/降级三要素）。
- UT（mock mapper）+ 轻量 IT（真 DB 查 seed：forex allow_open=0）。
- **Commit:** `feat(trading): STAGE-14C2 Task 5 FxPauseBehavior 读模型/Mapper/Repository + 缓存 + UT/IT`

### Task 6: FX_PAUSED 按类目接线（Task 10）
**Files:** `DefaultTradingRiskService.evaluateRiskControlRejection` + `QuoteDrivenEngine.processTick` + UT/IT
- 开仓：GLOBAL_PAUSE/FX_PAUSED 活跃时，取 `spec.category()` 查 `FxPauseBehaviorRepository`：`allow_open==0` → reject `GLOBAL_PAUSE_ACTIVE`（30087）；`allow_open==1` → 放行。**category 为 null（过渡期）→ 降级 allow_all（不阻断），warn 日志**。保留既有一刀切 GLOBAL_PAUSE 行为作为 category 缺失/behavior 缺失的兜底语义（注释清楚：C2 引入按类目细分，behavior 缺失回退原一刀切）。
- 平仓：master §6.5 allow_close 始终=1，不加限制（注释）。
- 强平：`QuoteDrivenEngine` auto-liquidate 前，若 GLOBAL_PAUSE 活跃且 `allow_liquidation==0`（forex/metal 默认）→ skip 被动强平 + warn（不强平）。
- UT/IT：forex 停开仓（30087）+ crypto 允许 + forex 停被动强平 + category null 降级放行 + behavior 缺失回退。
- **Commit:** `feat(trading): STAGE-14C2 Task 6 FX_PAUSED 按 t_fx_pause_behavior 类目控制开仓(30087)/被动强平（category null 降级 + 缺失回退一刀切）+ UT/IT`

### Task 7: console V12 — tier 权限角色关联 + 菜单
**Files:** `falconx-console-service/.../db/migration/V12__seed_tier_permissions_and_menu.sql`（+docs/sql 如有约定）
- 实查 console Flyway 最新版本顺延（应 V12）。不写 USE。
- `INSERT INTO t_admin_role_permission`（或等价）把 `tier:view`/`tier:edit` 关联到合适角色（参考 V4 risk 权限关联）。权限点本身由 AdminPermissionDictionaryInitializer 自动入库（Task 8 controller 注解后启动生成）。
- `INSERT INTO t_admin_menu` 加 tier 配置菜单行（path `/admin/trading/tier-configs`，绑定 `tier:view`，参考既有菜单行结构）。
- 验证：隔离库 source 验证。
- **Commit:** `feat(console): STAGE-14C2 Task 7 V12 tier:view/tier:edit 角色关联 + tier 配置菜单`

### Task 8: console-service tier 透传 RPC + RBAC + 审计
**Files:** `controller/AdminTierController.java`（新）+ `tier/AdminTierApplicationService.java`（新）+ AdminErrorCode 翻译（Task 2 已加，确认）+ IT
- REST（master §7.4 `/admin/trading/tier/*`）：
  - `GET /admin/trading/tiers`（@RequiresPermission `tier:view`）→ InternalRpcClient.get trading `/internal/v1/trading/console/tier`
  - `POST /admin/trading/tiers`（`tier:edit` 高危）→ post；写前 `AuditSnapshotHolder.set`
  - `PUT /admin/trading/tiers/{id}`（`tier:edit`）→ put
  - `DELETE /admin/trading/tiers/{id}`（`tier:edit`）→ delete
- 错误码透传翻译（90930-90932 → 前端可读消息）。
- IT（MockMvc + @MockitoBean InternalRpcClient + RBAC：无权限 403、有权限透传、审计写入断言）。
- **Commit:** `feat(console): STAGE-14C2 Task 8 AdminTierController tier CRUD 透传 + RBAC tier:view/edit + 审计 + IT`

### Task 9: console-frontend tier 配置页
**Files:** `falconx-console-frontend/src/features/tier/`（TierConfigListPage.tsx / TierFormModal.tsx / TierDeleteModal.tsx / tierApi.ts / types.ts）+ `App.tsx` 路由 + 测试
- 复用 `features/risk/RiskConfigListPage` 模式：AntD Table 列表（按 symbol 分组，**展开行**显示该 symbol 各档位 tier_no/notional 区间/maxLev/mmRate）+ 查询 Form（symbol/groupCode）+ 分页。
- FormModal（create/edit）：symbol/group/tier_no/notional_lower/notional_upper/max_leverage/mm_rate + 高危 reason 确认（参考 RiskConfigFormModal）；前端校验 max_lev×mm_rate≤1.0 即时提示。
- DeleteModal（软删确认 + reason）。
- tierApi.ts（adminApi.get/post/put/delete `/admin/trading/tiers`）+ types.ts。
- RBAC：`tier:edit` 按钮用 `RequiresPermission` 包裹（无权限隐藏）；列表需 `tier:view`。
- 路由 App.tsx lazy + Route `trading/tier-configs`。
- 测试：vitest（mock adminApi）覆盖列表渲染/展开/新建/编辑/删除/校验/无权限隐藏。
- 验证：`npm run test` + `npm run lint` + `npm run build`（在 falconx-console-frontend）+ 桌面/移动浏览器 QA 截图（WSL chromium 限制则记录）。
- **Commit:** `feat(console-frontend): STAGE-14C2 Task 9 tier 配置页（展开行多档位 + CRUD Modal + RBAC 按钮 + 校验）+ vitest`

### Task 10: 三端 IT + E2E + vitest 汇总
**Files:** trading IT + console IT + frontend vitest + 至少 1 条三端 E2E（admin 改 tier → trading-core 生效）
- trading IT：tier CRUD RPC（建/改/软删/分页 + 区间重叠拒 90932 + CHECK 90931）；FX_PAUSED 按类目（forex 停开仓 30087 / 停被动强平 / crypto 允许 / category null 降级）。
- console IT：tier 透传 + RBAC（tier:view/edit 权限校验）+ 审计写入。
- frontend vitest：tier 页交互。
- E2E（master §8.3 C「admin 改 tier 30s 生效」）：console 改 tier → trading-core findTiers/开仓校验反映新 tier（缓存 invalidate 或 ≤30s）。
- **Commit:** `test(STAGE-14C2): tier CRUD 三端 + FX_PAUSED 按类目 IT/vitest + admin 改 tier 生效 E2E`

### Task 11: R8 文档 + R7 收口 + 计划录入 + push
**Files:** docs（接口/RBAC/状态机/Kafka 若有/BBook）+ `docs/test/STAGE-14C2-...-R7-verification-report.md` + `docs/setup/当前开发计划.md`
- R8：`FalconX统一接口文档`（tier CRUD REST + 错误码）；`管理端接口规范`（若有）；`状态机规范`（FX_PAUSED 按类目补充 §6.5）；`数据库设计`（确认 fx_pause/tier 已在 C1 文档，补 console 权限/菜单）；BBook §15C2。
- R7 收口报告（三端验收：master §8.3 C「admin 改 tier 30s 生效」「tier CRUD UI」+ 前端 npm 三件套 + QA 截图）。**沿用 14B/14C1 部署阻断项提示**（falconx_trading schema 漂移 + 本阶段 console V12/market 无新 migration）。
- 计划 §1 录入 C2 收口 + 下一步指向 D。
- push origin main（控制者统一）。
- **Commit:** `docs(R7+R8): STAGE-14C2 收口报告 + 文档同步 + 计划录入`

---

## Self-Review
1. **Spec 覆盖**：master §7.4（tier/fx REST+RPC）→ Task 4/8；§7.3（错误码）→ Task 2；§4.1（console grant）→ Task 7；§6.5（FX_PAUSED 按类目）→ Task 5/6；§8.3 C「admin 改 tier 30s 生效」「tier CRUD UI」→ Task 9/10；Task 10（C1 延后的 FX_PAUSED）→ Task 5/6 ✅。CROSS/实时MM/mode 切换 = D，不在 C2。
2. **三端硬约束**（AI工作模式 §5.1）：R2 契约（Task1-2）+ R9 后端（Task7-8）+ R10 前端（Task9）+ E2E（Task10）+ 文档（Task11）齐全。
3. **类型一致**：SymbolSpec.category（Int）、FxPauseBehavior{category,allowOpen,allowClose,allowLiquidation}、tier CRUD DTO 在 Task1/5/6/4/8/9 一致。
4. **break 面**：SymbolSpec 加 category（6 构造点，Task1）；tier CRUD 不破坏 C1 只读路径。

## 实施风险预警
- **SymbolSpec category null 降级**：过渡期旧 Redis 快照无 category → FX_PAUSED 按类目降级 allow_all（不阻断），warn；market 重发布后补齐（同 14B currency）。
- **tier 缓存多实例**：单机 invalidate + 30s 惰性兜底，多实例最多 30s 生效（接受，master §8.3 容许）。
- **前端 WSL QA**：chromium 系统依赖限制时 QA 截图记录为已知（同既有阶段）。
- **Flyway 不写 USE**；console V12 实查版本顺延。
- **审计/RBAC**：tier:edit 高危需 AuditSnapshotHolder.set + reason；权限点自动入库但角色关联+菜单需 V12。

## 下一步（C2 后）
- **STAGE-14D**：CROSS/ISOLATED 用户级切换 + 切换闸门 + 5min 冷静期 + CROSS 强平排序 + 实时 MM（放开 closePositionByTrigger 二次校验，C1 latent 耦合）+ isolated_margin（V33+）+ supplement-margin。
- **STAGE-14E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL + WebSocket break 字段同步切换）+ admin 多币聚合。

— END —
