# FalconX Flyway V28/V29 schema 漂移修复 Runbook

> 任务：`STAGE-14B-V28V29-SCHEMA-REPAIR`
>
> 本手册供 DBA / 部署执行人在向**被 STAGE-14B root bug 污染过的 `falconx_trading` 库**（含远程演示库）部署 trading-core 前，先修复 V28/V29 Flyway 历史漂移，避免服务启动时 `flyway migrate` 撞 `Duplicate column` 阻断。
>
> 干净全新库（首次部署）无此问题，无需执行本手册。console 库（`falconx_console`）V1-V14 全干净无污染，亦无需 repair——仅按正常部署顺序迁移即可。

> **2026-06-02 实测修正**：经直接查询，**演示库 `falconx_trading` 实为干净 V27**（无 V28-V37 history 行、且三列/`entry_fx_rate` 物理不存在），**并非污染态**——污染仅发生在本地 dev 库（IT 跑经 USE 副作用建列）。该日全栈部署时 trading-core flyway 正常 migrate V27→V37（0 失败），**演示库无需 repair**。R7 报告/本手册早前「演示库已污染」属未经核实的假设，特此修正：是否需 repair **一律以目标库 §1.2 preflight 实测为准**，不假设。

---

## 1. 背景与判定

### 1.1 根因（真源指向，不重复正文）

详见 [STAGE-14B R7 报告 §0](../test/STAGE-14B-CURRENCY-CONVERTER-R7-verification-report.md) 与 [当前开发计划 §1 14B 条目](../setup/当前开发计划.md)。

要点：V28（`t_ledger` 三列）/ V29（`t_position.entry_fx_rate`）迁移在 root bug 期间误写 `USE falconx_trading;`（已由 commit `1031a9ce` 删除），把 DDL 打到了 `falconx_trading` 库而非当时连接的库。结果该库：

- `t_ledger` 三列（`original_amount` / `original_currency` / `fx_rate_at_settlement`）+ `t_position.entry_fx_rate` **列已物理存在且已回填（NOT NULL）**；
- `flyway_schema_history` **没有 V28/V29 行**（迁移记录落到了当时连接的别的库）。

删除 `USE` 后，trading-core 下次启动 → `flyway migrate` 见 history 无 V28 → 尝试重跑 `ALTER TABLE ADD COLUMN` → 列已存在 → `Duplicate column` → 启动失败 → 部署阻断。

### 1.2 适用判定（执行前必跑）

连接到目标库执行 [repair 脚本](../../scripts/db-repair/V28V29-flyway-history-repair.sql) 的 **A 段 preflight**（脚本守卫式，preflight 为只读）。判定为「待修复漂移态」需同时满足：

| preflight 检查 | 待修复库期望值 | 含义 |
|---|---|---|
| A1 `max_installed_rank` / `max_version` | `25` / `27` | flyway 基线停在 V27 |
| A2 已有 V28/V29 行 | 0 行 | history 无 V28/V29 |
| A3 `ledger_cols` / `position_col` | `3` / `1` | 列已物理存在 |
| A4 `ledger_null_residue` / `position_null_residue` | `0` / `0` | 回填完整 |

- 若 A2 已有 V28/V29 行且 `success=1` → 已修复或干净库，**无需执行**（repair 脚本对此为安全 no-op）。
- 若 A2 出现 `success=0` 的 V28/V29 行（说明已有人对本库裸跑过 migrate 并失败）→ **停止**，先人工删除该失败行，再走本手册（repair 脚本对失败行不会覆盖）。
- 若 A3 列不齐（非 3/1）或 A4 有 NULL 残留 → **停止**，状态非本手册覆盖的漂移态，人工核对后再决定。

> 🔴 在 A 段 preflight 核对通过前，**禁止对生产/演示库直接启动 trading-core 或裸跑 flyway migrate**。

---

## 2. 部署链路说明（为什么用手动 SQL 而非 flyway repair）

- trading-core / console-service 的迁移由 **Spring Boot 启动时 `spring.flyway.enabled=true` 自动触发**（`falconx-trading-core-service/src/main/resources/application.yml`）。
- 部署链路**不含 flyway CLI，也无 flyway-maven-plugin**（仅依赖 `flyway-core` / `flyway-mysql` 库）。
- 因此 R7 §0 备选的「flyway repair」DBA 手边没有可用二进制；**本手册以手动 SQL 补 history 行为主路径**（方案 1）。
- 备选方案 2（flyway repair）须另装与 `flyway-core` **同主版本**的 flyway CLI（当前 `11.14.1`，见根 BOM），否则 checksum 算法差异会导致补行后 `validate` 失败。除非已确认版本一致，否则**不推荐**。

---

## 3. 修复流程（生产/演示库，按授权执行）

> 全程连接到**目标库**操作（schema 由连接决定）。示例用 mysql CLI；`<host>` / `<user>` / 目标库名按环境替换。

### Step 1 · 备份 history 表（可回滚锚点）

```sql
-- 连接到目标库后执行
CREATE TABLE flyway_schema_history_bak_14brepair AS SELECT * FROM flyway_schema_history;
```

### Step 2 · 跑 preflight 核对（A 段）

```bash
mysql -h<host> -u<user> -p <目标库名> < scripts/db-repair/V28V29-flyway-history-repair.sql
```

脚本一次性输出 A 段（preflight）+ 执行 B 段（守卫式补行）+ C 段（post-check）。**首次执行前**，可只读地先核对 A 段输出是否符合 §1.2 期望；脚本 B 段带守卫（列不存在或行已存在则插 0 行），即使误跑也安全。

### Step 3 · 核对 post-check（C 段）

C 段应输出 V28/V29 两行：

| version | checksum | success |
|---|---|---|
| 28 | `-448423770` | 1 |
| 29 | `-809397028` | 1 |

checksum 必须与上表精确一致（这两个值是当前 `flyway-core 11.14.1` 对 V28/V29 迁移文件计算的 CRC32，已由复演 IT 校验，见 §5）。

### Step 4 · 启动 trading-core（自动 migrate V30-V37）

按正常部署启动 trading-core。Spring Boot 启动时 flyway 会：

1. `validate` 已应用迁移（含刚补入的 V28/V29，checksum 与文件比对，应通过）；
2. 从 V30 顺序应用 V30/V31/V32/V33/V34/V35/V36/V37。

### Step 5 · 验证末态

```sql
SELECT MAX(CAST(version AS UNSIGNED)) AS max_version,
       SUM(success = 0) AS failed_rows
FROM flyway_schema_history;
-- 期望：max_version = 37，failed_rows = 0
```

服务 `GET /actuator/health` 应为 `UP`（参见 [生产观测与回滚手册 §1](生产观测与回滚手册.md)）。

### Step 6 · 清理备份（确认稳定后）

```sql
DROP TABLE flyway_schema_history_bak_14brepair;
```

---

## 4. 回滚

修复后若需回退到 repair 前：

```sql
-- 删除本脚本补入的 V28/V29 行（installed_by 标记为 '14b-repair'）
DELETE FROM flyway_schema_history WHERE version IN ('28','29') AND installed_by = '14b-repair';
```

或从 Step 1 的备份整表还原：

```sql
DROP TABLE flyway_schema_history;
RENAME TABLE flyway_schema_history_bak_14brepair TO flyway_schema_history;
```

> 注意：回滚仅恢复 history 记录，不回滚已物理存在的列（列本就是 root bug 期间创建、与本 repair 无关）。

---

## 5. 验证证据（本会话）

- **dev `falconx_trading`（生产/演示阻断的实物样本）实测**：`flyway_schema_history` 止于 V27（installed_rank 25），无 V28-V37 行；`t_ledger` 三列 + `t_position.entry_fx_rate` 物理存在（NOT NULL），回填零 NULL 残留（409 ledger / 61 position 行）；V30-V37 产物（`t_symbol_leverage_tier` / `t_fx_pause_behavior` / `mm_rate_at_open` / `mode_cooling_until` / `cooling_period_seconds`）均未物理存在。
- **复演 IT**：`falconx-trading-core-service/.../TradingFlywayV28V29RepairRehearsalIntegrationTests`（2 tests，`Tests run: 2, Failures: 0`）——用 app 同版本 `flyway-core 11.14.1` 在自管隔离库 `falconx_trading_v2829repair_it` 复演：干净 migrate 到 V27 → 手工跑 V28/V29 body 模拟污染 → 执行**真实交付的 repair `.sql`** → flyway migrate 应用 V30-V37（默认 validate 校验补行 checksum 通过）→ 到 V37、`t_symbol_leverage_tier` 等产物存在、无 success=0 行；并验证干净库上 repair 为 no-op。
- checksum 真值源：从干净 IT 库 `falconx_trading_it.flyway_schema_history` 提取（flyway 用相同 CRC32），与 repair 脚本硬编码值一致。

---

## 6. 部署协调（与本 repair 同窗口）

> 这些不是 repair 的一部分，但与本次部署强相关，避免漏项。

- **trading 库**：repair（本手册）→ trading-core 启动 migrate 到 **V37**。
- **console 库**：无 repair，正常 migrate 到 **V14**（V13/V14 干净）。
- **WS 硬 break（STAGE-14E1/E2，无 legacy 兼容）**：`trading-core-service` + `falconx-frontend` + `console-frontend` **必须同发布窗口部署**。先部署后端而旧前端/旧管理端仍在线时，旧端解析新 WS 帧会丢失双币浮盈亏 / marginLevel 字段。详见 [STAGE-14E1 R7 §0](../test/STAGE-14E1-WS-BREAK-CLIENT-UI-R7-verification-report.md) / [STAGE-14E2 R7 §0](../test/STAGE-14E2-ADMIN-FX-AGG-R7-verification-report.md)。
- 演示环境（app/admin-falconx.lifebyteapp.dev）部署流程见 [AWS 演示档部署手册](../setup/AWS演示档部署手册.md)；该库同样被 root bug 污染，部署前须先按本手册 repair。

---

## 7. 关联文档

- [repair 脚本](../../scripts/db-repair/V28V29-flyway-history-repair.sql)
- [复演 IT](../../falconx-trading-core-service/src/test/java/com/falconx/trading/TradingFlywayV28V29RepairRehearsalIntegrationTests.java)
- [STAGE-14B R7 报告 §0](../test/STAGE-14B-CURRENCY-CONVERTER-R7-verification-report.md)
- [当前开发计划 §1](../setup/当前开发计划.md)
- [生产观测与回滚手册](生产观测与回滚手册.md)
