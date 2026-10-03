import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

export type ThemeMode = "dark" | "light";

/**
 * 管理端全局主题状态（与客户端 falconx-frontend/src/features/settings/themeStore.ts 对齐）。
 *
 * - `light` (默认)：2026-06-04 新增浅色 console 配色，与客户端「PRO LIGHT」同一套设计语言。
 * - `dark`：原暗色 trading-console 配色（保留，可自由切回）。
 *
 * 持久化到 localStorage（key `falconx-console-theme`，与客户端 `falconx-theme` 区分，避免两站
 * 偏好互相覆盖）。实际颜色切换由两层接管：
 *   (1) `:root[data-theme="light"]` CSS 选择器覆盖 console-tokens.css 调色板；
 *   (2) ConsoleAntdProvider 读本 store 切换 AntD algorithm + token，驱动全部 AntD 组件。
 * 本 store 只负责在 <html> 上写 `data-theme` 属性并广播 `falconx:theme:changed` 事件。
 */
interface ThemeState {
  theme: ThemeMode;
  setTheme: (mode: ThemeMode) => void;
  toggle: () => void;
}

const STORAGE_KEY = "falconx-console-theme";

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      theme: "light",
      setTheme: (mode) => {
        if (typeof document !== "undefined") {
          document.documentElement.setAttribute("data-theme", mode);
          // 读 css var 的 canvas-side / 第三方组件可监听此事件主动重绘
          window.dispatchEvent(new CustomEvent("falconx:theme:changed", { detail: { mode } }));
        }
        set({ theme: mode });
      },
      toggle: () => {
        const next: ThemeMode = get().theme === "dark" ? "light" : "dark";
        get().setTheme(next);
      },
    }),
    {
      name: STORAGE_KEY,
      storage: createJSONStorage(() => localStorage),
      /* version 1：管理端首版浅色默认。index.html 内联脚本已在首帧前同步 data-theme，
       * 这里 onRehydrateStorage 做二次兜底（持久化值与首帧推断不一致时纠正）。 */
      version: 1,
      onRehydrateStorage: () => (state) => {
        if (state && typeof document !== "undefined") {
          document.documentElement.setAttribute("data-theme", state.theme);
        }
      },
    },
  ),
);
