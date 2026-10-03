import { adminApi } from "../../lib/api/apiClient";
import type { FxRate } from "./types";

// STAGE-14E2 Task4：console FX rate 监控接口适配（透传 console-service E2 Task3）。
//   GET /admin/market/fx/rates   需 fx:view 权限（console 透传 market FX RPC）。
// 返回 FX rate 列表（固定 8 条左右）；后端无 stale 字段，stale 由页面依 eventTimeMillis 判定。
export const fxRateApi = {
  list: () => adminApi.get<FxRate[]>("/admin/market/fx/rates"),
};
