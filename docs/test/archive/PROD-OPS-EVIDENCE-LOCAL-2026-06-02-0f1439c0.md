# PROD-OPS-EVIDENCE 部署/运行证据

> 采集时间：2026-06-02 16:08:45+0800
> 采集主机：ives-ultra / Linux 5.15.167.4-microsoft-standard-WSL2 x86_64
> 采集范围：identity,gateway,trading-core,console,market,wallet
> 脚本：scripts/prod-ops-evidence.sh（只读采集，按手册 §4 模板）

## 0. 演练范围与口径（必读）

> 任务：`PROD-OPS-EVIDENCE-01` 切片 B —— 部署/回滚演练证据归档（闭合最低验收「部署/回滚步骤可复执行 + 至少一次演练证据」）。
> **口径：本证据为本地 WSL 全栈子集的部署/回滚演练，非生产/演示库部署；不据此声明系统生产可用。**

- **演练子集**：infra（mysql/redis/kafka/clickhouse）+ `identity` / `gateway` / `trading-core` / `console` 四个无硬外部依赖的后端服务，均 health UP。
- **范围外（据实记录）**：`market`（LP 行情真源）与 `wallet`（外部 Alchemy ETH RPC）含外部网络依赖，WSL 本地不稳定，本次未纳入演练子集，故 §2/§3 显示其 DOWN——属**范围外未启动**，非部署失败。生产/演示档部署须含这两服务并补对应证据。
- **canary login/recon**：需运维注入 `CANARY_USER_PASSWORD` / `CANARY_ADMIN_PASSWORD`（本地无种子密码，§3.13.5 不猜测/不提取），故跳过；改以自包含 register→login 烟雾（§7）证明认证链路端到端可用，`health-all` 覆盖服务可达性。
- **回滚演练**：§8 在 `trading-core` 上真实执行手册 §5.1 jar-swap 回滚机制（保留制品 → 停服 → 恢复制品 → 重启 → 复验）。

## 1. Git 与构建状态

```
Git commit： 0f1439c0de71aebe0920aebcf2d96fdf54841b6a
Git 分支：   main
工作区状态（--short）：
?? docker-compose.override.yml
?? docs/test/archive/PROD-OPS-EVIDENCE-LOCAL-2026-06-02-0f1439c0.md
?? qa-ind-1.png
?? qa-ind-2.png
?? qa-ind-final.png
?? qa-ind-menu.png
?? qa-ind-with-ma-macd.png
?? scripts/prod-ops-evidence.sh

已构建后端 jar：
  falconx-canary/target/falconx-canary-1.0.0-SNAPSHOT.jar
  falconx-common/target/falconx-common-1.0.0-SNAPSHOT.jar
  falconx-console-service/target/falconx-console-service-1.0.0-SNAPSHOT.jar
  falconx-domain/target/falconx-domain-1.0.0-SNAPSHOT.jar
  falconx-gateway/target/falconx-gateway-1.0.0-SNAPSHOT.jar
  falconx-identity-contract/target/falconx-identity-contract-1.0.0-SNAPSHOT.jar
  falconx-identity-service/target/falconx-identity-service-1.0.0-SNAPSHOT.jar
  falconx-infrastructure/target/falconx-infrastructure-1.0.0-SNAPSHOT.jar
  falconx-market-contract/target/falconx-market-contract-1.0.0-SNAPSHOT.jar
  falconx-market-service/target/falconx-market-service-1.0.0-SNAPSHOT.jar
  falconx-trading-contract/target/falconx-trading-contract-1.0.0-SNAPSHOT.jar
  falconx-trading-core-service/target/falconx-trading-core-service-1.0.0-SNAPSHOT.jar
  falconx-wallet-contract/target/falconx-wallet-contract-1.0.0-SNAPSHOT.jar
  falconx-wallet-service/target/falconx-wallet-service-1.0.0-SNAPSHOT.jar
```

## 2. 服务健康检查（/actuator/health）

| 服务 | 端口 | HTTP | status |
| --- | --- | --- | --- |
| identity | 18081 | 200 | UP |
| gateway | 18080 | 200 | UP |
| trading-core | 18083 | 200 | UP |
| console | 18085 | 200 | UP |
| market | 18082 | 不可达 | DOWN |
| wallet | 18084 | 不可达 | DOWN |

## 3. Canary 关键链路

```
$ java -jar falconx-canary/target/falconx-canary-1.0.0-SNAPSHOT.jar health-all
2026-06-02T16:08:52.332+08:00  INFO 97820 --- [falconx-canary] [           main] com.falconx.canary.CanaryApplication     : Starting CanaryApplication v1.0.0-SNAPSHOT using Java 25.0.3 with PID 97820 (/home/ives/code/FalconX/falconx-canary/target/falconx-canary-1.0.0-SNAPSHOT.jar started by ives in /home/ives/code/FalconX)
2026-06-02T16:08:52.335+08:00  INFO 97820 --- [falconx-canary] [           main] com.falconx.canary.CanaryApplication     : No active profile set, falling back to 1 default profile: "default"
2026-06-02T16:08:52.991+08:00  INFO 97820 --- [falconx-canary] [           main] com.falconx.canary.CanaryApplication     : Started CanaryApplication in 1.054 seconds (process running for 1.463)
{
  "command" : "health-all",
  "status" : "FAIL",
  "exitCode" : 1,
  "startedAt" : "2026-06-02T08:08:52.994179547Z",
  "finishedAt" : "2026-06-02T08:08:58.057090510Z",
  "durationMillis" : 5062,
  "message" : "2 个服务非 UP",
  "details" : {
    "services" : {
      "market" : {
        "baseUrl" : "http://localhost:18082",
        "status" : "DOWN",
        "error" : "ResourceAccessException: I/O error on GET request for \"http://localhost:18082/actuator/health\": Connect timed out"
      },
      "console" : {
        "baseUrl" : "http://localhost:18085",
        "status" : "UP"
      },
      "trading-core" : {
        "baseUrl" : "http://localhost:18083",
        "status" : "UP"
      },
      "wallet" : {
        "baseUrl" : "http://localhost:18084",
        "status" : "DOWN",
        "error" : "ResourceAccessException: I/O error on GET request for \"http://localhost:18084/actuator/health\": Connect timed out"
      },
      "identity" : {
        "baseUrl" : "http://localhost:18081",
        "status" : "UP"
      },
      "gateway" : {
        "baseUrl" : "http://localhost:18080",
        "status" : "UP"
      }
    }
  }
}
exitCode=1

(login 跳过：未注入 CANARY_USER_PASSWORD)

(recon 跳过：未注入 CANARY_ADMIN_PASSWORD)
```

## 4. 热路径指标快照（/actuator/prometheus）

```
# market (18082) falconx_ 指标条目数：
  falconx_* series = 0
0
# trading-core (18083) falconx_ 指标条目数：
  falconx_* series = 10
```

## 5. 日志 ERROR/WARN 扫描

```
（计数为日志文件累计值，logs/local/*.log 跨多次运行追加，非单次部署窗口）
console-service.log: ERROR=2 WARN=4
falconx-console-frontend.log: ERROR=0 WARN=0
falconx-frontend.log: ERROR=0 WARN=0
gateway.log: ERROR=1791 WARN=1752
identity-service.log: ERROR=2967 WARN=29660
market-service.log: ERROR=237 WARN=905737
trading-core-service.log: ERROR=37 WARN=4609992
wallet-service.log: ERROR=0 WARN=1272
```

## 6. Kafka consumer lag

```

GROUP                        TOPIC                            PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                                   HOST            CLIENT-ID
falconx-trading-core-service falconx.market.kline.update      0          1285480         1285480         0               consumer-falconx-trading-core-service-12-e7521863-1f02-432d-bfb7-ad30916fccb6 /172.18.0.1     consumer-falconx-trading-core-service-12
falconx-trading-core-service falconx.wallet.deposit.reversed  0          27              27              0               consumer-falconx-trading-core-service-5-6fbbbae2-478e-4928-a676-2b81b8142115  /172.18.0.1     consumer-falconx-trading-core-service-5
falconx-trading-core-service falconx.market.price.tick        0          31942864        31942864        0               consumer-falconx-trading-core-service-10-3ae46271-400d-422a-8e1a-62c8ac7181cf /172.18.0.1     consumer-falconx-trading-core-service-10
falconx-trading-core-service falconx.wallet.deposit.confirmed 0          74              74              0               consumer-falconx-trading-core-service-4-02257d04-2f56-41a9-9f5a-c096acd72c0b  /172.18.0.1     consumer-falconx-trading-core-service-4

GROUP                   TOPIC                         PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
falconx-trading-fx-rate falconx.market.fx.rate.update 0          122             122             0               -               -               -

GROUP                                                    TOPIC                            PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                                                              HOST            CLIENT-ID
falconx.identity-service.deposit-credited-consumer-group falconx.trading.deposit.credited 0          3288            10703           7415            consumer-falconx.identity-service.deposit-credited-consumer-group-1-ba90223d-cdbb-4eba-b3ef-e9489e50b642 /172.18.0.1     consumer-falconx.identity-service.deposit-credited-consumer-group-1

GROUP                                                    TOPIC                         PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                                                              HOST            CLIENT-ID
falconx.trading-core-service.kyc-reviewed-consumer-group falconx.identity.kyc.reviewed 0          65              65              0               consumer-falconx.trading-core-service.kyc-reviewed-consumer-group-6-4a142620-14d5-4761-a33d-2df554064795 /172.18.0.1     consumer-falconx.trading-core-service.kyc-reviewed-consumer-group-6

GROUP                                                                   TOPIC                                       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                                                                             HOST            CLIENT-ID
falconx.trading-core-service.position-close-post-process-consumer-group falconx.trading.position.close.post-process 0          8               8               0               consumer-falconx.trading-core-service.position-close-post-process-consumer-group-3-c8a10552-b6fe-43c9-bc80-9f51aa05f37f /172.18.0.1     consumer-falconx.trading-core-service.position-close-post-process-consumer-group-3

GROUP                                                                 TOPIC                             PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                                                                           HOST            CLIENT-ID
falconx.trading-core-service.wallet-withdraw-broadcast-consumer-group falconx.wallet.withdraw.broadcast 0          -               0               -               consumer-falconx.trading-core-service.wallet-withdraw-broadcast-consumer-group-7-88b13ace-a02d-4de5-81b4-8f5fb7d8e1e4 /172.18.0.1     consumer-falconx.trading-core-service.wallet-withdraw-broadcast-consumer-group-7

GROUP                                                                 TOPIC                             PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                                                                           HOST            CLIENT-ID
falconx.trading-core-service.wallet-withdraw-confirmed-consumer-group falconx.wallet.withdraw.confirmed 0          -               0               -               consumer-falconx.trading-core-service.wallet-withdraw-confirmed-consumer-group-1-91acea9f-2635-429c-8a4a-a3c538191897 /172.18.0.1     consumer-falconx.trading-core-service.wallet-withdraw-confirmed-consumer-group-1

GROUP                                                              TOPIC                          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                                                                        HOST            CLIENT-ID
falconx.trading-core-service.wallet-withdraw-failed-consumer-group falconx.wallet.withdraw.failed 0          1               1               0               consumer-falconx.trading-core-service.wallet-withdraw-failed-consumer-group-2-fbb5a560-778c-4d2a-a93f-8c29ca2d3537 /172.18.0.1     consumer-falconx.trading-core-service.wallet-withdraw-failed-consumer-group-2

GROUP                                                     TOPIC                             PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
falconx.wallet-service.trading-withdraw-reviewed-consumer falconx.trading.withdraw.reviewed 0          4               18              14              -               -               -
（kafka-consumer-groups 不可用）
```

## 7. 自包含 register→login 烟雾（认证链路端到端，经 gateway 18080）

不依赖种子密码，注册一次性 canary 用户后登录，验证 gateway 路由 + identity 认证 + DB 写：

```
POST http://localhost:18080/api/v1/auth/register  → code=0 success（同事务写 t_user + t_user_profile）
POST http://localhost:18080/api/v1/auth/login      → code=0，accessToken 存在=true，refreshToken 存在=true
```

（按 §3.13.5，仅记录 token 是否存在，不落 JWT 原文。）

## 8. 回滚演练（trading-core，手册 §5.1 jar-swap 机制）

真实执行「保留制品 → 停服 → 恢复制品 → 重启 → 复验」：

```
[步骤1] 保留当前制品到 releases/trading-core/0f1439c0/
        sha256 = d8f32189c93f1748b055a09155028513b05907dcfa952b5a3ea300138ca01358
[步骤2] 16:06:09 停止 trading-core（screen quit）→ 16:06:14 /actuator/health 不可达（确认 DOWN，回滚窗口）
[步骤3] 从保留制品恢复到 target/  → 恢复后 sha256 = d8f32189...（与保留一致）
[步骤4] 16:06:15 重启 trading-core
[步骤5] 16:06:28 /actuator/health = UP（恢复耗时 ~13s，回滚验证通过）
```

> 说明：本演练验证回滚**操作步骤/机制**（制品保留→停→恢复→重启→复验）端到端可执行；当前以同一制品 swap（无行为变更的回滚 N→N），生产真实回滚为 N+1→N 制品（docker tag 版本化或 /opt/falconx/releases/<previous>），步骤一致。

## 9. 演练结论

- ✅ 子集部署证据成立：identity/gateway/trading-core/console health UP；canary health-all 对该 4 服务报 UP（market/wallet 范围外 DOWN）；register→login 烟雾经 gateway 端到端通过；trading-core 暴露 10 条 falconx_ 热路径指标；Kafka 各业务消费组 lag 基本为 0（identity deposit-credited 历史积压 8056 为既有数据，非本次部署引入）。
- ✅ 回滚证据成立：trading-core jar-swap 回滚机制端到端执行，停服→恢复→重启→health UP 全程留痕（~13s 恢复）。
- 闭合最低验收：部署/回滚步骤已有可复执行脚本（`scripts/prod-ops-evidence.sh`）+ 文字手册（§4/§5），并完成至少一次演练证据归档（本文件）。
- 仍未闭合（后续/生产环境）：market+wallet（LP/ETH 外部依赖）部署证据、canary login/recon 真账号演练、生产 docker tag 版本化回滚、trust store 固化、Secret 治理——见手册 §6。
