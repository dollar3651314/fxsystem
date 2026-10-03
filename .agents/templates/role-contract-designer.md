# R2 Contract Designer 契约设计师任务模板

> 本模板用于 R2 角色的任务派发。R2 在 R3/R4/R5/R6 之前完成契约冻结。**契约先于实现**是不可妥协的硬约束。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R2 Contract Designer 契约设计师
- 上游：R1
- 下游：R3 / R4 / R5 / R6

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R2 Contract Designer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R2 的"✅ 可以"列表内：编辑 REST/WebSocket/Kafka/DB/状态机/错误码/统一接口文档
[ ] 不在 R2 的"❌ 不可以"列表内：写实现代码 / 修改其他 owner 的契约 / 跳过用户确认做契约决策
[ ] 已读取必读文档（见下）
[ ] 已限定本轮任务修改范围
[ ] 已说明关键假设
[ ] 涉及 API/DB/Kafka/WebSocket/状态机/错误码/owner/安全/事务变更，已获得用户明确确认
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2.2 R2 角色定义
- [`AI协作与提示规范`](../../docs/process/AI协作与提示规范.md) §4 字段级真源矩阵
- 涉及 REST → [`REST 接口规范`](../../docs/api/REST接口规范.md)
- 涉及 WebSocket → [`WebSocket 接口规范`](../../docs/api/WebSocket接口规范.md)
- 涉及 Kafka → [`Kafka 事件规范`](../../docs/event/Kafka事件规范.md)
- 涉及 DB → [`数据库设计`](../../docs/database/falconx一期数据库设计.md)
- 涉及状态机 → [`状态机规范`](../../docs/domain/状态机规范.md)
- 涉及事务 → [`事务与幂等规范`](../../docs/architecture/事务与幂等规范.md)
- 涉及安全 → [`安全规范`](../../docs/security/安全规范.md)
- [`SKILLS.md`](../../SKILLS.md) Skill 1 / 2 / 3 / 4 / 5 / 9 / 12（按涉及类型）

---

## 允许修改

- `docs/api/REST接口规范.md`（如新增 REST 协议规则）
- `docs/api/WebSocket接口规范.md`（如新增 WS 协议规则）
- `docs/api/FalconX统一接口文档.md`（接口汇总）
- `docs/event/Kafka事件规范.md`（如新增 topic / payload）
- `docs/database/falconx一期数据库设计.md`（如新增表 / 字段）
- `docs/domain/状态机规范.md`（如新增状态 / 迁移规则）
- 对应 contract 模块的 payload 文件（如 `falconx-trading-contract/.../XxxEventPayload.java`）

---

## 禁止修改

- 任何业务实现代码（Controller / Service / Repository）
- 其他 owner 服务的契约（不能跨 owner 决定）
- 任何 R3/R4/R5/R6 的输出物
- 已发布契约的破坏性变更（除非用户明确批准）

---

## 实施步骤

### 1. 读取本任务上下文

- [ ] 读取 R1 任务派发的完整说明
- [ ] 读取所有必读文档
- [ ] 识别本任务涉及的契约类型（REST / WS / Kafka / DB / 状态机 / 错误码）

### 2. 冻结契约 spec

按涉及类型，给出明确字段定义：

#### 2.1 REST 接口

- 路径、方法、认证要求
- 请求头（哪些必填，哪些可选，由谁注入）
- 请求体字段（类型、可选性、约束、示例）
- 成功响应字段
- 错误码清单（编码 + 含义 + 触发条件）
- 关键日志点

#### 2.2 WebSocket

- 握手 URL、鉴权方式
- 订阅消息体
- 推送消息体（每个 messageType 的字段）
- 错误帧
- 心跳与重连协议
- 连接限制

#### 2.3 Kafka 事件

- Topic 名称
- Payload 字段定义（contract 模块的 record）
- 分区键
- 生产者 owner
- 消费者 owner
- 幂等键
- 重试 / 死信策略

#### 2.4 数据库 schema

- 新表 DDL（含字段、类型、注释、索引、约束）
- 新字段 DDL（含 ALTER TABLE）
- Flyway 版本号（按当前各服务最大版本递增）
- 与现有表的关系（外键、参照完整性）
- migration 兼容性影响

#### 2.5 状态机

- 状态枚举值
- 允许的迁移边
- 终态
- 迁移触发条件
- 状态机变更与现有状态的兼容性

#### 2.6 错误码

- 错误码编码（按服务前缀分配：identity 1xxxx / market 2xxxx / trading 3xxxx 4xxxx / wallet 5xxxx）
- 错误码含义
- HTTP 状态码（如对应 REST）
- 触发条件
- 客户端处理建议

### 3. 兼容性分析

- [ ] 是否破坏现有消费方？
- [ ] 是否影响 owner 边界？
- [ ] 是否需要分阶段上线（先消费方再生产方）？

### 4. 输出契约 spec 文档

将契约 spec 写入对应正式规范文档（不只是任务输出，必须落地到文档）。

### 5. 与下游角色 handoff

输出物必须包含 R3 / R4 / R5 / R6 接收所需信息：

- R3 需要：字段含义 + 错误码 + 状态枚举（用于设计交互状态）
- R4 需要：完整契约 + 数据库 DDL + 状态机迁移
- R5 需要：API 路径 + 请求/响应字段 + WS 消息体 + 错误码
- R6 需要：所有需要测试覆盖的字段、状态、错误场景

---

## 验证命令

```bash
# 文档链接核对
grep -rn "{新接口路径或字段}" docs/

# 如果改了 contract 模块，检查编译
mvn -pl falconx-{service}-contract compile

# 如果改了 DB schema，检查 Flyway 版本号唯一
ls falconx-{service}-service/src/main/resources/db/migration/
```

---

## 必须返回的交付清单

1. **契约 spec**（文档形式，写入对应正式规范文档）
2. **修改文件清单**
3. **错误码清单**（如新增）
4. **状态机迁移图**（如涉及状态变化）
5. **兼容性分析**：是否破坏现有消费方
6. **下游 handoff 摘要**：R3 / R4 / R5 / R6 各自需要的关键信息
7. **是否触及未确认的契约变更**（如有，必须返回 R1 让用户确认）

---

## 强制约束

- **不得**写实现代码
- **不得**跳过用户确认做契约决策
- **不得**修改其他 owner 的契约
- **不得**输出"建议字段"而不写入正式规范（契约必须落地文档）
- **不得**改完契约不输出兼容性分析
