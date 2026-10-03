# BBook 风控运营手册

> 本手册面向 BBook 一期上线后的日常运营/客服/风控值班角色。涵盖阈值含义、监控指标、告警语义、应急动作 SOP、常见 DLQ/审计处置流程。
>
> 真源：`docs/setup/当前开发计划.md` §"BBOOK-RISK-CONTROL-01"、`docs/process/BBook一期完成执行路径.md` §12「阶段 9」。

## 1. 风控规则总览

FalconX 一期 BBook 自营风控由三层保护组成：

| 层 | 维度 | 触发动作 | 配置表 |
| --- | --- | --- | --- |
| L1 单 symbol 净敞口 | 多/空 USD 净敞口 ≥ `hedge_threshold_usd` | symbol 级 `REJECT_OPEN`（仅同方向新开拒绝） | `t_risk_config` |
| L1 单 symbol 方向失衡 | 多/空 USD 失衡比 ≥ `direction_imbalance_ratio` 且总敞口 ≥ `direction_imbalance_min_total_usd` | symbol 级 `REJECT_OPEN`（多空双向拒绝） | `t_risk_config` |
| L2 跨品种 market 集中度 | 同 `market_code`（FX/CRYPTO/COMMODITY）合计 ≥ `concentration_threshold_usd` | market 内全部 symbol `REJECT_OPEN` | `t_risk_market_config` |
| L2 跨品种相关性组 | 同 `group_code`（EUR_GROUP/JPY_GROUP/METAL_GROUP/CRYPTO_MAJOR_GROUP）按 weight 加权 ≥ `threshold_usd` | 组内全部 symbol `REJECT_OPEN`（V20 起，触发器待挂载） | `t_symbol_correlation_group` / `t_symbol_correlation_member` |
| L3 用户级 | 用户 USD 净敞口 ≥ `t_user_risk_threshold.threshold_usd`（盈利用户独立列） | 用户级开仓拒绝 | `t_user_risk_threshold` |
| L3 平台总 | 全平台净敞口 ≥ `platform_net_exposure_threshold_usd` | `GLOBAL_PAUSE`（全平台开仓暂停，scheduler 5s 自动激活/恢复） | `t_risk_config` symbol=NULL 全局行 |

### 1.1 风控动作类型

`t_risk_control_action.action_type`:

| 编码 | 名称 | 语义 |
| --- | --- | --- |
| 1 | `REJECT_OPEN` | 仅拒绝新开仓；已有持仓可平仓 |
| 2 | `REDUCE_ONLY` | 仅允许减仓；不允许新开同方向（一期未启用） |
| 3 | `SUSPEND_SYMBOL` | 该 symbol 全暂停 |
| 4 | `GLOBAL_PAUSE` | 整个平台全 symbol 暂停 |

### 1.2 触发来源

`trigger_source`:

- `AUTO`：单 symbol 敞口超阈值
- `AUTO_DIRECTION_IMBALANCE`：多/空方向集中度失衡
- `AUTO_CONCENTRATION`：跨品种 market 集中度
- `AUTO_PLATFORM`：平台总敞口 GLOBAL_PAUSE（5s scheduler）
- `MANUAL_ADMIN`：运营在 console 手动激活

## 2. 监控指标（Grafana 检索词）

> 阶段 11 完成 Grafana 接入后，下列日志 pattern 可直接索引。

| 检索词 | 语义 | 期望告警 |
| --- | --- | --- |
| `trading.risk.control.activated source=AUTO` | symbol 级敞口超阈值触发 | 5 分钟内同 symbol 出现 ≥ 3 次 → P2 告警 |
| `trading.risk.control.activated source=AUTO_DIRECTION_IMBALANCE` | 方向失衡触发 | 单次出现 → P2 |
| `trading.risk.control.activated source=AUTO_CONCENTRATION` | 跨品种集中度触发 | 单次出现 → P1（市场普遍同方向） |
| `trading.risk.control.activated source=AUTO_PLATFORM` | 平台总敞口 GLOBAL_PAUSE | 单次出现 → P0 立即 oncall |
| `wallet.consumer.user-registered.allocation-failed` | 注册后地址预分配失败 | 5 分钟内 ≥ 1 次 → P2，检查 xpub 配置 |
| `identity.kafka.user-registered.publish-failed` | identity 发 Kafka 失败 | 5 分钟内 ≥ 1 次 → P2，检查 Kafka 连接 |

## 3. 应急 SOP

### 3.1 平台 GLOBAL_PAUSE 触发

1. 进入 console `/admin/trading/risk-switches`，确认 `auto_liquidate_enabled` 状态。
2. 进入 `/admin/risk/actions` 查看最近一次 `AUTO_PLATFORM` 触发的 `trigger_reason`，记录敞口快照。
3. 行情核对：是否 LP 极端报价驱动；进入 market log 搜 `market.quote.received source=TM_QUOTE` 跨度 10 分钟。
4. 判断是否需要保留 GLOBAL_PAUSE：
   - 真实风险敞口超过承受力 → 保留至运营审批后人工 `deactivate`
   - 行情异常导致的错误敞口 → 拉行情值班核对后，console `/admin/risk/actions` 找到该条记录点击 `停用`（写入 `trigger_source=MANUAL_ADMIN` 审计）。

### 3.2 单 symbol REJECT_OPEN 持续触发

1. 在 console `/admin/trading/exposures` 看该 symbol 实时净敞口与方向。
2. 看 `/admin/risk/configs` 该 symbol 的 `hedge_threshold_usd` 是否合理。
3. 若需要临时提升：
   - 编辑 `t_risk_config.hedge_threshold_usd`（在 RBAC 限定下）。
   - 在 `/admin/risk/actions` 找到 AUTO 条目人工 `deactivate`。
4. 若需要保留拒单：通知用户运营，不需操作。

### 3.3 钱包地址预分配 DLQ 处置

详见 `/admin/wallet/provision-dlq` 页面：

1. 先核实 wallet-service 是否已配 `FALCONX_WALLET_ETH_ACCOUNT_XPUB / FALCONX_WALLET_TRON_ACCOUNT_XPUB`。
2. 若 xpub 未配：补充环境变量后重启 wallet-service。
3. 在 DLQ 列表每条 PENDING 行点击「重试」，填重试原因（审计字段，例：xpub 已补齐）。
4. 重试成功 → 状态变 RESOLVED + `resolvedAt` 记录。
5. 多次重试仍失败 → 检查 wallet log `wallet.deposit-address.ensure.request` 看具体异常，常见原因：xpub 格式非法、链 RPC 不通、t_wallet_address 主键冲突（理论不会，幂等表）。

### 3.4 价格告警异常

`/admin/trading/price-alerts` 监控页：

- 单用户 ACTIVE 数 = 10 上限，新增被拒：与用户沟通先撤销旧告警。
- 用户告警含违规内容/被举报：找到对应行点击「强制删除」，填原因（审计字段）。
- `triggerCount` 达到 3：自动 EXHAUSTED（5 分钟节流耗尽）。

## 4. 阈值默认值与调整流程

| 配置 | 默认 | 修改入口 |
| --- | --- | --- |
| `t_risk_config.hedge_threshold_usd` (per symbol) | seed 100w USD | console `/admin/risk/configs` |
| `t_risk_config.direction_imbalance_ratio` | 0.80（80% 失衡） | console `/admin/risk/configs` |
| `t_risk_config.platform_net_exposure_threshold_usd`（global 行） | seed 1000w USD | console `/admin/risk/platform` |
| `t_risk_market_config.concentration_threshold_usd` | CRYPTO 50w / FX 500w / COMMODITY 100w | DB 直改（一期暂无 admin UI） |
| `t_symbol_correlation_group.threshold_usd` | EUR/JPY 300w / METAL 100w / CRYPTO_MAJOR 50w | DB 直改（V20 新增，触发器待挂） |
| `t_user_risk_threshold.threshold_usd` | seed 10w USD（默认），盈利用户 50w | console `/admin/risk/user-thresholds` |

**调整流程（≥ 50% 幅度需走流程）**：

1. 风控值班提单（产品 + 财务双签）。
2. 提交 Flyway migration 或 console UI 操作。
3. 修改后 30 分钟内回看 `trading.risk.control.activated` 出现次数是否降低/异常增加。
4. 24 小时内 oncall 跟踪。

## 5. 审计查询

- `/admin/risk/actions` 支持按 `symbol / actionType / triggerSource / isActive` 过滤
- 时间窗：`fromCreatedAt` / `toCreatedAt`（STAGE-9 加入）
- 高危：`activate` / `deactivate` 写入 `t_admin_operation_log` 含 reason（必填）

## 6. 已知限制（V2 一期不闭环）

- `t_symbol_correlation_group` 表已建 + seed 已落，但 risk evaluator **尚未消费** —— 仅作为运维数据资产留存。
- 风控触发不广播站内信：用户级开仓拒绝走现有 WS `risk.warning` + critical toast；平台级 `GLOBAL_PAUSE` 仅 console 可见，需运营人工通知。
- 跨品种保护当前依赖 `t_risk_market_config` market 大类；细分相关性组归阶段 9 后续/阶段 10。

## 7. 联系人

| 角色 | 责任范围 |
| --- | --- |
| 风控值班 | L1/L2/L3 触发响应、阈值调整审批 |
| 钱包值班 | DLQ 处置、链 RPC 异常 |
| 行情值班 | LP 异常报价、market 服务运行 |
| Oncall | GLOBAL_PAUSE / 服务异常重启 |
