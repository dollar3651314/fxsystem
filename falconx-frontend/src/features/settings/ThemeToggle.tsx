import { Moon, Sun } from "lucide-react";
import { useThemeStore } from "./themeStore";

/**
 * 右上角主题切换按钮。
 *
 * - 默认渲染成 `fx-topbar-btn` 风格的图标按钮，与 TerminalTopbar / MobileShell header
 *   现有按钮视觉一致。
 * - 暗色时显示 ☀（"切到亮色"），亮色时显示 🌙（"切到暗色"），符合 macOS / Linear 的图标语义约定。
 * - `variant="compact"` 用于 H5 移动端 header 右上小尺寸槽位（仅图标，无文字）。
 */
interface ThemeToggleProps {
  variant?: "default" | "compact";
}

export function ThemeToggle({ variant = "default" }: ThemeToggleProps) {
  const theme = useThemeStore((s) => s.theme);
  const toggle = useThemeStore((s) => s.toggle);
  const isDark = theme === "dark";
  const label = isDark ? "切换到浅色" : "切换到深色";
  const Icon = isDark ? Sun : Moon;
  if (variant === "compact") {
    return (
      <button
        type="button"
        className="fx-theme-toggle fx-theme-toggle--compact"
        onClick={toggle}
        aria-label={label}
        title={label}
      >
        <Icon size={18} strokeWidth={1.8} aria-hidden="true" />
      </button>
    );
  }
  return (
    <button
      type="button"
      className="fx-topbar-btn fx-theme-toggle"
      onClick={toggle}
      aria-label={label}
      title={label}
    >
      <Icon size={14} strokeWidth={1.8} aria-hidden="true" />
      {isDark ? "浅色" : "深色"}
    </button>
  );
}
