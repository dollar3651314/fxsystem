import { adminApi } from "../../lib/api/apiClient";
import type { MarginModeConfig } from "./types";

// STAGE-14D3b Task5：保证金模式冷静期配置接口适配（透传 console-service D3b Task2）。
//   GET /admin/trading/margin-mode-config            margin-mode-config:view
//   PUT /admin/trading/margin-mode-config            margin-mode-config:edit（高危）
// reason 随 update body 上送供管理端审计；coolingPeriodSeconds 范围 60-604800 由 trading-core 裁决。
export const marginModeApi = {
  get: () => adminApi.get<MarginModeConfig>("/admin/trading/margin-mode-config"),
  update: (coolingPeriodSeconds: number, reason: string) =>
    adminApi.put<void>("/admin/trading/margin-mode-config", { coolingPeriodSeconds, reason }),
};
