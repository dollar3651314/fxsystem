# falconx-console-frontend

FalconX 管理后台前端（Vite + React + TypeScript + Ant Design 5）。

## 启动

```bash
npm install
npm run dev   # http://localhost:5300
```

## 验证

```bash
npm run lint
npm run build
npm run test
```

## 阶段 1 范围（已实施）

- **登录页** `/admin/login`：默认超管登录 + 失败码 90001 / 锁定 90006 / 禁用 90008 / IP 拦截 90005
- **改密页** `/admin/change-password`：首次强制改密 + 主动改密；前端密码策略校验（12+ / 大小写 / 数字 / 特殊字符）
- **仪表盘占位** `/admin`：当前管理员 + 角色 + 权限点统计
- **基础设施**：
  - admin token sessionStorage 隔离（按 DESIGN §10 安全约束）
  - access token 单飞 refresh（401 自动刷新 + 失败跳转登录）
  - useAuthGuard 路由级鉴权
  - RequiresPermission 按钮级 RBAC 渲染
  - AntD 5 ConfigProvider FalconX 品牌覆盖

## 后续阶段

- 5 个核心管理端页面（管理员 / 角色 / 菜单 / 权限点字典）—— 见 `docs/design/falconx-console-pages-V1.md`
- 阶段 2 业务管理端 5 模块 —— 待 R2 二轮接口冻结后实施

## 真源文档

- [管理端架构](../docs/architecture/管理端架构.md)
- [管理端接口规范](../docs/api/管理端接口规范.md)
- [管理端设计系统](../docs/design/falconx-console-DESIGN.md)
- [管理端 5 页面方案](../docs/design/falconx-console-pages-V1.md)
- [管理端前端测试骨架](../docs/test/falconx-console-frontend-test-skeleton.md)
