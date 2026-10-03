import { adminApi } from "../../lib/api/apiClient";
import type { RiskThreshold } from "./types";

// STAGE-14D3b Task6：StopOut/MarginCall 阈值配置接口适配（透传 console-service D3b Task2）。
//   GET /admin/trading/risk-thresholds            risk-threshold:view
//   PUT /admin/trading/risk-thresholds            risk-threshold:edit（高危）
// reason 随 update body 上送供管理端审计；阈值用 string 承载保精度，
//   范围 stopOut 0.05-0.95 / marginCall 0.50-2.00 由 trading-core 裁决（越界 console 翻 90952）。
export const riskThresholdApi = {
  get: () => adminApi.get<RiskThreshold>("/admin/trading/risk-thresholds"),
  update: (stopOutLevel: string, marginCallLevel: string, reason: string) =>
    adminApi.put<void>("/admin/trading/risk-thresholds", { stopOutLevel, marginCallLevel, reason }),
};
