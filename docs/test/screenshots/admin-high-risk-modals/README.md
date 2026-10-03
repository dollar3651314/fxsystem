# 管理端高危操作风控档位 — 浏览器 QA 截图

> 配套真源：[`docs/design/admin高危操作风控档位.md`](../../../design/admin高危操作风控档位.md)
>
> 采集：2026-05-19 Playwright + chromium-1223 1440×900，本地全栈（gateway / identity / trading-core /
> wallet / console-service / MySQL / Redis / Kafka）+ console-frontend dev server :5300。
> 8 个高危 modal 全部点开到「三重门」状态（reason ≥10 字符 + 用户名挑战匹配 + 必要时勾选 confirm
> checkbox），并截图验证：reason `已输入 N 字符` 计数生效、挑战项识别 `superadmin`、提交按钮启用。

## 截图清单

| # | 文件 | 入口 | 三重门档位 |
| - | - | - | - |
| 00 | [00-login.png](00-login.png) | `/admin/login` | 基线，仅作环境参考 |
| 01 | [01-dashboard.png](01-dashboard.png) | `/admin` | 基线，验证 superadmin 已登录 |
| 02 | [02-customer-balance-adjust.png](02-customer-balance-adjust.png) | 客户详情 → 调余额 | reason ✓ + 用户名挑战 ✓ + 勾选 ✓ |
| 03 | [03-withdraw-emergency-cancel.png](03-withdraw-emergency-cancel.png) | 出金详情 (APPROVED_DELAYED) → 紧急取消 | reason ✓ + 用户名挑战 ✓ + 勾选 ✓ |
| 04 | [04-auto-liquidate-switch.png](04-auto-liquidate-switch.png) | 风控开关 → 暂停自动强平 | reason ✓ + 用户名挑战 ✓ + 勾选 ✓ |
| 05 | [05-risk-action-activate.png](05-risk-action-activate.png) | 风控动作 → 激活（GLOBAL_PAUSE）| reason ✓ + 用户名挑战 ✓ + 勾选 ✓ + Radio 选 GLOBAL_PAUSE |
| 06 | [06-risk-action-deactivate.png](06-risk-action-deactivate.png) | 风控动作行 → 停用 | reason ✓ + 用户名挑战 ✓ + 勾选 ✓ |
| 07 | [07-manual-liquidate.png](07-manual-liquidate.png) | 持仓监控行 → 手动强平 | reason ✓ + 用户名挑战 ✓ + 勾选 ✓ |
| 08 | [08-pending-order-force-cancel.png](08-pending-order-force-cancel.png) | 挂单监控行 → 强制撤单 | reason ✓ + 用户名挑战 ✓（无 checkbox，二门档位）|
| 09 | [09-price-alert-force-delete.png](09-price-alert-force-delete.png) | 价格告警行 → 强制删除 | reason ✓ + 用户名挑战 ✓（无 checkbox，二门档位）|

## 备注

- **二门 vs 三门**：直接动钱 / 平台级风控开关 6 项走三重门（含 checkbox）；强制撤单 / 强制删除告警
  影响范围相对收敛（不直接动用户余额），走二重门（reason + 挑战）。完整档位定义见真源 §1。
- **样张数据**：截图中 999999001-003 是为 QA 临时 INSERT 的种子记录（withdraw / risk_control_action /
  pending_order_trigger / price_alert 各一条），截完已 `DELETE`。所有截图都在「取消」按钮被点击前完成，
  没有写入审计日志、没有调用 internal RPC。
- **WSL chromium**：本次需要安装 5 个 NSS / NSPR / ALSA 系统库（`sudo apt-get install -y libnspr4 libnss3
  libasound2`）+ 软链 `/opt/google/chrome/chrome → ~/.cache/ms-playwright/chromium-1223/chrome-linux64/chrome`，
  详细步骤记录在 `docs/process/统一问题清单.md`（如未来 CI 镜像化可移除）。
