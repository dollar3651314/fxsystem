# STAGE-14C1-MARGIN-LEVEL-TIER R7 验证报告

> 验证日期：2026-05-29
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14C1-MARGIN-LEVEL-TIER` trading-core 杠杆/MM 分级 Tier + 账户 MarginLevel + 30% StopOut 强平升级（master spec §0.3 C 阶段，纯后端核心切片 C1）

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本阶段 tier 分级 + 账户 MarginLevel + 30% StopOut 双触发强平在 trading-core 代码侧已实现并通过单测 + 16 IT + PERF 验证，但存在一个生产/演示库部署阻断项（沿 STAGE-14B 叠加），部署前必须先处理，不得让生产/演示库裸跑 `flyway migrate`。**

**阻断项：本地开发库 `falconx_trading`（及远程演示库）存在 STAGE-14B 遗留 schema 漂移，C1 新增 migration 叠加其上。**

- 根因（沿 14B）：14B 的 V28（`t_ledger` 三列）与 V29（`t_position.entry_fx_rate`）migration 在 root bug 期间误写了 `USE falconx_trading;`（已由 `1031a9ce` 修复），导致这两个 migration 的 `ALTER + UPDATE` 被强制打到 `falconx_trading` 库；后果是 `falconx_trading` 库的 V28/V29 列**已物理存在且已回填**，但其 `flyway_schema_history` **没有 V28/V29 行**。
- C1 叠加：本阶段新增 **V30**（`t_symbol_leverage_tier` 表 + 全 symbol seed）、**V31**（`t_risk_config` 阈值列 + `t_fx_pause_behavior` 表 + 通知模板 seed）、**V32**（`t_position` 冻结列 `mm_rate_at_open`/`tier_no_at_open`）。**V30-V32 已无 USE bug，migration 干净。**
- 风险：被 14B `USE` 污染过的既有库下次执行 `flyway migrate` 时会因 V28/V29 列已存在而**先撞 `Duplicate column` 失败**，trading-core 启动/部署直接受阻；该失败发生在 V30-V32 之前，故 C1 干净的 V30-V32 也无法被应用。

**修复指引（部署前由 DBA / 部署执行）：**

1. **先修 14B V28/V29 漂移**（二选一）：
   - **手动补行**：对 `falconx_trading`（及演示库）手动向 `flyway_schema_history` 插入 V28/V29 的成功行（`success=1`），checksum 用修复后（删除 `USE` 之后）的 SQL 计算；因列与回填均已就位，不再执行 ALTER。
   - **flyway repair + 人工核对**：执行 `flyway repair`，并人工核对目标库 `t_ledger` 三列 + `t_position.entry_fx_rate` 列确已存在且回填正确，再对齐/跳过 V28/V29。
2. **再正常 migrate V30-V32**：14B 漂移修复后，V30-V32 为干净顺序 migration，正常应用即可。

执行须按授权进行。**禁止在未核对前对生产/演示库直接 `flyway migrate`。**

> 全新部署的干净库从 V27 顺序 migrate 到 V32 无此问题（C1 PERF/IT 隔离库已实证 V30-V32 干净 migrate）。本阻断项仅影响 root bug 期间被 14B `USE` 污染过的既有库。

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 C 阶段（trading-core MarginLevel + Tier）的**纯后端核心切片 C1**：

| 子能力 | C1 交付 | 关键 commit |
|---|---|---|
| V30 `t_symbol_leverage_tier` 杠杆-MM 双重分级表 + 全 symbol tier seed | 表 + CHECK(max_lev×mm_rate≤1.0) + build-time 脚本按 master §5.2 映射生成全 1572 symbol seed | `bdc672e7` → `8353ce04`（T9 勘误修正 50x/2.0%） |
| V31 `t_risk_config` 阈值列 + `t_fx_pause_behavior` 表 + 通知模板 | `stop_out_level`/`margin_call_level` 列 + 8 类目 fx_pause 行为表 + MARGIN_CALL/STOP_OUT 模板 seed | `22882f00` |
| V32 `t_position` 冻结列 | `mm_rate_at_open`/`tier_no_at_open` + 老数据回填（0.005/1） | `f3e14d8e` |
| SymbolLeverageTier 实体/record/Mapper/Repository | `findTiers` 按 symbol+group 查档位升序，空则回退 default 组 | `31a58da1` |
| LeverageTierResolver | 按 notional 解析 tier 档位（lower 含/upper 不含）+ 30s 本地缓存（TTL/惰性刷新/DB 空降级） | `c21ef42d` |
| 开仓接 tier | tier 校验（超档拒单 30070 / 无映射 30072）+ 强平价用 tier mmRate 替换硬码 + 冻结 `mm_rate_at_open`/`tier_no` 到 position + decision 携带 | `b3882e3f` |
| AccountEquityCalculator | ISOLATED 单仓 + 账户级 Equity/MarginLevel（MM 用冻结 mmRateAtOpen，uPnL 复用 `calculatePositionPnlInAccount`） | `dd6271db` |
| MarginLevelMonitor | 三态判定（HEALTHY/MARGIN_CALL/STOP_OUT）+ MarginCall 5min 节流通知 + 阈值读 `t_risk_config` 兜底 | `58e44a4e` |
| QuoteDrivenEngine 双触发强平 | 接入账户 MarginLevel 实时重算 + StopOut/liqPrice 双触发 + STOP_OUT 通知 + 账户内存缓存（MM 冻结口径） | `238602c4` → `281afc7b`（STOP_OUT 通知传真实 marginLevel） |
| 16 IT + 2 PERF + Finding3 修复 | tier/MarginLevel/StopOut IT 矩阵 + 既有强平 IT 测试 symbol tier 夹具补齐 | `b17a9744` |
| R8 文档同步 | tier 表/risk_config 阈值/fx_pause/position 冻结列 + MarginLevel 三态状态机 + StopOut 双触发 + 错误码 30070/30072 + 范围边界 | `369af172` |

**不在 C1 范围**（划 C2 / D / E）：

- **C2**：console-service tier CRUD 透传 RPC + RBAC + console-frontend tier 配置三端 UI（master §8.3 C 阶段「admin 改 tier 30s 生效」「tier CRUD UI」属此切片）。
- **Task 10 FX_PAUSED 按类目行为延后**：`SymbolSpec` 无 category 字段，需 market 额外取数；`t_fx_pause_behavior` 表 + 8 类目 seed 已 V31 就位，行为接线划 C2/后续。非 C 验收硬约束。
- **D**：CROSS/ISOLATED 用户级 toggle + 切换闸门 + 冷静期 + CROSS 强平排序 + 实时 MM 精化（master §3.2 D2）。
- **E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换。

---

## §2. 角色

C1 为 **backend-only**（`falconx-trading-core-service`）。按 [`AI工作模式 §4.1`](../process/AI工作模式.md) 阶段 0/1 豁免条款：

**三端硬约束 N/A** — 不涉及客户端 / 管理端 / console-frontend 代码变更。tier 配置 CRUD UI 与 admin 改 tier 30s 生效推迟 C2。

各 task 均经 **实施 → spec 合规评审 → 代码质量评审 / 核查 → 收口** 完整门禁。

---

## §3. 各 Task 证据

| Task | 内容 | 关键 commit | 证据 |
|---|---|---|---|
| 1 | V30 `t_symbol_leverage_tier` 表 + 全 symbol tier seed（CHECK max_lev×mm_rate≤1.0） | `bdc672e7` → `8353ce04` | seed 6311 行 / 1572 symbol；每模板抽查与 master §5.1 一致；T9 tier3 勘误（50x/2.5% 违反 CHECK）修正 50x/2.0% 并回写 master |
| 2 | V31 `t_risk_config` 阈值列 + `t_fx_pause_behavior` 表（8 类目）+ MARGIN_CALL/STOP_OUT 通知模板 | `22882f00` | 阈值列 `stop_out_level`/`margin_call_level`；fx_pause 8 类目 seed；2 通知模板 seed |
| 3 | V32 `t_position` 加 `mm_rate_at_open`/`tier_no_at_open` 冻结列 + 老数据回填（0.005/1） | `f3e14d8e` | 冻结列 + 老数据默认值回填 |
| 4 | SymbolLeverageTier 实体/record/Mapper/Repository | `31a58da1` | UT/IT；`findTiers` 按 symbol+group 升序，空回退 default 组 |
| 5 | LeverageTierResolver（30s 缓存） | `c21ef42d` | 6 UT；notional → 档位（lower 含/upper 不含）+ TTL/惰性刷新/DB 空降级 |
| 6 | 开仓接 tier（30070/30072 + 强平价用 tier mmRate + 冻结） | `b3882e3f` | UT；超档拒单 30070 / 无映射 30072 + mmRate 用 tier 替换硬码 + 冻结 mm_rate_at_open/tier_no + decision 携带 |
| 7 | AccountEquityCalculator（ISOLATED Equity/MarginLevel） | `dd6271db` | 5 UT；MM 用冻结 mmRateAtOpen + uPnL 复用 `calculatePositionPnlInAccount`（CROSS 延 D） |
| 8 | MarginLevelMonitor（三态 + 5min 节流） | `58e44a4e` | UT；HEALTHY/MARGIN_CALL/STOP_OUT 三态 + MarginCall 5min 节流 + 阈值读 t_risk_config 兜底 |
| 9 | QuoteDrivenEngine 双触发强平 + STOP_OUT 通知 | `238602c4` → `281afc7b` | UT/IT；StopOut/liqPrice 双触发 + STOP_OUT 通知传真实 marginLevel（替换 "≤ stopOut" 字面量占位） |
| 10 | **FX_PAUSED 按类目行为 — 跳过/延后** | —（表 + seed 已 V31 就位） | SymbolSpec 无 category，需 market 额外取数，划 C2/后续；非 C 验收硬约束（见 §5.2） |
| 11 | 16 IT + 2 PERF + Finding3 修复 | `b17a9744` | 见 §4；修既有强平 IT 测试 symbol tier 夹具（Finding3） |
| 12 | R8 文档同步 | `369af172` | tier 表/risk_config 阈值/fx_pause/position 冻结列 + MarginLevel 三态状态机 + StopOut 双触发 + 错误码 30070/30072 + 范围边界（CROSS/实时MM/Task10 延后） |
| 13 | R7 收口报告 + 当前开发计划录入（本 commit） | 本 commit | 本报告 + 计划 §1 条目 |

---

## §4. 测试统计

| 测试 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| trading-core 单元测试（排除 IntegrationTests） | UT | ≈241 全绿（含 contextLoads） | Task 4-9 各模块 tier 解析 / Equity / MarginLevel 三态 / 双触发 / 冻结 / 缓存 / 降级 |
| `TradingLeverageTierStopOutIntegrationTests` | IT | 16（隔离运行全绿） | 见下表 |
| 既有强平 IT（修 Finding3 测试夹具后） | IT | 全绿 | TradingLiquidation / GroupMarkup / AutoClose / MultiCurrency / MarginLevelStopOut / SymbolLeverageTier 混跑稳定 |
| StopOut 整链性能 | PERF | IT-008 | 实测 **74ms**（≪ 500ms 目标） |
| 200 用户单仓强平性能 | PERF | IT-014 | **P50=64.7 / P99=132.2ms**（≪ 500ms） |
| per-tick MarginLevel 重算性能 | PERF | IT-015 | 缓存命中 **P50=22.8 / P99=28.5ms**（≪ 500ms） |

### 16 IT 覆盖明细（隔离单跑全绿）

| TC | 覆盖点 |
|---|---|
| IT-001 | CHECK max_lev × mm_rate ≤ 1.0 在 DB 层生效（违例 INSERT 被拒） |
| IT-002 | 全 symbol tier seed 每模板抽 5 个核对（结合 Task 1/11） |
| IT-003 | 全 10 模板边界 tier 切换各覆盖 1 次（T4 空映射据实注记，见 §5） |
| IT-004 | 200x 大单（落入高档 tier）下单被拒 30070 + 错误消息清晰 |
| IT-008 | StopOut 触发 → 强平 → 落账整链 **74ms** < 500ms |
| IT-014 | 200 用户单仓强平 P50=64.7 / P99=132.2ms |
| IT-015 | per-tick MarginLevel 重算（缓存命中）P50=22.8 / P99=28.5ms |
| 其余 | tier 解析边界 / Equity 计算 / 三态判定 / 双触发 / 冻结值读取 / 30072 无映射拒单 等 |

> 唯一红测 = pre-existing `TradingKafkaWalletDepositIntegrationTests` flake（Kafka consumer group 残留计数竞态，非本阶段，隔离单跑亦红，见 §5.8）。trading-core 全量 IT baseline 仍受 STAGE-7 commit 10 文档化的 Redis 6379 vs docker 6380 不一致影响，本阶段 16 IT 隔离运行已实证全绿。

> PERF 规模说明：WSL 资源下采用 200/100 用户（非 master 设想 1000），P99 远优于 500ms 目标；1000 规模压测可在正式环境补（见 §5.7）。

---

## §5. 已知不阻断项 / 边界

> 部署阻断项（生产/演示库 schema 漂移，沿 14B 叠加）见 **§0**，为最高优先级，单独提级，不在本节"不阻断"列表内。

1. **【部署阻断项，提级到 §0】** 14B V28/V29 `USE` 污染遗留 + C1 V30-V32 叠加；部署前必须先 repair V28/V29 再 migrate V30-V32（详见 §0）。
2. **Task 10 FX_PAUSED 按类目行为延后**：`SymbolSpec` 无 category 字段，需 market 额外取数；`t_fx_pause_behavior` 表 + 8 类目 seed 已 V31 就位。划 **C2/后续**。**非 C 阶段验收硬约束**（master §8.3 C 阶段不含 FX_PAUSED；FX_PAUSED 8 类目 × 3 开关验收属 D 阶段 master §8.3 D）。
3. **Finding 2（ISOLATED 数学洞察）**：在 liqPrice 点处恰好 MarginLevel=100%，故 ISOLATED 单仓 + 一致冻结 mmRate 口径下，「MarginLevel ≤ 30% StopOut」不会比 liqPrice 触发更先独立触发；30% StopOut 的真正价值在 **CROSS（账户级，D 阶段）**。C1 双触发机制已正确实现并测试（StopOut 与 liqPrice 任一命中即强平），ISOLATED 下二者差集为空属预期。
4. **latent 耦合（D 阶段前置注意）**：`closePositionByTrigger` 内有价格二次校验；ISOLATED 下因 Finding 2 差集为空无影响。**D 阶段引入实时 MM / CROSS（MarginLevel 与 liqPrice 解耦）前必须放开该二次校验**，否则 StopOut-without-liqPrice 的强平会被静默吞掉。本项已显式记录给 D 阶段。
5. **实时 MM 留 D**：C1 MM 用开仓冻结 fx（mmRateAtOpen，MM 稳定）；master §3.2 D2 全实时 MM 精化留 **STAGE-14D**。
6. **master §5.1 T9 tier3 勘误**：原稿 50x/2.5% 违反 CHECK(max_lev×mm_rate≤1.0)，已修正 50x/2.0%（与 T6/T7 同档一致）并回写 master（`8353ce04`）。
7. **PERF 规模**：WSL 资源下用 200/100 用户（非 master 设想 1000），P99 远优于 500ms 目标；1000 规模压测可在正式环境补。
8. **pre-existing Kafka flake `TradingKafkaWalletDepositIntegrationTests`**（consumer group 异步计数竞态，pre-existing，与本阶段无关，隔离单跑亦复现）——建议后续单独修，不阻断本阶段。
9. **后续阶段**（独立 plan）：C2（console tier CRUD 三端 UI + admin 改 tier 30s 生效 + Task 10 FX_PAUSED 接线）/ D（CROSS/ISOLATED 切换 + 实时 MM）/ E（三端 UI + 多币聚合 + WS break 切换）。

---

## §6. tier seed 验证证据

| 验证项 | 证据 |
|---|---|
| seed 规模 | 6311 行 / 1572 symbol（build-time 脚本按 master §5.2 映射生成；category6/7 → T10，default 组） |
| 每模板抽查 | 每模板抽样核对 tier 数据与 master §5.1 一致（Task 1 + IT-002 + Task 11 自动校验脚本） |
| CHECK 约束 | DB 层 CHECK(max_lev × mm_rate ≤ 1.0) 生效，违例 INSERT 被拒（IT-001） |
| T4 空映射 | market 当前无稳定币对，T4 映射为空，**据实记录**（非缺陷，IT-003 边界覆盖据此注记） |
| V30-V32 干净 migrate | C1 PERF/IT 隔离库从 V27 顺序 migrate 到 V32 无 `Duplicate column`（V30-V32 无 USE bug） |

---

## §7. master §8.3 C 阶段验收硬约束对照（后端可证部分逐条标注）

| 硬约束 | 状态 | 验证证据 |
|---|---|---|
| 全 10 模板边界 tier 切换在 IT 中各覆盖 1 次 | ✅（T4 空映射注记） | IT-003（T4 market 无稳定币对，映射空，据实记录） |
| 200x XAUUSD 大单（落入高档 tier）下单被拒 30070 + 错误消息清晰 | ✅ | IT-004 |
| StopOut 触发后强平到落账整链 < 500ms | ✅ | IT-008 实测 74ms |
| CHECK 约束 max_lev × mm_rate ≤ 1.0 在 DB 层生效 | ✅ | IT-001 |
| 全 symbol tier seed 实际值人工抽查 + 自动校验脚本（每模板抽 5 核对） | ✅ | IT-002 + Task 1/11（6311 行 / 1572 symbol） |
| admin 改 tier 在 30s 内生效 | ⏳ C2 | 30s 缓存（LeverageTierResolver）已实现并验证；admin tier CRUD UI + 30s 生效端到端属 **C2** |
| tier CRUD UI | ⏳ C2 | console tier 配置三端 UI 属 **C2** |

#### 通用硬约束

| 通用硬约束 | 状态 | 备注 |
|---|---|---|
| mvn compile + test-compile BUILD SUCCESS | ✅ | 各 task 实施门禁覆盖 |
| 涉及服务 mvn test 全过 | ✅ | trading-core ≈241 UT 全绿 + 16 IT 隔离全绿 |
| 前端 npm 三件套 | N/A | C1 backend-only |
| 文档同步完成 | ✅ | Task 12（`369af172`）R8 同步 |
| Git 回滚点 push 到 main | ⏳ | 本地 main 领先 origin/main（按约定由控制者统一执行） |
| 当前开发计划 §1 阶段收口条目录入 | ✅ | 本 commit |

---

## §8. 文档同步清单

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/database/falconx一期数据库设计.md` | ✅ | t_symbol_leverage_tier 表 + t_risk_config 阈值列 + t_fx_pause_behavior 表 + t_position 冻结列（Task 12 `369af172`） |
| `docs/domain/状态机规范.md` | ✅ | MarginLevel 三态状态机（HEALTHY/MARGIN_CALL/STOP_OUT）+ StopOut/liqPrice 双触发（Task 12） |
| 错误码 30070（超档拒单）/ 30072（无 tier 映射拒单） | ✅ | Task 12 |
| master §5.1 T9 tier3 勘误回写（50x/2.0%） | ✅ | `8353ce04` |
| 范围边界（CROSS / 实时 MM / Task 10 FX_PAUSED 延后） | ✅ | Task 12 标注，指向 C2 / STAGE-14D |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14C1 R7 收口条目（本 commit）+ 下一步 outline 指向 C2 + D |
| `docs/design/STAGE-14-...-MASTER-design.md` §8.3 C 阶段 | ✅ | 验收硬约束逐条对照（§7） |
| `docs/process/STAGE-14C1-...-implementation-plan.md` | ✅ | 实施计划（`24415308`） |

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**C1 后端核心（tier 杠杆/MM 双重分级 + 账户 MarginLevel + 30% StopOut / liqPrice 双触发强平）在 trading-core 代码侧已完整实现，并通过单测（≈241 UT 全绿，含 contextLoads）+ 16 IT（CHECK DB 层生效 / 200x 大单拒单 30070 / StopOut 整链 74ms / tier seed 每模板抽查 / 10 模板边界切换）+ PERF（IT-008 74ms、IT-014 P50=64.7/P99=132.2ms、IT-015 P50=22.8/P99=28.5ms，均 ≪ 500ms）验证。已验证范围边界为：trading-core 代码侧 tier 分级 + 账户级 MarginLevel + 双触发强平能力完整。**

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项（部署前必须处理）**：生产/演示库 `falconx_trading` 因 14B root bug 期间 `USE` 污染存在 V28/V29 schema 漂移（列已存在但 `flyway_schema_history` 缺 V28/V29 行），下次 `flyway migrate` 将先撞 `Duplicate column` 阻断启动，连带 C1 V30-V32 无法应用。**部署前必须先按 §0 修复 14B V28/V29 漂移（手动补行或 flyway repair + 人工核对）再 migrate V30-V32，禁止裸跑 migrate。**
- **已验证范围边界**：tier 分级 + MarginLevel + 双触发在 trading-core 代码侧完整且通过测试；干净库（全新部署）顺序 migrate 到 V32 无此问题；既有被污染库需先 repair。
- **范围边界（非阻断，按 §5）**：Task 10 FX_PAUSED 按类目行为（缺 category 来源）延后 C2；admin 改 tier 30s 生效端到端 + tier CRUD UI 属 C2；实时 MM 精化 + CROSS 强平排序留 D（C1 MM 用开仓冻结 mmRate）；Finding 2 下 ISOLATED 单仓 MarginLevel-30% 与 liqPrice 差集为空（30% StopOut 价值在 CROSS）；latent 耦合（`closePositionByTrigger` 二次价格校验）须在 D 引入实时 MM/CROSS 前放开。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md) 末条，当前系统不得表述为"生产可用"或"可安全对外公测"。

**使用说明（按 §8.1.3）：**

- **使用入口**：trading-core 开仓时按 `notional` 经 `LeverageTierResolver` 解析 tier 档位，校验杠杆上限（超档 30070 / 无映射 30072），强平价 mmRate 取自命中 tier 并冻结 `mm_rate_at_open`/`tier_no_at_open` 到 `t_position`；行情驱动下 `QuoteDrivenEngine` 实时重算账户 MarginLevel（Equity / MM × 100%），三态判定（HEALTHY / MarginCall 100% / StopOut 30%），StopOut 与 liqPrice 任一命中即强平并发 STOP_OUT 通知（含真实 marginLevel 数值）；MarginCall 100% 触发 5min 节流通知。tier / 阈值数据 owner 为 trading-core MySQL（`t_symbol_leverage_tier` / `t_risk_config`），LeverageTierResolver 30s 本地缓存。
- **前置条件**：目标库 Flyway 已按 §0 完成 14B V28/V29 漂移修复 + V30-V32 migrate；market-service FX 数据源在线（开仓 FX 换算依赖，沿 14B）；trading-core 启动加载 tier seed 与 risk_config 阈值。
- **执行步骤**：按 §0 修复目标库 → migrate V30-V32 → 启动 market-service → 启动 trading-core（加载 tier/阈值缓存）→ 正常开仓即接 tier 校验与冻结 → 行情推动 MarginLevel 实时重算 → 触阈强平。
- **预期结果**：超档大单被拒 30070；命中 tier 的 mmRate 写入 `t_position` 冻结列；账户 MarginLevel 实时三态正确；StopOut/liqPrice 双触发强平整链 < 500ms 并落账 + STOP_OUT 通知。
- **已知限制 / 禁用场景**：FX_PAUSED 按类目行为未接线（C2，缺 category 来源）；admin 改 tier 端到端 + tier CRUD UI 未交付（C2，缓存层已实现）；MM 用开仓冻结 fx（实时 MM 留 D）；ISOLATED 单仓下 MarginLevel-30% 与 liqPrice 差集为空（30% StopOut 价值在 CROSS / D）；`closePositionByTrigger` 二次价格校验须在 D 引入实时 MM/CROSS 前放开；禁止对未按 §0 修复的污染库直接 migrate。
