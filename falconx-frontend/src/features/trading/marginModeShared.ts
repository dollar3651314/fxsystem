/**
 * 账户级 margin mode 的共享常量（query key + 文案）。
 *
 * <p>独立成模块（而非从 MarginModeToggle.tsx 导出）以满足 react-refresh/only-export-components：
 * 组件文件只导出组件，常量/工具放这里，供 MarginModeToggle / SettingsPage / OrderTicket 复用，
 * 共享 react-query 缓存。
 */

/** GET /api/v1/me/margin-mode 的 react-query key，多处复用以共享缓存。 */
export const MARGIN_MODE_QUERY_KEY = ["trading", "margin-mode"] as const;

/** CROSS（全仓）平台未开放时的统一提示文案。 */
export const CROSS_DISABLED_HINT = "全仓暂未开放";
