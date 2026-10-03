import { adminApi } from "../../lib/api/apiClient";
import type {
  GroupMarkupBulkUpsertRequest,
  GroupMarkupCreateRequest,
  GroupMarkupDeleteRequest,
  GroupMarkupGroupedListResponse,
  GroupMarkupItem,
  GroupMarkupListQuery,
  GroupMarkupListResponse,
  GroupMarkupUpdateRequest,
} from "./types";

function buildListQuery(q: GroupMarkupListQuery): string {
  const params = new URLSearchParams();
  if (q.groupCode) params.set("groupCode", q.groupCode);
  if (q.symbolLike) params.set("symbolLike", q.symbolLike);
  if (q.enabled !== undefined) params.set("enabled", q.enabled ? "1" : "0");
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const groupMarkupApi = {
  list: (q: GroupMarkupListQuery) =>
    adminApi.get<GroupMarkupListResponse>(`/admin/symbols/group-markup?${buildListQuery(q)}`),

  listGrouped: () =>
    adminApi.get<GroupMarkupGroupedListResponse>(`/admin/symbols/group-markup/grouped`),

  detail: (groupCode: string, platformSymbol: string) =>
    adminApi.get<GroupMarkupItem>(
      `/admin/symbols/group-markup/${encodeURIComponent(groupCode)}/${encodeURIComponent(platformSymbol)}`,
    ),

  create: (body: GroupMarkupCreateRequest) =>
    adminApi.post<GroupMarkupItem>(`/admin/symbols/group-markup`, body),

  update: (groupCode: string, platformSymbol: string, body: GroupMarkupUpdateRequest) =>
    adminApi.put<GroupMarkupItem>(
      `/admin/symbols/group-markup/${encodeURIComponent(groupCode)}/${encodeURIComponent(platformSymbol)}`,
      body,
    ),

  bulkUpsert: (groupCode: string, body: GroupMarkupBulkUpsertRequest) =>
    adminApi.put<GroupMarkupItem[]>(
      `/admin/symbols/group-markup/${encodeURIComponent(groupCode)}/bulk`,
      body,
    ),

  delete: (groupCode: string, platformSymbol: string, body: GroupMarkupDeleteRequest) =>
    adminApi.delete<void>(
      `/admin/symbols/group-markup/${encodeURIComponent(groupCode)}/${encodeURIComponent(platformSymbol)}`,
      body,
    ),
};
