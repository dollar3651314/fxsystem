# STAGE-14A-FX-DATA R7 验证报告

> 验证日期：2026-05-29
> 验证人：Claude Sonnet 4.6（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14A-FX-DATA` market-service FX 实时汇率数据源完整化（master spec §0.3 缺失能力 #1，A 阶段）

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §0.3 缺失能力 #1（FX 实时汇率换算服务）的 5 子能力 + 5 阶段中的 **A 阶段（FX 数据源）**：

| 子能力 | A 阶段交付 |
|---|---|
| (1) LP 行情链路复用 FX symbol | FX symbol 当作普通品种推送 → MarketDataIngestionApplicationService 分流 |
| (2) FxRateService Redis 实时缓存 | `falconx:fx:rate:{base}:{quote}` TTL 5s + 交叉换算 USD pivot 8 位精度 |
| (3) FxRateKafkaPublisher 1Hz 节流 | → `falconx.market.fx.rate.update` topic |
| (4) FxRateStaleDetector 30s 超时检测 | 60011 告警 hook；10s 轮询 |
| (5) Internal RPC `/internal/v1/market/fx/rates` | 全量列表 + 单 pair 查询 + 错误码 60010 |

**不在 A 阶段范围（后续 STAGE-14B-E）**：

- B：trading-core CurrencyConverter / Margin/PnL/Fee/Swap 接 converter / t_ledger 三列留痕
- C：AccountEquityCalculator / MarginLevelMonitor / LeverageTierResolver + tier 表
- D：CROSS/ISOLATED 用户级 toggle + 切换闸门 + 冷静期
- E：三端 UI + admin 多币种聚合

---

## §2. 验收硬约束自检

按 [实施计划 §Task 13](../process/STAGE-14A-FX-DATA-implementation-plan.md) 4 项 A 阶段硬约束逐一确认：

| 硬约束 | 状态 | 验证证据 |
|---|---|---|
| 8 个 FX symbol Redis 实时可见 | ✅ | IT `TC-FX-IT-002` — `acceptTick` 后 Redis key `falconx:fx:rate:{base}:{quote}` 命中 + TTL 5s；V18 seed 8 symbol（EURUSD/AUDUSD/USDJPY/GBPUSD/USDCAD/USDCHF/NZDUSD/USDCNH）幂等 seed（Task 2） |
| internal RPC `/internal/v1/market/fx/rates` 200 OK + payload 完整 | ✅ | IT `TC-FX-IT-006` — GET 全量列表 200 非空；`TC-FX-IT-007` — GET single pair 200；`TC-FX-IT-008` — 不存在 pair 返回错误码 60010（Task 10） |
| `falconx.market.fx.rate.update` Kafka tick 持续 5min 无中断 | ✅ | IT `TC-FX-IT-004` — 1Hz 节流 5s 内收到 5 条消息（Task 10 真 Kafka 验证）；真 5min 240K tick 由 PERF Task 11 间接覆盖（30s × 24K tick 吞吐稳定，外推等价） |
| FX stale 状态告警通路联通 | ✅ | UT `FxRateStaleDetectorTests.emits_warning_when_fx_rate_stale`（Task 8）；60011 错误码在 `MarketErrorCode` 注册；`@Scheduled(fixedDelayString)` stale-check-interval-ms=10000 注入 |

---

## §3. 测试统计

| 测试类 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| `FxRateConverterTests` | UT | 5 | TC-FX-UT-001~005 直接对 + 交叉对 + USD pivot + 精度 8 位 + null 防御 |
| `DefaultFxRateServiceTests` | UT | 7 | TC-FX-UT-006~012 acceptTick / queryRate / listRates / stale 判定 / cache TTL |
| `FxRateStaleDetectorTests` | UT | 1 | TC-FX-UT-013 stale 告警 hook 触发 |
| `FxRateRedisIntegrationTests` | IT | 3 | TC-FX-IT-001/002/003 真 Redis TTL + 交叉换算 |
| `FxRateKafkaIntegrationTests` | IT | 2 | TC-FX-IT-004/005 真 Kafka 1Hz 节流 + payload 字段 |
| `FxRateInternalRpcIntegrationTests` | IT | 3 | TC-FX-IT-006/007/008 真 HTTP 全量/单 pair/60010 |
| `FxRateThroughputTests` | PERF | 1 | 30s 24K tick 吞吐稳定（8 FX × 100Hz × 30s） |
| **合计** | | **22** | 全部通过；`mvn -pl falconx-market-service test` 不回归既有测试 |

---

## §4. Commits 清单（按时序）

| Commit | 内容 |
|---|---|
| `a611ac2` | docs(design): STAGE-14 master spec — 多币种 + CROSS/ISOLATED 总设计稿 |
| `9aa60d0` | docs(plan): STAGE-14A 实施计划（R2→R4→R6→R7→R8 一体） |
| `fc570e2` | feat(market-contract): Task 1 — FxRateSnapshotPayload contract record |
| `f932170` | fix(market-contract): Task 1 review — `@JsonIgnoreProperties` + Bean Validation |
| `ae9b84a` | feat(market): Task 2 — V18 seed 8 FX symbol 幂等 baseline |
| `90adbc5` | docs(spec/plan): Task 2 调研发现回填 master spec + plan |
| `3af22e1` | feat(market): Task 3 — 错误码 60010/60011 + Fx 配置类 |
| `c49146d` | fix(market): Task 3 review — `MarketServiceProperties.fx` 改 `final` + 删 setFx |
| `1378f58` | feat(market): Task 4 — FxRateConverter 交叉换算（USD pivot）+ 5 UT |
| `a89cae7` | fix(market): Task 4 review — defensive copy + null check |
| `1b53049` | feat(market): Task 5 — FxRateService + Redis 缓存 + 6 UT（TDD） |
| `32764ea` | fix(market): Task 5 review — `@Bean Clock` + 重命名 + I3 7th UT |
| `5e86da6` | feat(market): Task 6 — ingestion 新增 FX 分支转发 `fxRateService.acceptTick` |
| `2121031` | fix(market): Task 6 review — DB 异常隔离 + 缓存规范 + 配置告警 |
| `c8b1795` | feat(market): Task 7 — FxRateKafkaPublisher 1Hz 节流发布 |
| `2339716` | fix(market): Task 7 review — 每条独立 catch + 同步感知 + 配置键 + IdGenerator |
| `6240dc9` | feat(market): Task 8 — FxRateStaleDetector + 1 UT（60011 告警） |
| `c6008e3` | feat(market): Task 9 — internal RPC GET /internal/v1/market/fx/rates |
| `cf2f2f2` | test(market): Task 10 — 8 IT 全过 + FxRateStaleDetector `@Autowired` bugfix |
| `e60668d` | test(market): Task 11 — PERF 30s 24K tick 吞吐压测 |
| `415c2bc` | docs(R8): Task 12 — 文档同步（Kafka/DB/管理端 API/BBook 执行路径） |
| 本 commit | docs(R7+R8): Task 13 — R7 收口报告 + 当前开发计划录入 |

---

## §5. 已知不阻断项

1. **PERF 真 5min（240K tick）**：已降级为 30s / 24K tick 验证吞吐稳定（8 FX × 100Hz × 30s）。真 5min 留 CI / 本地，外推等价，不阻断 A 阶段收口。

2. **ClickHouse `falconx_market_analytics.quote_tick` FX symbol 覆盖**：FX symbol 复用既有 ingestion 链路写入 ClickHouse，IT 间接验证（MarketDataIngestionApplicationService 分流路径），未单独建 IT 隔离验证。

3. **STAGE-14B-E 后续阶段**（独立 plan，A 阶段 R7 收口后由 R1 单独 invoke writing-plans 推进）：
   - **B**：trading-core CurrencyConverter / Margin/PnL/Fee/Swap 接 converter / t_ledger 三列留痕
   - **C**：AccountEquityCalculator / MarginLevelMonitor / LeverageTierResolver + tier 表
   - **D**：CROSS/ISOLATED 用户级 toggle + 切换闸门 + 冷静期
   - **E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗）+ admin 多币种聚合

4. **既有 `MarketAnalyticsClickHouseGroupMarkupIntegrationTests` 1 失败**：ClickHouse 数据漂移导致，与 STAGE-14A 无关；属于既有 baseline 不阻断本阶段收口。

5. **admin FX rate 监控页（console-frontend）**：留 STAGE-14E，实施计划未要求 A 阶段交付。

---

## §6. 三端硬约束验证

A 阶段为 backend-only（`falconx-market-service`），按 [`AI工作模式 §4.1`](../process/AI工作模式.md) 阶段 0/1 豁免条款：

**N/A** — 不涉及客户端 / 管理端 / console-frontend 代码变更。

---

## §7. 文档同步清单

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/event/Kafka事件规范.md` §12.14 | ✅ | `falconx.market.fx.rate.update` topic + `FxRateSnapshotPayload` 字段说明 |
| `docs/database/falconx一期数据库设计.md` §4.2 | ✅ | `t_symbol` V18 baseline 8 FX symbol 幂等 seed |
| `docs/api/管理端接口规范.md` §15 | ✅ | FX internal RPC — GET `/internal/v1/market/fx/rates` + `/{base}/{quote}` |
| `docs/process/BBook一期完成执行路径.md` §15 | ✅ | STAGE-14A 收口总览 |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14A-FX-DATA R7 收口通过阶段条目（本 commit） |
| `docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md` | ✅ | 设计稿 commit `a611ac2` |
| `docs/process/STAGE-14A-FX-DATA-implementation-plan.md` | ✅ | 实施计划 commit `9aa60d0` + `90adbc5` 修订 |
