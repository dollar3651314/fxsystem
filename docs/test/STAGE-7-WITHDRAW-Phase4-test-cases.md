# STAGE-7-WITHDRAW Phase 4 测试用例清单（R6 骨架）

> 范围：管理端 console-service 透传 5 端点 + RBAC + 审计 AOP + 错误码翻译 + console-frontend 审核工作台 + 高危二次确认 modal。
>
> 关联角色：R6（本清单） → R9 console-service（commit 1）→ R10 console-frontend（commit 2）→ R7 验证 → R8 文档同步。
>
> TC 编号注册：`docs/test/CFD全面测试用例规范.md` §13.X（R8 同步时补段位）。
>
> 契约来源：[管理端接口规范 §10](../api/管理端接口规范.md#10-阶段-7-出金审核已冻结2026-05-14-r2-phase-0)、[出金状态机](../domain/状态机规范.md#7-出金状态机)、[Phase 3 B 段验证报告](./archive/STAGE-7-WITHDRAW-Phase3-B-verification-report.md)。

## §0. 落地索引

| commit | 范围 | R6 主导 | 实施角色 |
| --- | --- | --- | --- |
| Phase 4 commit 1 | console-service 5 端点 + DTO + RBAC + audit + IT（§1） | ✅ 本清单 | R9 |
| Phase 4 commit 2 | console-frontend 列表 + 详情 + 高危 modal + Vitest + 浏览器 QA（§2） | ✅ 骨架（详细 TC 落地随实施） | R10 |
| Phase 4 commit 3 | E2E（§3）+ R8 文档收口 | ✅ 占位 | R7 + R8 |

---

## §1. console-service（IT，mock trading-core RPC + 真 audit DB）

### §1.1 List 端点 `GET /admin/withdraws`

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-200 | 列表透传所有 query 参数到 trading-core | mock `InternalRpcClient.get` 返回 `AdminWithdrawListResponse{items:[...]}` | `GET /admin/withdraws?status=PENDING&userId=48...&network=ERC20&minAmount=100&maxAmount=5000&page=2&pageSize=20` | InternalRpcClient 被调用一次，URL 路径 = `/internal/v1/trading/withdraws?...` 含全部 6 个参数；响应 200 + items 透传 |
| TC-WD-201 | 列表默认分页（page=1 pageSize=20） | 同上 | `GET /admin/withdraws` 无参数 | InternalRpcClient URL 含 `page=1&pageSize=20`；其他 query 不出现 |
| TC-WD-202 | pageSize 上限 100 | 同上 | `GET /admin/withdraws?pageSize=500` | 透传 100（前端裁剪），或返回 90004 类参数校验错误 |

### §1.2 Detail 端点 `GET /admin/withdraws/{id}`

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-210 | 详情透传 + admin 视图扩展字段（kycLevel / userEmail / dailyAccumulatedUsd） | mock trading-core 返回 `AdminWithdrawDetailResponse` 含全字段 | `GET /admin/withdraws/900000001` | 响应含 `txHash / confirmations / 近30天统计`；字段直接透传不丢失 |
| TC-WD-211 | trading 30047 → 90500 ADMIN_WITHDRAW_NOT_FOUND | mock InternalRpcClient 抛 `InternalRpcException(code=30047)` | `GET /admin/withdraws/notexist` | 响应 200 body `{code:"90500",message:"...NOT_FOUND...",data:null}` |

### §1.3 Approve 端点 `POST /admin/withdraws/{id}/approve`

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-220 | approve 透传 amount<$3K → APPROVED | mock trading-core 返回 success `{status:"APPROVED"}` | `POST /admin/withdraws/900000001/approve` body `{"reviewNote":"备注"}` | InternalRpcClient POST `/internal/v1/trading/withdraws/900000001/approve`；响应 200 status=APPROVED |
| TC-WD-221 | approve 透传 amount>=$3K → APPROVED_DELAYED | mock 返回 `{status:"APPROVED_DELAYED",delayedUntil:"..."}` | 同上 | 响应 status=APPROVED_DELAYED + delayedUntil 透传 |
| TC-WD-222 | approve 非 PENDING → 90501 | mock trading-core 抛 InternalRpcException(30049) | `POST .../approve` 已 APPROVED 单 | 响应 `{code:"90501",message:"...NOT_PENDING..."}` |
| TC-WD-223 | approve 不存在的 withdrawId → 90500 | mock 抛 InternalRpcException(30047) | `POST .../approve` 不存在 id | 响应 90500 |

### §1.4 Reject 端点 `POST /admin/withdraws/{id}/reject`

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-230 | reject 透传 reason | mock trading-core 返回 success | `POST .../reject` body `{"reason":"不符合 KYC 要求"}` | InternalRpcClient POST 命中；响应 200 status=REJECTED |
| TC-WD-231 | reject reason 空 → 90503 ADMIN_WITHDRAW_REJECT_REASON_REQUIRED（前置校验） | 无需 mock | `POST .../reject` body `{"reason":""}` | 响应 `{code:"90503"}`；InternalRpcClient **未**被调用 |
| TC-WD-232 | reject reason null → 90503 | 无需 mock | `POST .../reject` body `{}` | 响应 90503；InternalRpcClient 未被调用 |
| TC-WD-233 | reject reason 超长 (>512) → 校验错误 | 无需 mock | `POST .../reject` body 含 600 字符 reason | 响应参数校验错误（90004 或专项码，按现有 `@Size` 模式） |
| TC-WD-234 | reject 非 PENDING → 90501 | mock 抛 30049 | 同上 | 90501 |

### §1.5 Emergency Cancel 端点 `POST /admin/withdraws/{id}/emergency-cancel`

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-240 | emergency-cancel 透传 reason | mock 返回 success status=CANCELED | `POST .../emergency-cancel` body `{"reason":"风控紧急取消"}` | InternalRpcClient POST 命中；响应 status=CANCELED |
| TC-WD-241 | emergency-cancel reason 空 → 90503（共用同错误码） | 无需 mock | `POST .../emergency-cancel` body `{"reason":""}` | 响应 90503；InternalRpcClient 未被调用 |
| TC-WD-242 | emergency-cancel 非 APPROVED_DELAYED → 90502 | mock 抛 30050 | 同上 | 响应 90502 |

### §1.6 RBAC（@RequiresPermission）

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-250 | 无 `withdraw:view` 调列表 → 90004 | 普通管理员账号未授该权限 | `GET /admin/withdraws` | 响应 `{code:"90004",message:"权限不足"}`；InternalRpcClient 未被调用 |
| TC-WD-251 | 无 `withdraw:view` 调详情 → 90004 | 同上 | `GET /admin/withdraws/1` | 90004 |
| TC-WD-252 | 无 `withdraw:review` 调 approve → 90004 | 有 view 但无 review | `POST .../approve` | 90004 |
| TC-WD-253 | 无 `withdraw:review` 调 reject → 90004 | 同上 | `POST .../reject` | 90004 |
| TC-WD-254 | 无 `withdraw:emergency-cancel` 调 emergency-cancel → 90004 | 有 review 但无 emergency-cancel | `POST .../emergency-cancel` | 90004 |
| TC-WD-255 | superadmin 默认放行所有 5 端点 | superadmin 登录 | 调用任一端点 | 全部 200（forward 行为按各 TC） |

### §1.7 审计 AOP（写 `t_admin_operation_log`）

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-260 | approve 写审计 permission_code=withdraw:review risk_level=HIGH_RISK | mock trading-core success | `POST .../approve` | `t_admin_operation_log` 落 1 行：`admin_user_id`/`permission_code`=withdraw:review/`target_type`=withdraw/`target_id`=900000001/`risk_level`=HIGH_RISK |
| TC-WD-261 | reject 写审计 permission_code=withdraw:review risk_level=HIGH_RISK | mock success | `POST .../reject` body 含 reason | 同 260；reason 由 OperationAuditAspect 摘要进 `after_value` JSON |
| TC-WD-262 | emergency-cancel 写审计 permission_code=withdraw:emergency-cancel risk_level=HIGH_RISK | mock success | `POST .../emergency-cancel` | permission_code=withdraw:emergency-cancel/risk_level=HIGH_RISK |
| TC-WD-263 | approve 失败 (90501) 是否仍写审计 — `OperationAuditAspect` 仅 `@AfterReturning`，异常路径**不写**审计 | mock 抛 30049 | `POST .../approve` | `t_admin_operation_log` **无新行**（异常路径不命中切点） |
| TC-WD-264 | **list / detail GET 也写审计 risk_level=LOW**（修正：`OperationAuditAspect` 对所有 `@RequiresPermission` 端点都写日志，不区分 view/review；这是 baseline 行为，更安全更可追溯。Phase 4 commit 3 §4.2 已修正） | mock success | `GET /admin/withdraws` 与 `GET /admin/withdraws/1` | `t_admin_operation_log` 落 2 行 permission_code=withdraw:view risk_level=LOW |

### §1.8 其他错误码翻译

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-270 | wallet 不可达 → 90505 ADMIN_WITHDRAW_WALLET_UNREACHABLE | 注：当前阶段 trading-core 不直接调 wallet，approve 后通过 Kafka 异步广播；90505 在 review 流程不直接命中，暂留作 future-use；用 RestTemplate timeout 模拟 trading-core 调用失败 | `POST .../approve` 同时 InternalRpcClient 抛 `ConnectException` | 响应 90505 |
| TC-WD-271 | 审核竞态 → 90504 ADMIN_WITHDRAW_REVIEW_RACE | mock trading-core CAS 失败（30049 已 PENDING 但被另一 admin 更新） | 暂作 future-use：当前 trading-core 不区分竞态 vs 单纯 NOT_PENDING；预留 | 90501 兜底（spec 角度 future-use） |

---

## §2. console-frontend（Vitest + 浏览器 QA，Phase 4 commit 2 落地）

### §2.1 列表页 `/admin/withdraws`

骨架 TC（commit 2 实施时落地）：

| TC 编号 | 名称 | 验证 |
| --- | --- | --- |
| TC-WD-FE-300 | 待办分组展示 PENDING / APPROVED_DELAYED 笔数+合计 | UI snapshot |
| TC-WD-FE-301 | 过滤条件查询 → 调 `GET /admin/withdraws` 含全部 query | 网络 mock |
| TC-WD-FE-302 | 列表项点击进入详情页 | router |
| TC-WD-FE-303 | 无 `withdraw:view` 整页隐藏 / 跳转 403 | RBAC 上下文 |

### §2.2 详情页 `/admin/withdraws/:id`

| TC 编号 | 名称 | 验证 |
| --- | --- | --- |
| TC-WD-FE-310 | 详情完整展示 ($, 链, 地址, kycLevel, tx_hash 链接到 etherscan) | UI |
| TC-WD-FE-311 | approve 按钮触发高危二次确认 modal | modal 交互 |
| TC-WD-FE-312 | reject 按钮触发 modal + reason 输入框校验 | 校验 |
| TC-WD-FE-313 | emergency-cancel 按钮 status≠APPROVED_DELAYED 时 disabled | 状态依赖 |
| TC-WD-FE-314 | 无 review 权限 approve/reject 按钮 disabled + tooltip | RBAC |
| TC-WD-FE-315 | 无 emergency-cancel 权限按钮 disabled + tooltip | RBAC |
| TC-WD-FE-316 | tx 已 broadcast 时实时显示 confirmations 进度（轮询 30s） | poll |

### §2.3 高危二次确认 modal

| TC 编号 | 名称 | 验证 |
| --- | --- | --- |
| TC-WD-FE-320 | 显示 "请输入用户邮箱后 5 位确认" 类挑战项 | UI |
| TC-WD-FE-321 | 错误挑战值禁用确认按钮 | 交互 |
| TC-WD-FE-322 | 二次确认通过后调相应 RPC | API call |

---

## §3. E2E（Phase 4 commit 3 实施）

| TC 编号 | 名称 | 范围 |
| --- | --- | --- |
| TC-E2E-WD-FE-001 | 管理员浏览器整链：登录 console-frontend → 进 /admin/withdraws → 详情 → approve → 等 wallet 广播 → 查看 tx 状态 → COMPLETED | 全链 |
| TC-E2E-WD-FE-002 | 管理员浏览器拒绝整链 | 同上 reject 路径 |
| TC-E2E-WD-FE-003 | 紧急取消整链：APPROVED_DELAYED 6h 窗口内 emergency-cancel | 同上 cancel 路径 |

---

## §4. 验证命令

```bash
# Phase 4 commit 1（本清单 §1）
mvn -pl falconx-console-service -am -Dtest='AdminWithdraw*' test
# 预期：~20 IT 全过；t_admin_operation_log 行数 = approve+reject+emergency-cancel 调用次数

# Phase 4 commit 2（§2，留下次会话）
cd falconx-console-frontend && pnpm vitest run src/features/withdraw

# Phase 4 commit 3 E2E（§3，留下次会话）
# 见 Phase 4 E2E 任务卡
```
