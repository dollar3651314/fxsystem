# R7 QA Verifier QA 验证者任务模板

> 本模板用于 R7 角色的任务派发。R7 是完成前的**最终验证守门员**，负责运行所有测试、执行 canary、做浏览器 QA、输出验证报告。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R7 QA Verifier QA 验证者
- 上游：R4（后端实现）+ R5（前端实现）
- 下游：R8（文档同步）+ R1（最终回收）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R7 QA Verifier
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R7 的"✅ 可以"列表内：运行测试 / 跑 canary / 浏览器 QA / 生成截图 / 输出验证报告
[ ] 不在 R7 的"❌ 不可以"列表内：修复发现的 bug / 修改代码 / 跳过失败用例
[ ] R4 和 R5（如涉及）已完成实现
[ ] 已读取必读文档（见下）
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2.7 R7 角色定义
- [`完成定义`](../../docs/process/完成定义.md)
- [`SKILLS.md`](../../SKILLS.md) Skill 17 前端 QA / 设计复核
- R6 输出的测试用例清单
- R4 / R5 输出的实现说明
- 涉及前端时，R3 输出的设计方案（用于对照视觉）

---

## 允许操作

- 运行 `mvn test` / `npm run test` / `npm run lint` / `npm run build`
- 启动前端 dev server
- 用 `Skill { skill: "browse" }` 或 Playwright 做浏览器 QA
- 截图（桌面 + 移动）
- 检查控制台、网络请求
- 运行 canary 脚本（如 `falconx-canary` 模块完成）
- 输出验证报告

---

## 禁止操作

- **不得**修改代码（即使发现 bug 也只报告，由 R4/R5 修复）
- **不得**跳过失败的测试或构建
- **不得**说"应该可以"代替实际运行
- **不得**用部分输出代替完整输出（不接受截断）

---

## 实施步骤

### 1. 读取上下文

- [ ] R1 任务派发说明
- [ ] R6 测试用例清单
- [ ] R4 / R5 实施说明
- [ ] R3 设计方案（如涉及前端，用于视觉对照）

### 2. 后端验证

按涉及的服务串行执行 Maven 验证：

```bash
# 编译检查
mvn -pl falconx-{service} -am compile

# 测试（按 R6 清单覆盖）
mvn -pl falconx-{service} -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest={测试类名清单} test
```

**强制**：

- 串行执行，不并行 `mvn test` 与 `mvn clean compile`
- 完整记录命令输出（含通过 / 失败用例数、耗时）
- 任何失败 → 直接判定任务未完成，返回 R1

### 3. 前端验证（如涉及）

```bash
cd falconx-frontend
npm run test
npm run lint
npm run build
```

完整记录三个命令的输出。

### 4. 浏览器 QA（涉及前端时强制）

启动 dev server：

```bash
cd falconx-frontend && npm run dev
```

按 R3 设计方案的组件状态表，逐项验证：

- [ ] 桌面（>= 1024px）布局
- [ ] 移动（< 768px）布局
- [ ] default / loading / error / empty / disabled / hover / active 状态
- [ ] 关键用户路径（happy path + 异常分支）
- [ ] 控制台无 error（warning 可接受但要记录）
- [ ] 网络请求命中真实后端（不是 mock）
- [ ] WebSocket 连接 / 断线 / 重连状态

每项都要截图。

### 5. E2E 验证（如 R6 输出 E2E 用例）

```bash
# 后端 E2E（gateway 层）
mvn -pl falconx-gateway -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest={E2E 测试类} test

# 前端 E2E（如有 Playwright 测试）
npm run test:e2e
```

### 6. Canary（如 `falconx-canary` 模块完成且本任务影响关键链路）

```bash
java -jar falconx-canary/target/*.jar register-deposit-trade-close
java -jar falconx-canary/target/*.jar quote-flow-check
```

### 7. 强制调用 verification-before-completion

**Claude Code 用户**：

```
Skill { skill: "superpowers:verification-before-completion" }
```

**Codex 用户**：手动按以下结构输出：

```markdown
## 我是否还有未验证的范围？
（明确回答）

## 我是否准备好声明完成？
[x] 是 / [ ] 否（原因：...）
```

### 8. 输出验证报告

按以下结构输出（不接受截断或省略）：

```markdown
## R7 最终验证报告

### 1. 后端验证

#### 1.1 命令
```
mvn -pl ... test
```
#### 1.2 输出
```
[完整输出，含通过/失败用例数、耗时]
```
#### 1.3 结论
- 通过用例数：X
- 失败用例数：0
- 跳过用例数：Y

### 2. 前端验证（如涉及）

#### 2.1 npm run test
[完整输出]

#### 2.2 npm run lint
[完整输出]

#### 2.3 npm run build
[完整输出]

### 3. 浏览器 QA（如涉及前端）

#### 3.1 桌面截图
- 页面 1：`{截图路径}`
- 页面 2：`{截图路径}`

#### 3.2 移动截图
- 页面 1：`{截图路径}`

#### 3.3 状态覆盖
| 状态 | 桌面 | 移动 |
| --- | --- | --- |
| default | ✅ | ✅ |
| loading | ✅ | ✅ |
| error | ✅ | ✅ |
| empty | ✅ | ✅ |
| disabled | ✅ | ✅ |

#### 3.4 控制台错误
[0 错误]

#### 3.5 网络请求验证
[命中真实后端的截图或日志]

### 4. E2E 验证（如有）

[完整输出]

### 5. Canary（如有）

[完整输出]

### 6. 已知问题

[问题清单 / 无]

### 7. 我是否准备好声明完成？
[x] 是
（综合判断：全部测试通过、构建通过、浏览器 QA 通过、控制台无错误、E2E 链路成功）
```

---

## 必须返回的交付清单

1. **完整验证报告**（按 §8 结构）
2. **截图清单**（桌面 + 移动 + 关键状态）
3. **网络请求验证证据**
4. **控制台错误清单**（应为 0）
5. **失败用例清单**（如有，必须明确返回 R1 让 R4/R5 修复）
6. **是否准备好声明完成**：是 / 否（带原因）

---

## 强制约束

- **不得**修改代码（仅报告，由 R4/R5 修复）
- **不得**跳过失败用例
- **不得**用部分输出代替完整输出
- **不得**说"应该通过"代替实际运行
- **不得**绕过 [`AI工作模式`](../../docs/process/AI工作模式.md) §6 的 `superpowers:verification-before-completion` 门禁（Claude Code）
- 任意失败 → 直接判定任务未完成

R7 是完成前的最后一道闸门；R7 通过即视为本轮验证闭环。
