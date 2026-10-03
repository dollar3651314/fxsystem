import { Languages } from "lucide-react";
import { usePreferencesStore, type LanguagePreference } from "./preferencesStore";

const LABELS: Record<LanguagePreference, string> = {
  "zh-CN": "中文",
  "en-US": "English",
};

/**
 * 全站语言切换器。
 *
 * 用法：AuthGate 顶右 + TerminalTopbar 都嵌入这个组件，让用户在登录前后都能切换。
 * 持久化通过 `usePreferencesStore.language`（zustand persist）跨会话保留。
 *
 * 当前仅"切换 UI 上的偏好"，i18n 字符串切换由后续 i18n 接入再补；
 * 现阶段保证选项可见、可点、状态持久。
 */
export function LanguageSelect() {
  const language = usePreferencesStore((s) => s.language);
  const setLanguage = usePreferencesStore((s) => s.setLanguage);
  return (
    <label className="fx-lang-select" title="语言 / Language">
      <Languages size={14} strokeWidth={1.8} aria-hidden="true" />
      <select
        value={language}
        onChange={(e) => setLanguage(e.target.value as LanguagePreference)}
        aria-label="语言"
      >
        <option value="zh-CN">{LABELS["zh-CN"]}</option>
        <option value="en-US">{LABELS["en-US"]}</option>
      </select>
    </label>
  );
}
