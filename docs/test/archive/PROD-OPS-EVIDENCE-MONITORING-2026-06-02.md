# PROD-OPS-EVIDENCE 监控告警配置即代码 验证证据

> 任务：`PROD-OPS-EVIDENCE-01` 切片 A —— 监控告警配置即代码 + 本地验证
> 日期：2026-06-02
> 基线 commit：`8eb50b01`（监控配置由本切片 commit 引入）
> 环境：本地 WSL（Docker Desktop），基础设施容器 mysql/redis/kafka/clickhouse 已起
> 工具：promtool / prometheus / alertmanager `v2.55.1` / `v0.27.0`，grafana `11.2.0`，kafka-exporter `v1.7.0`
> 口径：本证据为**监控告警配置（规则/抓取/路由/仪表盘）的本地验证**，非生产/演示库部署演练；生产口径不因此声明可用。

---

## 1. 交付物

| 类别 | 文件 |
|---|---|
| 抓取配置 | `deploy/monitoring/prometheus/prometheus.yml`（本地 host.docker.internal:1808x + prod 服务名变体注释） |
| 告警规则 | `deploy/monitoring/prometheus/rules/falconx-{platform,market,trading,kafka}.rules.yml`（19 条） |
| 规则单测 | `deploy/monitoring/prometheus/rules/falconx-*.rules_test.yml`（promtool） |
| 告警路由 | `deploy/monitoring/alertmanager/alertmanager.yml`（severity P0/P1/P2 分级 + 抑制规则 + 占位 receiver） |
| 仪表盘 | `deploy/monitoring/grafana/dashboards/falconx-overview.json` + provisioning 数据源/provider |
| 本地验证栈 | `docker-compose.monitoring.yml`（prometheus/alertmanager/grafana/kafka-exporter） |
| 说明 | `deploy/monitoring/README.md` |

规则把 [生产观测与回滚手册](../../operations/生产观测与回滚手册.md) §2/§3 的文字失败判据与 P0/P1/P2 分级落成机器可执行规则。

## 2. promtool 规则校验 + 单测（确定性，无需运行栈）

```
$ promtool check rules /work/*.rules.yml
  falconx-kafka.rules.yml    SUCCESS: 4 rules found
  falconx-market.rules.yml   SUCCESS: 5 rules found
  falconx-platform.rules.yml SUCCESS: 2 rules found
  falconx-trading.rules.yml  SUCCESS: 8 rules found

$ promtool test rules *_test.yml
  SUCCESS  (falconx-platform)
  SUCCESS  (falconx-market)
  SUCCESS  (falconx-trading)
  SUCCESS  (falconx-kafka)
  EXIT=0
```

单测覆盖每条规则的 firing / 非 firing 两侧 + 输出 labels + 渲染 annotations（共 19 条规则，正/负用例齐全）。

`promtool check config prometheus.yml`：SUCCESS（4 rule files found / config 语法 valid）。
`amtool check-config alertmanager.yml`：SUCCESS（global + route + 1 inhibit + 4 receivers）。

## 3. 本地实跑：告警链路端到端（scrape → 规则 → Alertmanager → receiver）

起 `docker-compose.monitoring.yml` 栈（本地 WSL/Docker-Desktop 不能 bind-mount 本 distro 路径，改用 `docker cp` 注入等效配置 + 同镜像；正常 docker 主机含演示档服务器用 compose bind-mount 即可）。业务服务未启动 → 6 个 `falconx-*` 抓取 target down，**正是 P0 `FalconxServiceDown` 的设计触发条件**。

**Prometheus 已加载规则**：19 条 / 8 组（falconx-platform-availability/jvm、market-quote/kafka/websocket、trading-tick/websocket、kafka-lag）。

**Prometheus firing 告警**（`/api/v1/alerts`）：
```
FalconxServiceDown severity=P0 job=falconx-gateway       firing
FalconxServiceDown severity=P0 job=falconx-identity      firing
FalconxServiceDown severity=P0 job=falconx-market        firing
FalconxServiceDown severity=P0 job=falconx-trading-core  firing
FalconxServiceDown severity=P0 job=falconx-wallet        firing
FalconxServiceDown severity=P0 job=falconx-console       firing
```

**Prometheus → Alertmanager**：`/api/v1/alertmanagers` active = `http://alertmanager:9093/api/v2/alerts`。

**Alertmanager 接收并路由**（`/api/v2/alerts` + `/groups`）：接收 6 条，全部命中 `severity=P0` 路由 → receiver `p0-critical`，按 `group_by=[alertname, job]` 分 6 组。证明 severity 分级路由 + 分组配置生效。

**Grafana provisioning**：`/api/health` database=ok（11.2.0）；数据源 `Prometheus`（uid `falconx-prometheus`）；仪表盘 `FalconX 生产观测总览`（uid `falconx-overview`，文件夹 FalconX）。

## 4. 已知限制（据实标注，不夸大）

- **p95 延迟规则用 `_seconds_max` 保守代理**：相关 Micrometer Timer 当前未开 `percentiles-histogram`，Prometheus 算不出真 p95；`_seconds_max`（窗口最大值，max ≥ p95）作保守代理会更早预警。要得到真 p95 需为对应 Timer 开 `management.metrics.distribution.percentiles-histogram`（属服务配置改动，留后续切片）。
- **本地 kafka-exporter 取不到 lag**：本地 kafka `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092`，容器内 exporter 解析回 broker 失败（本地基础设施限制）。Kafka 告警规则已由 promtool 单测验证；生产/演示档正常 advertised listener 下 exporter 正常工作。
- **本地业务指标类规则未在运行栈实测触发**：market/trading 自定义指标的触发演练需起 6 服务（含 LP/外部依赖），本切片以 promtool 单测确定性覆盖规则逻辑 + ServiceDown 实跑覆盖链路；业务指标实跑触发归入后续（起全栈或注入故障）。
- **截图**：WSL chromium 受限，本证据以 Prometheus/Alertmanager/Grafana 的 JSON API 输出为准（比截图更精确可复现）。

## 5. 复现实验命令

```bash
# 规则单测
cid=$(docker run -d --entrypoint sleep prom/prometheus:v2.55.1 600)
docker cp deploy/monitoring/prometheus/rules/. "$cid":/work/
docker exec "$cid" sh -c 'promtool check rules /work/*.rules.yml'
docker exec "$cid" sh -c 'cd /work && promtool test rules *_test.yml'
docker rm -f "$cid"

# 实跑栈（正常 docker 主机）
docker compose -f docker-compose.monitoring.yml up -d
curl -s localhost:9090/api/v1/rules ; curl -s localhost:9090/api/v1/alerts
curl -s localhost:9093/api/v2/alerts/groups
docker compose -f docker-compose.monitoring.yml down
```
