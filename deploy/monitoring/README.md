# FalconX 监控告警配置即代码

把 [生产观测与回滚手册](../../docs/operations/生产观测与回滚手册.md) §2/§3 的指标失败判据与告警分级，落成机器可执行的 Prometheus 抓取配置、告警规则、Grafana 仪表盘与 Alertmanager 路由。

## 目录结构

```
deploy/monitoring/
├── prometheus/
│   ├── prometheus.yml                 # 抓取配置（本地默认 host.docker.internal:1808x；prod 变体见文件注释）
│   └── rules/
│       ├── falconx-platform.rules.yml # 服务可用性(up==0) + JVM 堆
│       ├── falconx-market.rules.yml   # market quote/kafka/WebSocket
│       ├── falconx-trading.rules.yml  # trading tick 队列/延迟/失败 + WebSocket
│       ├── falconx-kafka.rules.yml    # Kafka consumer lag（kafka-exporter）
│       └── *_test.yml                 # promtool 规则单测（与规则文件配对）
├── alertmanager/alertmanager.yml      # 按 severity(P0/P1/P2) 分级路由骨架（占位 receiver，凭据外部注入）
└── grafana/
    ├── provisioning/{datasources,dashboards}/  # 自动挂载数据源 + 仪表盘 provider
    └── dashboards/falconx-overview.json        # 生产观测总览仪表盘
```

## 验证规则（promtool，无需运行栈）

```bash
cid=$(docker run -d --entrypoint sleep prom/prometheus:v2.55.1 600)
docker cp deploy/monitoring/prometheus/rules/. "$cid":/work/
docker exec "$cid" sh -c 'promtool check rules /work/*.rules.yml'
docker exec "$cid" sh -c 'cd /work && promtool test rules *_test.yml'
docker rm -f "$cid"
```

> 注：本仓所在 WSL distro 路径对 docker daemon 不可直接 bind-mount，故用 `docker cp` 注入；docker-compose 的相对 bind-mount 正常（同 infra compose）。

## 本地实跑验证栈

```bash
# 前置：基础设施容器已起（docker-compose.yml）；如需业务指标，先 scripts/local-start.sh 起 6 服务
docker compose -f docker-compose.monitoring.yml up -d
# Prometheus  http://localhost:9090   （/api/v1/rules、/api/v1/targets、/api/v1/alerts）
# Alertmanager http://localhost:9093
# Grafana     http://localhost:3000   （admin/admin，FalconX/生产观测总览）
docker compose -f docker-compose.monitoring.yml down
```

业务服务未启动时，6 个 `falconx-*` target 抓取失败会触发 P0 `FalconxServiceDown`——这正是该规则的设计触发条件，可用作告警链路（scrape → 规则 → Alertmanager）端到端实跑演练。

## 生产 / 演示档适配

1. `prometheus.yml`：把各 job target 由 `host.docker.internal:1808x` 改为服务 DNS 名（`gateway:18080` / `identity-service:18081` / `market-service:18082` / `trading-core-service:18083` / `wallet-service:18084` / `console-service:18085`），并把 Prometheus 接入服务所在 docker 网络。
2. `alertmanager.yml`：把占位 receiver 改为真实 webhook/email/IM 集成，**凭据用环境变量 / 外部 secret 注入，不写入文件**（Secret 治理见手册 §6）。
3. 告警规则 / 仪表盘无需改动。

## 已知限制

- p95 延迟告警分两种口径：
  - **market Kafka 发布**（低基数 topic/eventType/outcome）已开 `percentiles-histogram`（market application.yml），用 `histogram_quantile(0.95, rate(_bucket[5m]))` 计算**真 p95**。
  - **trading tick timers** 按 symbol 打 tag（高基数，~1572 symbol），开 histogram 会 series 爆炸，故**有意保留** `_seconds_max` 作保守代理（max ≥ p95，更早预警）；若运营接受基数成本，可再为其单独开 histogram。
- 本地 kafka 的 advertised listener 为 `localhost:9092`，容器内 kafka-exporter 可能无法解析回 broker，本地 lag 指标未必可得；Kafka 规则已由 promtool 单测验证，生产正常 advertised listener 下 kafka-exporter 正常工作。
