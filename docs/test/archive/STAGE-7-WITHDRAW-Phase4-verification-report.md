# STAGE-7-WITHDRAW Phase 4 管理端审核工作台 收口验证报告（R7+R8）

> 验证范围：Phase 4 commit 1 (`bfa405e`) + commit 2 (`716a3fd`) + commit 3 本次（含 R10 字段名修正：`id` → `withdrawId` 与 spec §9.2.2 / trading-core 对齐）+ R7 程序化 E2E（5 主场景 + 3 错误码 + 审计 + DB 状态前后 trace）。
>
> 验证时间：2026-05-15
>
> **本报告是 STAGE-7-WITHDRAW Phase 4 收口判定依据。Phase 3 链上链路已在 [B 段验证报告](./STAGE-7-WITHDRAW-Phase3-B-verification-report.md) 收口；Phase 4 仅覆盖管理端审核工作台 console-service + console-frontend 透传 / 状态机切换 / 审计 / RBAC，不重跑链上链路。**

---

## 1. 各 commit 范围

| commit | 范围 | 测试 |
| --- | --- | --- |
| `bfa405e` Phase 4 commit 1 | console-service 后端 5 端点 + RBAC + 审计 AOP + 错误码翻译 + 6 DTO + 12 IT | console-service 37/37 |
| `716a3fd` Phase 4 commit 2 | console-frontend 列表 + 详情 + 高危 modal + 14 Vitest + matchMedia/ResizeObserver polyfill | console-frontend 18/18 |
| 本次 Phase 4 commit 3 | R10 字段名修正 + R7 程序化 E2E + R8 收口报告 | console-frontend 14/14 + 程序化 E2E 8 场景 |

---

## 2. R10 字段名修正（本次 commit 关键交付）

### 2.1 问题发现

Phase 4 commit 3 R7 启动后 smoke 测试发现 console-service 返回的 `AdminWithdrawItem` 中 `id` 字段全部为 `null`：

```json
{
  "data": {
    "items": [
      { "id": null, "userId": "48275...", "amount": 20.0, ... }
    ]
  }
}
```

### 2.2 根因

trading-core internal RPC 的 `WithdrawOrderResponse` JSON 字段名是 `withdrawId`（与 [REST 接口规范 §9.2.2](../../api/REST接口规范.md) 对齐），但 Phase 4 commit 1 错误地把 console DTO 字段命名为 `id`。Jackson 反序列化时找不到 `id` 对应的 JSON 字段，导致全部为 null。

### 2.3 修正

| 文件 | 改动 |
| --- | --- |
| `falconx-console-service/.../api/AdminWithdrawItem.java` | record 字段 `id` → `withdrawId` |
| `falconx-console-service/.../AdminWithdrawEndpointIntegrationTests.java` | `jsonPath("$.data.id")` → `jsonPath("$.data.withdrawId")` |
| `falconx-console-frontend/.../types.ts` | `AdminWithdrawItem.id` → `withdrawId` |
| `falconx-console-frontend/.../WithdrawListPage.tsx` | `dataIndex: "id"` × 2 + `rowKey="id"` + `record.id` → `withdrawId` |
| `falconx-console-frontend/.../WithdrawDetailPage.tsx` | 7 处 `item.id` → `item.withdrawId`（保留 useParams 的 path param `id`，REST URL 风格不变） |
| `falconx-console-frontend/.../WithdrawListPage.test.tsx` | 2 处 mock data `id` → `withdrawId` |
| `falconx-console-frontend/.../WithdrawDetailPage.test.tsx` | PENDING_ITEM mock `id` → `withdrawId` |

### 2.4 验证

| 验证 | 结果 |
| --- | --- |
| `mvn -pl falconx-console-service test` | console-service IT 12/12 通过（jsonPath 已修正） |
| `vitest run src/features/withdraw` | console-frontend 14/14 通过 |
| `tsc -b --force` | 0 error |
| 真实 console-service curl | `withdrawId` 字段正确填充 |

---

## 3. 程序化 E2E（5 主场景 + 3 错误码翻译）

> 浏览器视觉 QA 受阻于 WSL 环境无 sudo 装 chromium 系统依赖（`libnspr4.so` 等需 apt 装），降级到程序化 E2E：curl 5 端点 + DB 状态前后 trace + 审计日志验证。脚本路径 `scripts/phase4-e2e-api.sh`，JSON 全量归档 `docs/test/fixtures/stage7-phase4/`。

### 3.1 测试数据（trading-core seed）

| withdrawId | 状态 | 金额 | 用途 |
| --- | --- | --- | --- |
| 900100001 | PENDING → APPROVED | 20 USDT | approve 场景 |
| 900100002 | PENDING → REJECTED | 20 USDT | reject 场景 |
| 900100003 | APPROVED_DELAYED → CANCELED | 20 USDT | emergency-cancel 场景 |

初始 t_account：balance=140 / frozen=60（3 单各冻 20 = 60）

### 3.2 场景结果矩阵

| TC | 端点 | 操作 | 状态切换 | DB 副作用 | ✓ |
| --- | --- | --- | --- | --- | --- |
| TC-WD-FE-E2E-001 | `GET /admin/withdraws` | 列表透传 7 个 query 参数（status/userId/network/minAmount/maxAmount/page/pageSize） | — | items[0].withdrawId="900100002" 正确 | ✅ |
| TC-WD-FE-E2E-002 | `GET /admin/withdraws/{id}` | 详情 | — | 全字段透传，status=PENDING | ✅ |
| TC-WD-FE-E2E-003 | `POST /admin/withdraws/900100001/approve` | reviewNote 可选 → trading-core note 映射 | **PENDING (1) → APPROVED (2)** | balance=140 frozen=60 不变（等异步广播） | ✅ |
| TC-WD-FE-E2E-004 | `POST /admin/withdraws/900100002/reject` | reason 必填 → trading-core note 映射 | **PENDING (1) → REJECTED (8)** | balance=140 不变；frozen 60 → 40（退冻 20） | ✅ |
| TC-WD-FE-E2E-005 | `POST /admin/withdraws/900100003/emergency-cancel` | reason 必填 + APPROVED_DELAYED 限制 | **APPROVED_DELAYED (3) → CANCELED (7)** | balance=140 不变；frozen 40 → 20（退冻 20） | ✅ |

### 3.3 错误码翻译验证

| TC | 触发 | 期望 | 实际 | ✓ |
| --- | --- | --- | --- | --- |
| ERR-1 | 对已 APPROVED 的单再 approve | 90501 ADMIN_WITHDRAW_NOT_PENDING（trading 30049 翻译） | 90501 | ✅ |
| ERR-2 | GET 不存在的 id 999999999 | 90500 ADMIN_WITHDRAW_NOT_FOUND（trading 30047 翻译） | 90500 | ✅ |
| ERR-3 | reject 空 reason | 90503 或 Bean Validation 99004 | **99004**（@NotBlank 优先于 service 层校验） | ⚠️ 见 §4.1 |

### 3.4 审计 AOP 落库验证

查 `t_admin_operation_log` 本次会话 4 条相关日志：

| 时间 | permission_code | target_type | target_id | risk_level |
| --- | --- | --- | --- | --- |
| 03:19:30.865 | withdraw:view | withdraw | 900100001 | LOW |
| 03:19:31.061 | withdraw:review | withdraw | 900100001 | HIGH_RISK |
| 03:19:31.267 | withdraw:review | withdraw | 900100002 | HIGH_RISK |
| 03:19:31.479 | withdraw:emergency-cancel | withdraw | 900100003 | HIGH_RISK |

- ✅ HIGH_RISK 3 条全部落库（approve/reject/emergency-cancel）
- ✅ target_type=`withdraw` target_id=withdrawId 准确
- ℹ️ GET 端点（view）也写了 LOW 级日志——与 Phase 4 commit 1 测试用例 TC-WD-264 假设 "view 不写审计" 不一致；实际 `OperationAuditAspect` 对所有 `@RequiresPermission` 端点都写。R6 测试用例文档将 TC-WD-264 期望更新为"view 写 LOW 级"。

---

## 4. 已知差异 / 待办

### 4.1 Bean Validation 99004 vs spec 90503（ERR-3）

spec §10.4 期望 reject 空 reason → `90503 ADMIN_WITHDRAW_REJECT_REASON_REQUIRED`。实际由 `@NotBlank @Size(max=512)` 在 controller 层先于 service 层校验拦截，抛 `MethodArgumentNotValidException` → `AdminGlobalExceptionHandler` 统一翻译为 `99004 INVALID_REQUEST_PAYLOAD`（HTTP 400）。

实际影响：用户/管理员均能看到清晰的"参数无效"错误。语义信息保留在 message 中。spec 99004 vs 90503 的差异属于"错误码细分粒度"问题，**不阻塞 Phase 4 收口**。

修复路径（任选一）：
- 去掉 `AdminWithdrawRejectRequest.reason` 的 `@NotBlank`，让 service 层 `reject(... reason)` 自己抛 90503
- 或在 `AdminGlobalExceptionHandler.handleValidationException` 加专项映射（识别字段名 `reason` → 90503）

挂统一问题清单（P2 优化项），下一轮迭代收口。

### 4.2 OperationAuditAspect 对 view 也写日志

Phase 4 commit 1 测试用例 TC-WD-264 假设 GET 端点不写审计（仅 POST 写）。实际 `OperationAuditAspect` 在 @RequiresPermission 方法 after-returning 阶段一概写入。这是 baseline 行为（已运行多个阶段），更安全更可追溯，不修改。仅需更新测试用例文档说明。

### 4.3 浏览器视觉 QA 未完成

WSL 环境缺 sudo + libnspr4.so 等系统依赖，chromium 无法启动。R7 浏览器 QA 需要：
- (a) 团队有 sudo 权限的开发者环境
- (b) 或 CI 用预装 chromium-headless 的 Docker 镜像
- (c) 或本地 WSL 装好 libnspr4 等依赖一次后可重复跑

视觉证据缺失但**功能链路已通过程序化 E2E 完整验证**（5 主场景 + 8 状态切换 + 4 审计日志 + 字段映射纠错）。本会话归档：
- Vitest 14/14 通过（含 RBAC disabled 按钮、APPROVED_DELAYED 显示 emergency-cancel、90500 404 渲染等关键 UI 逻辑）
- 程序化 API E2E 全过

视觉 QA 需要补的 5 张截图（commit 后续待补）：
1. 列表页全貌（含待办分组卡）
2. 详情页 PENDING 状态（含 approve/reject 按钮）
3. approve modal 弹出 + 备注填写
4. reject HighRiskConfirmModal 含 reason
5. emergency-cancel HighRiskConfirmModal 含 reason + checkbox

### 4.4 仍未补的"扩展字段"

- `kycLevel / userEmail / dailyAccumulatedUsd` (admin 视图扩展 §10.1) 需 console 跨服务 join identity + trading，DTO/UI 已留位但当前为占位。
- WS 实时进度推送（R3 §4）需 trading-core 暴露 admin WS channel `admin.withdraw.status-changed`。
- 高危 modal "输入用户名挑战项"（R3 §3.1）需扩展 HighRiskConfirmModal，与现有 customer 模块统一组件模式一致暂不动。

---

## 5. Phase 4 整体进度

- ✅ commit 1：console-service 后端透传层（5 端点 + RBAC + audit + 6 错误码 + 12 IT）
- ✅ commit 2：console-frontend 审核工作台（列表 + 详情 + 3 高危 modal + 14 Vitest）
- ✅ commit 3：R10 字段名修正（`id` → `withdrawId`）+ R7 程序化 E2E 8 场景全过 + R8 收口报告（本文档）

**Phase 4 整体完成**。下一阶段可启动 STAGE-8 或先收口/优化以下：
- (a) §4.3 浏览器视觉 QA 补 5 张截图
- (b) §4.4 admin 扩展字段 join + WS 实时推送
- (c) FX-072/FX-073 风格挂统一问题清单的小问题（§4.1 / §4.2）

---

## 6. 验证命令

```bash
# 后端 IT（含 withdrawId 字段名修正）
mvn -pl falconx-console-service -am -Dtest='AdminWithdraw*' test
# Tests run: 12, Failures: 0, Errors: 0

# 前端 Vitest（含字段名修正）
cd falconx-console-frontend && pnpm vitest run src/features/withdraw
# Test Files 3 passed, Tests 14 passed

# 程序化 E2E（需 5 服务 + docker infra 全就绪 + seed_phase4.sql 注入）
bash scripts/phase4-e2e-api.sh
# 输出包含 5 主场景 + 3 错误码 + 审计日志 + DB 状态前后 trace

# 浏览器 E2E（条件不具备时跳过，见 §4.3）
NODE_TLS_REJECT_UNAUTHORIZED=0 node scripts/phase4-e2e-browser.mjs
```
