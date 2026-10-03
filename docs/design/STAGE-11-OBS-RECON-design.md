# STAGE-11-OBS-RECON 设计文档（R3 Phase 0，2026-05-15）

> 任务编号：`STAGE-11-OBS-RECON`（BBook 一期可观测性 + 对账）
> 范围：4 子任务 — 11.1 Actuator 健康检查 / 11.2 falconx-canary CLI / 11.3 日志检索手册 / 11.4 入金对账接口（P19）
> 关联：[`管理端接口规范 §14`](../api/管理端接口规范.md) + [`console-pages-V1 §17`](./falconx-console-pages-V1.md) + [`完成定义 §4.A`](../process/完成定义.md)

---

## §1. 任务范围 + 三端硬约束适用

| 子任务 | 三端范围 | 客户端 | 业务后端 | 管理端 |
| --- | --- | --- | --- | --- |
| 11.1 Actuator | 运维 | 豁免 | 6 服务全接入 | 豁免（不暴露给前端） |
| 11.2 canary CLI | 运维 | 豁免 | 新增 module | 豁免（不暴露给前端） |
| 11.3 日志检索手册 | 运维 | 豁免 | 豁免 | 豁免（仅运维文档） |
| 11.4 入金对账 | 三端齐全 | 豁免（无客户端入口）| wallet + trading-core 既有查询接口被聚合 | console-service /admin/reconciliation + console-frontend P19 |

按 R1 启动协议显式声明：**11.1 / 11.2 / 11.3 为纯运维任务，豁免三端硬约束 §4.A 第 1 / 第 4 / 第 11 项；11.4 完整三端**。

---

## §2. 11.1 Actuator 健康检查实施细节

### 2.1 6 服务依赖加入

每个服务 `pom.xml` 加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

不引入 `micrometer-registry-prometheus`（V2 一期不接 Prometheus）；保留 `/actuator/metrics` 默认 JSON 输出即可。

### 2.2 application.yml 配置（统一模板）

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,loggers
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized   # 公开仅 status，详细 components 走内网认证
      probes:
        enabled: true                  # 开启 /actuator/health/liveness + /actuator/health/readiness
    loggers:
      enabled: true
  info:
    git:
      mode: simple
    build:
      enabled: true
```

`/actuator/info` 包含 git commit + build time 需要 `pom.xml` 增 `git-commit-id-maven-plugin` + `spring-boot-maven-plugin` build-info goal（R4 实施时按需追加，可后置 PR 不阻塞 11.1 主体）。

### 2.3 内置 indicator（Spring Boot 自动装配）

各服务依赖装配会自动启用：

- `DataSourceHealthIndicator` —— 所有用 MySQL/HikariCP 的服务
- `RedisHealthIndicator` —— 所有引 Redisson / Lettuce 的服务
- `KafkaHealthIndicator` —— Spring Kafka 默认 admin client metadata 探测
- `DiskSpaceHealthIndicator` —— 通用

### 2.4 自定义 indicator（必选）

#### wallet 端 ETH/TRON RPC 可达性

新增 `EthRpcHealthIndicator implements HealthIndicator`：

- inject `walletWithdrawWeb3j` Bean（已存在）
- `health()` 调 `web3j.netVersion().send()`，5s timeout
- 命中则 `Health.up().withDetail("chainId", chainId).build()`
- 失败 `Health.down().withException(ex).build()`

TRON 类似 `TronRpcHealthIndicator`（V2 一期 TRON 依赖未集成时可省略，标记 `unknown`）。

#### market 端 LP 连接状态

复用既有 `LpConnectionTracker`（市场服务内已有 LP socket 状态），暴露 `LpConnectionHealthIndicator`，详细 component 字段：`{ "lp": "CONNECTED", "lastQuoteAt": "2026-05-15T..." }`。

#### gateway 端下游可达性

Spring Cloud Gateway 自带 `GatewayControllerEndpoint`（routes / globalfilters）；新增 `DownstreamReachabilityHealthIndicator` 探测 5 下游服务 `/actuator/health/liveness`（HEAD 请求 200 即 UP）。

### 2.5 安全配置

`/actuator/health` + `/actuator/health/liveness` + `/actuator/health/readiness` + `/actuator/info` 公开（运维网段 ACL）；`/actuator/metrics` + `/actuator/loggers` 内网受限。

各服务 SecurityConfig 加：

```java
.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
.requestMatchers("/actuator/**").hasRole("INTERNAL_OPS")
```

实际生产由网关 + LP/k8s ingress ACL 兜底；V2 一期暂用 `permitAll()` 对所有 `/actuator/**`（详见 §6 已知不阻断项）。

### 2.6 k8s 探针建议（运维参考，不在代码内）

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: <service-port> }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: <service-port> }
  initialDelaySeconds: 20
  periodSeconds: 5
```

---

## §3. 11.2 falconx-canary CLI 实施细节

### 3.1 模块结构

```
falconx-canary/
├── pom.xml
├── src/main/java/com/falconx/canary/
│   ├── CanaryApplication.java          # @SpringBootApplication + CommandLineRunner
│   ├── command/
│   │   ├── LoginCanaryCommand.java
│   │   ├── TradeCanaryCommand.java
│   │   ├── DepositListenCanaryCommand.java
│   │   ├── WithdrawCanaryCommand.java
│   │   ├── PriceAlertCanaryCommand.java
│   │   ├── ReconCanaryCommand.java
│   │   └── HealthAllCanaryCommand.java
│   ├── client/
│   │   ├── IdentityClient.java         # RestClient 包装 /api/v1/auth/* /me/*
│   │   ├── TradingClient.java          # WebFlux 或 RestClient
│   │   ├── WalletClient.java
│   │   └── AdminClient.java            # 模拟 admin 调 console-service /admin/*
│   └── report/CanaryReport.java        # 退出码 0/1/2 + JSON 报告输出
└── src/main/resources/
    └── application.yml                 # canary.targets.* 6 服务 base url
```

依赖：
- spring-boot-starter（不要 web，只跑 CLI）
- spring-boot-starter-webflux 或 RestClient
- picocli（如选 picocli）或自定义 `args[0]` switch
- jackson-databind

### 3.2 命令详细

| 命令 | 步骤 | PASS 条件 |
| --- | --- | --- |
| `canary login` | POST /api/v1/auth/login → GET /api/v1/me | 200 + me.id 非空 |
| `canary trade` | login → POST /api/v1/me/orders（市价单 BTCUSDT 0.001）→ GET /api/v1/me/positions → POST /api/v1/me/positions/{id}/close | 全 200 + 持仓平仓后 size=0 |
| `canary deposit-listen` | 调 wallet 测试桩 trigger detected → poll wallet `/me/deposits` 60s 内见 confirmed | 60s 内见 status=CONFIRMED |
| `canary withdraw` | login → 创建白名单 → POST /me/withdraw → admin 模拟 approve → 60s 内见 status=COMPLETED | 全 200 + 终态 COMPLETED |
| `canary price-alert` | login → POST /me/price-alerts → poll t_notification | 30s 内见 PRICE_ALERT_TRIGGERED notif |
| `canary recon` | admin login → GET /admin/reconciliation/deposits/unmatched | 200 + total < 阈值（配置项 canary.recon.maxUnmatched 默认 100） |
| `canary health-all` | 并发请求 6 服务 /actuator/health | 全 UP，否则列出 DOWN 列表 |

### 3.3 退出码

`0=PASS / 1=FAIL / 2=PARTIAL_TIMEOUT`，配合 cron / k8s CronJob 触发；失败时输出 JSON 报告到 stderr。

### 3.4 配置示例

```yaml
canary:
  base-url:
    gateway: http://localhost:18080
    identity: http://localhost:18081
    market: http://localhost:18082
    trading-core: http://localhost:18083
    wallet: http://localhost:18084
    console: http://localhost:18085
  admin:
    username: superadmin
    password: ${CANARY_ADMIN_PASSWORD}     # 从环境注入
  recon:
    maxUnmatched: 100
  timeout:
    default: 30s
    deposit-listen: 60s
    withdraw: 120s
```

---

## §4. 11.4 入金对账实施细节

### 4.1 数据流（无新 schema）

```
console-frontend P19
   │
   ▼  GET /admin/reconciliation/deposits/unmatched
console-service AdminReconciliationApplicationService
   │
   ├─→ wallet  GET /internal/v1/wallet/console/deposits?status=CONFIRMED&fromDetectedAt=&to=
   │     (返回 confirmed 项列表，含 chain/txHash/amount/walletTxId/userId)
   │
   └─→ trading-core GET /internal/v1/trading/console/deposits?status=CREDITED&fromCreatedAt=&to=
         (返回 credited 项列表，含 chain/txHash/amount/tradingDepositId)

In-memory diff:
- left outer join by (chain, tx_hash):
    wallet 项无 trading 项 → WALLET_ONLY
    trading 项无 wallet 项（罕见）→ TRADING_ONLY
    两端都有但 amount 不一致 → AMOUNT_MISMATCH
    两端都有但 status 不一致（wallet=REVERSED but trading=CREDITED 等）→ STATUS_DIVERGED
按 walletDetectedAt DESC 排序，应用 page/size 分页（in-memory 切片）
```

### 4.2 console-service 实施

新建 `AdminReconciliationController` `/admin/reconciliation/deposits/{unmatched, {walletTxId}/mark-resolved}`，依赖：

- `WalletInternalRpcClient.listDeposits(WalletDepositListQuery)`（既有，可能需新增 `status=CONFIRMED` 过滤参数）
- `TradingInternalRpcClient.listDepositsForRecon(TradingDepositListQuery)`（既有 admin RPC 是否覆盖需 R9 实施时核对；如不足，trading-core 端新增 internal endpoint）
- `AdminOperationLogRepository.findByFilters` 校验是否已 resolved（target_type='reconciliation' + target_id=walletTxId）

错误码翻译：传输级超时/不可达 → 90920 / 90921；mark-resolved 时已存在审计 → 90924。

### 4.3 上游 API 缺口（Phase 3 R4 + R9 需要核对）

- wallet `/internal/v1/wallet/console/deposits` 现有签名是否支持 `status` 过滤 + `fromDetectedAt/toDetectedAt` 时间窗 + 雪花 ID 字符串响应（Phase 3 R4 实施时核对，缺则补）
- trading-core 端 deposit 查询是否有 admin internal RPC（grep 结果未直接显示；Phase 3 R9 实施时核对，缺则新增）

### 4.4 性能上界

- 默认 time window 24h，预期 unmatched ≤ 数百条/天
- console-service in-memory diff 单次请求处理 ≤ 5000 项可接受（V2 一期）；超过则在 Phase 3 实施时改为 SQL 层 ANTI JOIN（需引入跨 schema 视图或 trading-core 端做对账，本阶段不实施）

### 4.5 RBAC 守门 + 审计

- list 接口 `reconciliation:view`，view 不写审计（OperationAuditAspect 不命中 view 类）
- mark-resolved 接口 `reconciliation:resolve`（高危），`OperationAuditAspect` 自动写 `t_admin_operation_log`：`permission_code='reconciliation:resolve'`、`target_type='reconciliation'`、`target_id={walletTxId}`、`risk_level='MEDIUM'`、`before_value={discrepancyType,walletAmount,tradingAmount}`、`after_value={resolutionType,reason}`

---

## §5. 11.3 日志检索手册大纲（R8 Phase 5 落地）

新写 `docs/operations/日志检索手册.md`，章节：

1. **场景索引**：按业务流（登录 / 下单 / 平仓 / 入金 / 出金 / KYC / 通知 / 风控触发）反查 traceId 入口
2. **关键日志 fingerprint**：每条 INFO/WARN/ERROR 的 logger 名 + 关键字（如 `admin.http.audit-log.list.received` / `wallet.deposit.confirmed`）
3. **traceId 联调**：MDC traceId 在网关 → identity → trading-core → wallet 全链路传递验证，举例查询
4. **错误码反查**：按错误码（10xxx identity / 20xxx wallet / 30xxx trading / 90xxx admin）找到对应日志行 / 业务流
5. **常用 grep / kubectl logs / journalctl 模板**

预期 ~400-600 行，参考 `日志打印规范.md` 但侧重 **如何查** 而非 **如何写**。

---

## §6. 已知不阻断项（设计层显式记录）

1. **Actuator 安全配置 V2 一期暂 permitAll**：内网受限 endpoint（metrics / loggers）依赖 k8s ingress ACL / 运维网段隔离兜底。生产化前需补 SpringSecurity `hasRole("INTERNAL_OPS")` 或 mTLS。
2. **trading-core 端 deposit admin RPC 可能缺失**：Phase 3 R9 实施时若发现缺失需新增 internal endpoint；不视为阶段 11 阻断（必要时补）。
3. **不持久化 unmatched 项**：本阶段不引入 `t_reconciliation_unmatched` 表，每次列表请求都重新 in-memory diff。已 resolved 项凭 `t_admin_operation_log` 中 `target_type='reconciliation'` 条目去重（list 返回时过滤掉已存在审计的项，避免重复列出）。**性能上界 5000 项**，超过则后续会话补 schema。
4. **canary CLI 不在 CI**：本阶段 canary 模块只交付代码 + 命令；CI 集成（GitHub Actions cron / k8s CronJob）归运维 PROD-READY-01。
5. **日志检索手册不依赖外部日志平台**：仅写 grep / kubectl logs 模板，不预设 Elasticsearch / Splunk / Loki 等具体后端（生产化时再补）。

---

## §7. 关联文档

- [`管理端接口规范 §14`](../api/管理端接口规范.md) — 入金对账 API 契约
- [`console-pages-V1 §17`](./falconx-console-pages-V1.md) — P19 页面设计
- [`管理端架构.md §145`](../architecture/管理端架构.md) — 既有 RBAC 占位 `reconciliation:view`
- [`完成定义.md §4.A`](../process/完成定义.md) — 三端硬约束 + 运维任务豁免
- [`BBook 一期完成执行路径 §14`](../process/BBook一期完成执行路径.md) — 阶段 11 总览
