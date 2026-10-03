import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

export type MarginModePreference = "ISOLATED" | "CROSS";
export type LanguagePreference = "zh-CN" | "en-US";

/**
 * 客户端本地偏好：默认杠杆 / 默认 marginMode / 语言。
 * persist 到 localStorage，跨会话保留。后端目前无对应字段，纯前端侧设置。
 */
interface PreferencesState {
  defaultLeverage: number;
  defaultMarginMode: MarginModePreference;
  language: LanguagePreference;
  setDefaultLeverage: (n: number) => void;
  setDefaultMarginMode: (m: MarginModePreference) => void;
  setLanguage: (l: LanguagePreference) => void;
  reset: () => void;
}

const DEFAULTS = {
  defaultLeverage: 10,
  defaultMarginMode: "ISOLATED" as MarginModePreference,
  language: "zh-CN" as LanguagePreference,
};

export const usePreferencesStore = create<PreferencesState>()(
  persist(
    (set) => ({
      ...DEFAULTS,
      setDefaultLeverage: (n) => set({ defaultLeverage: n }),
      setDefaultMarginMode: (m) => set({ defaultMarginMode: m }),
      setLanguage: (l) => set({ language: l }),
      reset: () => set(DEFAULTS),
    }),
    {
      name: "falconx-preferences",
      storage: createJSONStorage(() => localStorage),
      version: 1,
    },
  ),
);
