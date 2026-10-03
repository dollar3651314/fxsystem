import type { PropsWithChildren } from "react";
import { ConfigProvider, theme as antdTheme } from "antd";
import type { ThemeConfig } from "antd";
import zhCN from "antd/locale/zh_CN";
import { useThemeStore } from "../lib/theme/themeStore";

/**
 * AntD ConfigProvider —— 2026-06-04 支持深 / 浅双主题自由切换，默认浅色。
 * 与 falconx-frontend (客户端) 品牌语言完全对齐，浅色配色镜像客户端「PRO LIGHT」。
 *
 * 关键决策：
 * - 默认语言锁 zhCN（管理端用户都是中文）
 * - 主题来自 useThemeStore（持久化 localStorage `falconx-console-theme`，默认 light）；
 *   切换时本组件自动重渲染，AntD algorithm + token 同步翻转，CSS 变量由 console-tokens.css 接管。
 * - 主色 light=#0891b2 / dark=#54e6ff，与客户端 --fx-cyan 严格一致
 * - 字体：Inter 做 body / 行文，JetBrains Mono 做数据 / fontFamilyCode
 */

const SHARED_TOKEN: ThemeConfig["token"] = {
  fontFamily:
    'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif',
  fontFamilyCode:
    '"JetBrains Mono", ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
  borderRadius: 6,
  sizeUnit: 4,
  sizeStep: 4,
  fontSize: 13,
};

const DARK_TOKEN: ThemeConfig["token"] = {
  ...SHARED_TOKEN,
  colorPrimary: "#54e6ff",
  colorSuccess: "#a8ff5c",
  colorWarning: "#ffb84d",
  colorError: "#ff3d57",
  colorInfo: "#54e6ff",
  colorBgBase: "#050608",
  colorBgContainer: "#0b0e12",
  colorBgElevated: "#11161c",
  colorBgLayout: "#050608",
  colorBorder: "rgba(255, 255, 255, 0.1)",
  colorBorderSecondary: "rgba(255, 255, 255, 0.06)",
  colorText: "#f4f7fb",
  colorTextSecondary: "#9aa4b2",
  colorTextTertiary: "#687383",
  colorTextQuaternary: "#525c6b",
};

const LIGHT_TOKEN: ThemeConfig["token"] = {
  ...SHARED_TOKEN,
  colorPrimary: "#0891b2",
  colorSuccess: "#16a34a",
  colorWarning: "#d97706",
  colorError: "#dc2626",
  colorInfo: "#0891b2",
  colorBgBase: "#f4f7fb",
  colorBgContainer: "#ffffff",
  colorBgElevated: "#ffffff",
  colorBgLayout: "#f4f7fb",
  colorBorder: "rgba(15, 23, 42, 0.12)",
  colorBorderSecondary: "rgba(15, 23, 42, 0.07)",
  colorText: "#0f172a",
  colorTextSecondary: "#475569",
  colorTextTertiary: "#64748b",
  colorTextQuaternary: "#94a3b8",
};

const DARK_COMPONENTS: ThemeConfig["components"] = {
  Layout: {
    headerBg: "#0b0e12",
    headerHeight: 56,
    siderBg: "#070809",
    triggerBg: "#11161c",
    bodyBg: "#050608",
  },
  Menu: {
    darkItemBg: "#070809",
    darkItemSelectedBg: "rgba(84, 230, 255, 0.12)",
    darkItemSelectedColor: "#54e6ff",
    darkItemHoverBg: "rgba(255, 255, 255, 0.04)",
    darkItemColor: "#9aa4b2",
    darkSubMenuItemBg: "#070809",
    iconSize: 16,
  },
  Table: {
    headerBg: "#11161c",
    headerColor: "#9aa4b2",
    headerSplitColor: "rgba(255, 255, 255, 0.08)",
    rowHoverBg: "rgba(84, 230, 255, 0.04)",
    borderColor: "rgba(255, 255, 255, 0.06)",
    cellPaddingBlockMD: 10,
    cellPaddingBlockSM: 6,
    cellFontSize: 13,
  },
  Form: {
    itemMarginBottom: 16,
    labelColor: "#9aa4b2",
  },
  Card: {
    colorBgContainer: "#0b0e12",
    colorBorderSecondary: "rgba(255, 255, 255, 0.08)",
  },
  Drawer: {
    colorBgElevated: "#0b0e12",
  },
  Modal: {
    colorBgElevated: "#11161c",
  },
  Tag: {
    defaultBg: "rgba(255, 255, 255, 0.04)",
    defaultColor: "#9aa4b2",
  },
  Button: {
    primaryColor: "#060709",
  },
  Input: {
    colorBgContainer: "#11161c",
    activeBorderColor: "#54e6ff",
    hoverBorderColor: "#54e6ff",
  },
  Select: {
    colorBgContainer: "#11161c",
  },
  DatePicker: {
    colorBgContainer: "#11161c",
  },
};

const LIGHT_COMPONENTS: ThemeConfig["components"] = {
  Layout: {
    headerBg: "#ffffff",
    headerHeight: 56,
    siderBg: "#ffffff",
    triggerBg: "#eef5f9",
    bodyBg: "#f4f7fb",
  },
  Menu: {
    // theme="light" 走 item* 系列（非 darkItem*）
    itemBg: "#ffffff",
    itemSelectedBg: "rgba(8, 145, 178, 0.10)",
    itemSelectedColor: "#0891b2",
    itemHoverBg: "rgba(15, 23, 42, 0.04)",
    itemColor: "#475569",
    subMenuItemBg: "#ffffff",
    iconSize: 16,
  },
  Table: {
    headerBg: "#eef5f9",
    headerColor: "#475569",
    headerSplitColor: "rgba(15, 23, 42, 0.08)",
    rowHoverBg: "rgba(8, 145, 178, 0.05)",
    borderColor: "rgba(15, 23, 42, 0.07)",
    cellPaddingBlockMD: 10,
    cellPaddingBlockSM: 6,
    cellFontSize: 13,
  },
  Form: {
    itemMarginBottom: 16,
    labelColor: "#475569",
  },
  Card: {
    colorBgContainer: "#ffffff",
    colorBorderSecondary: "rgba(15, 23, 42, 0.08)",
  },
  Drawer: {
    colorBgElevated: "#ffffff",
  },
  Modal: {
    colorBgElevated: "#ffffff",
  },
  Tag: {
    defaultBg: "rgba(15, 23, 42, 0.04)",
    defaultColor: "#475569",
  },
  Button: {
    primaryColor: "#ffffff",
  },
  Input: {
    colorBgContainer: "#ffffff",
    activeBorderColor: "#0891b2",
    hoverBorderColor: "#0891b2",
  },
  Select: {
    colorBgContainer: "#ffffff",
  },
  DatePicker: {
    colorBgContainer: "#ffffff",
  },
};

export function ConsoleAntdProvider({ children }: PropsWithChildren) {
  const theme = useThemeStore((s) => s.theme);
  const isDark = theme === "dark";

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: isDark ? DARK_TOKEN : LIGHT_TOKEN,
        components: isDark ? DARK_COMPONENTS : LIGHT_COMPONENTS,
      }}
    >
      {children}
    </ConfigProvider>
  );
}
