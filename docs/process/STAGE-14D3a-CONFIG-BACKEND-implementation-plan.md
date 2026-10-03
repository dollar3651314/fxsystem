# STAGE-14D3a 运营可配后端 + supplement pause gating + FX_PAUSED 8×3 验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把冷静期与 StopOut/MarginCall 阈值从硬编码/只读改为 admin 运行时可配（落 `t_risk_config` 平台行 + 内部 RPC 写），给 supplement-margin 接 FX_PAUSED 闸门（30087），给 `t_fx_pause_behavior` 加 admin 写路径并完成 FX_PAUSED 8 类目×3 开关组合完整验收——全部为 **trading-core 纯后端**（D3a 切片，console 透传 + 三端 UI 留 D3b）。

**Architecture:** 复用 C1 已建的 `t_risk_config` 平台行（`symbol IS NULL`）承载冷静期/阈值，新增 V37 加 `cooling_period_seconds` 列；`MarginModeSwitchApplicationService` 改从 DB 读冷静期（低频用户动作，直读无需缓存，缺失回退 properties 默认 300s）；阈值写后由 `DefaultMarginLevelMonitor` 既有 30s TTL 缓存自然生效（≤30s）；FX_PAUSED 写后失效 `MybatisFxPauseBehaviorRepository` 快照即时生效。新增内部 RPC 挂 `/internal/v1/trading/console/*`（沿 `TradingInternalApiTokenFilter` 鉴权），写入校验用 Bean Validation `@Min/@Max/@DecimalMin/@DecimalMax` → 既有 `GlobalExceptionHandler` → `INVALID_REQUEST_PAYLOAD`，**D3a 不新增 trading 错误码**。supplement gating 复用开仓侧 `allow_open` 保守降级口径（category/behavior 缺失→全拒 30087）。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis Plus + XML Mapper / MySQL 8.4 / Flyway / JUnit5 + 隔离测试 schema（Skill 10）。

**关键约束（务必带入，来自 14B-D2 沉淀）：**
- Flyway **严禁** `USE <schema>`（14B root bug）。V37 保持干净，schema 由连接绑定。
- 跑 trading IT 前先 `mvn -pl falconx-market-contract -am install -DskipTests`（contract stale jar 否则 NoSuchMethodError）。
- IT 共享库 `falconx_trading_it` 含 V30/V31 真实 seed，**勿删真实 seed 行**；测试用合成 symbol。
- 默认 `main`，每 task 一个 commit（不 amend）+ 中文 message + 末尾 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。
- 仅 `git add` 本 task 文件，**禁止**混入 untracked（`docker-compose.override.yml` / `qa-ind-*.png`）。
- trading 当前最高 Flyway 版本 **V36** → 新增 **V37**。
- isolated_margin 回填：D1/D2 已豁免（`t_position.margin` + `marginMode` 字段等价），D3 **不加** isolated_margin 列（结论写入 R7，不产生 task）。

---

## File Structure

**新增文件：**
- `falconx-trading-core-service/src/main/resources/db/migration/V37__risk_config_cooling_period.sql` — t_risk_config 平台行加 `cooling_period_seconds`
- `docs/sql/V37__risk_config_cooling_period.sql` — 镜像（沿 V35 docs/sql 镜像惯例）
- `falconx-trading-core-service/src/main/java/com/falconx/trading/controller/AdminInternalTradingConfigController.java` — 配置写/读内部 RPC（冷静期 / 阈值 / fx-pause-behavior）
- `falconx-trading-core-service/src/main/java/com/falconx/trading/command/UpdateCoolingPeriodCommand.java`、`UpdateRiskThresholdsCommand.java`、`UpdateFxPauseBehaviorCommand.java` — record 命令对象（Bean Validation 注解）
- `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPlatformConfigApplicationService.java` — 配置写编排（写 DB + 触发缓存失效）
- `falconx-trading-core-service/src/test/java/com/falconx/trading/application/TradingPlatformConfigApplicationServiceTests.java` — 配置写 UT
- `falconx-trading-core-service/src/test/java/com/falconx/trading/AdminInternalTradingConfigControllerIntegrationTests.java` — 配置 RPC IT
- `falconx-trading-core-service/src/test/java/com/falconx/trading/SupplementMarginFxPauseGatingTests.java` — supplement gating UT/IT
- `falconx-trading-core-service/src/test/java/com/falconx/trading/FxPauseBehavior8x3AcceptanceIntegrationTests.java` — 8×3 验收 IT
- `docs/test/STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md` — R7 报告

**修改文件：**
- `.../repository/TradingRiskConfigRepository.java` + `MybatisTradingRiskConfigRepository.java` + `mapper/TradingRiskConfigMapper.java` + `resources/mapper/trading/TradingRiskConfigMapper.xml` — 加冷静期读 + 冷静期/阈值写
- `.../application/MarginModeSwitchApplicationService.java` — 冷静期改从 DB 读
- `.../repository/FxPauseBehaviorRepository.java` + `MybatisFxPauseBehaviorRepository.java` + `mapper/FxPauseBehaviorMapper.java` + `resources/mapper/trading/FxPauseBehaviorMapper.xml` — 加 updateByCategory + 缓存失效 + findAll
- `.../application/TradingPositionMarginApplicationService.java` — 接 FX_PAUSED supplement gating
- 文档：`docs/api/FalconX统一接口文档.md`、`docs/api/管理端接口规范.md`（占位/internal RPC）、`docs/database/falconx一期数据库设计.md`、`docs/domain/状态机规范.md`（supplement pause + 8×3）、`docs/setup/当前开发计划.md`、`docs/process/BBook一期完成执行路径.md`

---

## Task 1: V37 — t_risk_config 平台行加 cooling_period_seconds

**Files:**
- Create: `falconx-trading-core-service/src/main/resources/db/migration/V37__risk_config_cooling_period.sql`
- Create: `docs/sql/V37__risk_config_cooling_period.sql`

- [ ] **Step 1: 确认当前最高版本**

Run: `ls falconx-trading-core-service/src/main/resources/db/migration/ | sort -V | tail -3`
Expected: `V35__... V36__...`（确认 V37 是下一个；若已有更高版本则顺延并改本 task 全部 V37 引用）

- [ ] **Step 2: 写 V37 migration（干净，无 USE）**

`falconx-trading-core-service/src/main/resources/db/migration/V37__risk_config_cooling_period.sql`:

```sql
-- STAGE-14D3a: t_risk_config 平台行承载 margin mode 冷静期（admin 运行时可配 60s-7d）
-- 沿用 C1 V31 既有平台行（symbol IS NULL）承载 stop_out_level / margin_call_level，本列同源。
-- 严禁 USE <schema>（14B root bug）；schema 由连接绑定。

ALTER TABLE t_risk_config
    ADD COLUMN cooling_period_seconds INT NOT NULL DEFAULT 300
    COMMENT 'margin mode 切换冷静期秒数（admin 可配 60-604800，默认 300=5min）' AFTER margin_call_level;
```

- [ ] **Step 3: 写 docs/sql 镜像**

`docs/sql/V37__risk_config_cooling_period.sql`: 内容与 Step 2 完全一致（沿 V35 docs/sql 镜像惯例）。

- [ ] **Step 4: 编译 + 干净库 migrate 自测**

Run: `mvn -pl falconx-trading-core-service -am compile -DskipTests -q 2>&1 | tail -5`
Expected: `BUILD SUCCESS`

（migrate 验证由 Task 4 的 IT 在隔离库实证；此处仅确认 migration 文件可被加载、SQL 语法正确。）

- [ ] **Step 5: Commit**

```bash
git add falconx-trading-core-service/src/main/resources/db/migration/V37__risk_config_cooling_period.sql docs/sql/V37__risk_config_cooling_period.sql
git commit -m "feat(trading): STAGE-14D3a Task1 V37 t_risk_config 平台行加 cooling_period_seconds（admin 可配 60-604800 默认 300）+ docs/sql 镜像

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 冷静期读路径改从 DB 读（MarginModeSwitchApplicationService）

**Files:**
- Modify: `.../repository/mapper/TradingRiskConfigMapper.java`
- Modify: `.../resources/mapper/trading/TradingRiskConfigMapper.xml`
- Modify: `.../repository/TradingRiskConfigRepository.java`
- Modify: `.../repository/MybatisTradingRiskConfigRepository.java`
- Modify: `.../application/MarginModeSwitchApplicationService.java`
- Test: `.../test/.../application/MarginModeSwitchApplicationServiceTests.java`（D1 既有，追加用例）

参照：阈值读路径 `TradingRiskConfigMapper.selectPlatformMarginThresholds`（XML 行 ~62-70）+ `findPlatformMarginThresholds`。冷静期为低频（用户切模式时读），直读 DB 无需缓存，缺失回退 properties 默认。

- [ ] **Step 1: Mapper 加查询方法**

`TradingRiskConfigMapper.java` 加：
```java
/** 读平台行（symbol IS NULL）冷静期秒数；无行返回 null。 */
Integer selectPlatformCoolingPeriodSeconds();
```

`TradingRiskConfigMapper.xml` 加（参照 selectPlatformMarginThresholds 的 `WHERE symbol IS NULL LIMIT 1`）：
```xml
<select id="selectPlatformCoolingPeriodSeconds" resultType="java.lang.Integer">
    SELECT cooling_period_seconds
    FROM t_risk_config
    WHERE symbol IS NULL
    LIMIT 1
</select>
```

- [ ] **Step 2: Repository 加方法**

`TradingRiskConfigRepository.java` 加：
```java
/** 平台冷静期秒数；DB 行缺失返回 empty（调用方回退默认）。 */
java.util.Optional<Integer> findPlatformCoolingPeriodSeconds();
```

`MybatisTradingRiskConfigRepository.java` 实现（参照 findPlatformMarginThresholds）：
```java
@Override
public Optional<Integer> findPlatformCoolingPeriodSeconds() {
    return Optional.ofNullable(tradingRiskConfigMapper.selectPlatformCoolingPeriodSeconds());
}
```

- [ ] **Step 3: 写失败 UT（冷静期从 DB 读）**

`MarginModeSwitchApplicationServiceTests.java` 追加（mock `TradingRiskConfigRepository`）：
```java
@Test
void switchMode_usesCoolingPeriodFromDbWhenPresent() {
    // given: repo.findPlatformCoolingPeriodSeconds() 返回 120（2min，非默认 5min）
    when(riskConfigRepository.findPlatformCoolingPeriodSeconds()).thenReturn(Optional.of(120));
    // ... 构造无 OPEN 持仓 / 无挂单 / 不在冷静期 / 目标模式不同 的可切换账户
    // when: switchMode 成功
    // then: 写入的 mode_cooling_until ≈ now + 120s（断言落库 account.modeCoolingUntil 距 now 约 120s，容差 5s）
}

@Test
void switchMode_fallsBackToPropertiesDefaultWhenDbMissing() {
    when(riskConfigRepository.findPlatformCoolingPeriodSeconds()).thenReturn(Optional.empty());
    // then: mode_cooling_until ≈ now + properties 默认（300s）
}
```

- [ ] **Step 2.5: Run UT 验证失败**

Run: `mvn -pl falconx-trading-core-service test -Dtest=MarginModeSwitchApplicationServiceTests -q 2>&1 | tail -15`
Expected: 新增 2 用例 FAIL（service 仍读 properties，未调 repo）。

- [ ] **Step 4: 改 MarginModeSwitchApplicationService 读路径**

定位当前 `properties.getMarginMode().getCoolingDuration()` 消费点（约行 176）。改为：
```java
// 冷静期优先读 DB（admin 可配，t_risk_config 平台行）；缺失回退 properties 默认（兼容 + 干净库默认 300s）
long coolingSeconds = riskConfigRepository.findPlatformCoolingPeriodSeconds()
        .map(Integer::longValue)
        .orElseGet(() -> properties.getMarginMode().getCoolingDuration().toSeconds());
OffsetDateTime coolingUntil = now.plusSeconds(coolingSeconds);
```
注入 `TradingRiskConfigRepository riskConfigRepository`（构造器注入，沿现有风格）。

- [ ] **Step 5: Run UT 验证通过 + D1 既有用例不回归**

Run: `mvn -pl falconx-trading-core-service test -Dtest=MarginModeSwitchApplicationServiceTests -q 2>&1 | tail -15`
Expected: 全绿（D1 既有 12 UT + 新增 2）。

- [ ] **Step 6: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/repository/ falconx-trading-core-service/src/main/resources/mapper/trading/TradingRiskConfigMapper.xml falconx-trading-core-service/src/main/java/com/falconx/trading/application/MarginModeSwitchApplicationService.java falconx-trading-core-service/src/test/java/com/falconx/trading/application/MarginModeSwitchApplicationServiceTests.java
git commit -m "feat(trading): STAGE-14D3a Task2 冷静期改从 t_risk_config 平台行读（缺失回退 properties 默认 300s）+ UT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 冷静期 + 阈值写方法（Mapper / Repository）

**Files:**
- Modify: `.../repository/mapper/TradingRiskConfigMapper.java` + `.../mapper/trading/TradingRiskConfigMapper.xml`
- Modify: `.../repository/TradingRiskConfigRepository.java` + `MybatisTradingRiskConfigRepository.java`
- Test: `.../test/.../repository/MybatisTradingRiskConfigRepositoryConfigWriteTests.java`（新建，或并入既有 repo IT）

参照既有写方法 `updatePlatformHedgeThreshold`（同表平台行 UPDATE）。

- [ ] **Step 1: Mapper 加写方法**

`TradingRiskConfigMapper.java` 加：
```java
int updatePlatformCoolingPeriodSeconds(@Param("coolingPeriodSeconds") int coolingPeriodSeconds);
int updatePlatformMarginThresholds(@Param("stopOutLevel") java.math.BigDecimal stopOutLevel,
                                   @Param("marginCallLevel") java.math.BigDecimal marginCallLevel);
```

`TradingRiskConfigMapper.xml` 加（UPDATE 平台行 `symbol IS NULL`）：
```xml
<update id="updatePlatformCoolingPeriodSeconds">
    UPDATE t_risk_config SET cooling_period_seconds = #{coolingPeriodSeconds}
    WHERE symbol IS NULL
</update>
<update id="updatePlatformMarginThresholds">
    UPDATE t_risk_config SET stop_out_level = #{stopOutLevel}, margin_call_level = #{marginCallLevel}
    WHERE symbol IS NULL
</update>
```

- [ ] **Step 2: Repository 加写方法**

`TradingRiskConfigRepository.java` 加：
```java
void updatePlatformCoolingPeriodSeconds(int coolingPeriodSeconds);
void updatePlatformMarginThresholds(java.math.BigDecimal stopOutLevel, java.math.BigDecimal marginCallLevel);
```
`MybatisTradingRiskConfigRepository.java` 委托 mapper。

- [ ] **Step 3: 写 IT（隔离库写后回读）**

`MybatisTradingRiskConfigRepositoryConfigWriteTests.java`（参照既有 repo IT，`@ExtendWith(E2EDatabaseCleanupExtension.class)` + 独立 `_DB_NAME`，或并入既有 trading repo IT 套件）：
```java
@Test
void updateCoolingPeriod_thenReadReflectsNewValue() {
    repository.updatePlatformCoolingPeriodSeconds(600);
    assertThat(repository.findPlatformCoolingPeriodSeconds()).contains(600);
}

@Test
void updateMarginThresholds_thenReadReflectsNewValues() {
    repository.updatePlatformMarginThresholds(new BigDecimal("0.250000"), new BigDecimal("1.200000"));
    MarginThresholds t = repository.findPlatformMarginThresholds().orElseThrow();
    assertThat(t.stopOutLevel()).isEqualByComparingTo("0.25");
    assertThat(t.marginCallLevel()).isEqualByComparingTo("1.20");
}
```

- [ ] **Step 4: Run IT 验证通过**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test -Dtest=MybatisTradingRiskConfigRepositoryConfigWriteTests -q 2>&1 | tail -15`
Expected: 全绿（隔离库真 migrate 到 V37 + 写回读一致）。

- [ ] **Step 5: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/repository/ falconx-trading-core-service/src/main/resources/mapper/trading/TradingRiskConfigMapper.xml falconx-trading-core-service/src/test/java/com/falconx/trading/repository/MybatisTradingRiskConfigRepositoryConfigWriteTests.java
git commit -m "feat(trading): STAGE-14D3a Task3 t_risk_config 平台行冷静期/StopOut阈值写方法（Mapper+Repository）+ 隔离库写回读 IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 配置写/读内部 RPC（冷静期 + 阈值 + GET 平台配置）

**Files:**
- Create: `.../command/UpdateCoolingPeriodCommand.java`、`UpdateRiskThresholdsCommand.java`
- Create: `.../application/TradingPlatformConfigApplicationService.java`
- Create: `.../controller/AdminInternalTradingConfigController.java`
- Test: `.../test/.../application/TradingPlatformConfigApplicationServiceTests.java`
- Test: `.../test/.../AdminInternalTradingConfigControllerIntegrationTests.java`

参照：`AdminInternalTradingTierController`（内部 RPC controller 结构 + `TradingInternalApiTokenFilter` 鉴权）、`AdminInternalTradingConsoleController.updateAutoLiquidateSwitch`（写端点 + ApiResponse 包装）。**校验用 Bean Validation，无新错误码。**

- [ ] **Step 1: 命令 record（Bean Validation）**

`UpdateCoolingPeriodCommand.java`:
```java
public record UpdateCoolingPeriodCommand(
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(60) @jakarta.validation.constraints.Max(604800)
    Integer coolingPeriodSeconds) {}
```
`UpdateRiskThresholdsCommand.java`:
```java
public record UpdateRiskThresholdsCommand(
    @NotNull @DecimalMin("0.05") @DecimalMax("0.95") java.math.BigDecimal stopOutLevel,
    @NotNull @DecimalMin("0.50") @DecimalMax("2.00") java.math.BigDecimal marginCallLevel) {}
```
（范围对齐 master §7.6：cooling 60-604800、stop-out 0.05-0.95、margin-call 0.50-2.00。）

- [ ] **Step 2: ApplicationService**

`TradingPlatformConfigApplicationService.java`（`@Service`，注入 `TradingRiskConfigRepository`；写后 INFO 日志）：
```java
@Transactional
public void updateCoolingPeriod(int coolingPeriodSeconds) {
    log.info("trading.config.cooling-period.update.request seconds={}", coolingPeriodSeconds);
    riskConfigRepository.updatePlatformCoolingPeriodSeconds(coolingPeriodSeconds);
    log.info("trading.config.cooling-period.update.completed seconds={}", coolingPeriodSeconds);
}

@Transactional
public void updateRiskThresholds(BigDecimal stopOutLevel, BigDecimal marginCallLevel) {
    log.info("trading.config.risk-thresholds.update.request stopOut={} marginCall={}", stopOutLevel, marginCallLevel);
    riskConfigRepository.updatePlatformMarginThresholds(stopOutLevel, marginCallLevel);
    log.info("trading.config.risk-thresholds.update.completed stopOut={} marginCall={}", stopOutLevel, marginCallLevel);
}

/** GET 平台配置：冷静期 + 阈值（D3b console 两页共用）。 */
public PlatformConfigView getPlatformConfig() {
    int cooling = riskConfigRepository.findPlatformCoolingPeriodSeconds().orElse(300);
    MarginThresholds t = riskConfigRepository.findPlatformMarginThresholds()
        .orElse(new MarginThresholds(new BigDecimal("0.30"), new BigDecimal("1.00")));
    return new PlatformConfigView(cooling, t.stopOutLevel(), t.marginCallLevel());
}

public record PlatformConfigView(int coolingPeriodSeconds, BigDecimal stopOutLevel, BigDecimal marginCallLevel) {}
```
> 阈值写后由 `DefaultMarginLevelMonitor` 既有 30s TTL 缓存自然生效（≤30s，同 C2 tier 口径，不强制 invalidate）。冷静期为直读 DB（无缓存），即时生效。

- [ ] **Step 3: 写 ApplicationService UT**

`TradingPlatformConfigApplicationServiceTests.java`（mock repository）：验证 updateCoolingPeriod / updateRiskThresholds 委托 repository 对应方法各一次；getPlatformConfig 在 repo empty 时回退 300/0.30/1.00。

- [ ] **Step 4: Controller（内部 RPC）**

`AdminInternalTradingConfigController.java`（`@RestController @RequestMapping("/internal/v1/trading/console/config")`，方法 `@Valid @RequestBody`，ApiResponse 包装同 tier controller）：
```java
@GetMapping("/platform-risk")
public ApiResponse<PlatformConfigView> getPlatformConfig() { ... return success(service.getPlatformConfig()); }

@PutMapping("/cooling-period")
public ApiResponse<Void> updateCoolingPeriod(@Valid @RequestBody UpdateCoolingPeriodCommand cmd) {
    service.updateCoolingPeriod(cmd.coolingPeriodSeconds()); return success(null);
}

@PutMapping("/risk-thresholds")
public ApiResponse<Void> updateRiskThresholds(@Valid @RequestBody UpdateRiskThresholdsCommand cmd) {
    service.updateRiskThresholds(cmd.stopOutLevel(), cmd.marginCallLevel()); return success(null);
}
```

- [ ] **Step 5: 写 RPC IT**

`AdminInternalTradingConfigControllerIntegrationTests.java`（MockMvc + 带 internal token header；隔离库或同进程真 DB）：
- PUT cooling-period {600} → 200；GET platform-risk → coolingPeriodSeconds=600
- PUT cooling-period {30}（<60）→ 400 INVALID_REQUEST_PAYLOAD（Bean Validation）
- PUT cooling-period {700000}（>604800）→ 400
- PUT risk-thresholds {0.25, 1.20} → 200；GET → stopOutLevel=0.25 marginCallLevel=1.20
- PUT risk-thresholds {0.01, 1.00}（stopOut<0.05）→ 400

- [ ] **Step 6: Run UT+IT**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test -Dtest='TradingPlatformConfigApplicationServiceTests,AdminInternalTradingConfigControllerIntegrationTests' -q 2>&1 | tail -20`
Expected: 全绿。

- [ ] **Step 7: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/command/Update*Command.java falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPlatformConfigApplicationService.java falconx-trading-core-service/src/main/java/com/falconx/trading/controller/AdminInternalTradingConfigController.java falconx-trading-core-service/src/test/java/com/falconx/trading/application/TradingPlatformConfigApplicationServiceTests.java falconx-trading-core-service/src/test/java/com/falconx/trading/AdminInternalTradingConfigControllerIntegrationTests.java
git commit -m "feat(trading): STAGE-14D3a Task4 冷静期/StopOut阈值/平台配置 internal RPC（/internal/v1/trading/console/config，Bean Validation 60-604800 / 0.05-0.95 / 0.50-2.00）+ UT/IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: supplement-margin 接 FX_PAUSED 闸门（30087）

**Files:**
- Modify: `.../application/TradingPositionMarginApplicationService.java`
- Test: `.../test/.../SupplementMarginFxPauseGatingTests.java`

参照开仓 gating `DefaultTradingRiskService.evaluatePauseOpenRejection`（行 ~418-438）：`hasActiveGlobalPause()` → 取 `SymbolSpec.category()` → `FxPauseBehaviorRepository.findByCategory` → `allowOpen=false` 或 category/behavior 缺失 → 拒。supplement 等同"加保证金（开仓侧风险）"，用 `allow_open` 开关。插入点：`POSITION_NOT_ISOLATED`（30085）校验之后、余额校验（40001）之前。

- [ ] **Step 1: 写失败 UT/IT**

`SupplementMarginFxPauseGatingTests.java`：
- GLOBAL_PAUSE active + forex 仓（allow_open=false）→ supplement 抛 `GLOBAL_PAUSE_ACTIVE`（30087）
- GLOBAL_PAUSE active + crypto 仓（allow_open=true）→ supplement 放行（继续后续校验）
- GLOBAL_PAUSE active + category=null（spec 缺失）→ 保守全拒 30087
- 无 GLOBAL_PAUSE → supplement 正常放行（回归，不受影响）

- [ ] **Step 2: Run 验证失败**

Run: `mvn -pl falconx-trading-core-service test -Dtest=SupplementMarginFxPauseGatingTests -q 2>&1 | tail -15`
Expected: FAIL（gating 未接，pause 下 forex supplement 未被拒）。

- [ ] **Step 3: 接 gating**

`TradingPositionMarginApplicationService.addIsolatedMargin` 在 30085 校验后插入（注入 `TradingRiskControlActionRepository` / `MarketSymbolSpecRepository` / `FxPauseBehaviorRepository`，参照 `DefaultTradingRiskService` 既有注入）：
```java
// FX_PAUSED 闸门：supplement 视同开仓侧加保证金，用 allow_open；category/behavior 缺失保守全拒（同开仓口径）
if (riskControlActionRepository.hasActiveGlobalPause()) {
    Integer category = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
            .map(SymbolSpec::category).orElse(null);
    boolean allowOpen = category != null
            && fxPauseBehaviorRepository.findByCategory(category).map(FxPauseBehavior::allowOpen).orElse(false);
    if (!allowOpen) {
        log.warn("trading.supplement.fx-pause.rejected positionId={} symbol={} category={}",
                positionId, position.symbol(), category);
        throw new TradingBusinessException(TradingErrorCode.GLOBAL_PAUSE_ACTIVE);
    }
}
```
（确认 `hasActiveGlobalPause()` / `findByPlatformSymbol` / `findByCategory` / `SymbolSpec.category()` / `FxPauseBehavior.allowOpen()` 与开仓侧用法一致；方法名以仓库实际为准。）

- [ ] **Step 4: Run 验证通过 + supplement 既有用例不回归**

Run: `mvn -pl falconx-trading-core-service test -Dtest='SupplementMarginFxPauseGatingTests,TradingPositionMarginApplicationServiceTests' -q 2>&1 | tail -15`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPositionMarginApplicationService.java falconx-trading-core-service/src/test/java/com/falconx/trading/SupplementMarginFxPauseGatingTests.java
git commit -m "feat(trading): STAGE-14D3a Task5 supplement-margin 接 FX_PAUSED 闸门（allow_open，category/behavior 缺失保守全拒 30087）+ UT/IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: FxPauseBehavior 写方法 + 缓存失效

**Files:**
- Modify: `.../repository/mapper/FxPauseBehaviorMapper.java` + `.../mapper/trading/FxPauseBehaviorMapper.xml`
- Modify: `.../repository/FxPauseBehaviorRepository.java` + `MybatisFxPauseBehaviorRepository.java`
- Test: `.../test/.../repository/MybatisFxPauseBehaviorRepositoryWriteTests.java`

C2 已建只读模型（selectByCategory/selectAll + 60s TTL AtomicReference 快照）。本 task 加 updateByCategory + 写后失效快照。`allow_close` 始终 1（master §6.5 手动平仓不限制），写时强制为 1。

- [ ] **Step 1: Mapper 写方法**

`FxPauseBehaviorMapper.java` 加：
```java
int updateByCategory(@Param("category") int category,
                     @Param("allowOpen") boolean allowOpen,
                     @Param("allowClose") boolean allowClose,
                     @Param("allowLiquidation") boolean allowLiquidation,
                     @Param("adminUserId") Long adminUserId);
```
`FxPauseBehaviorMapper.xml` 加：
```xml
<update id="updateByCategory">
    UPDATE t_fx_pause_behavior
    SET allow_open = #{allowOpen}, allow_close = #{allowClose}, allow_liquidation = #{allowLiquidation},
        updated_by_admin_id = #{adminUserId}
    WHERE category = #{category}
</update>
```

- [ ] **Step 2: Repository 写方法 + 失效快照 + findAll**

`FxPauseBehaviorRepository.java` 加：
```java
void updateByCategory(int category, boolean allowOpen, boolean allowClose, boolean allowLiquidation, Long adminUserId);
java.util.List<FxPauseBehavior> findAll();
```
`MybatisFxPauseBehaviorRepository.java`：updateByCategory 调 mapper 后将缓存 `AtomicReference<CachedSnapshot>` 置 null（下次读重建，写即时生效）；findAll 复用既有全量加载（缓存快照转 list）。

- [ ] **Step 3: 写 IT（写后立即读到 + 快照失效）**

`MybatisFxPauseBehaviorRepositoryWriteTests.java`（隔离库；注意 V31 seed 的真实 8 行勿删）：
```java
@Test
void updateByCategory_thenFindByCategoryReflectsImmediately() {
    // crypto(category=1) 默认 allow_open=true → 改为 false
    repository.updateByCategory(1, false, true, false, 999L);
    FxPauseBehavior b = repository.findByCategory(1).orElseThrow();
    assertThat(b.allowOpen()).isFalse();
    assertThat(b.allowLiquidation()).isFalse();
}
```
> 测试后该 IT 修改了共享 IT 库 seed 值；用 `@AfterEach` 或测试内改回，或在隔离库（独立 `_DB_NAME`）跑避免污染共享 `falconx_trading_it`。**优先隔离库。**

- [ ] **Step 4: Run IT**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test -Dtest=MybatisFxPauseBehaviorRepositoryWriteTests -q 2>&1 | tail -15`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/repository/ falconx-trading-core-service/src/main/resources/mapper/trading/FxPauseBehaviorMapper.xml falconx-trading-core-service/src/test/java/com/falconx/trading/repository/MybatisFxPauseBehaviorRepositoryWriteTests.java
git commit -m "feat(trading): STAGE-14D3a Task6 FxPauseBehavior updateByCategory + 写后失效快照即时生效 + findAll + 隔离库 IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: FxPauseBehavior 内部 RPC（GET 全量 / PUT 按类目）

**Files:**
- Modify: `.../controller/AdminInternalTradingConfigController.java`（Task4 已建，追加）
- Create: `.../command/UpdateFxPauseBehaviorCommand.java`
- Modify: `.../application/TradingPlatformConfigApplicationService.java`（追加 fx 方法）
- Test: `.../test/.../AdminInternalTradingConfigControllerIntegrationTests.java`（追加）

- [ ] **Step 1: 命令 record**

`UpdateFxPauseBehaviorCommand.java`:
```java
public record UpdateFxPauseBehaviorCommand(
    @NotNull Boolean allowOpen,
    @NotNull Boolean allowClose,
    @NotNull Boolean allowLiquidation) {}
```

- [ ] **Step 2: ApplicationService 追加**

`TradingPlatformConfigApplicationService.java`:
```java
public List<FxPauseBehavior> listFxPauseBehaviors() { return fxPauseBehaviorRepository.findAll(); }

@Transactional
public void updateFxPauseBehavior(int category, boolean allowOpen, boolean allowClose, boolean allowLiquidation, Long adminUserId) {
    log.info("trading.config.fx-pause.update.request category={} open={} close={} liq={}", category, allowOpen, allowClose, allowLiquidation);
    fxPauseBehaviorRepository.updateByCategory(category, allowOpen, allowClose, allowLiquidation, adminUserId);
    log.info("trading.config.fx-pause.update.completed category={}", category);
}
```
注入 `FxPauseBehaviorRepository`。

- [ ] **Step 3: Controller 追加**

```java
@GetMapping("/fx-pause-behavior")
public ApiResponse<List<FxPauseBehavior>> listFxPauseBehaviors() { return success(service.listFxPauseBehaviors()); }

@PutMapping("/fx-pause-behavior/{category}")
public ApiResponse<Void> updateFxPauseBehavior(
        @PathVariable @Min(1) @Max(8) int category,
        @Valid @RequestBody UpdateFxPauseBehaviorCommand cmd,
        @RequestHeader(value = "X-Admin-User-Id", required = false) Long adminUserId) {
    service.updateFxPauseBehavior(category, cmd.allowOpen(), cmd.allowClose(), cmd.allowLiquidation(), adminUserId);
    return success(null);
}
```
> `X-Admin-User-Id` 由 `TradingInternalApiTokenFilter` 校验注入（沿 tier RPC 既有 header 口径，确认实际 header 名）。控制器类需 `@Validated` 以使 `@PathVariable @Min/@Max` 生效。

- [ ] **Step 4: RPC IT 追加**

`AdminInternalTradingConfigControllerIntegrationTests.java` 追加：
- GET fx-pause-behavior → 8 行（category 1-8）
- PUT fx-pause-behavior/2 {allowOpen:true,allowClose:true,allowLiquidation:true}（forex 默认全停 → 改全开）→ 200；GET 验证 category=2 allowOpen=true
- PUT fx-pause-behavior/9（越界）→ 400
- PUT fx-pause-behavior/1 缺字段 → 400

- [ ] **Step 5: Run IT**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test -Dtest=AdminInternalTradingConfigControllerIntegrationTests -q 2>&1 | tail -20`
Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/command/UpdateFxPauseBehaviorCommand.java falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPlatformConfigApplicationService.java falconx-trading-core-service/src/main/java/com/falconx/trading/controller/AdminInternalTradingConfigController.java falconx-trading-core-service/src/test/java/com/falconx/trading/AdminInternalTradingConfigControllerIntegrationTests.java
git commit -m "feat(trading): STAGE-14D3a Task7 FxPauseBehavior internal RPC（GET 全量 8 行 / PUT {category} 1-8 + adminUserId 落审计列）+ IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: FX_PAUSED 8 类目 × 3 开关组合完整验收 IT（master §8.3 D 硬约束）

**Files:**
- Create: `.../test/.../FxPauseBehavior8x3AcceptanceIntegrationTests.java`

目标：在 GLOBAL_PAUSE active 下，逐一覆盖 8 类目（crypto/forex/metal/index/energy/stock/etf/other）× 3 开关（allow_open 影响开仓+supplement、allow_close 手动平仓恒通、allow_liquidation 影响被动强平）的行为矩阵，作为 master §8.3 D 阶段「FX_PAUSED 8 类目×3 开关组合行为正确」的可证据。

- [ ] **Step 1: 写验收 IT**

`FxPauseBehavior8x3AcceptanceIntegrationTests.java`（同进程真 DB / 隔离库；用合成 symbol 绑各 category，激活 GLOBAL_PAUSE）。结构：
- 参数化 8 个 category：对每个 category 用 admin 写路径设置三开关，断言：
  - `allow_open=false` → 开仓拒 30087 **且** supplement 拒 30087；`allow_open=true` → 开仓放行 + supplement 放行
  - `allow_close` 恒为 1 → 手动平仓始终成功（即使 pause）
  - `allow_liquidation=false` → 被动强平 skip（QuoteDrivenEngine 不平）；`true` → 被动强平执行
- 至少覆盖：默认 seed 矩阵（forex/metal 停开仓+停强平、其余允许）各 1 次 + admin override 翻转 forex→全开 / crypto→全停 各 1 次验证可配生效。
- category=null / behavior 缺失双向降级（开仓全拒 / 强平继续）各 1 次（沿 C2 口径）。

断言示例：
```java
@Test
void forex_pauseDefault_blocksOpenAndSupplement_skipsLiquidation_allowsManualClose() {
    activateGlobalPause();
    // forex(category=2) 默认 allow_open=0 allow_close=1 allow_liquidation=0
    assertOpenRejected("SYNTH_FOREX", 30087);
    assertSupplementRejected(forexPositionId, 30087);
    assertManualCloseSucceeds(forexPositionId);           // allow_close 恒通
    assertPassiveLiquidationSkipped("SYNTH_FOREX");       // allow_liquidation=0
}

@Test
void forex_adminEnableOpen_thenOpenAllowed() {
    activateGlobalPause();
    configRpc.updateFxPauseBehavior(2, true, true, true, 999L);  // admin 翻转 forex 全开
    assertOpenAllowed("SYNTH_FOREX");                            // 可配即时生效（快照失效）
}
```

- [ ] **Step 2: Run 验收 IT**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test -Dtest=FxPauseBehavior8x3AcceptanceIntegrationTests -q 2>&1 | tail -25`
Expected: 全绿（8 类目矩阵 + override + 降级）。

- [ ] **Step 3: trading 全量非 IT 回归 + 关键 IT 套件**

Run: `mvn -pl falconx-trading-core-service test -q 2>&1 | tail -30`
Expected: 全绿（如遇 pre-existing flake `TradingKafkaWalletDepositIntegrationTests` 单独标注，不阻断）。

- [ ] **Step 4: Commit**

```bash
git add falconx-trading-core-service/src/test/java/com/falconx/trading/FxPauseBehavior8x3AcceptanceIntegrationTests.java
git commit -m "test(trading): STAGE-14D3a Task8 FX_PAUSED 8 类目×3 开关组合完整验收 IT（开仓/supplement/被动强平/手动平仓 + admin override 可配生效 + 降级）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: R8 文档同步 + R7 收口报告 + 计划录入

**Files:**
- Modify: `docs/api/FalconX统一接口文档.md`（§3.31.x 配置 internal RPC + supplement 30087 补充）
- Modify: `docs/api/管理端接口规范.md`（占位 D3b console 端点 + internal RPC 登记）
- Modify: `docs/database/falconx一期数据库设计.md`（V37 cooling_period_seconds）
- Modify: `docs/domain/状态机规范.md`（§6.x supplement FX_PAUSED gating + 8×3 矩阵）
- Modify: `docs/process/BBook一期完成执行路径.md`（§15G STAGE-14D3a 收口）
- Modify: `docs/setup/当前开发计划.md`（§1 D3a 收口条目 + 下一步 D3b）
- Create: `docs/test/STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md`

- [ ] **Step 1: R8 同步上述真源文档**（沿 D2 R8 清单结构；isolated_margin 不加列的结论写入 DB 设计 + 计划）

- [ ] **Step 2: R7 收口报告**（沿 D2 报告结构：§0 部署阻断项=沿 14B V28/V29 + D3a 新增仅 V37 干净无 USE；§1 范围；§3 各 task 证据；§4 测试统计；§5 已知不阻断；§7 master §8.3 D 硬约束对照——本片补「FX_PAUSED 8 类目×3」+「冷静期 admin 可配」+「StopOut admin 可配」；§9 结论 + 使用说明 + 下一步 D3b）

- [ ] **Step 3: 计划 §1 录入 D3a 收口条目**（格式同 D2，含 commits / 测试统计 / 阻断项 / 下一步 D3b）

- [ ] **Step 4: 链接完整性自检 + commit**

```bash
git add docs/
git commit -m "docs(R7+R8): STAGE-14D3a 运营可配后端 + supplement pause gating + FX_PAUSED 8×3 验收收口报告 + 文档同步 + 计划录入（下一步 D3b console 三端 UI）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 下一步切片（避免计划真空，§3.6.4）：STAGE-14D3b console 三端 UI

D3a 后端就位后，D3b 照搬 C2 console 模式交付 3 个 admin 配置页（三端）：
1. **冷静期配置页**（`margin-mode-config:view/edit` 高危）：console 透传 `PUT /internal/v1/trading/console/config/cooling-period`；前端新建独立单 Form 页（参照 `PlatformRiskConfigPage` + `RequiresPermission` 包裹 + 二次确认/reason）。
2. **StopOut/MarginCall 阈值配置页**（`risk-threshold:view/edit` 高危）：console 透传 `PUT .../config/risk-thresholds`；前端独立页（参照 `UserRiskThresholdListPage` 或单 Form）。
3. **FX_PAUSED 8 类目行为配置页**（`fx:pause-behavior:view/edit` 高危）：console 透传 `GET/PUT .../fx-pause-behavior[/{category}]`；前端固定 8 行 Table + Switch + 高危 Modal（参照 `RiskMarketConfigListPage`）。
- console 侧新增错误码 **90950 ADMIN_MARGIN_MODE_CONFIG_INVALID / 90951 ADMIN_FX_PAUSE_BEHAVIOR_INVALID**（master §7.3）+ 阈值非法码（90952 或复用）+ `AdminGlobalExceptionHandler` 翻译；console migration **V13** seed 6 个权限点（view/edit ×3）+ 角色关联（照搬已持 risk-config 角色）+ 3 个菜单 + `HighRiskPermissionRegistry` 注册 3 个 edit 码。
- 验证：console 透传 IT（WireMock for trading）+ console-frontend vitest（3 页 CRUD/RBAC/二次确认）+ 三件套 + 至少 1 条 E2E（console→trading）语义拼接（WSL 受限手动项标注）。
