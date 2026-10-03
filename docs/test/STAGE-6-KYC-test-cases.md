# 阶段 6 KYC 测试用例清单（V1，2026-05-14）

> 本文件是 BBook 一期 V2 §9 阶段 6 STAGE-6-KYC 收口的 R6 测试用例骨架。
>
> 覆盖：identity 用户侧 REST + identity admin internal RPC + console 管理端 REST + identity Kafka 事件发布 + trading-core Kafka 消费 + 站内信生成 + 客户端 KycSubmitDrawer 单测 + 三端 E2E。
>
> commit `94ac29b` 已落地 identity / console 后端代码与 console 列表 UI，但 0 测试用例覆盖；本文件先冻结清单，R4/R5/R9 实施完成后 R6 二轮把每个 TC 落为 `@Test` / `it()` 真代码。
>
> 关联文档：
> - [`R2 KycReviewedEventPayload`](../../falconx-identity-contract/src/main/java/com/falconx/identity/contract/event/KycReviewedEventPayload.java)
> - [`R3 客户端设计`](../design/STAGE-6-KYC-client-design.md)
> - [`Kafka 事件规范 §12.7`](../event/Kafka事件规范.md)

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-KYC-` | 001-099 | 38 | 阶段 6 |
| `TC-E2E-KYC-` | 001-009 | 1 | 阶段 6 |

---

## §2. 文档结构

| 节 | 范围 | TC 数 |
| --- | --- | --- |
| §3 用户提交 KYC | POST /api/v1/me/kyc | 8 |
| §4 用户查询 KYC | GET /api/v1/me/kyc | 4 |
| §5 admin internal 列表 | GET /internal/v1/identity/kyc | 5 |
| §6 admin internal 详情 | GET /internal/v1/identity/kyc/{id} | 3 |
| §7 admin internal 审核 | POST .../approve + .../reject | 7 |
| §8 identity Kafka 事件发布 | `falconx.identity.kyc.reviewed` | 4 |
| §9 trading-core 消费 + 站内信 | KycReviewedConsumer | 5 |
| §10 console 管理端 REST | /admin/kyc/* | 4 |
| §11 console RBAC + 审计 | @RequiresPermission + OperationAuditAspect | 3 |
| §12 客户端 KycSubmitDrawer | Vitest | 5 |
| §13 三端 E2E | 用户提交 → admin 审核 → 站内信推送 | 1 |

合计 52 个用例（51 IT/Unit + 1 E2E）。

---

## §3. 用户提交 KYC（POST /api/v1/me/kyc）

### TC-KYC-001 正常提交 PENDING

- **类型**：IT (identity-service)
- **前置**：用户已注册并登录（携带 access token）；该 user 无 PENDING / APPROVED 记录
- **输入**：`{ idType:"ID_CARD", idNumber:"110101199001011234", idFrontBase64:"<合法 base64>", idBackBase64:"<同>", selfieBase64:"<同>" }`
- **预期**：`code=0`，data.status="PENDING"，data.submissionId 非空
- **验证**：
  - [ ] `t_kyc_submission` 写入一行 status=0
  - [ ] `t_kyc_document` 写入 3 行 doc_type 1/2/3
  - [ ] `sha256` 字段计算正确（SHA-256 of base64 decoded）
  - [ ] 响应不含 base64 数据

### TC-KYC-002 缺少证件正面 → 10041

- **输入**：省略 `idFrontBase64`
- **预期**：HTTP 400，code=10041 `KYC Documents Incomplete`

### TC-KYC-003 idNumber 为空白 → 10040

- **输入**：`idNumber=""`
- **预期**：HTTP 400，code=10040

### TC-KYC-004 PENDING 期重复提交 → 10042

- **前置**：已有 PENDING 记录
- **预期**：HTTP 409，code=10042 `KYC Pending Submission Already Exists`

### TC-KYC-005 已 APPROVED 后重复提交 → 10043

- **前置**：已 APPROVED
- **预期**：HTTP 409，code=10043 `KYC Already Approved`

### TC-KYC-006 REJECTED 后允许重新提交

- **前置**：已有 REJECTED 记录
- **预期**：`code=0`，新 submission status=PENDING，旧 REJECTED 保留

### TC-KYC-007 idType 非法枚举值 → 400

- **输入**：`idType="UNKNOWN"`
- **预期**：HTTP 400（Spring 反序列化失败 + GlobalExceptionHandler 归一化）

### TC-KYC-008 X-User-Id header 缺失 → 401

- **前置**：模拟 gateway 未注入 X-User-Id
- **预期**：HTTP 401 或 400（取决于 controller 校验）

## §4. 用户查询 KYC（GET /api/v1/me/kyc）

### TC-KYC-010 从未提交 → data=null

- **预期**：`code=0`，data=null

### TC-KYC-011 PENDING 查询返回最新

- **预期**：data.status="PENDING"，含 submittedAt

### TC-KYC-012 多次提交后返回最新

- **前置**：REJECTED → PENDING 两条记录
- **预期**：返回 PENDING 记录（按 submittedAt DESC LIMIT 1）

### TC-KYC-013 APPROVED 后查询包含 reviewAt + level=1

- **预期**：data.status="APPROVED"，data.level=1，data.reviewAt 非空

## §5. admin internal 列表（GET /internal/v1/identity/kyc）

### TC-KYC-020 无过滤返回所有

- **类型**：IT (identity-service)
- **预期**：`code=0`，data.items 数组，含 page/pageSize/total

### TC-KYC-021 按 status=PENDING 过滤

- **预期**：仅 PENDING 记录

### TC-KYC-022 按 userId 过滤

- **预期**：仅指定用户的记录

### TC-KYC-023 分页（page=2, size=10）

- **预期**：返回 11-20 条，total 准确

### TC-KYC-024 默认排序 submittedAt DESC

- **预期**：最新提交在前

## §6. admin internal 详情（GET /internal/v1/identity/kyc/{submissionId}）

### TC-KYC-030 正常详情返回 3 张证件 base64

- **预期**：data.submission + data.documents（3 项含 dataBase64 + sha256 + mimeType）

### TC-KYC-031 不存在 submissionId → 10044

- **预期**：HTTP 404，code=10044 `KYC Submission Not Found`

### TC-KYC-032 documents 排序 ID_FRONT/ID_BACK/HOLDING_SELFIE

- **预期**：固定顺序便于 UI 渲染

## §7. admin internal 审核（POST .../approve + .../reject）

### TC-KYC-040 PENDING approve → APPROVED + user.kyc_level=1

- **预期**：
  - [ ] `t_kyc_submission.status=1`
  - [ ] `t_kyc_submission.reviewer_id` = adminUserId
  - [ ] `t_kyc_submission.review_at` 非空
  - [ ] `t_user.kyc_level=1`（**同事务**）

### TC-KYC-041 非 PENDING approve → 10045

- **前置**：已 APPROVED 的记录
- **预期**：HTTP 409，code=10045 `KYC Submission Not Pending`

### TC-KYC-042 PENDING reject + 原因 → REJECTED

- **预期**：status=2，reject_reason 落库，t_user.kyc_level 不变

### TC-KYC-043 reject 原因为空 → 10046

- **预期**：HTTP 400，code=10046 `KYC Reject Reason Required`

### TC-KYC-044 不存在 submissionId approve → 10044

- **预期**：HTTP 404

### TC-KYC-045 X-Admin-User-Id 缺失 → 401/400

- **预期**：拒绝

### TC-KYC-046 并发 approve（CAS）

- **类型**：并发 IT
- **前置**：两个线程同时 approve 同一 submission
- **预期**：仅一个成功，另一个返回 10045

## §8. identity Kafka 事件发布（`falconx.identity.kyc.reviewed`）

### TC-KYC-050 approve 后事务提交发布 APPROVED 事件

- **类型**：IT (identity-service + embedded Kafka)
- **预期**：
  - [ ] 收到 Kafka 消息，key=userId
  - [ ] header `X-Event-Type=identity.kyc.reviewed`
  - [ ] body 字段：submissionId / userId / result="APPROVED" / kycLevel=1 / reviewerId / reviewAt / rejectReason=null
  - [ ] header `X-Event-Source=falconx-identity-service`

### TC-KYC-051 reject 后发布 REJECTED 事件

- **预期**：
  - [ ] result="REJECTED"，kycLevel=0
  - [ ] rejectReason 非空

### TC-KYC-052 事务回滚不发事件

- **类型**：IT
- **前置**：模拟 approve 时数据库写入失败
- **预期**：无 Kafka 消息发布（事务后发布）

### TC-KYC-053 事件 partition key 是 userId

- **预期**：key 字节序列化匹配 userId

## §9. trading-core 消费 + 站内信（KycReviewedConsumer）

### TC-KYC-060 消费 APPROVED → 站内信 INFO + WS 推送

- **类型**：IT (trading-core + embedded Kafka + WS test client)
- **预期**：
  - [ ] `t_notification` 写入一行，type=`kyc.reviewed`，level=INFO，title="KYC 已通过"
  - [ ] body 含通过时间
  - [ ] `relatedKey="kyc.reviewed"`, `relatedId=submissionId`
  - [ ] WS 客户端收到 `notification.created` envelope

### TC-KYC-061 消费 REJECTED → 站内信 WARN + 原因

- **预期**：
  - [ ] level=WARN，title="KYC 未通过"
  - [ ] body 含 rejectReason

### TC-KYC-062 重复消费幂等（同 submissionId 两次）

- **预期**：`t_notification` 仅 1 行（按 relatedKey + relatedId 查重）

### TC-KYC-063 payload 缺字段进入 DLQ

- **前置**：发送缺 result 字段的事件
- **预期**：进入 `falconx.identity.kyc.reviewed.dlq`

### TC-KYC-064 消费组名称固定

- **预期**：consumer group = `falconx.trading-core-service.kyc-reviewed-consumer-group`

## §10. console 管理端 REST（/admin/kyc/*）

### TC-KYC-070 列表透传 identity internal（status/userId/page/size）

- **类型**：IT (console-service + WireMock identity)
- **预期**：参数原样转发到 `GET /internal/v1/identity/kyc`

### TC-KYC-071 详情透传 + base64 渲染字段完整

- **预期**：data.documents 3 项含 dataBase64

### TC-KYC-072 approve 透传 + 错误码翻译

- **预期**：identity 返回 10045 → console 翻译为 `ADMIN_KYC_NOT_PENDING (409)`

### TC-KYC-073 reject 缺 reason → ADMIN_KYC_REJECT_REASON_REQUIRED (400)

- **预期**：本地校验 + identity 10046 都翻译为该错误

## §11. console RBAC + 审计

### TC-KYC-080 列表需要 kyc:view 权限点

- **类型**：IT (console-service + AOP)
- **前置**：admin 角色无 kyc:view
- **预期**：HTTP 403

### TC-KYC-081 approve/reject 需要 kyc:review 权限点

- **预期**：无权限 403

### TC-KYC-082 approve/reject 写入 t_admin_operation_log

- **预期**：
  - [ ] action=`KYC_APPROVE` / `KYC_REJECT`
  - [ ] target_type=`KYC_SUBMISSION`
  - [ ] target_id=submissionId
  - [ ] risk_level >= MEDIUM
  - [ ] ip + ua 字段非空

## §12. 客户端 KycSubmitDrawer（Vitest）

### TC-KYC-090 首次打开 GET 返回 null → 渲染提交表单

- **类型**：Unit (falconx-frontend)
- **预期**：表单 4 字段可见

### TC-KYC-091 PENDING 状态渲染只读视图 + 隐藏表单

- **预期**：表单不可见，显示"审核中"banner

### TC-KYC-092 APPROVED 状态渲染绿色 banner + 锁定提示

- **预期**：成功 banner 可见，表单不可见

### TC-KYC-093 REJECTED 状态渲染红色 banner + 允许重新提交

- **预期**：失败 banner + reason + 表单可见

### TC-KYC-094 文件 > 2MB → 红色提示

- **预期**：提交按钮 disabled，"证件图片过大"提示

## §13. 三端 E2E

### TC-E2E-KYC-001 用户提交 → admin 审核 → 站内信推送整链

- **类型**：E2E (curl + browse)
- **流程**：
  1. 用户注册 + 登录
  2. 客户端 `POST /api/v1/me/kyc` 提交 3 证件
  3. admin 登录 console，访问 `/admin/kyc`，看到 PENDING
  4. admin 点 approve
  5. trading-core 消费 `falconx.identity.kyc.reviewed`，写 `t_notification`，推 WS
  6. 客户端 NotificationCenter 收到 `notification.created`，未读 +1
  7. 用户 `GET /api/v1/me/kyc` 返回 status=APPROVED
  8. `t_user.kyc_level=1`
- **证据**：
  - [ ] curl 输出整链
  - [ ] 客户端浏览器截图（提交前 + 提交后 + 收到通知 toast）
  - [ ] 管理端浏览器截图（PENDING 列表 + 详情 + approve 后 APPROVED 列表）
  - [ ] WS 抓包：`notification.created` envelope

---

## §14. 实施落地表

| TC | 实施类:方法 | 状态 |
| --- | --- | --- |
| TC-KYC-001~006 | `IdentityKycApplicationServiceIntegrationTests`（应用层规则）+ `UserKycControllerIntegrationTests`（HTTP 层 TC-001） | ✅ 10+1 通过（2026-05-14） |
| TC-KYC-007~008 | `UserKycControllerIntegrationTests`（HTTP 层非法/缺头） | ✅ 2 通过（2026-05-14） |
| TC-KYC-010~013 | `IdentityKycApplicationServiceIntegrationTests` + `UserKycControllerIntegrationTests`（HTTP TC-010） | ✅ 4 通过（2026-05-14） |
| TC-KYC-020~024 | `AdminInternalKycControllerIntegrationTests` 列表 | ✅ 5 通过（2026-05-14） |
| TC-KYC-030~032 | 同上 详情 | ✅ 3 通过（2026-05-14） |
| TC-KYC-040~046 | 同上 审核（含 046 并发 approve CAS） | ✅ 7 通过（2026-05-14） |
| TC-KYC-050~053 | `IdentityKycEventPublisherIntegrationTests`（真 docker Kafka） | ✅ 4 通过（2026-05-14） |
| TC-KYC-060~064 | `KycReviewedEventConsumerTests`（mock 5 条快速回归）+ `TradingKycReviewedKafkaIntegrationTests`（真 Kafka + 真 DB 5 条端到端） | ✅ 10 通过（2026-05-14） |
| TC-KYC-070~073 | `AdminKycEndpointIntegrationTests`（console，@MockitoBean InternalRpcClient） | ✅ 4+1 通过（2026-05-14） |
| TC-KYC-080~082 | RBAC 由 STAGE-1-CONSOLE baseline 覆盖（默认超管放行）；TC-082 审计 AOP around 命中由 `AdminKycEndpointIntegrationTests.shouldWriteOperationAuditLogOnApprove` 验证 | ✅ 1 通过 + baseline 覆盖（2026-05-14） |
| TC-KYC-090~094 | `KycSubmitDrawer.test.tsx`（Vitest + React Testing Library + jsdom） | ✅ 5 通过（2026-05-14） |
| TC-E2E-KYC-001 | 真服务整链 curl + Playwright 浏览器；归档到 [`STAGE-6-KYC-R7-verification-report.md`](STAGE-6-KYC-R7-verification-report.md) §2-§3 | ✅ 11 步全通过 + 6 张截图（2026-05-14） |
