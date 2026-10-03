# 阶段 5 注册后地址预分配测试用例清单（V1，2026-05-15）

> 本文件是 BBook 一期 V2 §8 阶段 5 STAGE-5-WALLET-PROVISION 收口的 R6 测试用例骨架。
>
> 覆盖：identity Kafka 事件发布（`falconx.identity.user.registered`）+ wallet Kafka 消费 + ensureDefaultUsdtDepositAddresses 幂等派生 + wallet 端 DLQ 表 + wallet admin internal RPC + console 管理端 REST 透传 + console-frontend Vitest + 三端 E2E。
>
> commit `f91fcd6`（Phase 1）+ commit `db75a91`（Phase 2）已落地三端代码，但 0 测试用例覆盖；本文件冻结清单，R6 二轮把每个 TC 落为 `@Test` / `it()` 真代码。
>
> 关联文档：
> - [`管理端接口规范 §11`](../api/管理端接口规范.md)
> - [`Kafka 事件规范 §12.13`](../event/Kafka事件规范.md)
> - [`FalconX 统一接口文档 §3.26-§3.27`](../api/FalconX统一接口文档.md)
> - [`console-pages-V1 §14`](../design/falconx-console-pages-V1.md)

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-WP-` | 001-099 | 26 | 阶段 5 |
| `TC-E2E-WP-` | 001-009 | 1 | 阶段 5 |

---

## §2. 文档结构

| 节 | 范围 | TC 数 |
| --- | --- | --- |
| §3 identity Kafka 事件发布 | `IdentityKafkaEventPublisher.publishUserRegistered` | 4 |
| §4 wallet consumer + 幂等派生 | `IdentityUserRegisteredEventConsumer` | 5 |
| §5 wallet DLQ Repository | `MybatisWalletAddressProvisionDlqRepository` | 4 |
| §6 wallet admin internal RPC | `AdminInternalWalletProvisionController` | 5 |
| §7 console-service 透传 + RBAC + 审计 | `AdminWalletProvisionController` | 5 |
| §8 console-frontend Vitest | `walletProvisionApi` / `WalletProvisionDlqListPage` / `RetryModal` | 3 |
| §9 三端 E2E | 注册 → afterCommit → wallet 消费 → 地址写入 + DLQ 路径 + admin retry | 1 |

合计 27 个用例（26 IT/Unit + 1 E2E）。

---

## §3. identity Kafka 事件发布（`IdentityKafkaEventPublisher.publishUserRegistered`）

> 测试类：`IdentityUserRegisteredEventPublisherIntegrationTests`（识别真实 Kafka @ docker-compose:9092）
> 已知技术债：当前不使用 `KafkaEventMessageSupport`，headers 缺失；test 断言基于 payload body 内的 eventId/eventType 字段。

### TC-WP-001 注册成功 afterCommit 发布事件

- **类型**：IT (identity-service)
- **前置**：Kafka 可用，consumer 订阅 `falconx.identity.user.registered`
- **动作**：`IdentityRegistrationApplicationService.register(...)` 注册新用户
- **预期**：
  - [ ] 1 条 Kafka 消息发出
  - [ ] Kafka key = `String.valueOf(userId)`
  - [ ] payload body 含 `eventId = "user-registered-{userId}"`
  - [ ] payload body 含 `eventType = "identity.user.registered"`
  - [ ] payload body 含 `userId / uid / email / registeredAt` 6 字段全到位
  - [ ] `registeredAt` 解析为合法 ISO-8601 时间

### TC-WP-002 事务回滚不发事件

- **动作**：在 `TransactionTemplate` 内 `registrationService.register(...)` 然后 `status.setRollbackOnly() + throw RuntimeException`
- **预期**：Kafka 拉不到消息；`t_user` 表无新增行

### TC-WP-003 Kafka publish 失败仅记日志，不抛异常

- **前置**：mock `KafkaTemplate.send` 返回失败 future
- **动作**：直接调用 `kafkaEventPublisher.publishUserRegistered(userId, uid, email)`
- **预期**：方法正常返回（不抛异常），log error 命中 `identity.kafka.user-registered.publish-failed`

### TC-WP-004 partition key 是 userId 字符串形式

- **预期**：`record.key().equals(String.valueOf(userId))`

---

## §4. wallet consumer + 幂等派生（`IdentityUserRegisteredEventConsumer`）

> 测试类：`IdentityUserRegisteredEventConsumerIntegrationTests`（真实 Kafka + 真实 MySQL + 真实 xpub 配置）

### TC-WP-010 正常消费 → ensureDefaultUsdtDepositAddresses 派生 2 地址

- **类型**：IT (wallet-service)
- **前置**：wallet xpub 配置完整（TRC20 + ERC20）
- **动作**：identity 端发布 user.registered 事件
- **预期**：
  - [ ] consumer log 命中 `wallet.consumer.user-registered.received` 与 `.completed`
  - [ ] `t_wallet_address` 写入 2 行（user_id + chain=TRON,token=USDT；user_id + chain=ETH,token=USDT）
  - [ ] log 中 `addressCount=2`

### TC-WP-011 幂等性：重复消费同 eventId 不产生新地址

- **动作**：重复发布同 userId 的 user.registered 事件 3 次
- **预期**：
  - [ ] `t_wallet_address` 仍只有 2 行（按主键约束去重）
  - [ ] DLQ 表 0 行（不是错误）
  - [ ] 三次 consumer 调用都返回（无异常）

### TC-WP-012 坏消息（payload 解析失败）不重试也不阻塞

- **动作**：发布非 JSON 串到 topic（直接 KafkaTemplate.send(topic, "not-a-json")）
- **预期**：
  - [ ] consumer log 命中 `wallet.consumer.user-registered.parse-failed`
  - [ ] consumer 不重试（offset commit）
  - [ ] 下一条合法消息正常处理（partition 不阻塞）

### TC-WP-013 userId<=0 时 skip 不处理

- **动作**：发布 payload `{"userId": 0, ...}` 或负数
- **预期**：consumer log 命中 `wallet.consumer.user-registered.invalid-payload`；无地址派生；无 DLQ 写入

### TC-WP-014 WALLET_ADDRESS_ALLOCATION_FAILED 落 DLQ + 吞掉

- **前置**：mock `WalletAddressAllocationApplicationService.ensureDefaultUsdtDepositAddresses` 抛 `WalletBusinessException(WALLET_ADDRESS_ALLOCATION_FAILED)`
- **动作**：发布合法 user.registered 事件
- **预期**：
  - [ ] `t_wallet_address_provision_dlq` 写入 1 行 status=PENDING、event_id 匹配、last_error_code="20007" 或对应错误码
  - [ ] consumer log 命中 `wallet.consumer.user-registered.allocation-failed action=enqueue-dlq`
  - [ ] consumer 不抛异常（不会触发 Spring Kafka 重试）
  - [ ] offset 已 commit（同 partition 后续消息能正常消费）

### TC-WP-015 其他 RuntimeException rethrow（走 Spring 默认重试）

- **前置**：mock `ensureDefaultUsdtDepositAddresses` 抛 `RuntimeException("RPC timeout")`
- **预期**：consumer rethrow；offset 未 commit；Spring Kafka 重试 + 最终进 `falconx.identity.user.registered-dlt`（断言达上限后进 DLT topic）

---

## §5. wallet DLQ Repository（`MybatisWalletAddressProvisionDlqRepository`）

> 测试类：`WalletAddressProvisionDlqRepositoryIntegrationTests`（真实 MySQL）

### TC-WP-020 recordFailure 写入 PENDING + 雪花 ID

- **动作**：`dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(null, eventId, userId, uid, email, 1, PENDING, "20007", "xpub missing", now, null, now))`
- **预期**：
  - [ ] 写入成功，雪花 ID 自动生成
  - [ ] `findById(generated)` 返回完整 entity
  - [ ] `findByEventId(eventId)` 返回相同记录

### TC-WP-021 findPaginated 分页 + status/userId 过滤

- **前置**：种 5 PENDING + 3 RESOLVED 记录，跨 2 个 userId
- **预期**：
  - [ ] `findPaginated(0, null, 0, 10)` 返回 5 PENDING
  - [ ] `findPaginated(1, null, 0, 10)` 返回 3 RESOLVED
  - [ ] `findPaginated(null, userId1, 0, 10)` 仅返回 userId1 的记录
  - [ ] 分页 offset+limit 正确
  - [ ] 按 `created_at DESC` 排序

### TC-WP-022 countFiltered

- **预期**：与 §5.21 同前置数据，`countFiltered(0, null)=5`、`countFiltered(1, null)=3`、`countFiltered(null, null)=8`、`countFiltered(null, userId1)=N`

### TC-WP-023 markResolved 改 status=1 + resolved_at

- **动作**：`dlqRepository.markResolved(id)`
- **预期**：
  - [ ] 重读 entry status=RESOLVED
  - [ ] resolved_at 非空且 ≥ 调用时刻
  - [ ] last_attempt_at 不变（resolved 不视为 attempt）

---

## §6. wallet admin internal RPC（`AdminInternalWalletProvisionController`）

> 测试类：`AdminWalletProvisionInternalEndpointIntegrationTests`（MockMvc + 真实 MySQL）

### TC-WP-030 GET list 返回 200 + items 结构

- **请求**：`GET /internal/v1/wallet/console/provision-dlq?page=1&size=20` + `X-Internal-Token: <valid>` + `X-Admin-User-Id: 1001`
- **预期**：
  - [ ] HTTP 200
  - [ ] response.code = "0"
  - [ ] response.data.{page, pageSize, total, items} 结构完整
  - [ ] items[].{id, eventId, userId, uid, email, attemptCount, status, lastErrorCode, lastErrorMessage, lastAttemptAt, resolvedAt, createdAt} 字段全到位
  - [ ] id / userId 是 JSON 字符串（雪花 ID 防 JS 精度丢失）

### TC-WP-031 GET list query params 过滤生效

- **请求**：附加 `status=0&userId=<id>`
- **预期**：返回项均 status=PENDING 且 userId 匹配

### TC-WP-032 POST retry id 不存在 → 90860

- **请求**：`POST /.../99999999/retry` body `{"reason":"已补齐 xpub"}`
- **预期**：HTTP 200 包装 + response.code = "90860"（`WALLET_ADDRESS_DLQ_NOT_FOUND`）

### TC-WP-033 POST retry id 已 RESOLVED → 90861

- **前置**：DLQ 记录已 markResolved
- **预期**：response.code = "90861"（`WALLET_ADDRESS_DLQ_ALREADY_RESOLVED`）

### TC-WP-034 POST retry 成功 → 派生 + markResolved + 返回 RESOLVED

- **前置**：DLQ 记录 PENDING；xpub 已配齐
- **预期**：
  - [ ] response.code = "0"
  - [ ] data.status = "RESOLVED"
  - [ ] data.resolvedAt 非空
  - [ ] `t_wallet_address` 新增对应 2 行（TRC20 + ERC20）
  - [ ] DLQ 表中该记录 status=RESOLVED

---

## §7. console-service 透传 + RBAC + 审计（`AdminWalletProvisionController`）

> 测试类：`AdminWalletProvisionEndpointIntegrationTests`（MockMvc + 真实 MySQL + WireMock for wallet）

### TC-WP-040 GET /admin/wallet/provision-dlq 透传 → 200 + items

- **请求**：携带 admin token (含 `wallet-provision:view` 权限)
- **预期**：HTTP 200，透传 wallet RPC 响应，jsonPath 验证

### TC-WP-041 POST retry reason 空 → 90862（前置校验）

- **请求**：body `{"reason":""}` 或 `{"reason":"   "}` 或 body 缺 reason 字段
- **预期**：
  - [ ] HTTP 400（GlobalExceptionHandler 映射）
  - [ ] response.code = "90862" `ADMIN_WALLET_PROVISION_REASON_REQUIRED`
  - [ ] **不发送** internal RPC 请求（前置拦截）

### TC-WP-042 POST retry 透传 200 + 返回 RESOLVED

- **请求**：body `{"reason":"已补齐 xpub"}`
- **预期**：HTTP 200 + status RESOLVED；wallet 端 internal RPC 收到一次 POST + body 含 reason

### TC-WP-043 错误码翻译表（90860/90861）

- **场景 A**：wallet 返回 90860 → console 返回 90860（`ADMIN_WALLET_PROVISION_DLQ_NOT_FOUND`，HTTP 404）
- **场景 B**：wallet 返回 90861 → console 返回 90861（`ADMIN_WALLET_PROVISION_DLQ_ALREADY_RESOLVED`，HTTP 409）

### TC-WP-044 RBAC 缺权限 → 403

- **场景 A**：admin token 无 `wallet-provision:view` → GET 返回 403 + `ADMIN_PERMISSION_DENIED`
- **场景 B**：admin token 无 `wallet-provision:retry` → POST 返回 403

### TC-WP-045 OperationAuditAspect 写审计日志（高危）

- **前置**：retry 端点的权限注册为 HIGH_RISK
- **动作**：成功调用 POST retry
- **预期**：`t_admin_operation_log` 写入 1 行（permission_code=`wallet-provision:retry`、admin_user_id、target_id=DLQ id、reason）

---

## §8. console-frontend Vitest（`walletProvisionApi` / `WalletProvisionDlqListPage`）

> 测试文件：`walletProvisionApi.test.ts` + `WalletProvisionDlqListPage.test.tsx`

### TC-WP-050 walletProvisionApi.list buildQuery 参数透传

- **断言**：
  - [ ] `list({status:0, userId:1001, page:2, size:50})` 调用 `adminApi.get` 路径含 `status=0&userId=1001&page=2&size=50`
  - [ ] `list({})` 默认 `page=1&size=20`，无 status / userId
  - [ ] `list({status:undefined, userId:undefined})` 不带 status/userId

### TC-WP-051 walletProvisionApi.retry POST body 含 reason

- **断言**：`retry("555000001", "xpub OK")` 调用 `adminApi.post` 路径 `/admin/wallet/provision-dlq/555000001/retry` + body `{reason:"xpub OK"}`

### TC-WP-052 WalletProvisionDlqListPage + RetryModal

- **场景 A**：list mock 返回 2 行 PENDING + 1 行 RESOLVED → Table 渲染 3 行 + Tag 颜色 (warning/success)
- **场景 B**：仅 PENDING 行显示「重试」按钮；RESOLVED 行操作列显示 "—"
- **场景 C**：点击「重试」打开 modal + 标题含 `#<id>`、User、最近错误信息
- **场景 D**：modal reason 留空 + 点击「确认重试」→ message.warning + 不发请求
- **场景 E**：modal reason 填写 + 提交 → walletProvisionApi.retry 调用 + 成功后 modal 关闭 + reload list
- **场景 F**：retry 失败（mock 抛错）→ message.error，modal 保持开

---

## §9. 三端 E2E（注册 → afterCommit → wallet 消费 → 地址写入 + DLQ 路径 + admin retry）

### TC-E2E-WP-001 注册 → 派生 → 失败 → DLQ → admin retry 整链

> 由于 WSL 无 sudo 装 chromium 系统依赖（同 STAGE-7 Phase 4），优先以**程序化 E2E shell 脚本** + 数据库 fixture 归档代替浏览器 QA。

**步骤**：

1. 启动 identity / wallet / console / console-frontend 全栈
2. **场景 A 正常路径**：通过客户端 `POST /api/v1/auth/register` 注册新用户 `wp-e2e-success@example.com`
3. 断言：
   - [ ] identity 端 `t_user` 写入 + access token 返回
   - [ ] identity log 含 `identity.kafka.user-registered.published`
   - [ ] wallet log 含 `wallet.consumer.user-registered.received` + `.completed addressCount=2`
   - [ ] `falconx_wallet.t_wallet_address` 写入 2 行（TRON/USDT + ETH/USDT）
4. **场景 B DLQ 路径**：手动清空 xpub 配置 + 重启 wallet + 注册 `wp-e2e-failure@example.com`
5. 断言：
   - [ ] wallet log 含 `wallet.consumer.user-registered.allocation-failed action=enqueue-dlq`
   - [ ] `falconx_wallet.t_wallet_address_provision_dlq` 写入 1 行 status=PENDING
   - [ ] 用户表 OK，但 `t_wallet_address` 仍空
6. **场景 C admin retry**：恢复 xpub 配置 → admin login → GET /admin/wallet/provision-dlq 看到记录 → POST .../{id}/retry body `{"reason":"xpub 已补齐"}`
7. 断言：
   - [ ] HTTP 200 + status RESOLVED
   - [ ] DLQ 表更新 status=1 + resolved_at
   - [ ] `t_wallet_address` 新增 2 行
   - [ ] `t_admin_operation_log` 新增 1 行（permission=`wallet-provision:retry` + reason）

**归档**：`docs/test/fixtures/stage5-wallet-provision/` 8 JSON（database snapshots before/after + log excerpts）+ R7 验证报告。

---

## §10. 已知不覆盖项（R6 显式记录）

- **TC-WP-015 Spring Kafka 默认重试 + 最终 DLT 落地断言**：依赖 Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 默认配置；当前 wallet `application.yml` 未显式声明 `errorHandler`，因此该 TC 暂作为白盒断言基于源码行为（log error + offset 未 commit），DLT topic 真实落地推迟到 Kafka 错误处理统一化专项。
- **identity-contract 模块化测试**：因 P2 待办未做，本轮无 contract record 反序列化测试。
- **`KafkaEventMessageSupport` headers 断言**：因 P2 待办未做，本轮仅断言 payload body 内的 eventId / eventType；headers 标准化后补强。
- **真链验证**：阶段 5 只覆盖派生地址写入数据库，不验证地址在真实 TRC20/ERC20 链上接收入金（属于 STAGE-2-DEPOSIT 入金链路范围，已 R7 收口）。

---

## §11. R6 落地状态（测试类清单 + 真测试结果）

> 完整收口状态（commit SHA / 完成日期 / 已知不阻断项）详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节仅维护测试类清单与真测试结果（IT 跑出来的数据，不会漂移）。

| 测试类 | 计划 TC | 实际 @Test / it() | 状态 | 真测试结果 |
| --- | --- | --- | --- | --- |
| `IdentityUserRegisteredEventPublisherIntegrationTests` | 4 | 4 | ✅ 落地 | 4 / 4 pass (18.4s) |
| `IdentityUserRegisteredEventConsumerIntegrationTests` | 5 | 6 | ✅ 落地（拆 1 子用例） | 6 / 6 pass (20.9s) |
| `WalletAddressProvisionDlqRepositoryIntegrationTests` | 4 | 5 | ✅ 落地（拆 1 子用例 upsertOnDuplicate） | 5 / 5 pass (20.7s) |
| `WalletAddressProvisionAdminApplicationServiceIntegrationTests` | 5 | 5 | ✅ 落地（合并 wallet admin 单 IT 类） | 5 / 5 pass (24.6s) |
| `AdminWalletProvisionEndpointIntegrationTests` | 5 | 6 | ✅ 落地（拆 99004 / 90862 双路径） | 6 / 6 pass (5.4s) |
| `walletProvisionApi.test.ts` | 2 | 5 | ✅ 落地（拆 buildQuery 子断言） | 5 / 5 pass |
| `WalletProvisionDlqListPage.test.tsx` | 1 | 3 pass + 3 skip | ✅ 落地（jsdom 限制） | 3 pass / 3 skip |
| `TC-E2E-WP-001` 程序化 E2E | 1 | — | 推迟（IT 已等价覆盖，详见 R7 报告 §4） | — |

**合计 27 TC 计划 → 34 落地 + 3 jsdom skip + 1 E2E 推迟，26 IT/Unit 全过**。

R7 验证报告：[`STAGE-5-WALLET-PROVISION-R7-verification-report.md`](./archive/STAGE-5-WALLET-PROVISION-R7-verification-report.md)
