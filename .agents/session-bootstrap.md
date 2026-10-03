# FalconX 会话启动协议

> 任何 AI 助手（Claude Code / Codex 等）进入 FalconX 仓库会话的**第一步**，必须按本协议执行启动检查清单。Claude Code 通过 SessionStart hook 自动加载本文件；Codex 用户必须手动 `read .agents/session-bootstrap.md`。
>
> **未完成启动协议前，不得开始任何编码、文档、提交动作。**
>
> 本文件**只有启动检查清单**，不复述任何规则正文。规则真源见 §2 链接索引。

---

## §1. 启动检查清单（必须按顺序执行）

### Step 1：识别工具

- **Claude Code**：原生支持 Skill 工具、Agent 子代理、TaskCreate；满足 handoff 后可并行派发 R4/R5/R9/R10 → `read docs/process/AI工作模式-Claude适配.md`
- **Codex**：单线串行执行 R1-R10，不支持原生 subagent → `read docs/process/AI工作模式-Codex适配.md`

### Step 2：读取核心规则

按顺序读取（缺一不可）：

1. `read AGENTS.md` — 仓库级红线 + 执行约束
2. `read docs/process/AI工作模式.md` — **角色定义、流程、约束、完成判定**（10 角色真源）
3. `read docs/process/Karpathy式AI编码行为准则.md` — 4 条强制编码准则
4. `read SKILLS.md` — 17 个 Skill 模板（按任务类型按需查阅，不必全读）

### Step 3：定位当前阶段

按顺序读取：

0. （**换机/陌生环境首次**）`read docs/setup/项目地图.md` — 导航式总入口：真源表 / 架构总览 / 换机 clone 后本地起栈最短路径 / demo 运维红线 / 开放项
1. `read docs/setup/当前开发计划.md` — **当前阶段状态、commit、不阻断项的唯一真源**
2. `read docs/process/BBook一期完成执行路径.md` — 剩余阶段执行手册（动态引用 §1，不重复 commit SHA）
3. `read docs/process/统一问题清单.md` — 当前活跃问题（按需）

### Step 4：检查工作区

执行 `git status --short --branch`，识别：

- 当前分支（必须为 `main`，除非用户授权其他分支）
- 与 `origin/main` 的差异
- 未提交改动（如有，必须在任务启动时识别并隔离无关改动）

### Step 5：等待用户任务

用户给出任务后，按 R1 流程执行：判断任务类型 → 拆分角色路由 → 派发 R2-R10 → 完成判定 → Git 回滚点。

具体路由表 + Superpowers 门禁规则详见 [`AI工作模式 §4.1`](../docs/process/AI工作模式.md)。

---

## §2. 规则速查索引（不重复正文，全部链接真源）

| 内容 | 真源 |
| --- | --- |
| 10 角色三端协作（V2 / 2026-05-08）— 职责 / 边界 / 必读 / 输出 | [`docs/process/AI工作模式.md §2`](../docs/process/AI工作模式.md) |
| 任务类型与角色路由（纯文档 / 三端业务 / Bug 修复 / 重构…） | [`docs/process/AI工作模式.md §4.1`](../docs/process/AI工作模式.md) |
| 角色边界自检卡（每个角色执行前必读） | [`docs/process/AI工作模式.md §3.1`](../docs/process/AI工作模式.md) |
| 红旗清单（越界即停止） | [`docs/process/AI工作模式.md §3.2`](../docs/process/AI工作模式.md) |
| 4 条强制编码准则（思考前 / 简洁 / 精准 / 目标驱动） | [`docs/process/Karpathy式AI编码行为准则.md`](../docs/process/Karpathy式AI编码行为准则.md) |
| 完成判定（含三端硬约束） | [`docs/process/完成定义.md §4.A`](../docs/process/完成定义.md) |
| 仓库级红线 / 执行约束 / Git 回滚点 | [`AGENTS.md`](../AGENTS.md) |
| 全栈 + Figma 协作流程 | [`docs/process/全栈Figma协作流程.md`](../docs/process/全栈Figma协作流程.md) |
| AI 协作最小阅读集 | [`docs/process/AI协作与提示规范.md`](../docs/process/AI协作与提示规范.md) |

---

## §3. 一句话启动总则

**先识别工具 → 读核心规则 → 定位阶段 → 检查工作区 → 等待任务 → 按 R1 拆分角色路由 → 严格执行边界 → 完整交付。**
