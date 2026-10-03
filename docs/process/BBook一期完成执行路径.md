# BBook 一期完成执行路径（V2，2026-05-08 三端协作扩展）

> 本文件是 FalconX BBook 一期完成判定项的剩余执行路径 V2 版本。后续开发严格按本文件推进。
>
> V1 已归档至 [`archive/BBook一期完成执行路径-V1-2026-05-08.md`](./archive/BBook一期完成执行路径-V1-2026-05-08.md)。
>
> 阶段状态、阻断项与下一步顺序仍以 [`当前开发计划`](../setup/当前开发计划.md) 为最终真源；本文件是该真源的剩余路径细化与执行手册。
>
> **本 V2 与 V1 的关键区别**：
>
> 1. **CROSS 模式回退**：原 V1 阶段 1（CROSS 收尾）已通过 `git revert b5484e9 15774b0` 回滚；CROSS 推迟到一期之外，仅保留扩展占位
> 2. **范围扩张**：新增挂单（LIMIT/STOP/SL-TP）、价格告警、出金、KYC、管理后台、通知系统
> 3. **协作模式升级**：从 8 角色二端（前后端）升级为 10 角色三端（客户端 + 后端服务 + 管理端）
> 4. **完成定义升级**：每个业务功能必须三端齐全才算完成（详见 [`完成定义`](./完成定义.md) §4.A）

---

## §1. 范围与完成判定

### 1.1 V2 一期范围

| 维度 | 范围 |
| --- | --- |
| 产品形态 | BBook CFD 平台 |
| 订单类型 | **MARKET + LIMIT + STOP + SL/TP**（V1 仅 MARKET） |
| 保证金模式 | **仅 ISOLATED**（CROSS 推迟到一期之外） |
| 资金链路 | **入金 + 出金（人工审核 + 链上）**（V1 仅入金） |
| KYC | **首次出金 + 出金地址 ≠ 入金地址触发简单 KYC**（V1 无 KYC） |
| 管理后台 | **falconx-console-service + falconx-console-frontend，含用户/角色/菜单/按钮 RBAC**（V1 无管理后台） |
| 通知 | **站内信 + WebSocket**（V1 无通知系统） |
| 价格告警 | **用户设价 → 触发 → 站内信/WS 通知**（V1 无） |

### 1.2 完成判定（三端硬约束）

按 [`完成定义`](./完成定义.md) §4.A，每个业务功能必须满足：

- ✅ 客户端实现 + 三件套通过 + 桌面/移动 QA 截图
- ✅ 后端服务实现 + 单元测试 + 集成测试
- ✅ 管理端后端实现 + 测试通过
- ✅ 管理端前端实现 + 三件套通过 + QA 截图
- ✅ 至少一条 E2E 测试覆盖三端整链
- ✅ 文档同步（统一接口文档 + 管理端接口规范）
- ✅ 形成单一 Git commit

### 1.3 不在 V2 范围内

- A-book 对冲执行出口（已明确不进一期）
- CROSS 保证金（推迟到一期之外）
- 部分平仓（一期不做）
- 邮件 / Telegram 真实发送（接口预留）
- 邀请码 / 资金密码 / 国别黑名单 / 多链多地址生产级钱包治理
- 资金费率（永续合约 funding rate，与现有 Swap 模式不同）
- i18n 后端（前端字符串硬编码即可）
- 工单系统

---

## §2. 阶段总览（11 阶段，约 36 周单线 / 5-6 个月并行）

| 阶段 | 范围 | 估时（单线）| 三端要求 |
| --- | --- | --- | --- |
| **阶段 0** | 基础设施（CROSS 回滚 + 完成定义升级 + 角色扩展 + 文档归档）| 1 周 | 豁免（基础设施任务）|
| **阶段 1** | 管理后台地基（console 骨架 + RBAC + 设计规范）| 4 周 | 豁免客户端（仅管理端）|
| **阶段 2** | 补齐已完成功能的管理端 | 4 周 | 三端齐全 |
| **阶段 3** | 挂单（LIMIT/STOP/SL-TP）| 5 周 | 三端齐全 |
| **阶段 4** | 价格告警 | 2 周 | 三端齐全 |
| **阶段 5** | 注册后地址预分配 | 1.5 周 | 三端齐全 |
| **阶段 6** | KYC（首次出金触发 + 地址不一致触发）| 3 周 | 三端齐全 |
| **阶段 7** | 出金完整链路 | 6 周 | 三端齐全 |
| **阶段 8** | 通知系统 | 2 周 | 三端齐全 |
| **阶段 9** | BBook 风控运营完整化 | 2 周 | 三端齐全 |
| **阶段 10** | 多实例 HA + 行情完整性 | 4 周 | 大部分豁免（运维任务）|
| **阶段 11** | 可观测性 + 对账 | 2 周 | 大部分豁免（运维任务）|

依赖关系：

- 阶段 0 → 阶段 1 → 阶段 2（必须串行）
- 阶段 3-9 依赖阶段 1 完成（管理端基础设施就绪后才能加业务管理端）
- 阶段 10、11 与 3-9 大部分独立可并行

---

## §3. 阶段 0：基础设施

任务编号：`STAGE-0-INFRA`

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节定义阶段任务清单与完成判定，不维护 commit SHA 与日期。

### 完成清单

- [x] CROSS 代码回滚（`git revert b5484e9 15774b0`）
- [x] BBook 风控 mapper SAX 转义补正（CROSS revert 误伤修复，commit `1da2a83`）
- [x] 完成定义升级为"三端任务完成条件"硬约束（§4.A，commit `a7caae9`）
- [x] AI 工作模式 8 角色 → 10 角色扩展（新增 R9 管理端后端 + R10 管理端前端）
- [x] 创建 `role-admin-backend-implementer.md` + `role-admin-frontend-implementer.md` 角色模板
- [x] V1 执行路径归档到 `archive/BBook一期完成执行路径-V1-2026-05-08.md`
- [x] V2 执行路径落地（本文件）

---

## §4. 阶段 1：管理后台地基

任务编号：`STAGE-1-CONSOLE-FOUNDATION`

依赖：阶段 0 完成

估时：4 周

三端要求：豁免客户端要求（仅管理端单端）

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节定义 R2-R8 各角色任务清单与输出物，不维护 commit SHA 与日期。

### 4.1 R2 契约冻结

- 创建 `falconx-console-contract` 模块（如需，或直接在 console-service 内）
- 冻结管理端核心契约：
  - `t_admin_user` / `t_admin_role` / `t_admin_permission` / `t_admin_role_permission` / `t_admin_user_role` / `t_admin_menu` 表结构
  - `POST /api/v1/admin/auth/login` / `POST /api/v1/admin/auth/refresh` / `POST /api/v1/admin/auth/logout`
  - `GET /api/v1/admin/me/permissions`（按 admin role 加载权限点集合）
  - admin JWT payload（独立私钥，与 C 端 JWT 隔离）
  - `t_admin_operation_log` 审计表结构
- 创建 `docs/api/管理端接口规范.md`
- 创建 `docs/architecture/管理端架构.md`（详见阶段 7 任务）

### 4.2 R3 双端设计

客户端按本阶段三端要求豁免；管理端单端设计已落地。

- 客户端：本阶段无新增（豁免）
- 管理端：
  - 信息架构（侧边栏菜单结构 + 顶部用户区 + 面包屑）→ [`docs/design/falconx-console-DESIGN.md`](../design/falconx-console-DESIGN.md) §4
  - 登录页 + 重置密码页 → [`docs/design/falconx-console-pages-V1.md`](../design/falconx-console-pages-V1.md) §1
  - 用户 / 角色 / 菜单 / 权限点管理核心页面 → [`docs/design/falconx-console-pages-V1.md`](../design/falconx-console-pages-V1.md) §2-§5
  - 设计 token 与 C 端共用 `tokens.css` + 管理端扩展 `console-tokens.css`：DESIGN §2
  - AntD 5 ConfigProvider 主题配置：DESIGN §3
  - 高风险操作二次确认视觉规则：DESIGN §7.3

R3 输出范围：DESIGN.md 设计系统 + 5 页面方案（文字 + ASCII 线框，未进 Figma，按用户阶段 1 边界确认）。R10 实施前置依赖：R2 阶段 1 接口规范 §3 增量冻结 `admin-users / admin-roles / admin-menus / admin-permissions` 全套接口。

### 4.3 R6 测试用例骨架

骨架以测试用例清单形式交付（@Test 真代码骨架待 R9/R10 实施后由 R6 二轮落地，避免引用未存在的类导致编译失败）。

- 后端用例清单：[`docs/test/STAGE-1-CONSOLE-test-cases.md`](../test/STAGE-1-CONSOLE-test-cases.md)
  - §3-§6 鉴权链路（登录 / 刷新 / 登出 / 改密）：25 用例
  - §7-§9 me / permissions / menus：12 用例
  - §10 RBAC 注解扫描与鉴权：4 用例
  - §11 IP 白名单：3 用例
  - §12 跨 schema 只读：3 用例
  - §13 审计日志：4 用例
  - §14 端到端链路：1 条 E2E（默认超管首次登录至看到全菜单）
- 前端骨架：[`docs/test/falconx-console-frontend-test-skeleton.md`](../test/falconx-console-frontend-test-skeleton.md)
  - 登录 / 改密 / 单飞刷新 / 路由 + 权限守卫 / 侧边栏 / 高风险二次确认 / Token 安全 / 响应式 / 拦截器：48 用例
- CFD 全面测试用例规范 §13.5 已注册新 prefix `TC-CONSOLE-` / `TC-E2E-CONSOLE-` / `FE-CONSOLE-` 编号块。

### 4.4 R9 管理端后端实施

落地清单：

- [x] 新建 `falconx-console-service` Maven 模块（commit `2ce4b53` 配置 + `94aad4c` JWT + `5c92c55` IP 白名单 + `56c0de5` BCrypt + `fde31ed` 鉴权 4 接口 + `fad9089` me 3 接口 + `55fd0d9` RBAC AOP + `8e8f1aa` 审计 AOP + `d0a384e` 单元测试）
- [x] 独立 schema `falconx_console`（V1 init schema：6 张表 + 1 审计表 + SUPER_ADMIN 角色 INSERT）
- [x] RBAC 用户 / 角色 / 菜单 / 权限点四级模型（`@RequiresPermission` 注解 + AOP 接口级鉴权 + 启动扫描字典 + SUPER_ADMIN 通配放行）
- [x] 默认超管启动初始化（`DefaultSuperAdminInitializer` ApplicationRunner，BCrypt 加密 + 多实例并发安全 + must_change_password=1 强制改密）
- [x] IP 白名单 filter（console 应用级；gateway 路由级单独派 R4 后续实施）
- [x] 操作审计 `t_admin_operation_log`（@AfterReturning AOP 切面 + risk_level + ip + ua + 写失败不阻塞业务）
- [x] admin JWT 独立 RSA 私钥 + Redis 黑名单（key prefix 与 C 端隔离）+ refresh token 一次性轮换
- [x] 19 个单元测试通过（密码策略 9 + token 7 + 高风险标记 3）

### 4.5 R10 管理端前端实施

落地清单：

- [x] 新建 `falconx-console-frontend` 项目（Vite 8 + React 19 + TS 6 + AntD 5.21 + React Query + Zustand + react-router-dom，端口 5300）
- [x] 阶段 1 页面：登录页（`/admin/login`）+ 改密页（`/admin/change-password` 主动 + 强制双模式）+ Dashboard 占位（`/admin`）
- [x] 全局基础设施：sessionStorage token 隔离 + 单飞 401 refresh + useAuthGuard 路由守卫 + RequiresPermission 按钮级 RBAC + ConsoleAntdProvider 主题
- [x] 三件套通过：`npm run lint` ✅ / `npm run build` 846KB → 267KB gzip ✅ / `npm run test` 4 tests pass（FE-CONSOLE-039~042 token 安全）
- [x] vite proxy bypass 修复（commit `a25bfc2`）：浏览器导航返回 index.html，XHR 代理到 console-service

阶段 1 范围豁免：用户列表 / 角色管理 / 权限分配 / 菜单管理 4 个 RBAC 自管页面（5 页面方案 §2-§5）推到 R2 二轮契约冻结后由 R10 二轮落地。

### 4.6 R7 验证 + R8 文档同步

R7 验证证据：

- [x] 后端启动：`mvn -pl falconx-console-service spring-boot:run -Dspring-boot.run.profiles=dev` → 1.78s ready + Flyway V1 + 默认超管 INSERT + RBAC 字典扫描日志
- [x] 14 个 curl 用例：登录 / 改密 / refresh 一次性轮换 / logout 黑名单 / 错误密码 90001 / 不存在用户 90001 / 篡改 token 90003 全部按预期返回
- [x] 浏览器 QA：根路径守卫重定向 + 登录 + Dashboard 渲染 + 改密页 + Avatar 下拉 + 登出 + sessionStorage 清空验证 + 桌面 1440 / 1280 / 移动 375 三档截图
- [x] 0 关键控制台错误（仅 antd v5/React 19 兼容性 warning，可升级 antd 5.22+ 解决）

R7 已知待修复（已记录到统一问题清单 FX-067、FX-068）：

- 移动视口 (<420px) 登录卡片宽度溢出
- 1280-1440px Sider 未变窄到 200px

R8 同步范围：

- 本文件 §4.4-§4.6 标记完成 + 引用 commits
- `当前开发计划.md` §3 文档边界 + §5 路线图新增「STAGE-1-CONSOLE-FOUNDATION 完成」条目
- `统一问题清单.md` 新增 FX-067 / FX-068 R10 二轮待修复

### 完成判定

阶段 1 完成 = 后续业务模块可在已有的 console 服务里加管理端入口（地基就绪）。

**已达成**：后端鉴权链路 + RBAC AOP + 审计 + 默认超管 + 前端登录改密 Dashboard + 路由守卫均已就绪。R10 二轮可在 R2 增量冻结业务管理端 4 类接口后立即接入。

---

## §5. 阶段 2：补齐已完成功能的管理端

任务编号：`STAGE-2-FILL-ADMIN-FOR-DONE`

依赖：阶段 1 完成

估时：4 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节按已落地业务能力逐个补齐管理端入口，定义各子任务（5.1-5.5）的 R2 契约 / R3 设计 / R6 用例 / R4-R10 实施清单，不维护 commit SHA 与日期。

### 5.1 客户管理 → 管理端

任务编号：`STAGE-2-CUSTOMER`

R2 二轮 A 范围：

- 用户决策（2026-05-09）：internal token 静态配置 + 手动轮换 / FROZEN 复用现有 status=2（schema 已存在）/ 调余额限额 Redis + DB 双写
- R2 已冻结：
  - [`管理端架构`](../architecture/管理端架构.md) §4.1 internal token 详细机制（环境变量 `FALCONX_INTERNAL_API_TOKEN` + `X-Internal-Token` header + filter 校验 + fail-closed）
  - [`管理端架构`](../architecture/管理端架构.md) §4.4 调余额限额 Redis 滑动窗口 + DB 审计兜底设计
  - [`管理端接口规范`](../api/管理端接口规范.md) §3 客户管理 5 个 REST + 错误码 90300-90307
  - [`管理端接口规范`](../api/管理端接口规范.md) §4 跨服务 internal RPC 3 个（identity freeze + unfreeze + trading balance/adjust）+ 错误码 90701-90703
- R3 / R6 / R4 / R9 / R10 / R7 / R8 后续推进

R3 二轮设计范围：设计交付到 [`5 页面方案 §9`](../design/falconx-console-pages-V1.md#9-阶段-21-客户管理页面方案r3-二轮2026-05-09)：

- C1 客户列表（跨 schema JOIN identity + trading 余额）+ 6 列定义 + 多筛选
- C2 客户详情（基本信息 + 资金概览 + 操作按钮按状态条件渲染）
- C3 冻结二次确认 Modal（DESIGN §7.3 高风险模板）
- C4 解冻二次确认 Modal（视觉与 C3 一致，文案微调）
- C5 调余额二次确认 Modal（限额实时显示 + 增加/扣减 + 防误操作勾选 + 实时预览）

字段命名修复：5 页面方案 §4 菜单管理 `sort` → `sortOrder`（与 R2 二轮 B 接口规范字段名对齐）。

R6 二轮测试骨架交付：

- [`STAGE-2-CUSTOMER-test-cases.md`](../test/STAGE-2-CUSTOMER-test-cases.md)：阶段 2.1 客户管理 87 用例（后端 50 IT + 跨服务 6 + 1 E2E + 前端 30）
- [`STAGE-1-RBAC-CRUD-test-cases.md`](../test/STAGE-1-RBAC-CRUD-test-cases.md)：阶段 1 P2-P5 RBAC 自管 110 用例（后端 60 + 前端 50）
- [`CFD 规范`](../test/CFD全面测试用例规范.md) §13.6 §13.7 注册 TC 块

实施依赖图：

```
[R2 二轮 A 契约 ✅] → [R3 二轮 客户列表/详情/3 Modal 设计 ✅] → [R6 二轮 测试用例 ✅]
                                                                    ↓
                          [R4 identity freeze/unfreeze internal RPC] → [R9 二轮 console 调用 + 审计 + 限额双写]
                                                                    ↓
                          [R4 trading-core balance/adjust internal RPC] → [R10 二轮 客户列表/详情/二次确认]
                                                                              ↓
                                                                        [R7 验证 + R8 同步]
```

前置说明：

- `t_user.status=2 FROZEN` 已存在于 V1 schema + UserStatus enum + 状态机规范 §2，**无需新增 V3 migration**
- internal token 默认值：dev profile `falconx-internal-dev-token`，prod 必须显式设置（fail-closed）
- 管理员调余额：单次 ≤ $5000，单日累计 ≤ $20000（Redis 实时校验 + DB 审计兜底）
- 调余额操作 owner 是 trading-core（持有 `t_account` + `t_ledger`），console 直接调用 trading-core internal RPC，不绕路 identity

### 5.2 入金记录 → 管理端

任务编号：`STAGE-2-DEPOSIT`

- R9：跨 wallet schema 只读查询入金记录 + 跨 trading schema 只读查询入账事实
- R10：入金记录列表（按状态/时间筛选）+ 详情（链上 tx 信息）+ 未匹配转账列表
- 前置：阶段 11 入金对账接口可在此处复用

### 5.3 行情品种 → 管理端

任务编号：`STAGE-2-SYMBOL`

补充任务卡：[`STAGE-2-SYMBOL-THREE-TABLE-ADMIN`](./task-cards/STAGE-2-SYMBOL-THREE-TABLE-ADMIN.md)。

用户新增要求（2026-05-11）：在管理后台把 Symbol 相关功能先实现完毕，明确覆盖 `t_symbol`、`t_symbol_group_visibility`、`t_symbol_quote_mapping` 三张表。本补充任务是阶段 2.3 的三表管理端收口，不推翻既有 R7 结论；既有 R7 范围只覆盖 `t_symbol` 基础配置、暂停/恢复、Swap Rate、Trading Hours。

已完成：

- R2 二轮 D（commit `663ac48`）：冻结 [`管理端接口规范`](../api/管理端接口规范.md) §6，包含 console 8 个 `/admin/symbols/*` REST 端点、market 7 个 `/internal/v1/market/symbols/*` internal RPC、错误码块 `90600-90649`，并重排阶段 2.2 / 2.4 / 2.5 错误码段避免与 `90701-90703` 冲突。
- R6 三轮（commit `a0cc2d8`）：新增 [`STAGE-2-SYMBOL-test-cases.md`](../test/STAGE-2-SYMBOL-test-cases.md)，覆盖 81 个 TC（console 30 + market internal RPC 20 + E2E 1 + 管理端前端 30）。
- R4.4（commit `b11c95d`）：market-service 落地 internal RPC controller + token filter + Mapper/XML + ApplicationService，owner 数据仍来自 `falconx_market.t_symbol / t_swap_rate / t_trading_hours*`。
- R9（commit `6f2c97c`）：console-service 通过 gateway internal RPC 调 market，新增 8 个管理端 REST 入口、10 个 `906xx` 错误码翻译、4 个 `symbol:*` RBAC 权限点。
- R10（commit `4bf2dcb`）：console-frontend 新增 P6 行情品种页，包含列表筛选、编辑 Drawer、暂停/恢复、Swap Rate Drawer 和 Trading Hours 只读弹窗。
- R7（2026-05-11）：修复 market admin ResultMap primitive 映射、swap-rate Redis 快照即时刷新、symbol 高风险审计等级、console favicon 404，并完成 API/浏览器/Swap 结算链路验证。
- 三表补充（2026-05-11）：新增 `quote-mappings` 与 `group-visibility` 管理端 REST / market internal RPC，覆盖 `t_symbol_quote_mapping` 新建/编辑、LP 订阅开关、价格乘数/加点、`t_symbol_group_visibility` upsert；接口不按 `.p / .c / .f` 或其他后缀做特殊拒绝，source 是否实际订阅由运行时 `t_symbol.status=1 AND mapping.enabled=1 AND lp_subscribe_enabled=1` 决定。

已验证：

- market-service：`MarketServiceApplicationTests` 2/2 通过，`package` 通过。
- console-service：`HighRiskPermissionRegistryTests` 3/3 通过，`package` 通过。
- R7 API live script：`53 pass / 0 fail`，覆盖 console 8 入口、market internal RPC、Redis swap-rate 快照即时刷新和审计 `HIGH_RISK`。
- trading-core：`TradingSwapSettlementIntegrationTests` 3/3 通过，确认 Redis swap-rate 快照格式可被结算链路消费。
- console-frontend：`npm run test` 4/4、`npm run lint` exit 0、`npm run build` 通过。
- 浏览器 QA：7 张截图归档 `/tmp/falconx-symbol-r7/`，DevTools `runtimeErrors=0 / networkFailures=0 / symbol5xx=0`。
- 三表补充自动化：`MarketSymbolAdminApplicationServiceMappingTests` 3/3 通过；`HighRiskPermissionRegistryTests` 覆盖 `symbol:quote-mapping:update` / `symbol:group-visibility:update` 高风险等级。
- 三表补充 R7 二轮收口（2026-05-11）：详见 [`STAGE-2-SYMBOL-THREE-TABLE-ADMIN-R7-verification-report`](../test/archive/STAGE-2-SYMBOL-THREE-TABLE-ADMIN-R7-verification-report.md)。API live 17/17 pass（含 `.p` 后缀不被拒、`90613/90614/90615/90616/90618` 错误码、热刷新 `after_commit + sourceSymbolCount=1563`）、桌面 + 移动浏览器 QA 12 张截图（三 Tab + mapping Drawer + visibility 批量 Drawer + falconx-frontend 入口）、客户端按 group 过滤验证 `default=1571 / vip=0` + 无 `sourceSymbol` 字段泄露、审计 `HIGH_RISK` 10 条落库。
- 测试债务：81 TC + 11 个三表补充 TC 尚未全部转成独立 CI `@Test` / 组件测试；本轮以 live API + 目标回归测试 + 浏览器 QA 完成 R7 收口。
- R7 二轮同步项（推回对应角色，非阻断）：`99004 vs 90618` empty body 错误码归属（推回 R2 三轮）、`POST /admin/symbols/quote-mappings` 审计 `target_id=NULL`（推回 R9 三轮）、WebSocket 客户端推送端到端截图（推回 R7 后续补做）、`vip` 等新 group 空列表用户体验（推回 R3 / R10 三轮）。

### 5.3-PARAMS-DOWNSHIFT Symbol 参数下沉到 mapping（插队在 5.4 之前）

任务编号：`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`

任务卡：[`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`](./task-cards/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT.md)

该任务卡范围包含 R2 契约 + R3 设计 + R6 用例 + R4 market + R4.2 trading-core + R9 console + R10 frontend + R7 验证 + R8 同步。

用户决策（2026-05-12）：把 LP 上游当成纯报价源，所有交易参数（杠杆、费率、点差、qty 限制）下沉到 `t_symbol_quote_mapping`，`t_symbol` 只保留 LP 源元数据 + 基础公共配置。

R2 三轮已冻结契约：

- **DB schema**（详见 [数据库设计 §4.2 / §4.3](../database/falconx一期数据库设计.md)）：
  - market 模块 `V11__downshift_symbol_params_to_mapping.sql`：t_symbol 删 6 交易字段；t_symbol_quote_mapping 加 6 交易字段 + 2 precision 覆盖 + 时间戳升级到 datetime(3) + 7 个 CHECK 约束；Flyway 同事务 backfill UPDATE m JOIN s
  - console 模块 `V2__migrate_symbol_update_to_source_update.sql`：把所有 symbol:update 持有者自动补 symbol:source:update（idempotent），废弃旧权限码不删
  - trading 模块 `V13__add_open_fee_rate_snapshot.sql`：t_order / t_position 各加 `open_fee_rate decimal(10,6) NOT NULL DEFAULT 0` 快照字段
  - t_symbol 不加 last_tick_at（用 ClickHouse `falconx_market_analytics.quote_tick.event_time` 替代）
- **API 契约**（详见 [管理端接口规范 §6.13](../api/管理端接口规范.md)）：
  - 新增 `POST /admin/symbols`（symbol:source:create HIGH_RISK）+ `GET /admin/symbols/group-visibility/grouped` 聚合视图 + `GET /internal/v1/market/symbols/last-tick` + `GET /internal/v1/market/symbols/spec/{platformSymbol}` 4 个新接口
  - 修改 `PUT /admin/symbols/{id}` 字段集裁剪 + 改用 `symbol:source:update`；mapping POST/PUT 加 6 交易字段 + 2 precision 覆盖；C 端 `/api/v1/market/symbols` 字段名不变，底层取值改自 mapping
  - 错误码 `90601-90604` 语义搬迁到 mapping；新增 `90619 SOURCE_DUPLICATE / 90620 SOURCE_INVALID`
- **trading-core 消费**（详见 [管理端架构 §4.3](../architecture/管理端架构.md)）：
  - market 暴露 `SymbolSpec` 通过 Redis Hash `falconx:market:symbol-spec:{platformSymbol}`；mapping CRUD afterCommit 触发 warmup
  - trading-core 改造 `DefaultTradingRiskService.evaluateOpenPosition` 消费 spec：effective max leverage = `min(mapping.max_leverage, t_risk_config.max_leverage)`；fee_rate 用 mapping；qty/notional 校验加入；开仓快照 `open_fee_rate`
  - 新增拒单错误码 `SYMBOL_SPEC_NOT_FOUND / QTY_BELOW_MIN / QTY_ABOVE_MAX / NOTIONAL_BELOW_MIN`
  - 历史 t_order / t_position 通过 `open_fee_rate` 快照保护，运营改 mapping 不影响存量

强制约束：

- Phase A（schema + 管理端可配）与 Phase B（trading-core 消费）**必须捆绑发布**，分阶段部署 = 调配不生效，视为违反任务卡
- 不可自动回滚：migration 前必须备份 t_symbol；回滚需要单独 reverse migration

实施前置依赖：本任务必须在阶段 5.4 订单监控启动**之前**完成，避免 5.4 中读取 mapping 字段触发二次重构。

### 5.4 订单与持仓监控 → 管理端

任务编号：`STAGE-2-TRADING-MONITOR`

- R2：内部 RPC `/internal/v1/trading/orders` / `/positions` / `/exposures` / `/positions/{id}/manual-liquidate`
- R9：调用 trading-core 内部 RPC
- R10：订单列表 / 持仓列表 / 净敞口看板 / 手动强平按钮 / 暂停自动强平开关

### 5.5 风控管理 → 管理端

任务编号：`STAGE-2-RISK-ADMIN`

- 把 V1 阶段 3 的"风控运营 REST"内部接口移到 console（替换业务服务暴露 `/internal/v1/trading/risk-controls`）
- R10：动作激活 / 停用 / 敞口查询 / 跨品种集中度配置 / 强平日志查询

### 阶段 2 完成判定

所有已落地业务功能（注册/登录/入金/开仓/平仓/TP/SL/强平/Swap/BBook 风控）均有管理端入口。

---

## §6. 阶段 3：挂单（LIMIT / STOP / SL-TP）

任务编号：`STAGE-3-PENDING-ORDER`

依赖：阶段 1 完成（管理端可同步交付）

估时：5 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。

### 6.1 R2 契约冻结

- `TradingOrderType` 扩展：`MARKET / LIMIT / STOP / STOP_LIMIT`
- `TradingOrderStatus` 已预留 `PENDING / TRIGGERED`，启用
- 新建 `t_pending_order_trigger`（挂单 → 触发条件 → 触发后转为成交订单）
- **决策 B：废弃 `t_position.take_profit_price / stop_loss_price`，全部统一为独立挂单**
- migration：迁移已有持仓的 SL/TP 到新挂单表
- API：
  - `POST /api/v1/trading/orders/limit`
  - `POST /api/v1/trading/orders/stop`
  - `POST /api/v1/trading/positions/{id}/sl-tp-orders`（开仓后追加 SL/TP）
  - `DELETE /api/v1/trading/orders/{id}`（撤单）
  - `PATCH /api/v1/trading/orders/{id}`（修改）
- Kafka：`falconx.trading.order.triggered` 内部 topic

### 6.2 R3 双端设计

- 客户端：下单面板支持 LIMIT / STOP，挂单列表，撤单/修改
- 管理端：挂单监控 / 强制撤单 / 异常挂单告警

### 6.3 R6 测试用例骨架

- LIMIT 触发 → 转市价成交
- STOP 触发 → 转市价成交
- SL/TP 触发 → 反向平仓
- 撤单 → 释放保证金
- 修改距离冻结（挂单距当前价 < 0.3% 拒改）
- 多挂单同时触发的优先级
- 已有持仓的 SL/TP 迁移到挂单表后行为一致

### 6.4 R4 后端实施

- 新建 `PendingOrderTriggerEvaluator`（与 `PositionTriggerRuleEvaluator` 同级，挂在 `QuoteDrivenEngine` 内）
- 资金冻结：挂单创建即冻结保证金到 `account.frozen`
- 撤单 / 修改 / 距离冻结校验
- migration：废弃 `t_position.take_profit_price / stop_loss_price`，迁移到 `t_pending_order_trigger`

### 6.5 R5 客户端前端实施

- 下单面板新增 LIMIT / STOP tab
- 挂单列表（按状态分组）
- 撤单 / 修改弹窗
- WebSocket 推送 `order.triggered`

### 6.6 R9 / R10 管理端

- R9：内部 RPC `/internal/v1/trading/pending-orders` / `/cancel`
- R10：挂单列表 / 强制撤单 / 异常挂单告警面板

### 6.7 R7 / R8

- E2E：客户端下挂单 → 行情触发 → 成交 → 客户端 + 管理端均看到
- 同步统一接口文档 + Kafka 事件规范 + 状态机规范

---

## §7. 阶段 4：价格告警

任务编号：`STAGE-4-PRICE-ALERT`

依赖：阶段 1 + 阶段 8（通知系统先行）；如阶段 8 未完成则先做基础站内信

估时：2 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。

### 7.1 数据模型 + API

- `t_price_alert`：user_id / symbol / direction(ABOVE/BELOW) / target_price / status(ACTIVE/TRIGGERED/CANCELLED) / triggered_at
- `POST /api/v1/market/price-alerts` / `GET` / `DELETE /{id}`
- 触发后通过通知系统发送站内信 + WebSocket 推送

### 7.2 引擎接入

- 在 `QuoteDrivenEngine` 内每个 tick 评估当前 symbol 的所有 ACTIVE 告警
- 触发后状态改 `TRIGGERED`，发布通知事件

### 7.3 三端实施

- R5 客户端：告警设置面板 + 告警历史 + 触发实时弹窗
- R10 管理端：告警规则列表 / 强制删除（运营干预）
- 测试覆盖触发 / 重复不再触发 / 撤销

---

## §8. 阶段 5：注册后地址预分配

任务编号：`STAGE-5-WALLET-PROVISION`

承接 V1 `WALLET-REGISTER-ADDRESS-01` 方案（identity → Kafka → wallet 派生）。

估时：1.5 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节定义阶段任务清单与 R 角色输出物，不维护 commit SHA 与日期。

### 8.1 R2 契约范围

- identity → wallet Kafka topic `falconx.identity.user.registered`（payload: eventId / userId / uid / email / registeredAt；详见 [Kafka 事件规范 §12.13](../event/Kafka事件规范.md)）
- wallet 端 DLQ 表 `t_wallet_address_provision_dlq` 用于落 `WALLET_ADDRESS_ALLOCATION_FAILED`（xpub 缺失等配置错误）
- wallet admin internal RPC `/internal/v1/wallet/console/provision-dlq[/{id}/retry]`（详见 [管理端接口规范 §11](../api/管理端接口规范.md)）
- console 透传 `/admin/wallet/provision-dlq*` + RBAC 权限点 `wallet-provision:view / wallet-provision:retry`
- 错误码段位：wallet `90860/90861` + console `90860/90861/90862`

### 8.2 R 角色任务

- R4 identity：注册事务 afterCommit 发布 `falconx.identity.user.registered`
- R4 wallet：consumer 幂等消费 + `ensureDefaultUsdtDepositAddresses` 派生 TRC20/ERC20；`WALLET_ADDRESS_ALLOCATION_FAILED` 落 DLQ + 吞掉；其他异常 rethrow（Spring Kafka 默认重试 + DLT）
- R9 console：透传 + 错误码翻译 + OperationAuditAspect 审计
- R10 console-frontend：DLQ 列表页 + 重试 modal（reason 必填）+ 按钮级 RBAC
- R6：identity 发布 IT + wallet consumer / DLQ Repo / admin RPC IT + console transport IT + frontend Vitest
- R7：三端 E2E（注册 → afterCommit → 派生地址 + DLQ 路径 + admin retry）+ 浏览器 QA（若 WSL chromium 受阻按程序化 E2E 降级）

### 8.3 已知技术债（P2 enhancement，不阻塞收口）

阶段 5 落地早于 STAGE-6-KYC contract / headers 标准化，存在三项不一致：

1. identity 未使用 `IdentityUserRegisteredEventPayload` contract record（仍用 `LinkedHashMap → JSON`，wallet 端用 `JsonNode` 解析）
2. identity 未使用 `KafkaEventMessageSupport` headers（缺 `X-Event-Id` / `X-Event-Type` / `X-Event-Source` / `X-Trace-Id`）
3. identity 未使用 Outbox 模式（当前 afterCommit 直接 publish + wallet DLQ 兜底）

后续在新一轮 Kafka 事件统一化专项中处理；当前 DLQ 兜底机制完整。

---

## §9. 阶段 6：KYC（首次出金触发 + 地址不一致触发）

任务编号：`STAGE-6-KYC`

依赖：阶段 1 + 阶段 8（通知系统）

估时：3 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节定义 Phase 1-4 任务清单与 R 角色输出物，不维护 commit SHA 与日期。

### 9.1 数据模型

实际落地（commit `94ac29b`）：

- `t_kyc_submission`：id / user_id / level / status(0/1/2 = PENDING/APPROVED/REJECTED) / id_type / id_number / submitted_at / reviewer_id / review_at / reject_reason
- `t_kyc_document`：id / submission_id / doc_type(1/2/3 = ID_FRONT/ID_BACK/HOLDING_SELFIE) / data_base64 (LONGTEXT) / sha256 / mime_type
- `t_user.kyc_level`（TINYINT 默认 0；APPROVED 后 = 1，同事务写入）

存储口径：V2 一期不接 S3；证件 base64 落 `data_base64` LONGTEXT，后续切 S3 时加 `s3_url` 列做兼容。

### 9.2 触发逻辑

- **触发 1：首次出金** — 阶段 7 出金前置校验：`user.kyc_level < 1` 且尝试出金 → `30043 WITHDRAW_KYC_REQUIRED`（已落地，阶段 7 Phase 1）
- **触发 2：地址不一致** — 阶段 7 出金前置校验（KYC 通过后）：本次 `targetAddress` ∉ 用户在对应链 `t_wallet_deposit_tx (status=CONFIRMED)` 的去重 `from_address` 集合 → `30056 WITHDRAW_UNFAMILIAR_ADDRESS`（**2026-05-19 已落地**：wallet 暴露 `/internal/v1/wallet/console/deposits/from-addresses`；trading-core 在 KYC 后、白名单前调用；ERC20 case-insensitive，TRC20 严格匹配）
- **触发 3：用户主动** — 客户端 TerminalTopbar 提供 `KYC 认证` 按钮，用户随时可打开 KycSubmitDrawer 提交（**阶段 6 Phase 3 实施**）

### 9.3 简单 KYC 字段集

- 真实姓名 / 出生日期 / 国籍：复用 `t_user_profile` 5 强制字段（STAGE-1B 已落地）
- 证件类型：`ID_CARD` / `PASSPORT` / `DRIVER_LICENSE`（KycIdType 枚举）
- 证件号：`id_number` VARCHAR(64)
- 证件正面 / 证件反面 / 手持证件自拍：3 张 base64 图片（doc_type 1/2/3）

### 9.4 三端实施

按 Phase 拆分：

**Phase 1+2 已完成（commit `94ac29b`）**：

- ✅ R2：V6__kyc.sql + 实体 + 错误码 10040-10046
- ✅ R4 identity：`/api/v1/me/kyc` POST/GET + `/internal/v1/identity/kyc` 4 接口 + IdentityKycApplicationService + Repository/Mapper
- ✅ R9 console：`AdminKycApplicationService` 透传 + 错误码翻译 + RBAC `kyc:view` / `kyc:review` + OperationAuditAspect
- ✅ R10 console-frontend：`/admin/kyc` 列表页 + 详情 modal（3 张证件 base64 渲染 + sha256）+ 通过/拒绝按钮 + 拒绝原因校验

**Phase 3+4 收口准备已完成（本会话）**：

- ✅ R2：`KycReviewedEventPayload` contract（identity-contract）+ Kafka 事件规范 §11 + §12.7 落地
- ✅ R3：客户端 KYC Drawer 设计冻结到 [`STAGE-6-KYC-client-design`](../design/STAGE-6-KYC-client-design.md)
- ✅ R6：测试用例骨架 52 条冻结到 [`STAGE-6-KYC-test-cases`](../test/STAGE-6-KYC-test-cases.md)

**Phase 3+4 实施待执行（下个会话）**：

- ⏳ R5 客户端：新建 `src/features/kyc/`（KycSubmitDrawer + kycApi + types + Vitest 用例 5 条）+ TerminalTopbar 加 KYC 按钮 + 状态徽章
- ⏳ R4 identity：`IdentityKycApplicationService.approve/reject` 事务后发布 Kafka `falconx.identity.kyc.reviewed`（@TransactionalEventListener 或显式发布点）+ TC-KYC-050~053 集成测试
- ⏳ R4 trading-core：`KycReviewedConsumer` 消费 → 调 `TradingNotificationApplicationService.create()`（APPROVED → INFO "KYC 已通过"；REJECTED → WARN "KYC 未通过：{reason}"，幂等键 `relatedKey="kyc.reviewed"+relatedId=submissionId`）+ TC-KYC-060~064 集成测试
- ⏳ R6/R9：identity + console + trading-core 集成测试 38 条 @Test 真代码落地
- ⏳ R10：管理端 KYC 列表/详情/审核浏览器 QA 截图（桌面 + 移动）
- ⏳ R7：三端最终验证报告 [`STAGE-6-KYC-R7-verification-report`](../test/archive/STAGE-6-KYC-R7-verification-report.md)（待 R7 输出）
- ⏳ R8：FalconX 统一接口文档 + 管理端接口规范 + 数据库设计 同步

### 9.5 通知集成

KYC 通过/拒绝 → 站内信 + WebSocket 推送（**Phase 4，下个会话实施**）：

- identity 发布 Kafka `falconx.identity.kyc.reviewed`（contract + 规范已冻结）
- trading-core 消费 → `TradingNotificationApplicationService.create()` → 写 `t_notification` + 推 WS `notification.created`
- 客户端 NotificationCenter 自动展示（已有 STAGE-8 Phase 1 MVP 链路，无需新增前端逻辑）

### 9.6 出金前置（阶段 7 锚点）

- identity 提供 `GET /internal/v1/identity/users/{userId}/kyc-status` 返回 `kyc_level`（阶段 6 Phase 1 落地状态待确认；如未提供则阶段 7 实施时补）
- 阶段 7 出金 ApplicationService 入参校验：`kyc_level < 1` → 抛 `WITHDRAW_KYC_REQUIRED` 错误码（待阶段 7 R2 冻结）+ 客户端跳转 KycSubmitDrawer
- **trigger 2 陌生地址校验（2026-05-19 落地）**：在 KYC 校验之后立即执行，wallet 端通过 `GET /internal/v1/wallet/console/deposits/from-addresses?userId=&chain=` 返回去重 `from_address` 集合（chain 映射：ERC20→ETH，TRC20→TRON，仅 `status=CONFIRMED`）；trading-core 在 `WithdrawSubmitApplicationService.submit()` step 6.5 做 `containsIgnoreCase` 比对，命中失败抛 `30056 WITHDRAW_UNFAMILIAR_ADDRESS`，附带 `historySize` 元数据辅助前端区分「无入金历史」与「历史不含目标地址」两种情况。

---

## §10. 阶段 7：出金完整链路

任务编号：`STAGE-7-WITHDRAW`

依赖：阶段 1 + 阶段 6 + 阶段 8

估时：6 周（最长阶段）

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。

### 10.1 数据模型

- `t_withdraw_order`：user_id / amount / target_address / network / status / created_at / reviewer_id 等
- `t_withdraw_tx`：withdraw_order_id / tx_hash / nonce / gas / confirmations
- `t_withdraw_whitelist`：user_id / address / network / added_at（白名单地址）

### 10.2 状态机

```
COOLING → PENDING → APPROVED / APPROVED_DELAYED → PROCESSING → COMPLETED / FAILED
```

- 冷静期：默认 2h
- 大额延迟期：≥ $3000 → APPROVED_DELAYED 6h，可紧急取消
- 单笔上限：$10K
- 单日上限：$30K

### 10.3 链上签名

- `KmsSigner` 抽象（一期本地实现，KMS 接口预留作为 stub）
- Nonce 管理（ERC20 / TRC20 各自单实例排队）
- 链上交易广播 + 确认追踪
- 失败重试 + DLQ

**当前状态（2026-05-14）：Phase 3 commit 10 B 段 IT 落地完成**

- ✅ `LocalKmsSigner` 落地 secp256k1 签名（支持 hex / PKCS#8 PEM / SEC1 EC PEM 三私钥格式）；@ConditionalOnExpression 私钥非空才激活；prod 未配置时 `KmsSignerStub` 兜底（抛 UnsupportedOperationException）
- ✅ ERC20 Nonce 管理：`EthNonceManager` 启动从 RPC `eth_getTransactionCount(pending)` 拉链 + AtomicLong 并发递增 + reset 机制
- ✅ TRC20 nonce 管理：推迟（需 user 补 Tron RPC env）
- ✅ 链上交易广播：`WalletWithdrawBroadcastApplicationService` 消费 `trading.withdraw.reviewed` APPROVED → encodeErc20Transfer(to, value × 10^decimals) → 构造 legacy RawTransaction → keccak hash → KmsSigner.sign → 拼装 signed tx → `eth_sendRawTransaction` → 写 t_withdraw_tx SIGNING→BROADCAST + 发布 `wallet.withdraw.broadcast`；gas price capped 至 100 gwei
- ✅ 确认追踪：`WalletWithdrawConfirmationScheduler` @Scheduled 30s 默认；`eth_getTransactionReceipt` → confirmations ≥ 12 → CONFIRMED + 发布 `wallet.withdraw.confirmed`；receipt status=0 → FAILED 20011 + 发布 `wallet.withdraw.failed`
- ✅ 失败收口：签名失败（KmsSignerException / UnsupportedOperationException）→ failed 20014；广播失败（IOException / web3j error）→ failed 20010；任意失败统一回滚 frozen 释放（trading-core 消费 failed 事件）
- ✅ 错误码段落地：`WalletErrorCode` 20010 BROADCAST_FAILED / 20011 TX_REVERTED / 20014 SIGNER_UNAVAILABLE
- ✅ 配置隔离：`falconx.wallet.withdraw.eth.*` 独立配置块，出金 RPC 从 `Alchemy_Ethereum_Sepolia_HTTPS` env 注入；不与入金 `chains.eth.rpc-url` 共用，避免误触发入金扫真链
- ⏳ DLQ：commit 9 暂未独立 DLQ 表；当前失败路径已通过 wallet.withdraw.failed 事件 + frozen 释放兜底
- ✅ B 段 IT 落地（commit 10，2026-05-14）：`docs/test/STAGE-7-WITHDRAW-Phase3-test-cases.md` §1-§2 共 28 个 IT/UT 真代码全部完成（wallet 端 22 + trading-core 端 9 - 重复计算的 TC-WD-103 双侧覆盖归并；具体落地索引详见该文档 §0）。wallet 全量 70/70 通过、trading-core Withdraw IT 49/49 通过；修复预存 `TradingKafkaEventListenerTests` 7 处构造函数未跟 commit 9 A 段新增 3 个 consumer 参数的 baseline 缺口。TC-WD-123 spec 与 impl 漂移：当前 IT 验证实际 `FAILED 20010` 行为；nonce 冲突 retry 策略列入 enhancement 待办。
- ⏳ Sepolia 真链 E2E（TC-E2E-WD-001 / 002）：推迟到 user 在 .env 补 `FALCONX_WALLET_ERC20_PRIVATE_KEY_PEM` / `FROM_ADDRESS` / `USDT_CONTRACT` / 测试网 ETH gas + USDT 余额 + 目标地址 后由 R7 单独执行

**trading-core 消费链路（commit 9 A 段一并完成）**：

- ✅ `WalletWithdrawBroadcastEventConsumer` CAS APPROVED→PROCESSING + 写 tx_hash + processing_started_at
- ✅ `WalletWithdrawConfirmedEventConsumer` CAS PROCESSING→COMPLETED + `TradingAccountService.settleConfirmedWithdraw`（balance/frozen 双扣减）+ t_ledger biz_type=WITHDRAW_SETTLE + 站内信（INFO 出金已完成）+ relatedKey 幂等
- ✅ `WalletWithdrawFailedEventConsumer` CAS 任意非终态→FAILED + `TradingAccountService.refundFailedWithdraw`（frozen 释放，balance 不变）+ t_ledger biz_type=WITHDRAW_REFUND_CHAIN_FAILED + 站内信（WARN 出金失败含 failureReason）+ relatedKey 幂等

### 10.4 三端实施

- R5 客户端：出金申请页 + 白名单管理页 + 出金历史 + 撤销冷静期内出金
- R9 管理端：审核列表 + 详情 + 通过 / 拒绝 / 紧急取消（APPROVED_DELAYED 阶段）
- R10 管理端：审核工作台 + 风控干预

### 10.5 KYC 集成（阶段 6 前置）

- 出金前置：检查 KYC 状态 + 地址比对（按阶段 6.2）

### 10.6 通知集成

- 出金状态变化（COOLING/PENDING/APPROVED/PROCESSING/COMPLETED）→ 站内信 + WS

---

## §11. 阶段 8：通知系统

任务编号：`STAGE-8-NOTIFICATION`

依赖：阶段 1（管理端模板管理依赖）

估时：2 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。

按用户决策 3，**最小集 = 站内信 + WebSocket**。

### 11.1 数据模型

- `t_notification`：user_id / template_code / params / status(UNREAD/READ) / created_at
- `t_notification_template`：code / title_template / body_template / level（INFO/WARN/CRITICAL）
- 邮件 / Telegram 接口预留（不真实发送）

### 11.2 触发场景

- 入金到账（已有事件源 → 触发通知）
- 出金状态变化（阶段 7）
- KYC 审核结果（阶段 6）
- 价格告警触发（阶段 4）
- 强平 / TP / SL 触发（已有事件源）
- 风控动作通知（已有 risk.warning，集成站内信）

### 11.3 三端实施

- R5 客户端：通知中心 + 未读数 + 标记已读 + 实时推送
- R10 管理端：模板管理 CRUD + 用户通知列表（运营查看）

---

## §12. 阶段 9：BBook 风控运营完整化

任务编号：`STAGE-9-RISK-OPS-COMPLETE`

依赖：阶段 1 + 阶段 2.5（风控管理已迁入 console）

估时：2 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。

继续完善 V1 `BBOOK-RISK-CONTROL-01-OPS` 中未做的部分：

### 12.1 跨品种集中度数据初始化

- V Schema migration：注入默认相关性组（EUR_GROUP / METAL_GROUP / CRYPTO_MAJOR_GROUP / JPY_GROUP）

### 12.2 风控审计完整化

- 审计日志查询接口
- 风控告警通知集成（阶段 8）

### 12.3 风控运营手册

- `docs/operations/BBook风控运营手册.md`

---

## §13. 阶段 10：多实例 HA + 行情完整性

任务编号：`STAGE-10-HA-MARKET`

依赖：阶段 1（部分管理端能力依赖）

估时：4 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。

合并 V1 阶段 4 + 阶段 5 的全部内容：

- 10.1 用户实时推送多实例广播（Kafka 内部 topic）
- 10.2 gateway WS 多实例连接数限制（Redis 全局计数）
- 10.3 K 线聚合 leader 选举（Redisson 锁）
- 10.4 OpenPositionSnapshotStore 多实例策略（Kafka 增量同步 + 一致性校验）
- 10.5 行情冷启动预热
- 10.6 LP 历史 K 线回填
- 10.7 LP 长断线保护（自动 SUSPEND）

大部分豁免管理端要求（运维任务），但运维监控面板（哪个实例是 K 线 leader？）建议补管理端。

---

## §14. 阶段 11：可观测性 + 对账

任务编号：`STAGE-11-OBS-RECON`

依赖：无（可与其他阶段并行）

估时：2 周

> **当前状态**：详见 [当前开发计划 §1](../setup/当前开发计划.md)。

合并 V1 阶段 6 + 阶段 7：

- 11.1 Spring Boot Actuator 健康检查
- 11.2 关键链路 canary 自动化脚本（`falconx-canary` 模块）
- 11.3 日志检索手册
- 11.4 入金对账接口（依赖阶段 2.2 入金管理端）

豁免管理端（运维任务）。

---

## §15A-GM. STAGE-12 用户组级双向加点全链路 (2026-05-21 R7 收口通过)

### 范围

用户组×symbol 双向 bid/ask 绝对加点全链路（推送/REST/撮合/PnL/强平/挂单触发）：

- market `V14__symbol_group_markup.sql` `t_symbol_group_markup` + `DefaultMarketGroupMarkupService` 内存快照 30s 刷新 + `MarketGroupMarkupInternalController`
- market REST 出参 + WebSocket `sendPriceTick` 按 `X-User-Group-Code` / session.groupCode 实时加点；`price.tick` / REST `/quotes` 补 `baseBid/baseAsk/baseMid/hasMarkup` 基准价
- trading `V27__position_pending_order_group_markup_freeze.sql`：`t_position` 加 group_code/bid_extra/ask_extra_at_open、`t_pending_order_trigger` 加 *_at_create 冻结列
- trading 8 改造点：开仓 fillPrice 含加点 + 冻结到 position + PnL / 强平 effectiveMark / 平仓 / 挂单触发全用冻结值 + `PlaceMarketOrderCommand` 加 groupCode（兼容旧构造器）
- console R9 7 端点透传 + RBAC `symbol:group-markup:update`(HIGH_RISK) + 审计 + 错误码 90640-90642
- console-frontend R10 组卡片视图 + 详情 Drawer + 行内编辑（`af39bbd8` 重设计）

### 测试

- 64 测试全过：market 25 UT/IT + trading 31 UT/IT（含 close double-markup bug 修复 4 IT）+ console 8 IT + frontend 9 Vitest
- R7 报告 §9 结论 PASS（含 1 真 bug 修复）
- 本会话复跑无回归：console 8/8 + frontend group-markup vitest 9/9 全绿（STAGE-14 后）

### Commits（按时序，~22 累积）

| SHA | 内容 |
| --- | --- |
| `eb8bc09e` | commit 1 R2 契约 + R4 market 全套(V14) + trading 数据层(V27) + R6 骨架 |
| `70eaf32` | commit 2 trading service 层 + 30s 刷新 |
| `45b5444` | commit 3 trading 8 改造点真应用加点 |
| `37db8e4f` | R9 console 7 端点透传 + RBAC + 审计 + 90640-90642 |
| `4ee3a910` → `af39bbd8` | R10 列表/Modal → 重设计组卡片 + Drawer + 行内编辑 |
| `ba37fe6c`…`23143f1a` | R6 二轮真 IT/UT（TC-GM-UT-001~024 + 14 IT + WS + 挂单 + 强平 + RPC path） |
| `4a25c1aa` | close 链路 double markup bug 修复 + 4 IT |
| `6cfc6bff` / `52da1ca4` | R7 验证报告 PASS + Integer→Boolean enabled 修复 + 6 截图 |
| `94d7c9aa` / `f24faf65` / `90e59690` / `0febc817` | 6 用户反馈 + K 线 baseBid/baseAsk/baseMid + 持仓详情透明化 |
| `6a39c353` / `2bd4d959` / `846bef0a` | audit 5 项 + §6.14.0 文档 + 收紧加点异常保护 |
| `df40d748` | group-markup init 失败降级不 fail-fast（P0 根因加固） |
| `3ca18b71` / `491c4623` | h5-mobile 规则收窄 tablet only + 隐藏 chart metrics |

### 已知不阻断项（R7 §8，非收口阻断）

- (1) 非 SUPER_ADMIN 运营角色未显式关联 `symbol:group-markup:update`（依赖 `@RequiresPermission` 运行时注册，运营角色写需求需补类 V11 角色关联 migration，可选 enhancement）
- (2) 编辑 Modal 实时预览计算未实现
- (3) `GroupMarkupListPage`/`EditModal` 页级 Vitest 未落（仅 `groupMarkupApi.test.ts` 9 Vitest）
- (4) TC-GM-E2E-001~004 + PERF-GM-001~005 未落（需 dev server + LP 真接入）
- (5) 浏览器 QA 截图 WSL 受限

### 迁移

- market V14 + trading V27 均无 `USE` 污染（早于 14B root bug），不背 14B/14C 部署阻断。

### 关联文档

- 设计稿 [`docs/design/STAGE-12-GROUP-MARKUP-design.md`](../design/STAGE-12-GROUP-MARKUP-design.md)
- R7 验证报告 [`docs/test/STAGE-12-GROUP-MARKUP-R7-verification-report.md`](../test/STAGE-12-GROUP-MARKUP-R7-verification-report.md)
- 用例 [`docs/test/STAGE-12-GROUP-MARKUP-test-cases.md`](../test/STAGE-12-GROUP-MARKUP-test-cases.md)
- 管理端接口规范 §6.14 / 数据库设计 §4.2+§4.3 / REST 规范 §2.1 / WebSocket 规范 §6.1

---

## §15. STAGE-14A FX 数据源 (2026-05-29 收口)

### 范围

market-service FX 实时汇率服务完整化：

- V18 baseline 8 FX symbol（EURUSD / AUDUSD / USDJPY / GBPUSD / USDCAD / USDCHF / NZDUSD / USDCNH）
- `FxRateService` + Redis cache（TTL 5s）+ 交叉换算（USD pivot，8 位精度）
- `FxRateKafkaPublisher` 1Hz 节流发布 → `falconx.market.fx.rate.update`
- `FxRateStaleDetector` 30s 超时检测 + 60011 告警
- internal RPC `GET /internal/v1/market/fx/rates(/{base}/{quote})`
- ingestion service FX 分支转发（不改 LP 监听器）

### 测试

- 5 UT `FxRateConverter`（Task 4）
- 7 UT `DefaultFxRateService`（Task 5）
- 1 UT `FxRateStaleDetector`（Task 8）
- 8 IT `FxRateRedis / Kafka / InternalRpcIntegrationTests`（Task 10）
- 1 PERF `FxRateThroughputTests` 30s 24K tick（Task 11，真 5min 留 CI）

共 22 测试全过 + 不回归既有测试。

### Commits（按时序）

| SHA | 内容 |
| --- | --- |
| `a611ac2` | docs(design): STAGE-14 master spec |
| `9aa60d0` | docs(plan): STAGE-14A 实施计划 |
| `fc570e2` / `f932170` | Task 1 contract |
| `ae9b84a` | Task 2 V18 seed |
| `90adbc5` | docs spec/plan 回填 |
| `3af22e1` / `c49146d` | Task 3 errorcode + config |
| `1378f58` / `a89cae7` | Task 4 converter + 5 UT |
| `1b53049` / `32764ea` | Task 5 service + 7 UT + @Bean Clock |
| `5e86da6` / `2121031` | Task 6 ingestion FX 分支 |
| `c8b1795` / `2339716` | Task 7 Kafka publisher |
| `6240dc9` | Task 8 stale detector |
| `c6008e3` | Task 9 internal RPC |
| `cf2f2f2` | Task 10 8 IT |
| `e60668d` | Task 11 PERF 30s |

### 已知不阻断项

- (1) PERF 真 5min 跑测留 CI / 本地
- (2) STAGE-14B-E 后续阶段：trading-core `CurrencyConverter` / MarginLevel + Tier / CROSS-ISOLATED / 三端 UI
- (3) admin FX rate 监控 page（console-frontend）留 STAGE-14E

### 关联文档

- 设计稿 [`docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md)
- 实施计划 [`docs/process/STAGE-14A-FX-DATA-implementation-plan.md`](./STAGE-14A-FX-DATA-implementation-plan.md)
- R7 验证报告 [`docs/test/STAGE-14A-FX-DATA-R7-verification-report.md`](../test/STAGE-14A-FX-DATA-R7-verification-report.md)（待 Task 13 生成）

---

## §15B. STAGE-14B trading-core 货币转换 + 账本三列留痕 (2026-05-29 收口)

### 范围

trading-core 把成交/结算金额从原币（quote currency）换算到账户币（account currency），并在账本/持仓留痕：

- DB（V28 / V29）：`t_ledger` 加 `original_amount` / `original_currency` / `fx_rate_at_settlement` 三列；`t_position` 加 `entry_fx_rate`（开仓 fx 快照，仅审计、不参与强平价）。老数据回填 fx=1、original=amount、currency=COALESCE(account.currency,'USDT')
- 契约：`falconx-market-contract.SymbolSpec` 加 `baseCurrency` / `quoteCurrency`（owner market-service 从 `t_symbol` 填充；trading-core 消费）；旧 Redis 快照无此两字段时反序列化为 null，向后兼容
- trading 侧 `FxRateService`：RPC bootstrap + `falconx.market.fx.rate.update` Kafka 增量刷新（group `falconx-trading-fx-rate`）+ USD pivot 交叉；算法层 FX 真源统一为 `FxRateService`
- 算法层：`MarginCalculator` → `MarginResult{inQuote,inAccount,fxRate}`；`TradingPricingSupport` → `PnlResult{inQuote,inAccount,fxRate,quoteCurrency}`；margin/fee 各携自身查询时刻 fxRate
- 落账三列口径：`amount` = 账户币；`original_amount` = 原币；`original_currency` = quoteCurrency；`fx_rate_at_settlement` = 当次 fx(QC→AC)；每条账自洽 `original_amount × fx_rate = amount`（负净值保护封顶为例外）
- FX 不可用：开仓拒单（30073 FX_RATE_UNAVAILABLE）；平仓/强平/swap 被动结算降级不阻断（fx=1 退化、original_currency 记真实 quoteCurrency、warn）

### 测试

- Task 3 `FxRateService` 17 UT；Task 4 `CurrencyConverter` UT；Task 6 `MarginCalculator` 5+ UT；Task 7 `TradingPricingSupport` PnL UT；Task 8 Fee/Swap 6+ UT；Task 9 开仓/平仓/强平落账三列 UT
- Task 10 跨服务 + EURAUD 端到端 15 IT（开/平/强平/Fee/Swap 三列真值 + entry_fx_rate + FX 缺失拒单 + 同币种回归 + PERF）

### Commits（按时序，节选）

| SHA | 内容 |
| --- | --- |
| `d47a7903` | docs(plan) STAGE-14B 实施计划 |
| `178924c1` / `a0c50b9a` | Task 1/2 V28 t_ledger 三列 + V29 t_position.entry_fx_rate |
| `278f263a` | Task 3 trading 侧 FxRateService + 17 UT |
| `63370161` / `10575df6` | Task 4 CurrencyConverter + UT |
| `a734b128` / `6f97678e` | Task 5 SymbolSpec 加币种字段 + 三列串入写账链路 |
| `be55aad0` / `0c02f74f` | Task 6 MarginCalculator 接 FX + MarginResult |
| `835739fa`…`cee527fb` | Task 7 PnlResult 货币感知 PnL |
| `68532974` / `c63fca56` | Task 8 Fee/Swap 货币感知 |
| `8527862b`…`83b696ab` | Task 9a/b/c 开仓/平仓/强平/挂单触发落账三列真值 |
| `1031a9ce` | root fix 删除 V28/V29 误写的 `USE falconx_trading`（跨库污染 + IT 永久失败） |
| `de41f9ff` | Task 10 跨服务 + EURAUD 端到端 15 IT |

### 已知不阻断项

- WebSocket `position.update` B 阶段维持旧字段名/结构（`unrealizedPnl` 仍为 quote 原币、`realizedPnl` 改为账户币的值口径过渡）；完整字段切换（`unrealizedPnlInQuote/inAccount` 等）留 STAGE-14E，详见 [`FalconX 统一接口文档`](../api/FalconX统一接口文档.md) 与 master §7.5
- MarginLevel + Tier / CROSS-ISOLATED / 三端 UI 留 STAGE-14C-E
- admin FX rate 监控 page（console-frontend）留 STAGE-14E

### 关联文档

- 设计稿 [`docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md)
- 实施计划 [`docs/process/STAGE-14B-CURRENCY-CONVERTER-implementation-plan.md`](./STAGE-14B-CURRENCY-CONVERTER-implementation-plan.md)
- 数据库设计 [`docs/database/falconx一期数据库设计.md`](../database/falconx一期数据库设计.md) §4.3
- 事务与幂等规范 [`docs/architecture/事务与幂等规范.md`](../architecture/事务与幂等规范.md)「写账三列口径与自洽性」

---

## §15C. STAGE-14C1 杠杆/MM 分级 + MarginLevel 三态 + StopOut 强平 (2026-05-29 收口)

### 范围（后端核心，ISOLATED）

- DB（V30 / V31 / V32）：
  - `t_symbol_leverage_tier`（V30）杠杆/MM 双重分级表，UNIQUE(symbol,group_code,tier_no) + INDEX(symbol,group_code,notional_lower) + CHECK(max_leverage×mm_rate≤1.0)；build-time 静态 seed（`tools/tier-seed/generate_tier_seed.py` 读 market `t_symbol` status=1 的 1572 symbol，按 master §5.2 映射 §5.1 T1–T10，stock/etf→T10，default 组共 6311 行）
  - `t_risk_config`（V31）加 `stop_out_level DEFAULT 0.30` / `margin_call_level DEFAULT 1.00`（平台全局行）
  - `t_fx_pause_behavior`（V31）新表（category PK + allow_open/close/liquidation），seed 8 类目（forex/metal 默认停开仓+停被动强平，其余允许）
  - `t_position`（V32）加 `mm_rate_at_open DEFAULT 0.005` / `tier_no_at_open DEFAULT 1`（开仓冻结，强平价/MM 用；老数据回填 0.005/1）
  - master §5.1 T9 tier3 勘误 50x/2.5%→50x/2.0%（违反 CHECK，已回写 master）
- 开仓：`LeverageTierResolver` 按 notional（AC）解析 tier（`[lower, upper)`，30s 缓存）；`leverage>tier.maxLeverage`→拒 30070；tier 缺失→拒 30072；强平价 mmRate 用 tier 值（替换硬码 0.005）并冻结到 `t_position.mm_rate_at_open`
- MarginLevel 三态（master §6.2）：HEALTHY(>marginCall=100%) / MARGIN_CALL(stopOut<ML≤marginCall，发 MARGIN_CALL_TRIGGERED + 5min 节流，不强平) / STOP_OUT(ML≤stopOut=30%，强平 + STOP_OUT_TRIGGERED)；阈值读 t_risk_config 平台行兜底默认
- ISOLATED 单仓：`Equity_i=margin_i(AC)+uPnL_i(AC)`，`MM_i=notional_i(AC)×mmRateAtOpen`（MM 用开仓冻结 fx，实时 MM 留 D），`MarginLevel_i=Equity_i/MM_i×100%`；账户级 `Equity=balance+frozen+ΣuPnL`
- `QuoteDrivenEngine` 每 tick 重算单仓 MarginLevel，**双触发**：liqPrice 命中 或 ML≤stopOut，任一即走 `closePositionByTrigger`（FOR UPDATE 防重复）；账户状态 1s 内存缓存判定、写经 FOR UPDATE 强一致

### 测试

- Task 7 `AccountEquityCalculator` 5 UT；Task 8 `MarginLevelMonitor` 三态 UT；Task 9 `QuoteDrivenEngine` 双触发 + STOP_OUT 通知 UT/IT
- Task 11 tier/MarginLevel/StopOut 16 IT + PERF（10 模板边界 / 200x 拒单 / StopOut 整链 <500ms / CHECK / tier seed 抽查）+ 修既有强平 IT 的 tier 夹具

### Commits（按时序，节选）

| SHA | 内容 |
| --- | --- |
| Task 1/2/3（V30/V31/V32） | tier 表 + risk_config 阈值 + fx_pause + position 冻结列 |
| `dd6271db` | Task 7 AccountEquityCalculator ISOLATED Equity/MarginLevel + 5 UT |
| `58e44a4e` | Task 8 MarginLevelMonitor 三态 + MarginCall 5min 节流 + 阈值读 t_risk_config |
| `238602c4` / `281afc7b` | Task 9 QuoteDrivenEngine MarginLevel 实时重算 + 双触发强平 + STOP_OUT 通知真值 |
| `b17a9744` | Task 11 16 IT + PERF + 修既有强平 IT tier 夹具（BASE） |

### 范围边界（划归 C2/D，C1 不实现）

- CROSS 账户级保证金共担与强平判据 → STAGE-14D
- console tier CRUD UI → STAGE-14C2
- `falconx.trading.tier.changed` Kafka 增量（缓存失效）→ STAGE-14C2（C1 用 30s 缓存，无事件驱动）
- 实时 MM（master §3.2 D2 全实时精化）→ STAGE-14D（C1 MM 用开仓冻结 fx）
- FX_PAUSED 按类目行为落地（Task 10，`t_fx_pause_behavior` 已建表 seed，但 category 来源缺失）→ C2/D，C1 未接入运行时
- 错误码 `30071 INSUFFICIENT_MARGIN_FOR_OPEN` / `30087 GLOBAL_PAUSE_ACTIVE`（master §7.3 已规划）C1 未接入 controller 映射，保证金不足现走 40002/40001

### 关联文档

- 设计稿 [`docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §5.1/§5.2/§6.2/§6.3
- 实施计划 [`docs/process/STAGE-14C1-MARGIN-LEVEL-TIER-implementation-plan.md`](./STAGE-14C1-MARGIN-LEVEL-TIER-implementation-plan.md)
- 数据库设计 [`docs/database/falconx一期数据库设计.md`](../database/falconx一期数据库设计.md) §4.3（V30/V31/V32）
- 状态机规范 [`docs/domain/状态机规范.md`](../domain/状态机规范.md) §6.2/§6.3
- 事务与幂等规范 [`docs/architecture/事务与幂等规范.md`](../architecture/事务与幂等规范.md) §6.11

---

## §15D. STAGE-14C2 console tier 配置三端 UI + FX_PAUSED 按类目行为 (2026-05-29 收口)

### 范围（三端，补齐 master §8.3 C 阶段「admin 改 tier 30s 生效」「tier CRUD UI」）

- market（Task 1）：`SymbolSpec` 加 `category` 字段，market owner `RedisMarketSymbolSpecRepository.toSpec` 从 owner 填充，trading 消费就绪；向后兼容旧快照 null。无新 migration（category 落在既有 `t_symbol`）。
- trading（Task 2-6）：错误码 90930-90932 ADMIN_TIER_* + 30087 GLOBAL_PAUSE_ACTIVE；`SymbolLeverageTier` CRUD 写扩展（insert/update/软删 enabled=0/分页/countBy/selectById）；tier CRUD internal RPC `/internal/v1/trading/console/tier`（区间重叠 90932 / CHECK 90931 / notFound 90930 校验 + 写后 `LeverageTierResolver.invalidate`）；`FxPauseBehavior` 读模型/缓存；FX_PAUSED 按 `t_fx_pause_behavior` 类目控制开仓(30087)/被动强平 + 降级（开仓缺信息保守全拒 / 强平缺信息继续）。
- console（Task 7-8）：V12 seed `tier:view`/`tier:edit` 权限 + 角色关联 + 「杠杆档位配置」菜单；`AdminTierController` 透传 `/admin/trading/tiers` GET/POST/PUT/DELETE + RBAC + 审计 + 错误翻译 90930-90932。
- console-frontend（Task 9）：tier 配置页（展开行多档位 + CRUD Modal + 高危二次确认/reason + RBAC 按钮 + `maxLev × mmRate` 校验）。

### admin 改 tier 30s 生效证据链（Task 10 闭环）

- `LeverageTierResolver` 写后 invalidate（Task 4 IT：改后 resolve 立即反映）+ `it017`（CRUD→开仓风控生效闭环，同进程真 DB）+ console 透传 IT；30s 惰性 TTL 为多实例兜底。
- 真三端跨服务 HTTP E2E（console→gateway→trading）= WSL 受限手动项，靠上述三段语义拼接。

### 测试

- trading C2 相关 308 tests 全绿（tier CRUD/RPC/FxPause/RiskControl/QuoteDrivenEngine/StopOut IT）；console AdminTier 透传 IT 11；前端 vitest 74（tier 页 10）。三件套 lint 0（改动文件）/ build 退出 0。

### Commits（按时序）

| SHA | 内容 |
| --- | --- |
| `949e7924` | 实施计划 |
| `645fd76c` | Task 1 SymbolSpec 加 category（market 填充 + trading 消费就绪 + 兼容 null） |
| `7471cb22` | Task 2 错误码 90930-90932 + 30087 + console 翻译 |
| `bf81242b` | Task 3 SymbolLeverageTier CRUD 写扩展 |
| `f7ed9641` | Task 4 tier CRUD internal RPC + 重叠/CHECK/notFound 校验 + 缓存 invalidate |
| `c733e5d6` | Task 5 FxPauseBehavior 读模型 + 缓存 |
| `711a198d` | Task 6 FX_PAUSED 按类目控制开仓(30087)/被动强平 + 降级 |
| `3baf6ffc` | Task 7 console V12 tier 权限 + 角色 + 菜单 |
| `a6e97941` | Task 8 console AdminTier 透传 + RBAC + 审计 + 错误翻译 |
| `be284f6a` | Task 9 console-frontend tier 配置页 |
| `e1bd2541` | Task 10 三端测试汇总 + it017 CRUD→开仓生效闭环 IT |

### 部署阻断项（沿 14B/C1 叠加）

- C2 新增 console V12（干净，无 USE），market 无新 migration（category 在 `t_symbol`）。被 14B root bug `USE` 污染过的 `falconx_trading`（及演示库）部署前仍须先 repair V28/V29 再 migrate，详见 [当前开发计划 §1](../setup/当前开发计划.md) + [STAGE-14C2 R7 报告 §0](../test/STAGE-14C2-CONSOLE-TIER-UI-R7-verification-report.md)。

### 范围边界（划归 D，C2 不实现）

- CROSS/ISOLATED 用户级切换 + 切换闸门 + 冷静期 + CROSS 强平排序 + 实时 MM 精化 → STAGE-14D（放开 C1 latent 耦合 `closePositionByTrigger` 二次校验）。
- 三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WS break 字段最终切换 → STAGE-14E。

### 关联文档

- 设计稿 [`docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §6.5/§7.4/§8.3
- R7 报告 [`docs/test/STAGE-14C2-CONSOLE-TIER-UI-R7-verification-report.md`](../test/STAGE-14C2-CONSOLE-TIER-UI-R7-verification-report.md)
- 统一接口文档 [`docs/api/FalconX统一接口文档.md`](../api/FalconX统一接口文档.md) §3.30 / 管理端接口规范 [`docs/api/管理端接口规范.md`](../api/管理端接口规范.md) §18
- 状态机规范 [`docs/domain/状态机规范.md`](../domain/状态机规范.md) §6.5

---

## §15E. STAGE-14D1 用户级 margin mode 切换闸门 + 冷静期 + supplement (2026-06-01 收口)

### 范围（纯 trading-core 后端，master §6.1 + §7.4）

- Task 1（`d7b0527d`）：V33 `t_account` 加 `mode_changed_at` / `mode_cooling_until` 列 + 实体/record/XML 串入 + `updateMarginMode`（`margin_mode` 列 V11 已有，不重复加）。
- Task 2（`603eed42`）：错误码 30080-30088（MODE_HAS_OPEN_POSITIONS/MODE_HAS_ACTIVE_PENDING/MODE_COOLING_PERIOD_ACTIVE/MODE_NO_CHANGE/POSITION_NOT_ISOLATED/SUPPLEMENT_AMOUNT_INVALID/CROSS_MODE_NOT_ENABLED）+ `AccountMarginModeChangedEventPayload` contract。
- Task 3（`afbd8c6a`）：`GET/POST /api/v1/me/margin-mode` 切换端点 + 切换闸门（30083 同模式 → 30088 CROSS gate → 30080 持仓 → 30081 挂单 → 30082 冷静期，事务内 FOR UPDATE）+ 5min 冷静期 + 状态机。
- Task 4（`3a156005`）：切换成功同事务发 Outbox `falconx.trading.account.mode.changed` + V34 ACCOUNT_MODE_CHANGED 站内信模板。
- Task 5（`986b2396`）：supplement-margin 收口 — `/api/v1/me/positions/{id}/supplement-margin`（复用既有 service + 越权校验 40004）+ 30085/30086 错误码对齐。

### 切换闸门 + 冷静期

- 闸门 4 项（OPEN 持仓 / 开仓挂单 / 冷静期 / 同模式）+ D1 临时 CROSS gate（30088，`cross_mode.enabled` 默认 false）。
- 冷静期默认 5min（`falconx.trading.margin-mode.cooling-duration` 可配；admin 配置 UI 留 D3）；到期后允许再切。

### 测试（2026-06-01）

- `MarginModeSwitchApplicationServiceTests` 12 UT（闸门 30080-30083 各拒 + CROSS gating 30088 + 冷静期 + queryMode blockers）+ `MarginModeSwitchKafkaNotificationIntegrationTests` 1 IT（Outbox 计数 + 通知渲染）+ `TradingAccountModeSwitchRepositoryIntegrationTests` 1 IT + `MybatisTradingAccountRepositorySwitchMarginModeTests` 2 + `TradingControllerIntegrationTests` 40（含 supplement `/me/` 成功 + 越权 40004 + 旧端点兼容）+ `TradingPositionMarginApplicationServiceTests` 2 = **58 全绿**。

### 部署阻断项（沿 14B/C1 叠加）

- D1 新增 V33/V34 干净（无 `USE`）。被 14B root bug `USE` 污染过的 `falconx_trading`（及演示库）部署前仍须先 repair V28/V29 再 migrate（V30-V32 + V33 + V34），禁止裸跑 migrate。详见 [STAGE-14D1 R7 报告 §0](../test/STAGE-14D1-MARGIN-MODE-SWITCH-R7-verification-report.md)。

### 范围边界（划归 D2/D3，D1 不实现）

- CROSS 账户级强平排序「浮亏最大优先」+ 实时 MM 精化（master §3.2 D2）+ 放开 C1 latent 耦合 `closePositionByTrigger` 二次价格校验 + 打开 `cross_mode.enabled` + FX_PAUSED 8×3 完整验收 + CROSS 高并发 PERF → STAGE-14D2。
- 升级窗口 `isolated_margin` 回填 / supplement pause gating（30087）/ admin 冷静期配置 UI → D2/D3。
- 三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币聚合 + WS break 字段最终切换 → STAGE-14E。

### 关联文档

- 设计稿 [`STAGE-14-...-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §6.1/§7.2/§7.4/§8.3 D 阶段
- R7 报告 [`STAGE-14D1-MARGIN-MODE-SWITCH-R7-verification-report.md`](../test/STAGE-14D1-MARGIN-MODE-SWITCH-R7-verification-report.md)
- 统一接口文档 §3.31 / 状态机规范 §6.4 / Kafka 事件规范 §12.15

---

## §15F. STAGE-14D2 CROSS 账户级强平 + 实时 MM 精化 (2026-06-01 收口)

### 范围（纯 trading-core 后端，master §6.3 + §3.2 D2）

- Task 1（`0dcf048e`）：`TradingPositionCloseReason` 扩 `CROSS_STOP_OUT` close_reason + `closePositionByTrigger` 对 `CROSS_STOP_OUT` 放开二次价格校验（**放开 C1 latent 耦合**，账户级触发不依赖单仓 liqPrice 命中）+ `settlePositionExit` liquidation 判断扩。
- Task 2（`d7559759`）：CROSS 开仓放开（`cross_mode.enabled` gate→30088）+ CROSS 仓 `liquidation_price=null`（账户级强平）+ IM 冻结同 ISOLATED。
- Task 3（`927b28db`）：实时 MM 精化 —— `maintenanceMargin` 用实时 fx，`mmRate` 冻结（`mm_rate_at_open`），FX 不可用降级 `entry_fx_rate`；ISOLATED 强平 IT 回归。
- Task 4（`4e31fe47`）：`CrossLiquidationOrchestrator` 账户级 ML 触发 + 浮亏最大优先逐仓强平直到恢复 + Redisson user-level 锁（`cross-liq:{userId}` tryLock 不等待）+ `QuoteDrivenEngine` CROSS 接入 + **V35**（`t_liquidation_log.liquidation_price` 改 NULL）。
- Task 5（`202506d3`）：`CROSS_STOP_OUT_TRIGGERED` 账户级通知（仓位清单）+ **V36** 模板 + 逐仓 `POSITION_LIQUIDATED` 去重 + Kafka close_reason=CROSS_STOP_OUT。
- Task 6（`4327545d`）：CROSS 强平 PERF + 实时 MM/cross_mode 全链 IT 汇总 + docs/sql V35 镜像。

### CROSS 账户级强平算法（master §6.3）

- 账户级 ML（实时 MM）跌穿 stopOut（30%）→ 按 `|uPnL(账户币)|` 降序**浮亏最大优先逐仓平**，每平一仓重算账户级 ML，恢复即止步（避免过度强平）。
- user-level Redisson 锁串行化同一用户多 symbol tick 的账户级评估，与单仓 `FOR UPDATE` 维度正交不死锁；只读账户快照评估，逐仓 close 各自 `FOR UPDATE` 落账。
- 逐仓走 `CROSS_STOP_OUT`（同 LIQUIDATION 落账：LIQUIDATED + `biz_type=9` + `t_liquidation_log`，liqPrice=NULL）；逐仓站内信去重，编排器发一条账户级 `CROSS_STOP_OUT_TRIGGERED` 汇总。

### 测试（2026-06-01）

- `CrossLiquidationOrchestratorTests` 6 UT（排序逐仓 / 恢复止步 / ML 健康不平 / null 不平 / 锁失败跳过 / 平 1 仓恢复）+ `CrossLiquidationIntegrationTests` 5 IT（真 DB：CROSS 3 仓浮亏最大优先逐仓平 + close_reason=CROSS_STOP_OUT + biz_type=9 + 账户级通知 + 逐仓去重 + 实时 MM + cross_mode flag gate + PERF）。
- trading 全量非 IT 单测无回归（305+）；ISOLATED 强平 IT 18/18 回归绿（实时 MM 数值一致）。
- PERF：120 用户 CROSS 账户同时跌穿，单账户强平编排 tick P50≈97ms / P99≈177ms（**<500ms 达成**）；吞吐 ≈20 ops/s（WSL 顺序构造限制，未达 master ≥100；标注实测不伪造，1000 规模 + ≥100 ops/s 留正式环境压测）。

### 部署阻断项（沿 14B/C1/C2/D1 叠加）

- D2 新增 V35/V36 干净（无 `USE`）。被 14B root bug `USE` 污染过的 `falconx_trading`（及演示库）部署前仍须先 repair V28/V29 再 migrate（V30-V36），禁止裸跑 migrate。详见 [STAGE-14D2 R7 报告 §0](../test/STAGE-14D2-CROSS-LIQUIDATION-REALTIME-MM-R7-verification-report.md)。

### 范围边界（划归 D3/E，D2 不实现）

- 挂单触发 CROSS 资金校验（master §6.4）未放开（D2 仅放开市价开仓），留后续。
- FX_PAUSED 8 类目×3 开关完整验收 + supplement pause gating（30087）+ admin 冷静期/StopOut 配置 UI + 升级窗口 `isolated_margin` 回填 → STAGE-14D3。
- 三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币聚合 + WS break 字段最终切换 → STAGE-14E。

### cross_mode.enabled 启用步骤

- D2 代码全链就位（CROSS 开仓 + 账户级强平 + 实时 MM），生产默认 `cross_mode.enabled=false`；生产启用 = admin 经 risk-switch 接口开启 `cross_mode.enabled`（运维决策），开启后 CROSS 开仓/切换放行且账户级强平生效。

### 关联文档

- 设计稿 [`STAGE-14-...-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §3.2/§6.3/§8.3 D 阶段
- R7 报告 [`STAGE-14D2-CROSS-LIQUIDATION-REALTIME-MM-R7-verification-report.md`](../test/STAGE-14D2-CROSS-LIQUIDATION-REALTIME-MM-R7-verification-report.md)
- 状态机规范 §6.3 补充/§6.6 / Kafka 事件规范 §12.16 / 事务与幂等规范 §6.4.1 / 统一接口文档 §3.31.6 / 数据库设计 §4.3

---

## §15G. STAGE-14D3a 运营可配后端 + supplement pause gating + FX_PAUSED 8×3 验收 (2026-06-01 收口)

### 范围（纯 trading-core 后端，master §6.5 + §7.4/§7.6 + §8.3 D 阶段可配/验收部分）

- 实施计划（`26f3f4eb`）：`docs/process/STAGE-14D3a-CONFIG-BACKEND-implementation-plan.md`。
- Task1（`a78bc2ac`）：**V37** `t_risk_config` 平台行加 `cooling_period_seconds`（admin 可配 60-604800 默认 300）+ docs/sql 镜像。
- Task2（`1dbf7045` / `bb60b0c3`）：冷静期改从 `t_risk_config` 平台行读（`MarginModeSwitchApplicationService`，缺失回退 properties 默认 300s）+ UT。
- Task3（`0b3e1d9e`）：冷静期 / StopOut·MarginCall 阈值写方法（Mapper + Repository）+ 隔离库写回读 IT。
- Task4（`0c3adb2c`）：配置 internal RPC（`/internal/v1/trading/console/config`：GET `/platform-risk` / PUT `/cooling-period` / PUT `/risk-thresholds`，Bean Validation 60-604800 / 0.05-0.95 / 0.50-2.00 → 99004）+ UT/IT。
- Task5（`7633a361`）：supplement-margin 接 FX_PAUSED 闸门（`allow_open`，category/behavior 缺失保守全拒 30087，插入点在 30085 后、40001 前）+ UT/IT。
- Task6（`73c9be9b`）：`FxPauseBehavior.updateByCategory` + 写后失效快照即时生效 + `findAll` + 隔离库 IT。
- Task7（`7249ecfd`）：FxPauseBehavior internal RPC（GET 全量 8 行 / PUT `/{category}` 1-8 + `X-Admin-User-Id` 落审计列）+ IT。
- Task8（`72483c2c`）：FX_PAUSED 8 类目×3 开关组合完整验收 IT（开仓 / supplement / 被动强平 / 手动平仓 + admin override 可配生效 + 降级）。
- Task3 IT 收口 fix（`7780670c`）：配置写 IT `@AfterEach` 还原 `t_risk_config` 平台行 V31 默认（修 it013 共享库阈值污染回归）。

### 测试（2026-06-01）

- D3a 新增：`MarginModeSwitchApplicationServiceTests` 14 UT（D1 12 + DB 读冷静期 / 回退默认）+ `TradingPlatformConfigApplicationServiceTests` 4 UT + 配置 RPC IT 9（含 fx-pause）+ 配置写 repo IT 2 + `SupplementMarginFxPauseGatingTests` 6 UT/IT + FxPauseBehavior 写 IT 3 + FX_PAUSED 8×3 验收 IT 22 全绿。
- 全量 `mvn -pl falconx-trading-core-service test`：**577 tests / 3 failures**，逐条定性：**it013 = D3a 一度引入的回归，已由 `7780670c` 修复消除**（不计入残留）；残留 3 failures 均非 D3a 引入——#1 `TradingKafkaWalletDepositIntegrationTests`（既有 Kafka flake）、#2 `TradingControllerIntegrationTests.shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`（**D1/D2 遗留 stale IT，应另立修复**，基线 `aefb9f9c` 已失败）、#3 `TradingKafkaMarketEventIntegrationTests.cleanOwnerTables` 死锁（瞬态 flake）。详见 [R7 报告 §4](../test/STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md)。

### 部署阻断项（沿 14B/C1/C2/D1/D2 叠加）

- D3a 新增 V37 干净（无 `USE`，带 DEFAULT 的常规加列，**非「计划未预见的 schema 阻断」**）。被 14B root bug `USE` 污染过的 `falconx_trading`（及演示库）部署前仍须先 repair V28/V29 再 migrate（V30-V37），禁止裸跑 migrate。详见 [STAGE-14D3a R7 报告 §0](../test/STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md)。

### 关键结论 / owner 修正

- **`isolated_margin` 列不加（D3a 评估作废）**：D1/D2 `t_position.margin` + `marginMode` 等价，master §4.2 升级窗口停服 5min 回填演练作废（无需）。
- **fx-pause-behavior owner 修正（master §7.4 设计稿偏差）**：`t_fx_pause_behavior` 物理在 trading-core 库，admin 写路径加在 trading-core internal RPC，**D3b console 透传目标修正为 trading-core（非 market）**。

### 范围边界（划归 D3b/E，D3a 不实现）

- console 三端 UI（3 配置页透传 + RBAC `margin-mode-config:edit` / `risk-threshold:edit` / `fx:pause-behavior:edit` + 审计 + 前端 + console 错误码 90950/90951 + console V13 权限/菜单 seed）→ STAGE-14D3b。
- 三端展示 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币聚合 + WS break 字段最终切换 → STAGE-14E。
- #2 `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross` 为 D1/D2 遗留 stale IT，应另立修复（明确 per-order `marginMode=CROSS` 预期行为）。

### 关联文档

- 设计稿 [`STAGE-14-...-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §6.5/§7.4/§7.6/§8.3 D 阶段
- R7 报告 [`STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md`](../test/STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md)
- 实施计划 [`STAGE-14D3a-CONFIG-BACKEND-implementation-plan.md`](STAGE-14D3a-CONFIG-BACKEND-implementation-plan.md)
- 统一接口文档 §3.31.7/§3.32 / 管理端接口规范 §4.3a / 数据库设计 §4.3 / 状态机规范 §6.5

---

## §15H. STAGE-14D3b console 三端 UI（冷静期 / StopOut·MarginCall 阈值 / FX_PAUSED 8 类目行为）(2026-06-01 收口，STAGE-14D3 整体完成)

### 范围（console-service 后端 + console-frontend 前端，照搬 C2 console 模式，master §8.3 D 阶段 UI 部分）

- 实施计划（`a4438be9`）：3 配置页 console 透传 + RBAC + 审计 + 错误码 90950/90951/90952 + V13 seed + 前端，8 task。
- Task1（`8a65be4b`）：console 错误码 90950 ADMIN_MARGIN_MODE_CONFIG_INVALID / 90951 ADMIN_FX_PAUSE_BEHAVIOR_INVALID / 90952 ADMIN_RISK_THRESHOLD_INVALID + 400 翻译 + 3 个 edit 高危注册。
- Task2（`3b599059` / `ef3616eb`）：冷静期 / StopOut·MarginCall 阈值 console 透传（GET/PUT `/admin/trading/margin-mode-config|risk-thresholds` → trading config/platform-risk + cooling-period/risk-thresholds）+ RBAC + 审计 + 99004→90950/90952 翻译 + IT。
- Task3（`1a4aa3c1` / `5faf89e1`）：FX_PAUSED 行为 console 透传（GET `/admin/trading/fx-pause-behavior` + PUT `/{category}` → trading fx-pause-behavior）+ RBAC + 审计 + 99004→90951；`5faf89e1` 给 3 写请求加 `@NotNull` + `@Valid` 拒 null 字段返 400（防 `Map.of` NPE）。
- Task4（`09c644de`）：console V13 seed 6 权限点（margin-mode-config / risk-threshold / fx:pause-behavior view+edit）+ 角色关联 + 3 菜单（Flyway 实测 Migrating to v13 通过）。
- Task5（`529f4df7`）：前端冷静期配置页（单 Form 60s-7d + RBAC + 高危 reason/acknowledge）+ vitest。
- Task6（`37c77ea4`）：前端 StopOut/MarginCall 阈值配置页（0.05-0.95 / 0.50-2.00 + RBAC + 高危确认）+ vitest。
- Task7（`c6d790b1`）：前端 FX_PAUSED 8 类目行为配置页（8 行 Table + 3 开关 Switch + RBAC + 高危 reason/acknowledge）+ vitest。
- Task8（本 commit）：全量验证 + R7 收口报告 + R8 文档同步 + 计划录入。

### console 端点（透传 trading-core internal RPC §3.32，console 不直写 trading 业务表）

- GET/PUT `/admin/trading/margin-mode-config`（`margin-mode-config:view|edit`）→ trading config/platform-risk + config/cooling-period
- GET/PUT `/admin/trading/risk-thresholds`（`risk-threshold:view|edit`）→ trading config/platform-risk + config/risk-thresholds
- GET `/admin/trading/fx-pause-behavior` + PUT `/{category}`（`fx:pause-behavior:view|edit`）→ trading fx-pause-behavior

### 测试（2026-06-01）

- console 透传 IT 17 全过（`AdminPlatformConfigEndpointIntegrationTests` 10 + `AdminFxPauseBehaviorEndpointIntegrationTests` 7，含 RBAC / 99004→90950/90951/90952 翻译 / reason 必填 / `@NotNull` 拒 null 400），BUILD SUCCESS。
- console-frontend vitest 100（97 通过 + 3 既有 skip），D3b 三新页 23（冷静期 7 / 阈值 8 / FX_PAUSED 8）；三件套 build 退出 0、lint 0 改动文件（18 errors 既有 baseline，不在三新页）。

### 部署阻断项（沿 14B/C1/C2/D1/D2 叠加）

- D3b 仅新增 console V13（干净，无 `USE`，作用于 `falconx_console` 库，Flyway 实测 v13 通过）；trading/market 无新 migration（trading 沿 D3a V37）。被 14B root bug `USE` 污染过的 `falconx_trading`（及演示库）部署前仍须先 repair V28/V29 再 migrate（trading V30-V37 + console V13），禁止裸跑 migrate。详见 [STAGE-14D3b R7 报告 §0](../test/STAGE-14D3b-CONSOLE-UI-R7-verification-report.md)。

### 已知不阻断项 / 边界

- 浏览器 QA WSL 受限（dev server 起在 :5300，未登录态重定向 `/admin/login`，登录态截图为手动项，vitest+build 覆盖页面行为）。
- 真三端 console→gateway→trading E2E = WSL 受限手动项（靠 console 透传 IT + D3a trading 配置 RPC IT 拼接代证）。
- 沿用 D3a #2 stale IT（`shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`，D1/D2 遗留，非本阶段引入，建议另立修复）。
- `Map.of` 透传 NPE 风险已由 `5faf89e1` `@NotNull`/`@Valid` 修复（拒 null 返 400）。

### 关联文档

- 设计稿 [`STAGE-14-...-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §6.5/§7.4/§7.6/§8.3 D 阶段
- R7 报告 [`STAGE-14D3b-CONSOLE-UI-R7-verification-report.md`](../test/STAGE-14D3b-CONSOLE-UI-R7-verification-report.md)
- 统一接口文档 §3.33 / 管理端接口规范 §19 / D3a R7 报告 §0

> **STAGE-14D3 整体完成（D3a 后端 + D3b console UI）；下一步 STAGE-14E 三端展示 UI。**

---

## §15I. STAGE-14E0 stale IT 修 + STAGE-14E1 WebSocket break + 客户端实时 UI (2026-06-02 收口)

### STAGE-14E0（stale IT 修，`e52856c1`）

- 修 D1/D2 遗留 stale IT `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`：`marginMode=CROSS` 下单现返 `30088`（CROSS_MODE_NOT_ENABLED，非旧 40010），改名 + 改断言 + 补 `cross_mode` 开启镜像用例（restore 防污染），消除 D3a R7 报告 §5 #2 残留项。

### STAGE-14E1（WebSocket break + 客户端实时 UI，master §9 E 阶段第一切片）

> **🔴 硬 break，无 legacy 兼容**：`position.update` / `position.pnl` / REST 持仓列表 **删 `unrealizedPnl`**，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`；`account.update` / `account.snapshot` / REST account 加 `equity / marginLevel / marginLevelStatus`，`openPositions` 同步双币。**后端 trading-core + 客户端 falconx-frontend 同切片同步切换，部署须同窗口上线。**

- 实施计划（`c39fc5e3`）：position/account 硬切双币 + marginLevel + mode toggle + MarginLevel 浮窗 + 双币 PnL，8 task。
- Task1（`772d513b`）：`position.update` / `position.pnl` 硬切双币（删 `unrealizedPnl`，加 5 双币字段）+ 工厂 `calculatePositionPnlInAccount` 实时算（复用 D2）+ FX 降级 entryFxRate + UT；REST 持仓列表同步切（`toPositionResponse` 委托 factory）。
- Task2（`ce7ffbd9`）：`account.update` / 快照加 `equity / marginLevel / marginLevelStatus` 实时算（`AccountEquityCalculator` + `MarginLevelMonitor`）+ `openPositions`（`TradingAccountPositionResponse`）硬切双币 + UT；修循环依赖（`@Lazy`）。
- Task3（`7f8e1d47`）：真 WS 连接端到端 IT（position.pnl 双币 + account.update marginLevel）+ 全量回归 586/1-fail（唯一失败 = 既有 Kafka flake，零 E1 新增失败）。
- Task4（`e8c4a817`）：客户端 WS hook + 类型硬切（`useTradingSocket` / `tradingTypes` / `tradingApi` 删 `unrealizedPnl` 加双币 + equity/marginLevel/accountMarginMode）+ Terminal state + vitest + build0。
- Task5（`9b31d3b6`）：`MarginModeToggle` 对接 `/me/margin-mode`（canSwitch/blockers 中文/确认 modal/30080-30088）+ vitest。
- Task6（`4a5333f3`）：`MarginLevelIndicator` 浮窗（三态 `--fx-long`/`--fx-risk`/`--fx-danger` + 百分比 + MARGIN_CALL/STOP_OUT 跃迁 critical toast）+ Dashboard 第 6 卡 + marginMode 展示 + vitest。
- Task7（`a2105bba` + `20d25cbb`）：持仓双币双行展示（账户币 USDT 主 + 报价币副 + 币种标注，`pnlDisplay.ts` 共享助手同币种省略）+ 清理 Task4 遗留 JSDoc/openPositions + 全量 vitest 162 绿 + build0。
- Task8（本 commit）：浏览器 QA + R8 文档 + R7 报告 + 计划录入。

### 测试（2026-06-02）

- 后端：trading UT（`TradingUserRealtimePayloadFactoryTests` 4 + `TradingAccountSnapshotApplicationServiceTests` 3）+ 真 WS 端到端 IT 1 + 全量回归 586 / 1-fail（唯一失败 = 既有 `TradingKafkaWalletDepositIntegrationTests` Kafka flake，零 E1 新增失败）。
- 前端：vitest 全量 162 绿 + lint 0（改动文件）+ build 退出 0。

### 部署阻断项（沿 14B/C1/C2/D1/D2/D3 叠加）+ 硬 break 部署协调

- E1 无新 migration（纯 WS/REST DTO 字段 + 实时算 + 前端）。被 14B root bug `USE` 污染过的 `falconx_trading`（及演示库）部署前仍须先 repair V28/V29 再 migrate（trading V30-V37 + console V13），禁止裸跑 migrate。
- **🔴 硬 break 部署协调**：`trading-core-service` 与 `falconx-frontend` 必须同窗口部署，无 legacy 兼容。先部署后端、旧前端仍在线时，旧前端解析新帧将丢失浮盈亏字段（无 `unrealizedPnl`）。详见 [STAGE-14E1 R7 报告 §0](../test/STAGE-14E1-WS-BREAK-CLIENT-UI-R7-verification-report.md)。

### 已知不阻断项 / refinement

- marginLevel 推送在 fill/close 非 per-tick（浮窗显示最近值，per-tick 实时化 refinement）。
- account 级 FX 降级 equity/marginLevel=null 而 per-position 行降级 entryFxRate→非空（口径不对称，账户级保守、行级优雅降级；建议 `TradingAccountResponse.equity` 加 doc 注释，tech-debt）。
- `MarginLevelMonitor`→Notification→Push→Registry 循环用 `@Lazy` 打破（建议后续事件化解耦 MarginCall 通知，tech-debt）。
- `MarginLevelIndicator` 移动端 tap 开浮窗（button focus on tap，多数移动浏览器 OK）真机 QA 待核。
- `getPositionSummary` 聚合仍单币（B 阶段既有 mixed-currency 已知问题，非 E1 范围）。
- 浏览器 QA WSL 受限（无 chromium 系统包 + 无后端栈/认证态，dev server 起 :5200 serve build 但渲染空白；客户端实时 UI 视觉 QA 为真机/CI 手动项，vitest 162 + build 0 覆盖）。
- 既有 Kafka flake（`TradingKafkaWalletDepositIntegrationTests`）+ 瞬态死锁 flake（非本阶段）。

### 关联文档

- 设计稿 [`STAGE-14-...-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §7.5 / §3.2
- R7 报告 [`STAGE-14E1-WS-BREAK-CLIENT-UI-R7-verification-report.md`](../test/STAGE-14E1-WS-BREAK-CLIENT-UI-R7-verification-report.md)
- [WebSocket 接口规范 §5.4](../api/WebSocket接口规范.md) / [统一接口文档 §3.5 / §3.15 / §3.31.8](../api/FalconX统一接口文档.md)

> **下一步 STAGE-14E2：admin FX rate 监控 page + 多币种聚合（console 端）+ admin 持仓多币聚合视图。**

---

## §15J. STAGE-14E2 管理端 FX 监控 + 多币聚合 + admin WS break (2026-06-02 收口，STAGE-14E 整体完成，STAGE-14 A-E 全阶段收官)

> **🔴 admin WS 硬 break，无 legacy 兼容（同 E1 客户端口径）**：`admin.position.update` **删 `unrealizedPnl`**，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount`；`admin.exposure.update` 补 `quoteCurrency`；`admin.position.summary` `totalUnrealizedPnl` QC→AC 修正。双币算法与客户端共用 `TradingRealtimeDualPnlSupport`（trading-core 单一实现）。**`trading-core-service` 与 `console-frontend` 同切片同步切换，部署须同窗口上线。**

- 实施计划（`c4a6401a`）：admin FX 监控 + 多币聚合 + admin WS break，6 task；FX 用 REST 5s 轮询（`admin.fx.rate.update` 1Hz WS 推送 channel = defer refinement）。
- Task1（`4804f043`）：admin WS 硬切双币（`admin.position.update`）+ `admin.exposure.update` 补 `quoteCurrency` + 提取共享 `TradingRealtimeDualPnlSupport`（client + admin 单一实现）+ `AdminPositionSummary` QC→AC 修正 + UT。
- Task2（`63b48f20`）：exposure internal RPC + console 响应补 `quoteCurrency`（多币聚合数据基础）+ IT。
- Task3（`9a2ee0ea`）：admin FX 监控透传（`/admin/market/fx/rates` → market RPC `/internal/v1/market/fx/rates`，`InternalRpcClient` 按 path 经 gateway 路由）+ 错误码 90940 ADMIN_FX_RATE_NOT_FOUND + V14 `fx:view` 权限/菜单 seed + IT。
- Task4（`afbc7489` + `8c67f8be`）：console-frontend FX rate 监控页（8 FX Table + `eventTimeMillis` 判 stale + REST 5s 轮询 + `fx:view` RBAC + error Alert + sourceSymbol）+ vitest。
- Task5（`ff06bc12`）：exposure 多币种聚合 tab（按 `quoteCurrency` 汇总 `netExposureUsd` USD 等价 + 多/空 + 占比）+ admin WS 双币/quoteCurrency 消费（删旧单币）+ vitest + build0。
- Task6（本 commit）：全量验证 + R8 文档 + R7 报告 + 计划录入。

### 测试（2026-06-02）

- trading-core：UT 6（admin payload 3 `TradingAdminRealtimePushServicePayloadTests` + exposure quoteCurrency 3 `TradingMonitorAdminExposureQuoteCurrencyTests`）随全量回归 **592 tests / 2-fail**（2 失败均为既有 `TradingKafkaWalletDepositIntegrationTests` flake——`shouldNotCreditTwice...` consumer group 异步计数竞态 expected:1 was:4 + `shouldCreditDeposit...` 瞬态 Deadlock；**零 E2 新增失败**；E0 已消除 D3a stale IT，本轮不复现）。
- console IT：**22 全过**（`AdminMarketFxEndpointIntegrationTests` 4 + `AdminTradingExposureQuoteCurrencyPassThroughTests` 1 + 回归 `AdminPlatformConfigEndpointIntegrationTests` 10 + `AdminFxPauseBehaviorEndpointIntegrationTests` 7），BUILD SUCCESS。
- console-frontend：vitest 全量 **115（+3 skip）**，FX 页 7 + 聚合/WS 11；build 退出 0。客户端 `falconx-frontend` build 0（E1 无回归）。

### 部署阻断项（沿 14B/C1/C2/D1/D2/D3/E1 叠加）+ 硬 break 部署协调

- E2 新增 migration 仅 console V14（干净，无 `USE`，作用于 `falconx_console`）；trading/market 无新 migration。被 14B root bug `USE` 污染过的 `falconx_trading`（及演示库）部署前仍须先 repair V28/V29 再 migrate（trading V30-V37 + console V13/V14），禁止裸跑 migrate。E2 不引入新库阻断但不解除既有阻断。
- **🔴 硬 break 部署协调**：admin WS break 与 E1 客户端 break 同属硬切，`trading-core-service` 与 `console-frontend` 必须同窗口部署，无 legacy 兼容。

### 已知不阻断项 / defer refinement

- `admin.fx.rate.update` 1Hz WS 推送 channel = defer refinement（本期 FX 用 REST 5s 轮询）。
- `admin.account.mode.changed` 专用模式变更推送流 = defer（D1 Kafka 已发，additive）。
- marginLevel per-tick 实时化 + `MarginLevelMonitor` 事件化解耦（E1 `@Lazy` tech-debt）= defer refinement。
- `getPositionSummary` / `UserPositionSummaryAggregator` 用户侧多币聚合修正（B 阶段既有 mixed-currency；E2 已修 admin 侧 `AdminPositionSummary`，user 侧未动）= defer。
- 真机浏览器 QA 6 截图（客户端 + admin 桌面 / 移动）+ `MarginLevelIndicator` 移动端 tap = WSL 受限手动项 / 待核。
- `TradingPositionListPage` admin WS 消费侧 merge 无专用 vitest（WS 解析层已测，merge 为 trivial `??`）。
- 既有 Kafka flake（`TradingKafkaWalletDepositIntegrationTests`）+ 瞬态死锁 flake（非本阶段）。

### 关联文档

- 设计稿 [`STAGE-14-...-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §7.5 / §8.3 E 阶段
- R7 报告 [`STAGE-14E2-ADMIN-FX-AGG-R7-verification-report.md`](../test/STAGE-14E2-ADMIN-FX-AGG-R7-verification-report.md)
- [WebSocket 接口规范 §5.5](../api/WebSocket接口规范.md) / [管理端接口规范 §20 / §7.3](../api/管理端接口规范.md) / [统一接口文档 §3.34](../api/FalconX统一接口文档.md) / [Kafka 事件规范 §12.14](../event/Kafka事件规范.md)

> **✅ STAGE-14E（E0 stale IT 修 + E1 客户端 + WS break + E2 管理端 FX 监控 + 多币聚合 + admin WS break）整体完成。**
> **✅✅ STAGE-14（A FX 数据源 / B 货币转换 / C 杠杆 Tier + MarginLevel + StopOut / D CROSS·ISOLATED 切换 + 实时 MM + 运营可配 / E 三端 UI + WS break）A-E 全阶段收官——多币种 + CROSS/ISOLATED 保证金体系代码层完整。**
> **下一步**：剩余 14B 库 schema 漂移修复（部署前）+ 硬 break 同窗口部署协调 + defer refinement 清单；按 [`当前开发计划`](../setup/当前开发计划.md) §1 推进 BBook 一期剩余阶段（避免计划真空）。

---

## §16. 不在 V2 范围内（如需进入需先冻结新范围）

- A-book 对冲执行出口
- CROSS 保证金（推迟）
- 部分平仓
- 邮件 / Telegram 真实发送
- i18n 后端
- 工单系统
- 邀请码 / 资金密码 / 国别黑名单
- 多链多地址生产级钱包治理
- 资金费率（funding rate）
- 证书 / Trust Store / Secret Manager / 部署演练

---

## §17. 完成标准

每个阶段 / 每个子任务都必须满足 [`完成定义`](./完成定义.md) §2 + §2.1 + §3 + **§4.A 三端任务完成条件**。

阶段完成时还必须：

- ✅ 同步 [`当前开发计划`](../setup/当前开发计划.md) 阶段状态
- ✅ 同步 [`统一问题清单`](./统一问题清单.md) 关联问题
- ✅ 同步本文件的阶段状态（在阶段标题后加 "已完成 + 验证证据"）

---

## §18. 执行原则

1. **按阶段顺序推进**（详见 §2 依赖关系）
2. **任务级独立提交**：每个子任务一个 Git commit
3. **遇到契约变更必须先确认**
4. **不得自行扩张范围**
5. **测试优先验证**
6. **本文件是真源**：执行过程中如本文件与其他文档冲突，以本文件为准；同时反向修正其他文档
7. **完成判定一票否决**：任意一项 §1.2 完成判定项未达成，整个 BBook 一期不能宣告"开发完成"
8. **三端硬约束**：每个业务功能必须三端齐全才算完成（除明确豁免）

---

## §19. 关联文档

- [当前开发计划](../setup/当前开发计划.md)（最终真源）
- [完成定义](./完成定义.md)
- [AI 工作模式](./AI工作模式.md)
- [Karpathy 式 AI 编码行为准则](./Karpathy式AI编码行为准则.md)
- [V1 执行路径归档](./archive/BBook一期完成执行路径-V1-2026-05-08.md)
- [统一问题清单](./统一问题清单.md)
- [架构方案](../architecture/falconx一期网关-服务-数据库架构方案.md)
- [REST 接口规范](../api/REST接口规范.md)
- [WebSocket 接口规范](../api/WebSocket接口规范.md)
- [Kafka 事件规范](../event/Kafka事件规范.md)
- [状态机规范](../domain/状态机规范.md)
- [数据库设计](../database/falconx一期数据库设计.md)
- [LP 自建行情源接入契约](../market/LP自建行情源接入契约.md)
- [全栈与 Figma 协作流程](./全栈Figma协作流程.md)
