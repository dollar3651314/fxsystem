import { useEffect, useMemo, useState } from "react";
import { Outlet, useNavigate } from "react-router-dom";
import { Avatar, Dropdown, Layout, Menu, Spin, Typography, message } from "antd";
import type { MenuProps } from "antd";
import {
  ApartmentOutlined,
  AreaChartOutlined,
  AlertOutlined,
  AppstoreOutlined,
  AuditOutlined,
  ControlOutlined,
  ReconciliationOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  FundOutlined,
  GlobalOutlined,
  KeyOutlined,
  LineChartOutlined,
  LogoutOutlined,
  MenuOutlined,
  SafetyCertificateOutlined,
  SafetyOutlined,
  SettingOutlined,
  SlidersOutlined,
  SolutionOutlined,
  StockOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { useAuthGuard } from "../../lib/auth/useAuthGuard";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { useThemeStore } from "../../lib/theme/themeStore";
import { adminAuthApi } from "../auth/adminAuthApi";
import type { AdminMeMenuNode } from "../auth/types";
import { FalconMark } from "../../components/brand/FalconMark";
import { ThemeSwitch } from "../settings/ThemeSwitch";

// STAGE-13 fix：DB icon string → Antd Icon component。新增菜单时直接 INSERT 行 + 在这里加一行 map。
// 未匹配的 icon 用 AppstoreOutlined 兜底（不会显示 broken icon）。
const ICON_MAP: Record<string, React.ComponentType> = {
  ApartmentOutlined,
  AreaChartOutlined,
  AlertOutlined,
  AppstoreOutlined,
  AuditOutlined,
  ControlOutlined,
  ReconciliationOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  FundOutlined,
  GlobalOutlined,
  KeyOutlined,
  LineChartOutlined,
  MenuOutlined,
  SafetyCertificateOutlined,
  SafetyOutlined,
  SettingOutlined,
  SlidersOutlined,
  SolutionOutlined,
  StockOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined,
};

function renderIcon(iconName: string | null | undefined): React.ReactNode {
  if (!iconName) return undefined;
  const Component = ICON_MAP[iconName] ?? AppstoreOutlined;
  return <Component />;
}

/**
 * STAGE-13 fix：把 GET /admin/me/menus 返回的菜单树递归转成 Antd Menu items。
 *
 * - 有 path → onClick 跳路由
 * - 有 children 且非空 → 渲染子菜单组（无 onClick）
 * - 无 children 且无 path → 跳过（防御性，DB 异常数据）
 */
function buildMenuItems(
  nodes: AdminMeMenuNode[],
  navigate: (to: string) => void
): NonNullable<MenuProps["items"]> {
  return nodes
    .map((node) => {
      const hasChildren = node.children && node.children.length > 0;
      if (hasChildren) {
        return {
          key: node.code,
          icon: renderIcon(node.icon),
          label: node.name,
          children: buildMenuItems(node.children, navigate),
        };
      }
      if (!node.path) return null;
      return {
        key: node.code,
        icon: renderIcon(node.icon),
        label: node.name,
        onClick: () => navigate(node.path!),
      };
    })
    .filter((x): x is NonNullable<typeof x> => x !== null);
}

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

export function AdminLayout() {
  useAuthGuard();
  const navigate = useNavigate();
  const user = useAdminAuthStore((s) => s.user);
  const setPermissions = useAdminAuthStore((s) => s.setPermissions);
  const clear = useAdminAuthStore((s) => s.clear);
  const themeMode = useThemeStore((s) => s.theme);
  const siderMenuTheme = themeMode === "dark" ? "dark" : "light";
  const [loading, setLoading] = useState(true);
  // STAGE-13 fix：菜单来自 DB，不再硬编码。AdminLayout 启动时拉一次，登录态不变就不重拉。
  const [menus, setMenus] = useState<AdminMeMenuNode[]>([]);
  // 移动端侧边栏改抽屉式：默认收起（collapsedWidth=0 完全隐藏，不再遮挡主内容），
  // 由顶栏汉堡按钮开合，展开时浮层覆盖 + 背景遮罩，点菜单/遮罩即收起。
  const [isMobile, setIsMobile] = useState(false);
  const [collapsed, setCollapsed] = useState(false);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    void (async () => {
      try {
        // 并行拉权限 + 菜单（菜单 API 已按用户角色权限过滤）
        const [permRes, menuRes] = await Promise.all([
          adminAuthApi.mePermissions(),
          adminAuthApi.meMenus(),
        ]);
        if (!cancelled) {
          setPermissions(permRes.permissions, permRes.isSuperAdmin);
          setMenus(menuRes.menus);
        }
      } catch (err) {
        if (!cancelled) {
          message.error(`加载菜单失败：${(err as Error).message ?? "未知错误"}`);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [user, setPermissions]);

  // 菜单 items 缓存：menus / navigate 变化时才重建
  const menuItems = useMemo(() => buildMenuItems(menus, navigate), [menus, navigate]);

  const handleLogout = async () => {
    try {
      await adminAuthApi.logout();
    } catch {
      // logout 接口失败也强制清空本地 session
    }
    clear();
    navigate("/admin/login", { replace: true });
  };

  const userMenu: MenuProps["items"] = [
    {
      key: "change-password",
      icon: <KeyOutlined />,
      label: "修改密码",
      onClick: () => navigate("/admin/change-password"),
    },
    { type: "divider" },
    {
      key: "logout",
      icon: <LogoutOutlined />,
      label: "登出",
      onClick: () => void handleLogout(),
    },
  ];

  if (!user || loading) {
    return (
      <Layout style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
        <Spin size="large" />
      </Layout>
    );
  }

  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Sider
        theme={siderMenuTheme}
        width={240}
        breakpoint="lg"
        collapsedWidth={isMobile ? 0 : 80}
        collapsed={collapsed}
        trigger={null}
        onBreakpoint={(broken) => {
          setIsMobile(broken);
          // 进入移动端默认收起隐藏；回到桌面端展开
          setCollapsed(broken);
        }}
        style={
          isMobile
            ? { position: "fixed", height: "100vh", top: 0, left: 0, zIndex: 1000 }
            : undefined
        }
      >
        <div
          style={{
            height: 56,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            gap: 10,
            borderBottom: "1px solid var(--fx-hairline)",
          }}
        >
          <FalconMark style={{ width: 28, height: 17, color: "var(--fx-cyan)" }} />
          <span
            style={{
              fontFamily: "var(--fx-mono-stack)",
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: "0.22em",
              color: "var(--fx-text)",
              textTransform: "uppercase",
            }}
          >
            FalconX · Admin
          </span>
        </div>
        <Menu
          theme={siderMenuTheme}
          mode="inline"
          defaultSelectedKeys={["dashboard"]}
          items={menuItems}
          onClick={() => {
            // 移动端点菜单后收起抽屉，避免浮层挡住跳转后的页面
            if (isMobile) setCollapsed(true);
          }}
        />
      </Sider>
      {/* 移动端抽屉展开时的背景遮罩：点击收起 */}
      {isMobile && !collapsed && (
        <div
          onClick={() => setCollapsed(true)}
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(0, 0, 0, 0.45)",
            zIndex: 999,
          }}
        />
      )}
      <Layout>
        <Header
          style={{
            background: "var(--fx-surface-1)",
            padding: "0 24px",
            display: "flex",
            justifyContent: "flex-end",
            alignItems: "center",
            gap: 16,
            borderBottom: "1px solid var(--fx-hairline)",
          }}
        >
          {isMobile && (
            <button
              type="button"
              aria-label={collapsed ? "展开导航" : "收起导航"}
              onClick={() => setCollapsed((v) => !v)}
              style={{
                marginRight: "auto",
                display: "inline-flex",
                alignItems: "center",
                justifyContent: "center",
                width: 36,
                height: 36,
                border: "1px solid var(--fx-hairline)",
                borderRadius: 8,
                background: "transparent",
                color: "var(--fx-text)",
                fontSize: 18,
                cursor: "pointer",
              }}
            >
              <MenuOutlined />
            </button>
          )}
          <ThemeSwitch />
          <Dropdown menu={{ items: userMenu }} placement="bottomRight">
            <span style={{ cursor: "pointer", display: "inline-flex", alignItems: "center", gap: 8 }}>
              <Avatar icon={<UserOutlined />} />
              <Text style={{ color: "var(--fx-text)" }}>{user.realName ?? user.username}</Text>
              <Text type="secondary" style={{ fontSize: 11, fontFamily: "var(--fx-mono-stack)", letterSpacing: "0.08em", textTransform: "uppercase" }}>
                {user.roles.join(" · ")}
              </Text>
            </span>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: "var(--fx-surface-1)", borderRadius: 8, border: "1px solid var(--fx-hairline)", minHeight: "calc(100vh - 104px)" }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
