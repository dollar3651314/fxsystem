import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

/** 主图叠加指标 key */
export type MainIndicator = "MA" | "EMA" | "BOLL" | "SAR" | "ZIG";
/** 副图指标 key（每个副图占一个 pane） */
export type SubIndicator = "VOL" | "MACD" | "RSI" | "KDJ" | "WR";

export const MAIN_INDICATORS: { key: MainIndicator; label: string; desc: string }[] = [
  { key: "MA",   label: "MA",   desc: "5/10/30 周期 SMA" },
  { key: "EMA",  label: "EMA",  desc: "12/26 周期指数 MA" },
  { key: "BOLL", label: "BOLL", desc: "布林带 20 / 2σ" },
  { key: "SAR",  label: "SAR",  desc: "抛物线 0.02/0.2" },
  { key: "ZIG",  label: "ZIG",  desc: "ZigZag 5% 阈值" },
];

export const SUB_INDICATORS: { key: SubIndicator; label: string; desc: string }[] = [
  { key: "VOL",  label: "VOL",  desc: "tick 活动量代理" },
  { key: "MACD", label: "MACD", desc: "12-26-9 DIF/DEA/Hist" },
  { key: "RSI",  label: "RSI",  desc: "14 周期相对强弱" },
  { key: "KDJ",  label: "KDJ",  desc: "9-3-3 随机指标" },
  { key: "WR",   label: "WR",   desc: "Williams %R 14" },
];

interface IndicatorsState {
  /** 当前激活的主图叠加 */
  main: MainIndicator[];
  /** 当前激活的副图（每个一个 pane） */
  sub: SubIndicator[];
  toggleMain: (k: MainIndicator) => void;
  toggleSub: (k: SubIndicator) => void;
  /** 一次性设置（用于"全部开/关"快捷） */
  setAll: (main: MainIndicator[], sub: SubIndicator[]) => void;
}

export const useIndicatorsStore = create<IndicatorsState>()(
  persist(
    (set) => ({
      main: ["MA"],   // 默认开 MA 让用户进站就能看到指标在工作
      sub: ["VOL"],   // 默认开 VOL
      toggleMain: (k) =>
        set((s) => ({
          main: s.main.includes(k) ? s.main.filter((x) => x !== k) : [...s.main, k],
        })),
      toggleSub: (k) =>
        set((s) => ({
          sub: s.sub.includes(k) ? s.sub.filter((x) => x !== k) : [...s.sub, k],
        })),
      setAll: (main, sub) => set({ main, sub }),
    }),
    {
      name: "falconx-chart-indicators",
      storage: createJSONStorage(() => localStorage),
      version: 1,
    },
  ),
);
