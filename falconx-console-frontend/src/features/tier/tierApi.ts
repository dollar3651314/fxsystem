import { adminApi } from "../../lib/api/apiClient";
import type {
  TierCreateRequest,
  TierItem,
  TierListQuery,
  TierListResponse,
  TierUpdateRequest,
} from "./types";

// STAGE-14C2 Task 9 R10：tier 配置接口适配（透传 console-service /admin/trading/tiers）。
// 路径对齐 Task 8 AdminTierController（@RequestMapping /admin + /trading/tiers）：
//   GET    /admin/trading/tiers?symbol=&groupCode=&page=&size=   tier:view
//   POST   /admin/trading/tiers                                   tier:edit（高危）
//   PUT    /admin/trading/tiers/{id}                              tier:edit（高危）
//   DELETE /admin/trading/tiers/{id}                              tier:edit（高危，软删）
// reason 为高危审计原因，随 create/update body 上送；delete 请求体携带 reason 供管理端审计意图记录。

function buildListQuery(q: TierListQuery): string {
  const params = new URLSearchParams();
  if (q.symbol) params.set("symbol", q.symbol);
  if (q.groupCode) params.set("groupCode", q.groupCode);
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const tierApi = {
  listTiers: (q: TierListQuery) =>
    adminApi.get<TierListResponse>(`/admin/trading/tiers?${buildListQuery(q)}`),
  createTier: (req: TierCreateRequest) =>
    adminApi.post<TierItem>(`/admin/trading/tiers`, req),
  updateTier: (id: string, req: TierUpdateRequest) =>
    adminApi.put<TierItem>(`/admin/trading/tiers/${id}`, req),
  deleteTier: (id: string, reason: string) =>
    adminApi.delete<void>(`/admin/trading/tiers/${id}`, { reason }),
};
