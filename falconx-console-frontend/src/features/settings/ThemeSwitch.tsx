import { Segmented } from "antd";
import { BulbOutlined, BulbFilled } from "@ant-design/icons";
import { useThemeStore, type ThemeMode } from "../../lib/theme/themeStore";

/**
 * 深 / 浅主题切换器（与客户端 ThemeToggle 同语义）。
 * Segmented 显式展示当前主题，点击即切并持久化到 localStorage。
 * 放在 AdminLayout Header 与登录页角落，全站任意位置可自由切换。
 */
export function ThemeSwitch() {
  const theme = useThemeStore((s) => s.theme);
  const setTheme = useThemeStore((s) => s.setTheme);

  return (
    <Segmented<ThemeMode>
      size="small"
      value={theme}
      onChange={(value) => setTheme(value)}
      aria-label="主题切换"
      options={[
        { label: "浅色", value: "light", icon: <BulbOutlined /> },
        { label: "深色", value: "dark", icon: <BulbFilled /> },
      ]}
    />
  );
}
