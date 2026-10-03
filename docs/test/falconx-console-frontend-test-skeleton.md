# falconx-console-frontend 测试骨架（V1，2026-05-08）

> 阶段 1 R6 测试骨架（前端部分）。`falconx-console-frontend` 项目尚未创建，本文件列出 R10 项目落地时必须按此骨架补齐的测试方法 + 断言要点。
>
> 测试框架建议：`vitest` + `@testing-library/react` + `msw`（mock service worker，仅模拟 HTTP 边界，不替代真实业务逻辑）+ `@testing-library/user-event`。前端 E2E 待 R10 决策（Playwright vs Cypress），本文件不预设。
>
> 后端测试见 [`STAGE-1-CONSOLE-test-cases.md`](./STAGE-1-CONSOLE-test-cases.md)。

---

## §1. 概览

| 测试模块 | 数量 | 类型 |
| --- | --- | --- |
| §3 登录表单 | 8 | 组件测试 |
| §4 改密表单 | 6 | 组件测试 |
| §5 Token 单飞刷新 | 4 | hook 单元测试 |
| §6 路由鉴权守卫 | 3 | hook 单元测试 |
| §7 权限渲染 | 7 | hook + 组件测试 |
| §8 侧边栏菜单 | 5 | 组件测试 |
| §9 高风险二次确认 | 5 | 组件测试 |
| §10 Token 存储与安全 | 4 | 单元测试 |
| §11 响应式与动效 | 3 | 组件 / e2e |
| §12 API 拦截器 | 3 | 单元测试 |

合计 **48 个**前端用例。

---

## §2. 测试基础设施约定

R10 项目落地时必须满足：

- **单元测试**：`npm run test`（vitest）
- **覆盖率**：核心 hook（auth / permission / api）≥ 80%
- **mock**：仅 `msw` 拦截 HTTP，不 mock React Query / Zustand 内部
- **测试不依赖真实后端**：所有用例可在前端项目内独立运行
- **测试隔离**：每个测试 `beforeEach` 清空 sessionStorage 与 store

---

## §3. AdminLoginForm（`features/auth/AdminLoginForm.test.tsx`）

### FE-CONSOLE-001 空值提交时显示行内校验错误

- **操作**：直接点击「登录」按钮
- **断言**：用户名 / 密码 Form.Item 显示 required 错误
- **断言**：未发起 HTTP 请求

### FE-CONSOLE-002 用户名 < 3 字符行内错误

- **操作**：输入用户名 `ab`
- **断言**：用户名行内错误「3-32 字符」

### FE-CONSOLE-003 90001 显示行内 Alert 红色

- **mock**：POST /admin/auth/login → `{code:"90001", message:"..."}`
- **断言**：顶部 `<Alert type="error">` 显示「用户名或密码错误」
- **断言**：密码 Input 已清空，用户名保留

### FE-CONSOLE-004 90006 显示 Result 全屏锁定

- **mock**：POST /admin/auth/login → `{code:"90006"}`
- **断言**：`<Result status="error">` 标题「账号已锁定」，无重试按钮

### FE-CONSOLE-005 90008 显示 Result 账号禁用

- **mock**：90008 响应
- **断言**：「账号已禁用」+「请联系超级管理员」副标题

### FE-CONSOLE-006 mustChangePassword=true 跳转 /admin/change-password

- **mock**：登录成功 + `mustChangePassword:true`
- **断言**：navigate 到 `/admin/change-password`

### FE-CONSOLE-007 mustChangePassword=false 跳转 /admin

- **mock**：登录成功 + `mustChangePassword:false`
- **断言**：navigate 到 `/admin`

### FE-CONSOLE-008 token 不出现在 DOM / console / DataLayer

- **mock**：登录成功
- **断言**：
  - [ ] document.body.innerHTML 不含 access/refresh token
  - [ ] console.log spy 未被调用 with token 字符串
  - [ ] window 上无任何挂载 token 的全局变量

---

## §4. ChangePasswordForm（`features/auth/ChangePasswordForm.test.tsx`）

### FE-CONSOLE-009 强密码强度条 5 段渲染

- **操作**：输入新密码 `Abc123!@`（长度不足 12 → 弱）
- **断言**：`<Progress steps={5}>` 仅亮 1-2 段；文案「弱」

### FE-CONSOLE-010 满足策略时强度条满档

- **操作**：输入 `Abc123!@#$XYZ`（含大写/小写/数字/特殊字符 ≥12）
- **断言**：5 段全亮，文案「强」

### FE-CONSOLE-011 提交不符合策略密码时拒绝

- **操作**：输入弱密码后点击「提交修改」
- **断言**：未发起 HTTP；行内错误显示具体不符合点

### FE-CONSOLE-012 确认密码不一致

- **操作**：新密码 vs 确认密码不一致
- **断言**：确认密码行内错误「两次密码不一致」

### FE-CONSOLE-013 强制改密时隐藏取消按钮

- **prop**：`mode="forced"`
- **断言**：`<Alert type="warning">` 显示，「取消」按钮不存在

### FE-CONSOLE-014 改密成功后清空 token + 跳转 /admin/login

- **mock**：成功响应
- **断言**：
  - [ ] sessionStorage `admin_access_token` 已清空
  - [ ] message.success 显示
  - [ ] navigate 到 `/admin/login`

---

## §5. Token 单飞刷新（`lib/api/useAdminAuthRefresh.test.ts`）

### FE-CONSOLE-015 并发 401 仅触发一次 refresh

- **场景**：3 个请求并发收到 401
- **mock**：POST /admin/auth/refresh
- **断言**：refresh 接口仅被调用 1 次
- **断言**：3 个原请求都用新 access 重试 1 次

### FE-CONSOLE-016 refresh 成功后重试原请求

- **断言**：原请求的 Authorization header 是新 access

### FE-CONSOLE-017 refresh 失败清空 token + 跳转登录

- **mock**：refresh 返回 90002
- **断言**：sessionStorage 清空 + navigate `/admin/login`
- **断言**：原 3 个请求全部 reject（不重试）

### FE-CONSOLE-018 refresh token 不写 localStorage

- **断言**：登录后 localStorage.getItem('admin_refresh_token') === null
- **断言**：refresh token 仅在 sessionStorage 或内存

---

## §6. 路由鉴权守卫（`lib/auth/useAuthGuard.test.ts`）

### FE-CONSOLE-019 无 token → 重定向 /admin/login

- **前置**：sessionStorage 空
- **断言**：navigate `/admin/login`

### FE-CONSOLE-020 token 已过期 → 重定向 /admin/login

- **前置**：sessionStorage 含已过期 access（exp < now）
- **断言**：navigate `/admin/login`
- **断言**：sessionStorage 已清空

### FE-CONSOLE-021 token 有效 → 放行

- **前置**：sessionStorage 含有效 access
- **断言**：渲染受保护内容

---

## §7. 权限渲染（`lib/auth/usePermissionGuard.test.ts` + `components/RequiresPermission.test.tsx`）

### FE-CONSOLE-022 SUPER_ADMIN 对任意权限点返回 true

- **前置**：useMePermissionsStore.isSuperAdmin=true
- **断言**：`hasPermission('any:any')` === true

### FE-CONSOLE-023 权限码在 list 中返回 true

- **前置**：permissions=['customer:view']
- **断言**：`hasPermission('customer:view')` === true

### FE-CONSOLE-024 权限码不在 list 中返回 false

- **前置**：permissions=['customer:view']
- **断言**：`hasPermission('customer:freeze')` === false

### FE-CONSOLE-025 路由级 guard 无权限渲染 Result 403

- **场景**：`usePermissionGuard('customer:freeze')` + 用户无该权限
- **断言**：渲染 `<Result status="403">`

### FE-CONSOLE-026 RequiresPermission 组件按权限渲染 children

- **prop**：`code="customer:freeze"` + 用户有该权限
- **断言**：children 渲染

### FE-CONSOLE-027 RequiresPermission 无权限隐藏 children

- **prop**：`code="customer:freeze"` + 用户无该权限
- **断言**：children 不渲染（不是 disabled，是完全不渲染）

### FE-CONSOLE-028 RequiresPermission 对超管恒渲染

- **prop**：任意 code + isSuperAdmin=true
- **断言**：children 渲染

---

## §8. 侧边栏菜单（`features/layout/SiderMenu.test.tsx`）

### FE-CONSOLE-029 从 /admin/me/menus 渲染菜单树

- **mock**：API 返回固定树结构
- **断言**：渲染对应数量 MenuItem

### FE-CONSOLE-030 子节点按 sortOrder 升序

- **mock**：返回 sortOrder=[3, 1, 2]
- **断言**：DOM 顺序按 1, 2, 3

### FE-CONSOLE-031 isVisible=false 节点不渲染

- **mock**：某节点 `isVisible:false`
- **断言**：该节点不在 DOM

### FE-CONSOLE-032 当前路由对应 MenuItem 高亮

- **场景**：当前 location.pathname='/admin/users'
- **断言**：`/admin/users` MenuItem 有 selected class

### FE-CONSOLE-033 折叠状态仅显示图标

- **prop**：`collapsed={true}`
- **断言**：MenuItem 不显示文字 label

---

## §9. 高风险二次确认（`components/HighRiskConfirmModal.test.tsx`）

### FE-CONSOLE-034 触发高风险按钮显示 Modal

- **操作**：点击「冻结账户」按钮
- **断言**：`<Modal>` 渲染，标题前缀「⚠ 高风险操作 - 」

### FE-CONSOLE-035 操作原因 < 10 字符时禁用提交

- **操作**：输入 `xx`
- **断言**：「确认冻结」按钮 disabled

### FE-CONSOLE-036 操作原因 ≥ 10 字符时启用提交

- **操作**：输入 `处理用户违规交易行为`
- **断言**：「确认冻结」按钮可点

### FE-CONSOLE-037 显示前后值对比

- **prop**：`beforeValue={status:"ACTIVE"}` `afterValue={status:"FROZEN"}`
- **断言**：DOM 含「ACTIVE → FROZEN」

### FE-CONSOLE-038 提交时按钮 loading 锁住

- **场景**：提交后等待 API
- **断言**：「确认」按钮 loading=true 且 disabled

---

## §10. Token 存储与安全（`lib/auth/adminTokenStorage.test.ts`）

### FE-CONSOLE-039 access token 写入 sessionStorage

- **操作**：登录成功
- **断言**：sessionStorage.getItem('admin_access_token') 等于 token

### FE-CONSOLE-040 access token 不写入 localStorage

- **断言**：localStorage.getItem('admin_access_token') === null

### FE-CONSOLE-041 logout 清空所有 token

- **操作**：调用 logout
- **断言**：sessionStorage 与 localStorage 都不含 token 键

### FE-CONSOLE-042 token 不出现在 console.log

- **场景**：登录成功 + 任意 API 错误
- **spy**：监听 console.log / console.error / console.warn
- **断言**：没有调用包含 access/refresh token 字符串

---

## §11. 响应式与动效（`features/layout/ResponsiveSider.test.tsx` + `lib/motion/reducedMotion.test.ts`）

### FE-CONSOLE-043 ≥ 1280px 显示完整 sider

- **场景**：viewport 1440x900
- **断言**：sider width = 240px

### FE-CONSOLE-044 < 1280px 折叠 sider

- **场景**：viewport 1100x800
- **断言**：sider width = 80px

### FE-CONSOLE-045 prefers-reduced-motion 下禁用 Modal 动画

- **场景**：matchMedia('(prefers-reduced-motion: reduce)') === true
- **断言**：Modal 进入退出动画时长 = 0 或仅透明度变化

---

## §12. API 拦截器（`lib/api/interceptor.test.ts`）

### FE-CONSOLE-046 不发送 X-Trace-Id

- **场景**：发起任意请求
- **断言**：headers 中不含 `X-Trace-Id`（按 [安全规范](../security/安全规范.md)）

### FE-CONSOLE-047 自动附加 Authorization Bearer

- **前置**：sessionStorage 含 access token
- **断言**：headers `Authorization: Bearer <token>`

### FE-CONSOLE-048 401 触发单飞 refresh

- **mock**：原请求 401
- **断言**：自动调用 useAdminAuthRefresh，原请求重试

---

## §13. 浏览器 QA 清单（R7 验证阶段）

R10 实施完成后由 R7 执行（仅文档不写代码）：

| 项 | 命令 / 检查 |
| --- | --- |
| 单元测试 | `npm run test` 在 `falconx-console-frontend/` |
| Lint | `npm run lint` |
| Build | `npm run build` |
| 桌面 1440 | 手动浏览器，5 个核心页面截图 |
| 桌面 1280 | sider 折叠效果截图 |
| 移动 375 | 仅可用性检查 |
| 控制台 0 错误 | 全程 console clean |
| 网络无 token 泄漏 | DevTools Network 面板检查 |
| 高风险 Modal 必现 | 5 处高风险操作覆盖 |
| Reduced motion | macOS 系统设置开启验证 |

---

## §14. 实施落地（R10 完成后由 R6 二轮填写）

| 测试方法 | 文件路径 | 状态 |
| --- | --- | --- |
| FE-CONSOLE-001 ~ 008 | `src/features/auth/AdminLoginForm.test.tsx` | ⏳ |
| FE-CONSOLE-009 ~ 014 | `src/features/auth/ChangePasswordForm.test.tsx` | ⏳ |
| FE-CONSOLE-015 ~ 018 | `src/lib/api/useAdminAuthRefresh.test.ts` | ⏳ |
| FE-CONSOLE-019 ~ 021 | `src/lib/auth/useAuthGuard.test.ts` | ⏳ |
| FE-CONSOLE-022 ~ 028 | `src/lib/auth/usePermissionGuard.test.ts` + `src/components/RequiresPermission.test.tsx` | ⏳ |
| FE-CONSOLE-029 ~ 033 | `src/features/layout/SiderMenu.test.tsx` | ⏳ |
| FE-CONSOLE-034 ~ 038 | `src/components/HighRiskConfirmModal.test.tsx` | ⏳ |
| FE-CONSOLE-039 ~ 042 | `src/lib/auth/adminTokenStorage.test.ts` | ⏳ |
| FE-CONSOLE-043 ~ 045 | `src/features/layout/ResponsiveSider.test.tsx` + `src/lib/motion/reducedMotion.test.ts` | ⏳ |
| FE-CONSOLE-046 ~ 048 | `src/lib/api/interceptor.test.ts` | ⏳ |

---

## §15. 关联文档

- [STAGE-1-CONSOLE-test-cases.md](./STAGE-1-CONSOLE-test-cases.md)（后端测试用例清单）
- [管理端设计系统](../design/falconx-console-DESIGN.md)
- [管理端 5 页面方案](../design/falconx-console-pages-V1.md)
- [管理端架构](../architecture/管理端架构.md)
- [管理端接口规范](../api/管理端接口规范.md)
- [安全规范](../security/安全规范.md)
- [BBook 一期完成执行路径 §4 阶段 1](../process/BBook一期完成执行路径.md)
- [SKILLS Skill 17](../../SKILLS.md)（前端 QA / 设计复核）
