# AI 工作模式 - Claude Code 适配

> 本文件是 [`AI工作模式`](./AI工作模式.md) 的 Claude Code 工具适配指南。Claude Code 用户在 FalconX 仓库内开展工作时，按本文件把核心规则映射到 Claude Code 的具体工具调用。
>
> 核心规则（角色、流程、完成判定、4 条准则）以 [`AI工作模式`](./AI工作模式.md) 为真源；本文件不复述。

---

## §1. 工具映射表

| 抽象能力 | Claude Code 工具 | 用法要点 |
| --- | --- | --- |
| 自动加载启动协议 | SessionStart hook | 见 §2 |
| 调用 Skill | `Skill` 工具 | 直接 `Skill { skill: "skill-name" }` |
| 派发子代理 | `Agent` 工具 + `subagent_type` | 角色映射见 §3 |
| 多步规划 | `Skill { skill: "superpowers:writing-plans" }` | 输出计划 markdown |
| 任务跟踪 | `TaskCreate` / `TaskUpdate` / `TaskList` | 10 角色任务集合（按介入角色筛选） |
| 文件搜索 | `Explore` 子代理 | 查找符号 / 文件 / 引用 |
| 进入 Plan 模式 | `EnterPlanMode` | 与用户对齐方案后 `ExitPlanMode` |
| Git worktree 隔离 | `EnterWorktree` / `ExitWorktree` | 平行任务隔离 |
| Bash 执行 | `Bash` 工具 | 串行 / 后台 / 超时控制 |
| 文件编辑 | `Edit` / `Write` | 优先 Edit |
| 文件读取 | `Read` | 不再 Read 已 Edit 的文件 |

---

## §2. SessionStart Hook 自动加载启动协议

Claude Code 通过 `.claude/settings.json` 中的 `SessionStart` hook 在每个会话启动时自动读取启动协议，确保**无论从哪个会话进入，都能按既定工作模式工作**。

项目级 `.claude/settings.json` 配置（与本仓库共享）：

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "cat .agents/session-bootstrap.md"
          }
        ]
      }
    ]
  }
}
```

效果：每个新会话开始时 Claude 即刻收到启动协议内容，包括必读文档清单、当前阶段、剩余执行路径入口。

如不希望强制加载，可在用户级 `~/.claude/settings.local.json` 通过覆盖关闭，但本仓库默认强制开启。

---

## §3. 角色到 Subagent 的映射

Claude Code 的 `Agent` 工具支持 `subagent_type` 参数（取值见环境提示中的"Available agent types"）。FalconX 10 角色（V2 三端协作）映射到现有 subagent_type：

| FalconX 角色 | 推荐 subagent_type | 说明 |
| --- | --- | --- |
| R1 Commander | 主会话（不派发） | 主会话本身扮演 R1 |
| R2 Contract Designer | `Plan` | 用 Plan agent 输出契约设计（含客户端 + 管理端双端 API） |
| R3 UI/UX Designer | 主会话或 `general-purpose` | 设计阶段建议主会话执行（需要与用户互动；客户端 + 管理端双端设计） |
| R4 业务后端实施者 | `general-purpose` 或 fork（无 subagent_type） | fork 节省主会话上下文 |
| R5 客户端前端实施者 | `general-purpose` 或 fork | `falconx-frontend`，同 R4 |
| R6 Test Designer | `general-purpose` 或 fork | 同 R4 |
| R7 QA Verifier | `general-purpose` | 验证报告需要回主会话审阅 |
| R8 Doc Synchronizer | `general-purpose` 或 fork | fork 节省上下文 |
| **R9 管理端后端实施者** | `general-purpose` 或 fork | `falconx-console-service` |
| **R10 管理端前端实施者** | `general-purpose` 或 fork | `falconx-console-frontend` |

**派发原则**：

- **fork（不传 subagent_type）**：适合"我不需要看这个 agent 的中间过程"的场景，如大文件搜索、批量重构、独立模块实现。fork 共享主会话 prompt cache，便宜。
- **subagent_type=Plan**：适合需要专门的规划 agent（设计契约、设计架构）。
- **subagent_type=general-purpose**：通用执行，主会话能看到完整结果。

**派发模板**：

```text
Agent({
  description: "任务编号：{编号} - {显示名称}",
  subagent_type: "{推荐类型}",
  prompt: "{从对应角色模板加载，并填入任务详情}"
})
```

---

## §4. 10 角色并行派发

Claude Code 支持 Agent 并行，但 FalconX 的并行只允许发生在 handoff 依赖满足之后。三端业务功能固定前置顺序是：

```text
R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8 → R1
```

R2 契约、R3 双端设计、R6 测试骨架完成并被 R1 回收后，R1 才能按 `superpowers:dispatching-parallel-agents` 同消息内派发 R4/R5/R9/R10：

```
Agent({ description: "R4 业务后端实现", ... })
Agent({ description: "R5 客户端前端实现", ... })
Agent({ description: "R9 管理端后端实现", ... })
Agent({ description: "R10 管理端前端实现", ... })
```

**并行约束**（与 [`AI工作模式`](./AI工作模式.md) §3.2 handoff 协议一致）：

- 同一文件不能被两个 agent 同时修改
- R2 必须在 R3/R4/R5/R6/R9/R10 之前完成（契约依赖）
- R3 必须在 R5/R10 之前完成（双端设计依赖）
- R6 测试用例骨架必须在 R4/R5/R9/R10 之前完成
- R4/R9 端别隔离：R4 只动业务服务（identity/market/trading-core/wallet），R9 只动 `falconx-console-service`
- R5/R10 端别隔离：R5 只动 `falconx-frontend`，R10 只动 `falconx-console-frontend`
- R9/R10 可并行时，R9 必须在返回物中列出实际 API / DTO / 错误码 / RBAC 权限点；R10 必须按 R2 契约实现，若发现 R9 实现与契约不一致只能返回 R1，不得自行改后端或契约
- R7 / R8 不参与实现并行；R7 必须等 R4/R5/R9/R10 全部回收后再验证，R8 必须等 R7 验证报告后再同步

---

## §5. TaskCreate 跟踪 10 角色三端协作

R1 在任务启动时，使用 `TaskCreate` 创建一组任务，每个介入角色一项。**三端业务功能默认路径**：

```
TaskCreate([
  { description: "R1 任务拆分与角色路由", status: "in_progress" },
  { description: "R2 契约冻结（含管理端 API）：{任务名}", status: "pending" },
  { description: "R3 UI/UX 双端设计：{任务名}", status: "pending" },
  { description: "R6 测试用例骨架（含三端 E2E）：{任务名}", status: "pending" },
  { description: "R4 业务后端实现：{任务名}", status: "pending" },
  { description: "R5 客户端前端实现：{任务名}", status: "pending" },
  { description: "R9 管理端后端实现：{任务名}", status: "pending" },
  { description: "R10 管理端前端实现：{任务名}", status: "pending" },
  { description: "R7 QA 三端验证：{任务名}", status: "pending" },
  { description: "R8 文档同步：{任务名}", status: "pending" }
])
```

每个角色完成后立即 `TaskUpdate` 状态为 `completed`；不允许批量更新。

**任务类型筛选**（按 [`AI工作模式`](./AI工作模式.md) §4.1）：

- **纯运维 / 内部维护**：跳过 R3、R5、R9、R10；R1 在任务启动时显式声明豁免
- **客户端纯实现**（已有契约 + 管理端）：跳过 R2、R4、R9、R10
- **管理端纯实现**（已有客户端 + 后端）：跳过 R2、R4、R5
- **阶段 0 基础设施**：仅 R1 + R8（豁免三端）
- **阶段 1 管理端地基**：R1 → R2 → R3 → R6 → (R9 ∥ R10) → R7 → R8（豁免客户端，单端任务）

---

## §6. Plan 模式与 Brainstorming

涉及"新功能、行为变化、协作模式变化"时，R1 必须先：

1. `Skill { skill: "superpowers:brainstorming" }` 与用户对齐方向
2. `EnterPlanMode` 输出方案给用户审批
3. 用户 approve 后 `ExitPlanMode` 进入实施

**禁止**：未经过 brainstorming 直接进入 Plan，或未经过 Plan 直接派发实现。

---

## §7. Verification Before Completion

R7 在最终验证阶段**强制**调用：

```
Skill { skill: "superpowers:verification-before-completion" }
```

未调用即视为完成失败。该 skill 会强制 R7 回答：

- 测试是否真跑过？输出在哪？
- 浏览器 QA 是否截图？
- 文档是否同步？
- 是否还有遗漏？

---

## §8. Git 操作

Git 操作通过 `Bash` 工具执行，按 [`AI工作模式`](./AI工作模式.md) §5.3 + [`AGENTS.md`](../../AGENTS.md) §3.12：

```bash
# 1. 检查状态识别无关改动
git status --short --branch

# 2. 精确暂存（不用 git add . / -A）
git add file1 file2 ...

# 3. 提交（使用 HEREDOC 格式）
git commit -m "$(cat <<'EOF'
{描述本轮修改}

涉及角色: R2 R3 R4 R5 R6 R7 R8 R9 R10
验证: {命令与结果摘要}

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"

# 4. 验证
git status --short
```

不得使用 `--no-verify` 或 `--amend`（除非用户明确要求）。

---

## §9. Worktree 隔离

涉及高风险大改动（如重构核心模块、迁移 schema）时，R1 用 `EnterWorktree` 隔离工作区：

```
EnterWorktree({ branch: "feature/xxx" })
```

完成后 `ExitWorktree` 合回。`isolation: "worktree"` 也可作为 `Agent` 工具的参数，让派发的 subagent 在隔离工作区操作。

---

## §10. 调试

R6/R7 遇到测试失败、bug、异常时，按门禁路由调用：

```
Skill { skill: "superpowers:systematic-debugging" }
```

强制按系统化调试流程：复现 → 定位 → 假设 → 验证假设 → 修复 → 回归。

---

## §11. 边界自检卡（Claude Code 版）

每个角色启动时，先在脑中（或显式输出）执行 [`AI工作模式`](./AI工作模式.md) §3.1 的边界自检卡（X ∈ {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}）。建议把自检结果作为 Agent prompt 的开头：

```text
## 角色：R{X} {角色名}
## 任务：{任务编号 + 显示名称}
## 边界自检：
[x] 这件事在 R{X} 的"✅ 可以"列表内
[x] 这件事不在 R{X} 的"❌ 不可以"列表内
[x] 已读取必读文档：[列表]
[x] 已限定修改文件范围：[列表]
[x] 已说明关键假设：[假设]

## 实施步骤：
1. ...
```

---

## §12. 一句话执行原则

**SessionStart hook 自动加载启动协议；R1 用 TaskCreate + Agent 派发 R1-R10 三端角色；R7 强制调用 verification-before-completion；三端业务任务客户端 + 后端 + 管理端齐全才算完成；任务完成形成单一 commit。**
