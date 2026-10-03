# STAGE-2-DEPOSIT 任务卡

> 阶段 2.2 入金记录管理端：wallet-service 暴露入金记录 admin internal RPC；console-service 转发；console-frontend 提供入金列表 + 详情 Drawer。

| 项 | 值 |
| --- | --- |
| 任务编号 | STAGE-2-DEPOSIT |
| 所属阶段 | 阶段 2.2（入金记录） |
| 启动日期 | 2026-05-12 |
| 角色路由 | R1 → R2 → R3 → R6 → (R4 ∥ R9 ∥ R10) → R7 → R8 |
| 涉及服务 | wallet-service / console-service / console-frontend |
| 影响 schema | falconx_wallet（无变更）/ falconx_console（V5 RBAC + 错误码） |
| 错误码段位 | 90850-90899 |

---

## §1. 范围

### 1.1 必须交付

| 能力 | 终态 |
| --- | --- |
| 入金记录列表 | 管理端可分页/筛选查询 t_wallet_deposit_tx（userId/chain/token/status 多选/detected_at 范围/orphan-only） |
| 入金记录详情 | 管理端可按 id 查详情 |
| wallet-service internal RPC | 新建 WalletInternalApiTokenFilter（同 trading-core 模板）+ AdminInternalWalletDepositController |

### 1.2 不在范围

- 跨 schema JOIN（identity 邮箱 / trading t_ledger 入账行）：列表只返 wallet 字段；如需邮箱/入账查询，用户点击 userId 跳客户详情页（5.1 已有）
- 手动调整入金状态（reverse / re-confirm 等）：本任务只读
- 链上 tx_hash 重新扫描：内部链监听已实现，本任务不动
- CSV 导出（留下一轮）

---

## §2. R2 契约关键决策（已冻结 2026-05-12 R2 一轮）

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 数据范围 | 只读 wallet schema | owner 边界清晰；wallet-service 不跨 schema 查；跨表信息（邮箱 / 入账）由 console 调多个 internal RPC 在 UI 层组合（或前端跳转） |
| status 筛选 UI | 多选 | 与 5.1 客户管理 status 多选模式一致；运营可一次查多个中间态 |
| 脱钩记录可查 | 需要 + 独立筛选项 `onlyOrphan=true` | user_id IS NULL 表示链上检测到但未归属（误打/饱和攻击），运营需可见 |
| RBAC 权限点 | `deposit:view`（单一只读权限点） | 本任务无写操作，无需 high-risk 权限点 |
| 错误码段位 | 90850-90899（规范 §1.5 已分配） | 详见 §3 |
| 操作审计 | 仅 view，无需 high-risk 审计；普通登录审计已覆盖 | 与 trading-monitor 查询同档 |

---

## §3. 错误码分配（90850-90899）

| 错误码 | 场景 | 抛出位置 |
| --- | --- | --- |
| 90850 | DEPOSIT_NOT_FOUND（按 id 查不到） | wallet-service |

仅 1 个错误码（5.2 是纯只读 + 多条件查询，列表查空集是合法，不抛错）。

---

## §4. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1-§3 齐全 | ✅ commit `e19f6df` |
| R2 | 管理端接口规范 §9 落盘（5 子节） | ✅ commit `e19f6df` |
| R3 | console-pages §13 设计稿（1 页 + 1 Drawer） | ✅ commit `e19f6df` |
| R6 | 测试用例集骨架 22 TC | ✅ commit `e19f6df` |
| R4 | wallet-service：filter + admin mapper + 2 internal RPC + 错误码 90850 | ✅ commit `8462d67` |
| R9 | console-service：V5 + AdminDepositController + 错误码翻译 | ✅ commit `8462d67` |
| R10 | console-frontend：DepositListPage + DepositDetailDrawer + 路由 + 菜单 + 三件套全过 | ✅ commit `8462d67` |
| R7 | wallet 32/32 + console 19/19 + frontend lint/build/test 全过 | ✅ 本 commit |
| R8 | 当前开发计划新条目 + 任务卡完成打勾 + R7 报告归档 | ✅ 本 commit |

---

## §5. Git 回滚点

- 流程产物 commit + 实施 commit + R7+R8 commit
- console V5 idempotent；wallet-service 无 schema 变更

---

## §6. 验证要求

- 编译：3 服务（trading-core 无变化）compile success
- 单测：wallet-service / console-service 全过；console-frontend lint/test/build 全过
- R7 必跑 TC：5 个核心
