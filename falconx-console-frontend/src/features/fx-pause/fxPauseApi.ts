import { adminApi } from "../../lib/api/apiClient";
import type { FxPauseBehavior } from "./types";

// STAGE-14D3b Task7：FX_PAUSED 8 类目行为配置接口适配（透传 console-service D3b Task3）。
//   GET /admin/trading/fx-pause-behavior              fx:pause-behavior:view
//   PUT /admin/trading/fx-pause-behavior/{category}   fx:pause-behavior:edit（高危）
// list 返回固定 8 行；update 携带 reason 供管理端审计；category 1-8。
export const fxPauseApi = {
  list: () => adminApi.get<FxPauseBehavior[]>("/admin/trading/fx-pause-behavior"),
  update: (
    category: number,
    body: { allowOpen: boolean; allowClose: boolean; allowLiquidation: boolean; reason: string },
  ) => adminApi.put<void>(`/admin/trading/fx-pause-behavior/${category}`, body),
};
