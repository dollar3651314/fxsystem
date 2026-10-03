# R4 Backend Implementer 后端实施者任务模板

> 本模板用于 R4 角色的任务派发。R4 在 R2 冻结契约 + R6 输出测试用例骨架后，按 SKILLS.md 模板实现后端代码。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R4 Backend Implementer 后端实施者
- 上游：R2（契约 spec）+ R6（测试用例骨架）
- 下游：R7（QA 验证）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R4 Backend Implementer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R4 的"✅ 可以"列表内：编辑指派的后端服务代码 / Mapper-XML / Repository / ApplicationService / Controller / Producer-Consumer / 迁移脚本 / 单元集成测试
[ ] 不在 R4 的"❌ 不可以"列表内：修改 R2 冻结的契约 / 跨服务 owner 边界 / 写前端代码 / 修改正式规范文档
[ ] R2 输出的契约 spec 已就绪
[ ] R6 输出的测试用例骨架已就绪（先有测试再有实现）
[ ] 已读取必读文档（见下）
[ ] 已限定本轮修改文件范围
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2.4 R4 角色定义
- [`Karpathy 式 AI 编码行为准则`](../../docs/process/Karpathy式AI编码行为准则.md) 4 条强制准则
- [`完成定义`](../../docs/process/完成定义.md)
- [`SKILLS.md`](../../SKILLS.md) 对应 Skill（按任务类型 1-12）
- [`falconx 编码与测试规范`](../../docs/architecture/falconx编码与测试规范.md)
- [`日志打印规范`](../../docs/architecture/日志打印规范.md)
- 涉及事务幂等 → [`事务与幂等规范`](../../docs/architecture/事务与幂等规范.md)
- R2 输出的契约 spec
- R6 输出的测试用例骨架

---

## 允许修改

按任务派发时 R1 指定的范围。常见允许修改：

- `falconx-{service}/src/main/java/...`（指派服务的代码）
- `falconx-{service}/src/main/resources/mapper/...`（XML Mapper）
- `falconx-{service}/src/main/resources/db/migration/V{N+1}__xxx.sql`（新增迁移，不改已有版本）
- `falconx-{service}/src/main/resources/application*.yml`（如配置项调整）
- `falconx-{service}/src/test/java/...`（实现对应的测试）

---

## 禁止修改

- R2 冻结的契约文档（API/WebSocket/Kafka/DB schema/状态机/错误码）
- 已发布的 Flyway migration（V1, V2, ... 不可改）
- 其他 owner 服务的代码
- 任何前端代码（`falconx-frontend/`）
- 任何正式规范文档（除非 R1 明确派发）

---

## 实施步骤

### 1. 读取上下文

- [ ] R1 任务派发说明
- [ ] R2 契约 spec
- [ ] R6 测试用例骨架（先有测试再有实现）
- [ ] 对应 SKILLS.md Skill 的"操作步骤"和"禁止事项"
- [ ] 当前服务的现有代码模式（参考 `falconx-{service}/README.md`）

### 2. 按 Skill 模板实施

按 [`SKILLS.md`](../../SKILLS.md) 的对应 Skill：

| 任务类型 | 必读 Skill |
| --- | --- |
| 新 REST 接口 | Skill 1 |
| 新低频 Kafka 事件（Outbox/Inbox） | Skill 2 |
| 新高频行情事件 | Skill 3 |
| 新 DB 表 / 字段 | Skill 4 |
| 新 Redis 缓存 | Skill 5 |
| 新 ClickHouse 查询 | Skill 6 |
| 新 Wallet 链 | Skill 7 |
| 新风控规则 | Skill 8 |
| 新状态机迁移 | Skill 9 |
| 新跨服务 contract 变更 | Skill 12 |

### 3. 关键实施原则（4 条 Karpathy 准则）

- **思考前先**：写代码前明确"我即将做什么 / 我假设了什么 / 是否有更简单的方案"
- **简洁优先**：只写完成任务所需的最少代码，不为"未来可能"添加抽象
- **精准修改**：每行改动都能追溯到 R2 契约或 R6 测试用例
- **目标驱动**：先让 R6 测试通过，再考虑性能/优化

### 4. 测试覆盖

- [ ] 单元测试：覆盖业务逻辑分支
- [ ] 集成测试：必须命中真实 MySQL/Redis/Kafka/ClickHouse（按 Skill 10）
- [ ] R6 提供的测试用例骨架必须全部通过
- [ ] **不接受 mock 替代真实数据库或 Kafka**

### 5. 日志埋点

按 [`日志打印规范`](../../docs/architecture/日志打印规范.md) 在关键节点打日志：

- INFO：业务关键节点（请求接收 / 业务完成 / 事件发布）
- WARN：业务异常但已处理（业务校验失败 / 重试触发）
- ERROR：未预期异常（必须含 traceId 和上下文）

### 6. 自检

- [ ] 是否引入了 R2 未冻结的字段或行为？（如有 → 红旗，停止）
- [ ] 是否修改了不在允许范围的文件？（如有 → 红旗，停止）
- [ ] 是否使用了 in-memory repository / stub provider 进入主路径？（不允许）
- [ ] 是否在 Kafka listener 回调线程直接执行业务链路？（必须切线程）
- [ ] 是否硬编码 owner 数据（symbol 列表 / 错误消息）？（不允许）

### 7. 运行验证

```bash
# 编译
mvn -pl falconx-{service} -am compile

# 单元 + 集成测试（串行，不要并行 mvn test 与 mvn clean compile）
mvn -pl falconx-{service} -am test
```

---

## 必须返回的交付清单

1. **修改文件清单**
2. **关键实现说明**：每个新文件 / 大改文件的目的
3. **测试通过证据**：完整命令输出（不接受截断）
4. **日志埋点清单**：新增的关键日志 key
5. **是否触及契约边界**：理论上不应该（R2 已冻结），但需明确声明无越界
6. **未完成项 / 风险**

---

## 强制约束（4 条 Karpathy 准则在 R4 的体现）

- **思考前先**：实现前必须能口头复述"R2 契约要我做什么 / R6 测试要我满足什么"
- **简洁优先**：500 行能实现的不要写 1000 行；优先复用现有 Repository / Service 模式
- **精准修改**：每行改动追溯到 R2 契约 / R6 测试 / 用户请求
- **目标驱动**：跑过 R6 测试 + 自己的集成测试 + 编译，才算完成

其他强制：

- **不得**修改 R2 冻结的契约（即使发现问题，也必须返回 R1 让 R2 重新冻结）
- **不得**写前端代码
- **不得**绕过 SKILLS.md 的禁止事项（如硬编码 traceId、跨 owner 查询）
- **不得**用 mock 替代真实数据库 / Kafka / Redis 测试
- **不得**说"测试应该通过"——必须真跑并提供完整输出
