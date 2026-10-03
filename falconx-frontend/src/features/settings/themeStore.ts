import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

export type ThemeMode = "dark" | "light";

/**
 * 全局主题状态。
 *
 * - `dark` (default)：FalconX PRO 当前的暗色 CFD 终端配色。
 * - `light`：2026-05-28 新增浅色「PRO LIGHT」配色（纸质金融报感）。
 *
 * 持久化到 localStorage（key `falconx-theme`），跨会话保留。
 * 实际颜色切换由 `:root[data-theme="..."]` CSS 选择器接管 —— 这里只负责
 * (1) 在 <html> 上设置 `data-theme` 属性；
 * (2) 通知 MarketChart 重新读取 CSS 变量重绘 series 颜色（dispatch CustomEvent）。
 */
interface ThemeState {
  theme: ThemeMode;
  setTheme: (mode: ThemeMode) => void;
  toggle: () => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      theme: "light",
      setTheme: (mode) => {
        if (typeof document !== "undefined") {
          document.documentElement.setAttribute("data-theme", mode);
          // chart / 其它读 css var 的 canvas-side 组件需要主动重绘
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
      name: "falconx-theme",
      storage: createJSONStorage(() => localStorage),
      /* version 2: 默认主题从 dark 改成 light（2026-05-28）；老用户 v1 持久化的 dark 不强迁，
       * 但新装/清缓存用户进站就是 light。 */
      version: 2,
      onRehydrateStorage: () => (state) => {
        // 页面初始加载时把持久化值同步到 DOM。
        // 注意：persist 默认在 hydrate 完成后调用 onRehydrateStorage，此时 document 已就绪。
        if (state && typeof document !== "undefined") {
          document.documentElement.setAttribute("data-theme", state.theme);
        }
      },
    }
  )
);
