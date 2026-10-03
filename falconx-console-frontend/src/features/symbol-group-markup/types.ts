/**
 * STAGE-12-GROUP-MARKUP: 用户组加点前端类型定义。
 *
 * 后端契约见 docs/api/管理端接口规范.md §6.14
 *  - GET   /admin/symbols/group-markup           列表（支持 groupCode/symbolLike/enabled 筛选）
 *  - GET   /admin/symbols/group-markup/grouped   按组聚合视图
 *  - GET   /admin/symbols/group-markup/{group}/{symbol}  单条详情
 *  - POST  /admin/symbols/group-markup           新建
 *  - PUT   /admin/symbols/group-markup/{group}/{symbol}  编辑
 *  - PUT   /admin/symbols/group-markup/{group}/bulk      批量 upsert
 *  - DELETE/admin/symbols/group-markup/{group}/{symbol}  删除（带 reason body）
 */

export interface GroupMarkupItem {
  groupCode: string;
  platformSymbol: string;
  /** bid 加点，可正可负，(-1000000, 1000000) */
  bidExtra: string | number;
  /** ask 加点，可正可负，(-1000000, 1000000) */
  askExtra: string | number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface GroupMarkupListQuery {
  groupCode?: string;
  symbolLike?: string;
  enabled?: boolean;
  page?: number;
  size?: number;
}

export interface GroupMarkupListResponse {
  items: GroupMarkupItem[];
  total: number;
  page: number;
  size: number;
}

export interface GroupMarkupGroupedItem {
  groupCode: string;
  configuredCount: number;
  items: GroupMarkupItem[];
}

export interface GroupMarkupGroupedListResponse {
  groups: GroupMarkupGroupedItem[];
}

export interface GroupMarkupCreateRequest {
  groupCode: string;
  platformSymbol: string;
  bidExtra: number | string;
  askExtra: number | string;
  enabled: boolean;
  reason: string;
}

export interface GroupMarkupUpdateRequest {
  bidExtra: number | string;
  askExtra: number | string;
  enabled: boolean;
  reason: string;
}

export interface GroupMarkupBulkUpsertRequest {
  items: Array<{
    platformSymbol: string;
    bidExtra: number | string;
    askExtra: number | string;
    enabled: boolean;
  }>;
  reason: string;
}

export interface GroupMarkupDeleteRequest {
  reason: string;
}
