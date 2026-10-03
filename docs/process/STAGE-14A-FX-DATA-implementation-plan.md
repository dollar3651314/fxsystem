# STAGE-14A FX 数据源 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **FalconX R 角色映射**：本计划综合了 R2 契约 / R4 业务后端 / R6 测试 / R7 验证 / R8 文档 5 个角色。R1 派发时可以把整个 plan 交给单代理串行执行，或按 task 拆给 R4/R6 等。

**Goal:** market-service 注入 8 个核心 FX symbol，落地 FxRateService（Redis 实时缓存 + 交叉换算 + stale 检测）+ internal RPC + Kafka topic，为后续 STAGE-14B-E 提供 FX 实时汇率基础设施。

**Architecture:** 复用现有 LP（GODSA）Socket.IO 行情链路，将 FX symbol 当作普通品种推送 → market-service 落入既有 ClickHouse `quote_tick` 表 + 新增 `FxRateService` 维护 Redis `falconx:fx:rate:{base}:{quote}` 实时缓存（TTL 5s）→ 1Hz 节流发布 Kafka `falconx.market.fx.rate.update` topic + 暴露 internal RPC + stale 超时检测告警。

**Tech Stack:** Spring Boot 4.0.5 / MyBatis Plus 3.5.15 / Redisson 4.3.0 / Kafka 4.2.0 / Flyway / Jackson 3.1.0 / JDK 25

**前置阅读（agentic worker 必读）：**
- [`docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §0-§2、§4.2 (V15 seed) 、§7.1-7.2、§8.1 A 行
- [`AGENTS.md`](../../AGENTS.md) §3.3 产品数据来源规则 / §3.8 跨服务兼容性 / §3.9 缓存实现规则
- [`docs/architecture/falconx一期网关-服务-数据库架构方案.md`](../architecture/falconx一期网关-服务-数据库架构方案.md) market-service 章节
- [`docs/process/AI工作模式.md`](AI工作模式.md) §2 R 角色定义、§3.1 角色边界自检卡
- [`docs/event/Kafka事件规范.md`](../event/Kafka事件规范.md) topic 命名 + payload 冻结规则
- 既有相邻参考：`falconx-market-service/src/main/java/com/falconx/market/service/impl/DefaultMarketGroupMarkupService.java`（30s 增量刷新缓存模式可参考）

---

## File Structure

### Create

- `docs/sql/V15__seed_fx_symbols.sql` — FX symbol seed（8 主对）
- `falconx-market-service/src/main/resources/db/migration/V15__seed_fx_symbols.sql` — 同上（Flyway 实际执行）
- `falconx-market-contract/src/main/java/com/falconx/market/contract/FxRateSnapshotPayload.java` — Kafka payload record
- `falconx-market-service/src/main/java/com/falconx/market/service/FxRateService.java` — interface
- `falconx-market-service/src/main/java/com/falconx/market/service/impl/DefaultFxRateService.java` — 主实现
- `falconx-market-service/src/main/java/com/falconx/market/service/FxRateConverter.java` — 交叉换算工具
- `falconx-market-service/src/main/java/com/falconx/market/scheduler/FxRateStaleDetector.java` — stale 检测 + 告警
- `falconx-market-service/src/main/java/com/falconx/market/producer/FxRateKafkaPublisher.java` — Kafka 节流发布
- `falconx-market-service/src/main/java/com/falconx/market/controller/internal/MarketFxRateInternalController.java` — internal RPC
- `falconx-market-service/src/main/java/com/falconx/market/error/MarketFxErrorCode.java` — 60010-60011 错误码（如本服务尚无 ErrorCode，新建）
- `falconx-market-service/src/test/java/com/falconx/market/service/impl/DefaultFxRateServiceTests.java` — 单元测试
- `falconx-market-service/src/test/java/com/falconx/market/service/FxRateConverterTests.java`
- `falconx-market-service/src/test/java/com/falconx/market/integration/FxRateRedisIntegrationTests.java` — 真 Redis IT
- `falconx-market-service/src/test/java/com/falconx/market/integration/FxRateKafkaIntegrationTests.java` — 真 Kafka IT
- `falconx-market-service/src/test/java/com/falconx/market/integration/FxRateInternalRpcIntegrationTests.java` — RPC IT
- `falconx-market-service/src/test/java/com/falconx/market/scheduler/FxRateStaleDetectorTests.java`
- `docs/test/STAGE-14A-FX-DATA-test-cases.md` — R6 16 条用例骨架
- `docs/test/STAGE-14A-FX-DATA-R7-verification-report.md` — R7 收口报告（在 Task 13 创建）

### Modify

- `falconx-market-service/src/main/resources/application.yml` — 新增 `falconx.market.fx.*` 配置块
- `falconx-market-service/src/main/java/com/falconx/market/config/MarketServiceProperties.java` — 配置类扩展
- `falconx-market-service/src/main/java/com/falconx/market/listener/LpQuoteListener.java`（实际类名以代码为准）— 行情回调时分流 FX 到 FxRateService
- `docs/api/管理端接口规范.md` — 补 §X internal RPC `/internal/v1/market/fx/rates`（如需 admin 透传）
- `docs/event/Kafka事件规范.md` — 新增 `falconx.market.fx.rate.update` topic 章节
- `docs/database/falconx一期数据库设计.md` — 补 FX symbol seed 章节
- `docs/setup/当前开发计划.md` — §1 录入 STAGE-14A 收口条目

---

## Tasks

### Task 1: 冻结契约 — FxRateSnapshotPayload

**Files:**
- Create: `falconx-market-contract/src/main/java/com/falconx/market/contract/FxRateSnapshotPayload.java`

- [ ] **Step 1: 创建 contract record**

```java
package com.falconx.market.contract;

import java.math.BigDecimal;

/**
 * FX 实时汇率快照事件。
 *
 * <p>对应 Kafka topic {@code falconx.market.fx.rate.update}。
 * <p>消费者：trading-core-service、console-service。
 */
public record FxRateSnapshotPayload(
    String baseCurrency,      // 如 "EUR"
    String quoteCurrency,     // 如 "USD"
    BigDecimal rate,          // 1 base = rate × quote
    long eventTimeMillis,     // 行情时间戳（来自 LP）
    String sourceLpCode,      // 数据来源 LP，如 "GODSA"
    String sourceSymbol       // 来源平台 symbol，如 "EURUSD"
) {}
```

- [ ] **Step 2: 编译 contract 模块**

Run: `mvn -pl falconx-market-contract -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add falconx-market-contract/src/main/java/com/falconx/market/contract/FxRateSnapshotPayload.java
git commit -m "feat(market-contract): STAGE-14A 新增 FxRateSnapshotPayload (FX 实时汇率事件 payload)"
```

---

### Task 2: V18 Flyway baseline — 8 FX symbol 幂等校验 ✅ **已完成（commit `ae9b84a`）**

> **2026-05-28 修订**：原 plan 假设 V15 + ID 1001-1008 + 新增 symbol。subagent 调研发现：
> 1. V15-V17 已被其他 stage 占用（quote_mapping / swap_schedule / lp_code）→ 用 V18
> 2. V2 已 seed 8 FX symbol（EURUSD/AUDUSD/USDJPY/GBPUSD/USDCAD/USDCHF/NZDUSD/USDCNH）→ V18 改为 `ON DUPLICATE KEY UPDATE` 幂等校验
> 3. V5 已占用 ID 1-1870 → 占位 ID 改为 2001-2008（命中 `uk_symbol_lp_symbol` 不真正 INSERT）

**Files:**
- Created: `docs/sql/V18__seed_fx_symbols.sql`
- Created: `falconx-market-service/src/main/resources/db/migration/V18__seed_fx_symbols.sql`（与 docs/sql 同内容）

- [ ] **Step 1: 雪花 ID 占位生成**

雪花 ID 由 R2 派发前生成（参考 STAGE-12 group markup 模式），本步骤先用占位常量 `1001-1008`：

- [ ] **Step 2: 写 seed SQL**

```sql
-- V15__seed_fx_symbols.sql
USE falconx_market;

INSERT INTO t_symbol (
    id, lp_code, symbol, category, market_code,
    base_currency, quote_currency, price_precision, qty_precision, status
) VALUES
    (1001, 'GODSA', 'EURUSD', 2, 'FX', 'EUR', 'USD', 5, 2, 1),
    (1002, 'GODSA', 'AUDUSD', 2, 'FX', 'AUD', 'USD', 5, 2, 1),
    (1003, 'GODSA', 'USDJPY', 2, 'FX', 'USD', 'JPY', 3, 2, 1),
    (1004, 'GODSA', 'GBPUSD', 2, 'FX', 'GBP', 'USD', 5, 2, 1),
    (1005, 'GODSA', 'USDCAD', 2, 'FX', 'USD', 'CAD', 5, 2, 1),
    (1006, 'GODSA', 'USDCHF', 2, 'FX', 'USD', 'CHF', 5, 2, 1),
    (1007, 'GODSA', 'NZDUSD', 2, 'FX', 'NZD', 'USD', 5, 2, 1),
    (1008, 'GODSA', 'USDCNH', 2, 'FX', 'USD', 'CNH', 5, 2, 1)
ON DUPLICATE KEY UPDATE
    base_currency = VALUES(base_currency),
    quote_currency = VALUES(quote_currency),
    price_precision = VALUES(price_precision),
    qty_precision = VALUES(qty_precision),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

-- 注意: 8 个 FX symbol 自动按 t_trading_hours 默认规则（如未单独配置走兜底；
--       若需 24x5 配置，由 admin 通过 SYMBOL-ADMIN-SCHEDULE-HOLIDAY 接口设定）。
```

- [ ] **Step 3: 复制到 Flyway 目录**

```bash
cp docs/sql/V15__seed_fx_symbols.sql falconx-market-service/src/main/resources/db/migration/V15__seed_fx_symbols.sql
```

- [ ] **Step 4: 本地启动 Flyway 验证**

Run: `docker compose up -d mysql && mvn -pl falconx-market-service -am test-compile && mvn -pl falconx-market-service flyway:migrate -Dflyway.url=jdbc:mysql://localhost:3306/falconx_market -Dflyway.user=root -Dflyway.password=...`

Expected: V15 migration applied successfully.

验证 DB：`SELECT COUNT(*) FROM t_symbol WHERE category=2 AND lp_code='GODSA';`  Expected ≥ 8

- [ ] **Step 5: Commit**

```bash
git add docs/sql/V15__seed_fx_symbols.sql \
        falconx-market-service/src/main/resources/db/migration/V15__seed_fx_symbols.sql
git commit -m "feat(market): STAGE-14A V15 seed 8 个核心 FX symbol (EURUSD/AUDUSD/USDJPY/GBPUSD/USDCAD/USDCHF/NZDUSD/USDCNH)"
```

---

### Task 3: 错误码 + 配置类

**Files:**
- Create: `falconx-market-service/src/main/java/com/falconx/market/error/MarketFxErrorCode.java`
- Modify: `falconx-market-service/src/main/java/com/falconx/market/config/MarketServiceProperties.java`
- Modify: `falconx-market-service/src/main/resources/application.yml`

- [ ] **Step 1: 错误码 enum**

```java
package com.falconx.market.error;

import com.falconx.common.ErrorCode;

public enum MarketFxErrorCode implements ErrorCode {
    FX_SYMBOL_NOT_FOUND("60010", "FX symbol 未在 t_symbol 中配置"),
    FX_RATE_STALE("60011", "FX rate 超过 stale 阈值，监控告警");

    private final String code;
    private final String message;
    MarketFxErrorCode(String code, String message) { this.code = code; this.message = message; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
```

- [ ] **Step 2: MarketServiceProperties 扩展**

在既有 `MarketServiceProperties.java` 中新增内部类 `Fx`：

```java
public static class Fx {
    private int staleThresholdSeconds = 30;
    private int pauseThresholdSeconds = 300;
    private int kafkaThrottleHz = 1;
    private int redisTtlSeconds = 5;
    // getters/setters
}
private Fx fx = new Fx();
public Fx getFx() { return fx; }
public void setFx(Fx fx) { this.fx = fx; }
```

- [ ] **Step 3: application.yml 增加**

```yaml
falconx:
  market:
    fx:
      stale-threshold-seconds: 30
      pause-threshold-seconds: 300
      kafka-throttle-hz: 1
      redis-ttl-seconds: 5
```

- [ ] **Step 4: 编译**

Run: `mvn -pl falconx-market-service -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/error/MarketFxErrorCode.java \
        falconx-market-service/src/main/java/com/falconx/market/config/MarketServiceProperties.java \
        falconx-market-service/src/main/resources/application.yml
git commit -m "feat(market): STAGE-14A 错误码 60010/60011 + FX 配置项"
```

---

### Task 4: FxRateConverter 工具 — 交叉换算

**Files:**
- Create: `falconx-market-service/src/main/java/com/falconx/market/service/FxRateConverter.java`
- Create: `falconx-market-service/src/test/java/com/falconx/market/service/FxRateConverterTests.java`

- [ ] **Step 1: 写失败测试**

```java
package com.falconx.market.service;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FxRateConverterTests {

    @Test
    void direct_pair_returns_rate() {
        FxRateConverter c = new FxRateConverter(Map.of("EUR/USD", new BigDecimal("1.0800")));
        assertThat(c.rate("EUR", "USD")).isEqualByComparingTo("1.0800");
    }

    @Test
    void same_currency_returns_one() {
        FxRateConverter c = new FxRateConverter(Map.of());
        assertThat(c.rate("USD", "USD")).isEqualByComparingTo("1");
    }

    @Test
    void reverse_pair_returns_reciprocal() {
        FxRateConverter c = new FxRateConverter(Map.of("EUR/USD", new BigDecimal("1.0800")));
        // USD→EUR = 1 / 1.0800
        assertThat(c.rate("USD", "EUR"))
            .isEqualByComparingTo(new BigDecimal("0.92592593"));  // 8 位精度
    }

    @Test
    void cross_pair_via_usd_pivot() {
        // EUR/USD = 1.08, AUD/USD = 0.65 → EUR/AUD = 1.08 / 0.65 = 1.66153846
        FxRateConverter c = new FxRateConverter(Map.of(
            "EUR/USD", new BigDecimal("1.0800"),
            "AUD/USD", new BigDecimal("0.6500")
        ));
        assertThat(c.rate("EUR", "AUD")).isEqualByComparingTo(new BigDecimal("1.66153846"));
    }

    @Test
    void cross_pair_with_usd_quoted_base() {
        // USD/JPY = 150, USD/CAD = 1.36 → JPY/CAD = (1/150) × 1.36 = 0.00906667
        FxRateConverter c = new FxRateConverter(Map.of(
            "USD/JPY", new BigDecimal("150.000"),
            "USD/CAD", new BigDecimal("1.3600")
        ));
        assertThat(c.rate("JPY", "CAD")).isEqualByComparingTo(new BigDecimal("0.00906667"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl falconx-market-service test -Dtest=FxRateConverterTests`
Expected: FAIL（FxRateConverter 类不存在）

- [ ] **Step 3: 实现 FxRateConverter**

```java
package com.falconx.market.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * FX 汇率交叉换算工具。
 *
 * <p>输入是 {@code base/quote → rate} 的 snapshot 映射，
 * 通过 USD 作为 pivot 计算任意两币种间的换算率。
 *
 * <p>精度统一 8 位（DECIMAL(24,8) 对齐），HALF_UP 截断。
 */
public final class FxRateConverter {

    private static final int SCALE = 8;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private final Map<String, BigDecimal> directRates;

    public FxRateConverter(Map<String, BigDecimal> directRates) {
        this.directRates = Objects.requireNonNull(directRates);
    }

    /**
     * 计算 from → to 的汇率。
     * 优先级：同币种 → 直接对 → 反向对 → 通过 USD pivot 交叉。
     *
     * @return 1 from = rate × to，找不到时返回 null
     */
    public BigDecimal rate(String from, String to) {
        if (from.equals(to)) return ONE;

        // 直接对
        BigDecimal direct = directRates.get(from + "/" + to);
        if (direct != null) return direct.setScale(SCALE, RoundingMode.HALF_UP);

        // 反向对
        BigDecimal reverse = directRates.get(to + "/" + from);
        if (reverse != null && reverse.signum() != 0) {
            return ONE.divide(reverse, SCALE, RoundingMode.HALF_UP);
        }

        // USD pivot
        BigDecimal fromToUsd = currencyToUsd(from);
        BigDecimal usdToTo = usdToCurrency(to);
        if (fromToUsd == null || usdToTo == null) return null;

        return fromToUsd.multiply(usdToTo).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal currencyToUsd(String currency) {
        if ("USD".equals(currency)) return ONE;
        BigDecimal direct = directRates.get(currency + "/USD");
        if (direct != null) return direct;
        BigDecimal reverse = directRates.get("USD/" + currency);
        if (reverse != null && reverse.signum() != 0) {
            return ONE.divide(reverse, SCALE + 4, RoundingMode.HALF_UP);
        }
        return null;
    }

    private BigDecimal usdToCurrency(String currency) {
        if ("USD".equals(currency)) return ONE;
        BigDecimal direct = directRates.get("USD/" + currency);
        if (direct != null) return direct;
        BigDecimal reverse = directRates.get(currency + "/USD");
        if (reverse != null && reverse.signum() != 0) {
            return ONE.divide(reverse, SCALE + 4, RoundingMode.HALF_UP);
        }
        return null;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn -pl falconx-market-service test -Dtest=FxRateConverterTests`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/service/FxRateConverter.java \
        falconx-market-service/src/test/java/com/falconx/market/service/FxRateConverterTests.java
git commit -m "feat(market): STAGE-14A FxRateConverter 交叉换算 (USD pivot) + 5 UT"
```

---

### Task 5: FxRateService 接口与 Redis 缓存实现

**Files:**
- Create: `falconx-market-service/src/main/java/com/falconx/market/service/FxRateService.java`
- Create: `falconx-market-service/src/main/java/com/falconx/market/service/impl/DefaultFxRateService.java`
- Create: `falconx-market-service/src/test/java/com/falconx/market/service/impl/DefaultFxRateServiceTests.java`

- [ ] **Step 1: 接口定义**

```java
package com.falconx.market.service;

import com.falconx.market.contract.FxRateSnapshotPayload;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;

public interface FxRateService {

    /** 接收新 FX 报价（由 LP 行情监听调用） */
    void acceptTick(FxRateSnapshotPayload payload);

    /** 查询 from→to 实时 rate（含交叉），null 表示不可用 */
    Optional<BigDecimal> queryRate(String from, String to);

    /** 列出所有 base/quote 当前已知 rate 快照（供 internal RPC） */
    List<FxRateSnapshotPayload> snapshotAll();

    /** 检查某个 base/quote 是否 stale（用于 StaleDetector）  */
    boolean isStale(String base, String quote);
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.falconx.market.service.impl;

import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.config.MarketServiceProperties;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultFxRateServiceTests {

    private DefaultFxRateService service;
    private MarketServiceProperties props;
    private FakeRedisCommands fakeRedis;     // 内存版 Redis，单测用
    private FakeClock clock;

    @BeforeEach
    void setUp() {
        props = new MarketServiceProperties();
        props.getFx().setRedisTtlSeconds(5);
        props.getFx().setStaleThresholdSeconds(30);
        fakeRedis = new FakeRedisCommands();
        clock = new FakeClock();
        service = new DefaultFxRateService(props, fakeRedis, clock);
    }

    @Test
    void accept_writes_redis_with_ttl() {
        service.acceptTick(new FxRateSnapshotPayload(
            "EUR", "USD", new BigDecimal("1.0800"),
            clock.nowMillis(), "GODSA", "EURUSD"));

        assertThat(fakeRedis.get("falconx:fx:rate:EUR:USD")).isEqualTo("1.0800");
        assertThat(fakeRedis.ttl("falconx:fx:rate:EUR:USD")).isEqualTo(5);
    }

    @Test
    void query_returns_direct_rate() {
        service.acceptTick(new FxRateSnapshotPayload(
            "EUR", "USD", new BigDecimal("1.0800"),
            clock.nowMillis(), "GODSA", "EURUSD"));

        Optional<BigDecimal> result = service.queryRate("EUR", "USD");
        assertThat(result).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.08000000"));
    }

    @Test
    void query_returns_cross_via_usd() {
        service.acceptTick(new FxRateSnapshotPayload(
            "EUR", "USD", new BigDecimal("1.0800"),
            clock.nowMillis(), "GODSA", "EURUSD"));
        service.acceptTick(new FxRateSnapshotPayload(
            "AUD", "USD", new BigDecimal("0.6500"),
            clock.nowMillis(), "GODSA", "AUDUSD"));

        Optional<BigDecimal> result = service.queryRate("EUR", "AUD");
        assertThat(result).hasValueSatisfying(v ->
            assertThat(v).isEqualByComparingTo("1.66153846"));
    }

    @Test
    void same_currency_returns_one() {
        Optional<BigDecimal> result = service.queryRate("USD", "USD");
        assertThat(result).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1"));
    }

    @Test
    void is_stale_returns_true_after_threshold() {
        service.acceptTick(new FxRateSnapshotPayload(
            "EUR", "USD", new BigDecimal("1.0800"),
            clock.nowMillis(), "GODSA", "EURUSD"));

        clock.advance(31_000);  // > 30s
        assertThat(service.isStale("EUR", "USD")).isTrue();
    }

    @Test
    void snapshot_returns_all_known_pairs() {
        service.acceptTick(new FxRateSnapshotPayload(
            "EUR", "USD", new BigDecimal("1.0800"),
            clock.nowMillis(), "GODSA", "EURUSD"));
        service.acceptTick(new FxRateSnapshotPayload(
            "USD", "JPY", new BigDecimal("150"),
            clock.nowMillis(), "GODSA", "USDJPY"));

        assertThat(service.snapshotAll()).hasSize(2);
    }
}
```

附 `FakeRedisCommands` / `FakeClock` 辅助测试类（同包 src/test/java 下，简单的 ConcurrentHashMap 实现，10-20 行）。

- [ ] **Step 3: 运行测试验证失败**

Run: `mvn -pl falconx-market-service test -Dtest=DefaultFxRateServiceTests`
Expected: FAIL（class 不存在）

- [ ] **Step 4: 实现 DefaultFxRateService**

```java
package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateConverter;
import com.falconx.market.service.FxRateService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
public class DefaultFxRateService implements FxRateService {

    private static final String KEY_PREFIX = "falconx:fx:rate:";
    private final MarketServiceProperties props;
    private final RedissonClient redisson;
    private final Clock clock;
    private final ConcurrentHashMap<String, FxRateSnapshotPayload> latestByPair = new ConcurrentHashMap<>();

    public DefaultFxRateService(MarketServiceProperties props, RedissonClient redisson, Clock clock) {
        this.props = props; this.redisson = redisson; this.clock = clock;
    }

    @Override
    public void acceptTick(FxRateSnapshotPayload payload) {
        String key = KEY_PREFIX + payload.baseCurrency() + ":" + payload.quoteCurrency();
        redisson.getBucket(key).set(payload.rate().toPlainString(),
            props.getFx().getRedisTtlSeconds(), TimeUnit.SECONDS);
        latestByPair.put(payload.baseCurrency() + "/" + payload.quoteCurrency(), payload);
    }

    @Override
    public Optional<BigDecimal> queryRate(String from, String to) {
        Map<String, BigDecimal> snapshot = new HashMap<>();
        latestByPair.forEach((k, v) -> snapshot.put(k, v.rate()));
        FxRateConverter converter = new FxRateConverter(snapshot);
        return Optional.ofNullable(converter.rate(from, to));
    }

    @Override
    public List<FxRateSnapshotPayload> snapshotAll() {
        return List.copyOf(latestByPair.values());
    }

    @Override
    public boolean isStale(String base, String quote) {
        FxRateSnapshotPayload p = latestByPair.get(base + "/" + quote);
        if (p == null) return true;
        long ageSec = (clock.millis() - p.eventTimeMillis()) / 1000;
        return ageSec > props.getFx().getStaleThresholdSeconds();
    }
}
```

注意：测试用 `FakeRedisCommands` 替换 `RedissonClient`，实施期可用 `@MockBean` 或者把 set/ttl 封装成可测接口。

- [ ] **Step 5: 运行测试验证通过**

Run: `mvn -pl falconx-market-service test -Dtest=DefaultFxRateServiceTests`
Expected: 6 tests PASS

- [ ] **Step 6: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/service/FxRateService.java \
        falconx-market-service/src/main/java/com/falconx/market/service/impl/DefaultFxRateService.java \
        falconx-market-service/src/test/java/com/falconx/market/service/impl/DefaultFxRateServiceTests.java
git commit -m "feat(market): STAGE-14A FxRateService + Redis 缓存 + 6 UT"
```

---

### Task 6: FxRateService 集成到 MarketDataIngestionApplicationService

> **2026-05-28 修订**：原 plan 写"LP 监听器分流 FX"基于过期假设。实际现状（Task 2 调研发现）：
> - `SocketIoLpMarketQuoteProvider` 已对所有 symbol 无差别处理（含 8 FX）
> - LP 推送 → `quoteDispatchExecutor` → `quoteConsumer` → `MarketDataIngestionApplicationService.ingestPlatformQuote(StandardQuote)`
> - 不需改 LP 监听器，改在 ingestion service 加 FX 分支

**Files:**
- Modify: `falconx-market-service/src/main/java/com/falconx/market/application/MarketDataIngestionApplicationService.java`（实际类名以代码为准）

- [ ] **Step 1: 定位 ingestion service**

Run: `grep -rln "ingestPlatformQuote\|writeLatestQuote\|publishPriceTick" falconx-market-service/src/main/java --include="*.java"`

定位到 `MarketDataIngestionApplicationService.ingestPlatformQuote(StandardQuote)`。Read 整个方法体（约 150-220 行），找到 `marketQuoteCacheWriter.writeLatestQuote(standardQuote)` 之后的位置。

- [ ] **Step 2: 在 ingestion 内加 FX 分支**

伪代码（实际改造点以代码为准）：

```java
public void ingestPlatformQuote(StandardQuote standardQuote) {
    // 现有路径（不动）：
    marketQuoteCacheWriter.writeLatestQuote(standardQuote);
    // ... 异步 ClickHouse + Kafka price.tick + WS ...

    // STAGE-14A 新增：若 symbol category=2 (FX)，转发到 FxRateService
    SymbolSpec spec = symbolSpecCache.findBySymbol(standardQuote.symbol());
    if (spec != null && spec.category() == 2 /* forex */) {
        fxRateService.acceptTick(new FxRateSnapshotPayload(
            spec.baseCurrency(),
            spec.quoteCurrency(),
            standardQuote.mid(),
            standardQuote.timestampMillis(),
            spec.lpCode(),
            standardQuote.symbol()
        ));
    }
}
```

**关键约束**：
- 不阻塞主路径（FxRateService 内部用 ConcurrentHashMap，写入是 O(1)；Redis 写入异步或 fire-and-forget）
- 不在 FxRateService.acceptTick 内做 IO blocking
- 如 ingestion 已是异步线程，无需再起新线程

- [ ] **Step 3: 启动 market-service + 注入 mock LP tick 验证 Redis 写入**

```bash
docker compose up -d redis mysql
mvn -pl falconx-market-service spring-boot:run &
# 等待启动后...
# 用既有的 SymbolQuoteInjector / debug endpoint 注入一条 EURUSD tick
# 验证 Redis 写入: redis-cli -p 6380 GET 'falconx:fx:rate:EUR:USD'
```

Expected: Redis `falconx:fx:rate:EUR:USD` 返回当前 EURUSD 的 mid price。同时 `falconx:market:price:EURUSD`（既有 key）也保持正常写入。

- [ ] **Step 4: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/application/MarketDataIngestionApplicationService.java
git commit -m "feat(market): STAGE-14A 在 ingestion service 加 FX 分支转发到 FxRateService"
```

---

### Task 7: FxRateKafkaPublisher — 1Hz 节流发布

**Files:**
- Create: `falconx-market-service/src/main/java/com/falconx/market/producer/FxRateKafkaPublisher.java`
- Modify: `application.yml` 增加 topic 配置

- [ ] **Step 1: application.yml topic 注册**

```yaml
falconx:
  market:
    kafka:
      fx-rate-update-topic: falconx.market.fx.rate.update
```

- [ ] **Step 2: Publisher 实现**

```java
package com.falconx.market.producer;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateService;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FxRateKafkaPublisher {

    private final FxRateService fxRateService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MarketServiceProperties props;

    @Value("${falconx.market.kafka.fx-rate-update-topic}")
    private String topic;

    public FxRateKafkaPublisher(FxRateService s, KafkaTemplate<String, Object> kt, MarketServiceProperties p) {
        this.fxRateService = s; this.kafkaTemplate = kt; this.props = p;
    }

    /** 1Hz 节流发布所有 FX rate */
    @Scheduled(fixedRateString = "${falconx.market.fx.kafka-throttle-interval-ms:1000}")
    public void publish() {
        for (FxRateSnapshotPayload p : fxRateService.snapshotAll()) {
            String key = p.baseCurrency() + ":" + p.quoteCurrency();
            kafkaTemplate.send(topic, key, p);
        }
    }
}
```

(注意：实际项目用 `KafkaEventMessageSupport` 注入 trace headers，参考 STAGE-12 `MarketGroupMarkupKafkaPublisher` 的模式。)

- [ ] **Step 3: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/producer/FxRateKafkaPublisher.java \
        falconx-market-service/src/main/resources/application.yml
git commit -m "feat(market): STAGE-14A Kafka FxRateKafkaPublisher 1Hz 节流发布"
```

---

### Task 8: FxRateStaleDetector — 30s 超时检测

**Files:**
- Create: `falconx-market-service/src/main/java/com/falconx/market/scheduler/FxRateStaleDetector.java`
- Create: `falconx-market-service/src/test/java/com/falconx/market/scheduler/FxRateStaleDetectorTests.java`

- [ ] **Step 1: 写失败测试**

```java
package com.falconx.market.scheduler;

import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateService;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FxRateStaleDetectorTests {

    @Test
    void emits_warning_when_fx_rate_stale() {
        FxRateService service = mock(FxRateService.class);
        when(service.snapshotAll()).thenReturn(List.of(
            new FxRateSnapshotPayload("EUR","USD",new BigDecimal("1.08"),
                System.currentTimeMillis() - 60_000, "GODSA","EURUSD")
        ));
        when(service.isStale("EUR","USD")).thenReturn(true);

        AtomicReference<String> warned = new AtomicReference<>();
        FxRateStaleDetector detector = new FxRateStaleDetector(service,
            (base, quote, ageSec) -> warned.set(base + "/" + quote));
        detector.scan();

        assertThat(warned.get()).isEqualTo("EUR/USD");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl falconx-market-service test -Dtest=FxRateStaleDetectorTests`
Expected: FAIL

- [ ] **Step 3: 实现 FxRateStaleDetector**

```java
package com.falconx.market.scheduler;

import com.falconx.market.service.FxRateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FxRateStaleDetector {

    private static final Logger log = LoggerFactory.getLogger(FxRateStaleDetector.class);
    private final FxRateService service;
    private final WarningHook hook;

    public interface WarningHook {
        void onStale(String base, String quote, long ageSec);
    }

    public FxRateStaleDetector(FxRateService service, WarningHook hook) {
        this.service = service; this.hook = hook;
    }

    public FxRateStaleDetector(FxRateService service) {
        this(service, (b, q, age) -> log.warn("FX rate stale: {}/{} age={}s [60011]", b, q, age));
    }

    @Scheduled(fixedDelayString = "${falconx.market.fx.stale-check-interval-ms:10000}")
    public void scan() {
        for (var p : service.snapshotAll()) {
            if (service.isStale(p.baseCurrency(), p.quoteCurrency())) {
                long ageSec = (System.currentTimeMillis() - p.eventTimeMillis()) / 1000;
                hook.onStale(p.baseCurrency(), p.quoteCurrency(), ageSec);
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn -pl falconx-market-service test -Dtest=FxRateStaleDetectorTests`
Expected: 1 test PASS

- [ ] **Step 5: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/scheduler/FxRateStaleDetector.java \
        falconx-market-service/src/test/java/com/falconx/market/scheduler/FxRateStaleDetectorTests.java
git commit -m "feat(market): STAGE-14A FxRateStaleDetector + 1 UT (60011 告警)"
```

---

### Task 9: Internal RPC — `/internal/v1/market/fx/rates`

**Files:**
- Create: `falconx-market-service/src/main/java/com/falconx/market/controller/internal/MarketFxRateInternalController.java`

- [ ] **Step 1: 实现 Controller**

```java
package com.falconx.market.controller.internal;

import com.falconx.common.ApiResponse;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/market/fx")
public class MarketFxRateInternalController {

    private final FxRateService service;

    public MarketFxRateInternalController(FxRateService service) { this.service = service; }

    /** 全量快照 */
    @GetMapping("/rates")
    public ApiResponse<List<FxRateSnapshotPayload>> snapshotAll() {
        return ApiResponse.ok(service.snapshotAll());
    }

    /** 单对查询 */
    @GetMapping("/rates/{base}/{quote}")
    public ApiResponse<FxRateSnapshotPayload> single(@PathVariable String base, @PathVariable String quote) {
        return service.snapshotAll().stream()
            .filter(p -> p.baseCurrency().equalsIgnoreCase(base)
                      && p.quoteCurrency().equalsIgnoreCase(quote))
            .findFirst()
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.error("60010", "FX symbol 未配置: " + base + "/" + quote));
    }
}
```

- [ ] **Step 2: 编译**

Run: `mvn -pl falconx-market-service -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/controller/internal/MarketFxRateInternalController.java
git commit -m "feat(market): STAGE-14A internal RPC GET /internal/v1/market/fx/rates(/{base}/{quote})"
```

---

### Task 10: 集成测试 — Redis / Kafka / RPC 真链路

**Files:**
- Create: `falconx-market-service/src/test/java/com/falconx/market/integration/FxRateRedisIntegrationTests.java`
- Create: `falconx-market-service/src/test/java/com/falconx/market/integration/FxRateKafkaIntegrationTests.java`
- Create: `falconx-market-service/src/test/java/com/falconx/market/integration/FxRateInternalRpcIntegrationTests.java`

参考现有 IT 模板（`MarketGroupMarkupInternalControllerTests` 或 STAGE-12 测试），用 `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers Redis/Kafka。

- [ ] **Step 1: Redis IT 关键场景**

```
TC-FX-IT-001: V15 启动后 8 个 FX symbol 在 t_symbol 可见
TC-FX-IT-002: acceptTick 后 Redis key 命中 + TTL 5s
TC-FX-IT-003: queryRate(EUR,AUD) 通过 USD pivot 返回正确交叉值
```

- [ ] **Step 2: Kafka IT 关键场景**

```
TC-FX-IT-004: 1Hz 节流发布；持续 5s 应收到 5 条消息
TC-FX-IT-005: payload 字段与 contract record 一致
```

- [ ] **Step 3: RPC IT 关键场景**

```
TC-FX-IT-006: GET /internal/v1/market/fx/rates 200 + 列表非空
TC-FX-IT-007: GET /internal/v1/market/fx/rates/EUR/USD 200
TC-FX-IT-008: GET /internal/v1/market/fx/rates/XXX/YYY 错误码 60010
```

- [ ] **Step 4: 运行全部 IT**

Run: `mvn -pl falconx-market-service test -Dtest='*FxRate*IntegrationTests'`
Expected: 8 IT PASS（注意：Testcontainers 启动需 docker daemon）

- [ ] **Step 5: Commit**

```bash
git add falconx-market-service/src/test/java/com/falconx/market/integration/FxRate*
git commit -m "test(market): STAGE-14A FX 集成测试 8 IT (Redis/Kafka/RPC)"
```

---

### Task 11: PERF 压测 — 8 FX × 100Hz 持续 5min

**Files:**
- Create: `falconx-market-service/src/test/java/com/falconx/market/perf/FxRateThroughputTests.java` （或 JMH 微基准）

- [ ] **Step 1: 实现压测**

```java
@Test
@Tag("perf")
void fx_rate_throughput_8pairs_100hz_5min() throws Exception {
    // 8 FX × 100 tick/s × 300s = 240,000 tick
    ExecutorService pool = Executors.newFixedThreadPool(8);
    AtomicInteger counter = new AtomicInteger();

    for (String pair : List.of("EUR/USD","AUD/USD","USD/JPY","GBP/USD",
                               "USD/CAD","USD/CHF","NZD/USD","USD/CNH")) {
        pool.submit(() -> {
            String[] parts = pair.split("/");
            for (int i = 0; i < 30000; i++) {
                fxRateService.acceptTick(new FxRateSnapshotPayload(
                    parts[0], parts[1], BigDecimal.valueOf(Math.random()),
                    System.currentTimeMillis(), "GODSA", parts[0] + parts[1]));
                counter.incrementAndGet();
                LockSupport.parkNanos(10_000_000);  // 10ms
            }
        });
    }
    pool.shutdown();
    pool.awaitTermination(6, TimeUnit.MINUTES);

    assertThat(counter.get()).isEqualTo(240_000);
    // 监控 GC / 内存 / Redis 写入延迟
}
```

- [ ] **Step 2: 执行 + 监控**

Run: `mvn -pl falconx-market-service test -Dtest='FxRateThroughputTests' -Dgroups=perf`
监控：CPU < 50% / Heap 稳定 / Redis 无连接池耗尽 / 平均写延迟 < 5ms

- [ ] **Step 3: Commit**

```bash
git add falconx-market-service/src/test/java/com/falconx/market/perf/FxRateThroughputTests.java
git commit -m "test(market): STAGE-14A PERF 8 FX × 100Hz × 5min 压测"
```

---

### Task 12: 文档同步 — R8 角色

**Files:**
- Modify: `docs/event/Kafka事件规范.md` (新增章节 `falconx.market.fx.rate.update`)
- Modify: `docs/database/falconx一期数据库设计.md` (§FX symbol seed 章节)
- Modify: `docs/api/管理端接口规范.md` (新增 §X internal RPC)
- Modify: `docs/process/BBook一期完成执行路径.md` (新增 §15 STAGE-14A 章节)

- [ ] **Step 1: Kafka 事件规范补 topic**

参考既有 `falconx.market.price.tick` 章节格式，补 `falconx.market.fx.rate.update`：
- Producer: market-service
- Consumer: trading-core, console
- Payload: FxRateSnapshotPayload
- 节流：1Hz
- 错误码：60011 (stale 告警)

- [ ] **Step 2: 数据库设计补 FX seed**

```markdown
### t_symbol 增量：8 个核心 FX symbol (V15)

| symbol | base | quote | price_precision | qty_precision |
|---|---|---|---|---|
| EURUSD | EUR | USD | 5 | 2 |
| AUDUSD | AUD | USD | 5 | 2 |
| USDJPY | USD | JPY | 3 | 2 |
| GBPUSD | GBP | USD | 5 | 2 |
| USDCAD | USD | CAD | 5 | 2 |
| USDCHF | USD | CHF | 5 | 2 |
| NZDUSD | NZD | USD | 5 | 2 |
| USDCNH | USD | CNH | 5 | 2 |
```

- [ ] **Step 3: 管理端接口规范补 RPC**

新增 §X.X FX rate 查询：
- `GET /internal/v1/market/fx/rates` → 全量快照
- `GET /internal/v1/market/fx/rates/{base}/{quote}` → 单对
- RBAC：暂无（仅 internal 服务调用）

- [ ] **Step 4: BBook 执行路径录入 §15 STAGE-14A**

新增章节，含范围、commit SHA（待 R7 收口时回填）、关联文档链接。

- [ ] **Step 5: Commit**

```bash
git add docs/event/Kafka事件规范.md \
        docs/database/falconx一期数据库设计.md \
        docs/api/管理端接口规范.md \
        docs/process/BBook一期完成执行路径.md
git commit -m "docs(R8): STAGE-14A FX 数据源文档同步 (Kafka/DB/API/执行路径)"
```

---

### Task 13: R7 收口报告 + 当前开发计划录入

**Files:**
- Create: `docs/test/STAGE-14A-FX-DATA-R7-verification-report.md`
- Modify: `docs/setup/当前开发计划.md` §1（新增 STAGE-14A 阶段收口条目）

- [ ] **Step 1: 全量回归**

Run（依次串行）：
```bash
mvn -pl falconx-market-service -am clean compile
mvn -pl falconx-market-service test
```
Expected: BUILD SUCCESS + 全部测试 PASS（已知不阻断项除外，需在报告中标注）。

- [ ] **Step 2: R7 报告骨架**

参考 [`docs/test/archive/STAGE-7-WITHDRAW-Phase3-B-verification-report.md`](../test/archive/STAGE-7-WITHDRAW-Phase3-B-verification-report.md) 模板，章节：
- §1 范围
- §2 验收硬约束自检（A 阶段 4 项）
- §3 测试统计
- §4 commits 清单
- §5 已知不阻断项
- §6 三端硬约束验证（A 阶段豁免，标 N/A）
- §7 文档同步清单

- [ ] **Step 3: 当前开发计划 §1 录入**

```
- `STAGE-14A-FX-DATA` **R7 收口通过（YYYY-MM-DD，N commits 累积）**：
  market-service FX 数据源完整化。范围：V15 seed 8 FX + FxRateService + Redis + FxRateConverter
  + FxRateStaleDetector + Kafka publisher + internal RPC + 60010/60011 错误码。
  测试统计：5 UT + 8 IT + 1 PERF = 14 真测试全过。
  已知不阻断项：① 待 STAGE-14B 接入 trading-core 后才能端到端验证 EUR→AUD 跨 service 流。
  关联文档：[设计稿]() / [R7 报告]() / [执行路径 §15]()
```

- [ ] **Step 4: Commit**

```bash
git add docs/test/STAGE-14A-FX-DATA-R7-verification-report.md docs/setup/当前开发计划.md
git commit -m "docs(R7+R8): STAGE-14A R7 收口报告 + 当前开发计划录入"
```

---

## Self-Review Check

1. **Spec coverage**：本 plan 覆盖 master spec §1 D1（FX 数据源）+ §4.2 V15 seed + §7.1（fx.rate.update topic）+ §7.2（FxRateSnapshotPayload contract）+ §8.1 A 阶段所有 8 IT + §11.2 文档同步清单（A 阶段相关项）。✅
2. **Placeholder scan**：所有代码块完整无 TBD；雪花 ID 1001-1008 在 Task 2 已说明（R2 派发前替换为生成器输出）；现有 LP 监听器类名需 grep 定位（Task 6 Step 1 给出 grep 命令）。✅
3. **Type consistency**：`FxRateSnapshotPayload` 6 字段在 Task 1/Task 5/Task 7/Task 9/Task 10 全部一致。✅
4. **R 角色映射**：Task 1（R2）→ Task 2-9（R4）→ Task 4/5/8/10/11（R6）→ Task 12（R8）→ Task 13（R7）。✅

---

## STAGE-14B-E 后续 Outline

A 阶段 R7 收口后，由 R1 单独 invoke writing-plans 为下面阶段出独立 plan：

| 阶段 | 主交付 | 关键 task 群（参考） |
|---|---|---|
| **B 算法换算** | CurrencyConverter / Margin/PnL/Fee/Swap 接 converter / t_ledger 三列 | V28+V29 / converter 单测 / 算法层改造 / 老数据回填 / 跨服务 IT |
| **C MarginLevel + Tier** | LeverageTierResolver / AccountEquityCalculator / MarginLevelMonitor / tier seed | V30+V31 / 10 模板边界 UT / StopOut 触发 IT / 500 symbol seed 校验脚本 / admin tier CRUD |
| **D CROSS / ISOLATED** | margin_mode 切换闸门 / supplement / CROSS 强平 / FX_PAUSED 按类目行为 | V32+V33 / 切换状态机 IT / CROSS 强平排序 IT / FX_PAUSED 8×3 IT / 升级窗口期回填演练 |
| **E 三端 UI** | 客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL / admin 多币聚合 | Vitest 12 + E2E 3 + 桌面+移动浏览器 6 截图 + WebSocket break 字段切换 |

— END —
