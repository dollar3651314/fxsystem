# R6 Test Designer 测试设计者任务模板

> 本模板用于 R6 角色的任务派发。R6 与 R2 同步冻结测试场景，为 R4/R5 提供测试用例骨架。**测试用例必须先于实现写出**。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R6 Test Designer 测试设计者
- 上游：R2（契约 spec）+ R3（设计方案，如涉及前端）
- 下游：R4（后端实施）+ R5（前端实施）+ R7（QA 验证）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R6 Test Designer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R6 的"✅ 可以"列表内：编写测试代码（含 E2E）/ 定义测试用例命名 / 提供测试支持类
[ ] 不在 R6 的"❌ 不可以"列表内：写业务实现代码（测试代码本身可以写）/ 修改契约
[ ] R2 输出的契约 spec 已就绪
[ ] 涉及前端时，R3 输出的设计方案已就绪
[ ] 已读取必读文档（见下）
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2.6 R6 角色定义
- [`SKILLS.md`](../../SKILLS.md) Skill 10 编写集成测试 / E2E 测试
- [`CFD 全面测试用例规范`](../../docs/test/CFD全面测试用例规范.md)
- [`falconx 编码与测试规范`](../../docs/architecture/falconx编码与测试规范.md)
- R2 输出的契约 spec
- R3 输出的设计方案（如涉及前端）

---

## 允许修改

- 测试代码文件（`src/test/java/...`、`falconx-frontend/src/**/*.test.{ts,tsx}`）
- E2E 测试支持类
- `docs/test/CFD全面测试用例规范.md`（注册 TC 编号）

---

## 禁止修改

- 任何业务实现代码（即使为了让测试通过也不行）
- R2 冻结的契约
- R3 输出的设计方案
- 任何正式规范文档（除 CFD 测试用例规范）

---

## 实施步骤

### 1. 读取上下文

- [ ] R1 任务派发说明
- [ ] R2 契约 spec：所有需要测试的字段、状态、错误场景
- [ ] R3 设计方案（如涉及前端）：所有需要测试的 UI 状态
- [ ] [`SKILLS.md`](../../SKILLS.md) Skill 10 真实基础设施参考
- [ ] [`CFD 全面测试用例规范`](../../docs/test/CFD全面测试用例规范.md) 现有 TC 编号

### 2. 设计测试用例

按以下层级设计：

#### 2.1 单元测试（针对纯逻辑）

每个用例包含：

- **场景**：测试什么逻辑分支
- **前置**：mock 的输入数据
- **操作**：调用什么方法
- **断言**：期望的返回值或状态变化

#### 2.2 集成测试（针对真实基础设施）

按 [`SKILLS.md`](../../SKILLS.md) Skill 10 真实模式：

- [ ] 使用 `@ExtendWith(E2EDatabaseCleanupExtension.class)` 自动清理测试 DB
- [ ] 使用真实 MySQL / Redis / Kafka / ClickHouse（不接受 mock）
- [ ] 用 `waitForAssertion()` 等待异步操作（不用 `Thread.sleep()`）

每个用例包含：

- **场景**
- **前置**：真实数据准备（写 DB / 发送 Kafka）
- **操作**：调用 API / 触发事件
- **断言**：DB 状态、Kafka 消息、Redis key、响应内容

#### 2.3 E2E 测试（涉及客户端可见行为时**强制**）

每个三端业务任务必须包含至少一条 E2E 用例覆盖：

```
客户端调用 → gateway → 后端处理 → 管理端可见状态 → 响应/推送 → 客户端响应
```

参考 `GatewayTradingRiskE2ETestSupport`：

- 完整注册→激活→登录流程
- 通过 owner 路径写入数据
- 触发 Outbox / Kafka
- 浏览器 / Playwright 验证 UI 响应

#### 2.4 前端单元 / 组件测试

按 R3 设计的组件状态表，每个状态都要测：

- default / loading / error / empty / disabled / hover / active

### 3. 输出测试用例骨架

为每个用例输出 `@Test` 方法签名 + TODO 占位，让 R4/R5 知道"成功标准是什么"：

```java
@Test
void shouldRejectOpenWhenSymbolHasActiveRejectOpen() {
    // TODO R4 实施时填充：
    // 1. 准备：在 t_risk_control_action 写入 REJECT_OPEN
    // 2. 操作：调用 evaluateMarketOrder
    // 3. 断言：accepted=false, rejectReason=BBOOK_RISK_OPEN_REJECTED
}
```

```typescript
test("shows error message when login fails with invalid credentials", () => {
  // TODO R5 实施时填充：
  // 1. 准备：mock /api/v1/auth/login 返回 10005
  // 2. 操作：渲染登录页 + 填写表单 + 点击登录
  // 3. 断言：屏幕上展示 "Invalid Credentials"
});
```

### 4. TC 编号注册

按 [`CFD 全面测试用例规范`](../../docs/test/CFD全面测试用例规范.md) 体系，给每个用例分配 TC 编号：

- TC-{服务前缀}-{编号}：如 TC-TRD-080、TC-MKT-046
- TC-E2E-{编号}：跨服务 E2E
- TC-FE-{编号}：前端单元 / 组件测试

### 5. 验证测试可执行性

测试代码骨架必须能编译通过（即使 TODO 内还没填充逻辑）：

```bash
mvn -pl falconx-{service} -am test-compile
cd falconx-frontend && npm run lint
```

---

## 必须返回的交付清单

1. **测试用例清单**：表格形式，含 TC 编号 / 场景 / 类型（单元/集成/E2E/前端）/ 优先级
2. **测试代码骨架**：`@Test` 方法签名 + TODO 占位，可编译通过
3. **测试支持类**（如需新增）：自动清理扩展、E2E 工具方法等
4. **TC 编号注册**：`CFD 全面测试用例规范.md` diff
5. **下游 handoff**：R4 / R5 各自需要满足的测试清单

---

## 强制约束

- **不得**写业务实现代码（测试代码本身可以写）
- **不得**修改契约或设计
- **不得**用 mock 替代真实数据库 / Kafka / Redis（按 SKILLS.md Skill 10）
- **不得**在测试中用 `Thread.sleep()`（用 `waitForAssertion`）
- **不得**遗漏客户端可见行为的 E2E 用例
- **不得**输出"应该测试..."而不写测试代码骨架

涉及客户端可见业务功能的任务，**至少一条 E2E 测试覆盖客户端 + 后端服务 + 管理端整链是硬约束**。
