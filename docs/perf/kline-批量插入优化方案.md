# kline 批量插入优化方案（#2）

- 提出日期：2026-06-08
- 范围：falconx-market-service kline 写入链路（ClickHouse `falconx_market_analytics.kline`）
- 状态：**已实施并部署 demo**（2026-06-08，commit `d49b1a7f`）。实测：flush batchSize=115、部署后新建 part 0 个单行碎片/平均 115 行/part、kline 查询仍 6ms+200 正常；存量碎片 part 由后台 merge 渐进合并（read_rows/merge 率为滞后指标）。本文档保留作设计记录。
- 关联：本方案是「K线接口 500/504 排查」系列的收尾项 #2。前序 #1/#3 已完成并部署（见下「背景」）。
- 真源口径：以本仓库为准；实施时以届时代码为准复核行号/方法名。

---

## 0. 背景：为什么有这个方案

2026-06-08 排查「某些 symbol K线接口报错」时，逐层定位并修复了 3 层问题（均已 push + 部署 demo）：

1. **CH 容器内存欠配**（`mem_limit 2g→4g`）— 修原始 500（CH OOM）。
2. **kline 查询用 FINAL**（改 `LIMIT 1 BY` 显式去重）— 去掉读时合并的内存/耗时放大。
3. **网关 market-route 熔断 500ms 阈值误触发**（→`2s`）+ **CH 系统日志无界涨到 40GB 抢 CPU**（config.d 关 text/trace/processors_profile + 其余 3 天 TTL）+ **CH CPU 1.5→4 核**。
   - 效果：kline 查询 CH 端 **191ms→7ms**、端到端 992ms→21~74ms。

但还剩一个**写入侧**的结构性问题未动，即本方案 **#2**。

### 问题本质

market-service 在每根 K线收盘时**逐行** `insertKline` 落 ClickHouse。整分钟边界 ~1571 个品种的 1m bucket 几乎同时收盘 → 瞬间 1571 个**单行 part**；整点时 1m+5m+15m+1h 叠加可达 ~6000+。实测后果：

- kline 表 merge 频率 **~1993 次/小时**（~33 次/分钟）。
- 最近 part 全是 1 行、5 行、11 行的碎片。
- 读放大：500 行的 kline 查询因数据碎在 6+ 个 part、每 part 最小读 1 个 8192 行 granule，被迫扫 **~49k 行**。

ClickHouse 铁律是**批量插入（≥1000 行/批）**，逐行写是头号反模式。本方案把 kline 写入改为缓冲攒批 + 定时批量 flush。

### 预期收益

| 指标 | 现状 | 预期 |
|---|---|---|
| kline merge 频率 | ~1993 次/小时 | 降 1~2 个量级（数十次/小时） |
| 写入产生的 part | 每秒多个 1 行 part | 每 flush 周期几个批量 part |
| 查询 read_rows | 500 行查询扫 ~49k 行 | 趋近实际需要 |
| 写 ClickHouse 网络往返 | 每根 K线 1 次 | 每批 1 次 |

---

## 1. 设计原则

**完全镜像 quote_tick 已验证的批量基建**（`MybatisClickHouseMarketAnalyticsWriter` 里的 `pendingQuoteTicks` 队列 + `@Scheduled` flush + 失败 restore + drop-oldest 容量保护 + Micrometer metric + `@PreDestroy` flush）。这套机制 2026-05-20 起在生产跑稳，复用 = 低风险、低认知成本。

---

## 2. 改动清单

### ① Mapper 加批量方法
`falconx-market-service/.../analytics/mapper/MarketKlineMapper.java`
- 接口加 `int insertKlines(List<MarketKlineRecord> records);`
- **保留** `insertKline`（单行）——测试与回退路径仍用。

`falconx-market-service/.../mapper/market/analytics/MarketKlineMapper.xml`
- 加 `insertKlines`，镜像 `MarketQuoteTickMapper.xml` 的 `insertQuoteTicks` 写法：
  ```xml
  INSERT INTO falconx_market_analytics.kline (...) VALUES
  <foreach collection="list" item="record" separator=",">
      ( #{record.symbol}, ..., 
        fromUnixTimestamp64Milli(#{record.openTimeEpochMillis}, 'UTC'),
        fromUnixTimestamp64Milli(#{record.closeTimeEpochMillis}, 'UTC'),
        #{record.source} )
  </foreach>
  ```

### ② Writer 加 kline 缓冲 + flush
`falconx-market-service/.../analytics/MybatisClickHouseMarketAnalyticsWriter.java`（照搬 quote 那套，独立队列）
- 新字段：`ConcurrentLinkedQueue<MarketKlineRecord> pendingKlines` + `AtomicInteger pendingKlineCount` + `AtomicLong droppedKlineCount` + `ReentrantLock klineFlushLock`。
- `writeKline`：保留 `isFinal` 守卫；由直接 `insertKline` 改为**入队**（drop-oldest 容量保护，阈值高，见配置）。
- 新增 `@Scheduled(fixedDelayString="${falconx.market.analytics.kline-flush-interval:2000}", timeUnit=MILLISECONDS)` → `flushKlines("scheduled")`：drain 批量 → `marketKlineMapper.insertKlines(batch)`；失败 `restoreKlines(batch)` 回队列重试（与 quote 完全一致）。
- `@PreDestroy`：把 kline 缓冲一并 flush（扩展现有 `flushRemainingQuoteTicks` 或并列加 `flushRemainingKlines`）。
- 新增 metric：`falconx.market.kline.pending.size`、`falconx.market.kline.dropped.total`（注册方式同 quote 的 3 个 gauge）。

### ③ 配置
`falconx-market-service/.../config/MarketServiceProperties.Analytics`，新增（与 quote 三项对称）：
```
klineBatchSize     = 1000   # 整分钟 ~1571 根，2~4 批写完
klineFlushInterval = 2s     # 收盘后最多 2s 落库
klineQueueMaxSize  = 100_000
```
可经 `-Dfalconx.market.analytics.kline-*` 覆盖（compose JAVA_OPTS）。

---

## 3. 关键设计决策

- **flush 只在 Spring 调度线程**：遵守仓库红线「外部回调线程不得直接执行下游链路」，**不**在 `writeKline`（LP dispatch 线程）里触发 flush，也不做 size-trigger；靠 2s 短间隔覆盖整分钟 burst。
- **丢弃策略比 quote 保守**：丢一根 K线 = `ReplacingMergeTree` 不会重建的**永久历史缺口**（不像 tick 可丢旧）。kline 量远小于 tick，正常不触顶；cap 设 10 万 + drop 时 WARN。
- **quote 与 kline 独立队列/锁/调度**：互不阻塞，可独立调参。
- **去重语义不变**：仍进 `ReplacingMergeTree(ingest_time)`，批量不影响读路径的 `ORDER BY open_time DESC, ingest_time DESC LIMIT 1 BY open_time`。

---

## 4. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 收盘到可查最多 2s 延迟 | 图表实时蜡烛走 WebSocket 不受影响；历史 REST 2s 可接受。需更快可调 `kline-flush-interval` |
| 进程崩溃丢失缓冲中 K线 | `@PreDestroy` flush（正常停机不丢）；崩溃丢失可后续补数（本方案外） |
| flush 失败丢数 | 批量失败 restore 回队列重试（镜像 quote，已验证） |
| 测试假设同步落库 | `MarketDataIngestionApplicationServiceTests` 是 mock 验证 `writeKline` 被调用——不受影响；`TC_GM_012`/`TC_KLINE_DEDUP` 直接调 `insertKline`——不受影响 |

---

## 5. 测试计划

- 新增 IT：经 `writeKline` 入队 N 根 → 触发 `flushKlines` → 断言 N 根全部落库（`countKlineBySymbolAndInterval`）。
- 失败回滚 IT：mock `insertKlines` 抛异常 → 断言记录 restore 回队列、计数恢复。
- 回归：`TC_GM_012`、`TC_KLINE_DEDUP`、`MarketDataIngestionApplicationServiceTests` 全绿。

---

## 6. 部署后验证（demo）

ssh `ubuntu@10.143.170.189`，`docker exec falconx-clickhouse clickhouse-client --user default --password falconx`：
- `system.part_log` kline merge 次数/小时（期望 1993 → 数十）。
- `system.parts WHERE table='kline' AND active` part 数 + 最近 part 行数（期望不再是 1 行 part）。
- 重跑 kline 查询看 `system.query_log` 的 `read_rows`（⚠️ 此项预期已被实测推翻，见 §9）。
- 功能：新收盘 K线 ≤2s 可经 `GET /api/v1/market/klines/{symbol}` 查到。

部署注意：本地 WSL `docker compose build` 会因拉 docker.io 基础镜像 x509 证书校验失败而静默发旧镜像（`deploy-to-server.sh` 已加 build 退出码检查中止）；可靠做法是本地 `mvn -pl falconx-market-service -am clean package -Dmaven.test.skip=true` 出 jar，scp jar + tools/*.p12 到服务器干净 buildctx，服务器侧 `docker build -f deploy/docker/Dockerfile.backend` 再 `docker compose up -d --force-recreate market-service`。

---

## 7. 回滚

保留 `insertKline` 单行路径；把 `kline-flush-interval` 调极小或代码回退 `writeKline` 即恢复原行为。改动集中在一个 writer + 一个 mapper，回退面小。

---

## 8. 工作量

1 个 mapper 方法 + XML、writer ~60 行（照抄 quote）、3 个配置项、2~3 个 IT。**纯写入侧改动，不碰读路径/SQL/schema。**

---

## 9. 部署后观测结论（2026-06-08，含一处预期纠正）

部署后对 BTCUSD 1m / limit 500 做了 T0/+5m/+15m/+30m 四点观测，并对比干净的部署后窗口写入模式。

### 9.1 批量化目标达成（核心收益）

| 指标 | pre-fix（逐行 insert） | 部署后（近 30min 干净窗口） |
|---|---|---|
| 新建 part 速率 | ~1600 个/分钟 | **49 个/30min ≈ 1.6 个/分钟（↓~1000x）** |
| 新 part 行数 | 1 行 | **p50/p90/max = 110/112/114 行** |
| merge 次数 | ~1993~5947 次/小时 | **11 次/30min** |

**part 爆炸与 merge 风暴彻底消除**——这是 #2 的真实价值。

### 9.2 预期纠正：read_rows 不随合并下降（原 §6 预期作废）

原设计预期"存量碎片合并后 read_rows 从 ~49k 显著下降"。**实测推翻**：read_rows 全程平在 **~74k**（T0 73956 → +30m 73949），耗时稳定 **6~7ms**。

原因：read_rows **不是碎片问题，是 granule 地板**。kline 表按 `(interval_type, toYYYYMM(open_time))` 分月分区、`index_granularity=8192`。取"近 500 根"要从分区里每个相关 part 各读 ≥1 个 8192 行 granule，再叠加 `ORDER BY open_time DESC, ingest_time DESC + LIMIT 1 BY` 的去重/跨分区扫描。BTCUSD 当前 6 个 part（3 个近期批量 110 行小 part + 1 中 part + **2 个 ~130 万行的历史大 part**），那 2 个大 part 已足够大、ClickHouse 不会再 merge → read_rows 停在该台阶，**与碎片合并无关**。

### 9.3 结论

- **read_rows ~74k 不必管**：74k 行 ≈ <1MB，查询稳定 6~7ms。read_rows 从来不是瓶颈，把 CPU 占满的 merge 风暴（已由系统日志收敛 + CPU 扩容 + 本批量化共同根治）才是。
- 若确需压低 read_rows，只能走读侧（去掉 `ingest_time` 二级排序走纯 read-in-order 可到 ~41k、或调小 granularity），但 6~7ms 下得不偿失，不做。
- **指标取用教训**：评估批量化看「**part 创建速率 / 新 part 行数 / merge 次数**」，不是 read_rows；后者由分区布局 + granularity + 查询形状决定，不随写入批量化或后台合并变化。
