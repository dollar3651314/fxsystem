import { adminApi } from "../../lib/api/apiClient";
import type {
  SnowflakeId,
  MarketHolidayItem,
  MarketHolidayListResponse,
  MarketHolidayQuery,
  MarketHolidayUpsertRequest,
  SymbolGroupVisibilityItem,
  SymbolGroupVisibilityBulkUpdateRequest,
  SymbolGroupVisibilityGroupedListResponse,
  SymbolGroupVisibilityGroupedQuery,
  SymbolGroupVisibilityListResponse,
  SymbolGroupVisibilityQuery,
  SymbolGroupVisibilityUpdateRequest,
  SwapRateItem,
  SwapRateResponse,
  SwapRateUpdateRequest,
  SymbolDetail,
  SymbolListItem,
  SymbolListQuery,
  SymbolListResponse,
  SymbolQuoteMappingCreateRequest,
  SymbolQuoteMappingItem,
  SymbolQuoteMappingListResponse,
  SymbolQuoteMappingQuery,
  SymbolQuoteMappingUpdateRequest,
  SymbolSourceCreateRequest,
  SymbolStatusRequest,
  SymbolUpdateRequest,
  SymbolTradingRuleDeleteRequest,
  TradingExceptionUpsertRequest,
  TradingHoursResponse,
  TradingSessionItem,
  TradingSessionUpsertRequest,
} from "./types";

function buildListQuery(q: SymbolListQuery): string {
  const params = new URLSearchParams();
  if (q.category !== undefined) params.set("category", String(q.category));
  if (q.marketCode) params.set("marketCode", q.marketCode);
  if (q.status !== undefined) params.set("status", String(q.status));
  if (q.lpCode) params.set("lpCode", q.lpCode);
  if (q.symbolLike) params.set("symbolLike", q.symbolLike);
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

function buildMappingQuery(q: SymbolQuoteMappingQuery): string {
  const params = new URLSearchParams();
  if (q.platformSymbolLike) params.set("platformSymbolLike", q.platformSymbolLike);
  if (q.sourceLpCode) params.set("sourceLpCode", q.sourceLpCode);
  if (q.sourceSymbolLike) params.set("sourceSymbolLike", q.sourceSymbolLike);
  if (q.enabled !== undefined) params.set("enabled", String(q.enabled));
  if (q.lpSubscribeEnabled !== undefined) params.set("lpSubscribeEnabled", String(q.lpSubscribeEnabled));
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

function buildVisibilityQuery(q: SymbolGroupVisibilityQuery): string {
  const params = new URLSearchParams();
  if (q.groupCode) params.set("groupCode", q.groupCode);
  if (q.symbolLike) params.set("symbolLike", q.symbolLike);
  if (q.visible !== undefined) params.set("visible", String(q.visible));
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

function buildHolidayQuery(q: MarketHolidayQuery): string {
  const params = new URLSearchParams();
  if (q.marketCode) params.set("marketCode", q.marketCode);
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const symbolApi = {
  list: (q: SymbolListQuery) =>
    adminApi.get<SymbolListResponse>(`/admin/symbols?${buildListQuery(q)}`),

  detail: (id: SnowflakeId) =>
    adminApi.get<SymbolDetail>(`/admin/symbols/${id}`),

  update: (id: SnowflakeId, body: SymbolUpdateRequest) =>
    adminApi.put<SymbolListItem>(`/admin/symbols/${id}`, body),

  /** STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10：新建 LP 源 symbol。 */
  createSource: (body: SymbolSourceCreateRequest) =>
    adminApi.post<SymbolListItem>("/admin/symbols", body),

  suspend: (id: SnowflakeId, body: SymbolStatusRequest) =>
    adminApi.post<SymbolListItem>(`/admin/symbols/${id}/suspend`, body),

  resume: (id: SnowflakeId, body: SymbolStatusRequest) =>
    adminApi.post<SymbolListItem>(`/admin/symbols/${id}/resume`, body),

  getSwapRate: (platformSymbol: string) =>
    adminApi.get<SwapRateResponse>(`/admin/symbols/${encodeURIComponent(platformSymbol)}/swap-rate`),

  putSwapRate: (platformSymbol: string, body: SwapRateUpdateRequest) =>
    adminApi.put<SwapRateItem>(`/admin/symbols/${encodeURIComponent(platformSymbol)}/swap-rate`, body),

  getTradingHours: (platformSymbol: string) =>
    adminApi.get<TradingHoursResponse>(`/admin/symbols/${encodeURIComponent(platformSymbol)}/trading-hours`),

  createTradingSession: (platformSymbol: string, body: TradingSessionUpsertRequest) =>
    adminApi.post<TradingSessionItem>(
      `/admin/symbols/${encodeURIComponent(platformSymbol)}/trading-hours/sessions`,
      body,
    ),

  updateTradingSession: (platformSymbol: string, sessionId: SnowflakeId, body: TradingSessionUpsertRequest) =>
    adminApi.put<TradingSessionItem>(
      `/admin/symbols/${encodeURIComponent(platformSymbol)}/trading-hours/sessions/${encodeURIComponent(sessionId)}`,
      body,
    ),

  deleteTradingSession: (platformSymbol: string, sessionId: SnowflakeId, body: SymbolTradingRuleDeleteRequest) =>
    adminApi.delete<void>(
      `/admin/symbols/${encodeURIComponent(platformSymbol)}/trading-hours/sessions/${encodeURIComponent(sessionId)}`,
      body,
    ),

  createTradingException: (platformSymbol: string, body: TradingExceptionUpsertRequest) =>
    adminApi.post<TradingHoursResponse["exceptions"][number]>(
      `/admin/symbols/${encodeURIComponent(platformSymbol)}/trading-hours/exceptions`,
      body,
    ),

  updateTradingException: (platformSymbol: string, exceptionId: SnowflakeId, body: TradingExceptionUpsertRequest) =>
    adminApi.put<TradingHoursResponse["exceptions"][number]>(
      `/admin/symbols/${encodeURIComponent(platformSymbol)}/trading-hours/exceptions/${encodeURIComponent(exceptionId)}`,
      body,
    ),

  deleteTradingException: (platformSymbol: string, exceptionId: SnowflakeId, body: SymbolTradingRuleDeleteRequest) =>
    adminApi.delete<void>(
      `/admin/symbols/${encodeURIComponent(platformSymbol)}/trading-hours/exceptions/${encodeURIComponent(exceptionId)}`,
      body,
    ),

  listMarketHolidays: (q: MarketHolidayQuery) =>
    adminApi.get<MarketHolidayListResponse>(`/admin/symbols/market-holidays?${buildHolidayQuery(q)}`),

  createMarketHoliday: (body: MarketHolidayUpsertRequest) =>
    adminApi.post<MarketHolidayItem>("/admin/symbols/market-holidays", body),

  updateMarketHoliday: (holidayId: SnowflakeId, body: MarketHolidayUpsertRequest) =>
    adminApi.put<MarketHolidayItem>(`/admin/symbols/market-holidays/${encodeURIComponent(holidayId)}`, body),

  deleteMarketHoliday: (holidayId: SnowflakeId, body: SymbolTradingRuleDeleteRequest) =>
    adminApi.delete<void>(`/admin/symbols/market-holidays/${encodeURIComponent(holidayId)}`, body),

  listQuoteMappings: (q: SymbolQuoteMappingQuery) =>
    adminApi.get<SymbolQuoteMappingListResponse>(`/admin/symbols/quote-mappings?${buildMappingQuery(q)}`),

  createQuoteMapping: (body: SymbolQuoteMappingCreateRequest) =>
    adminApi.post<SymbolQuoteMappingItem>("/admin/symbols/quote-mappings", body),

  updateQuoteMapping: (platformSymbol: string, body: SymbolQuoteMappingUpdateRequest) =>
    adminApi.put<SymbolQuoteMappingItem>(
      `/admin/symbols/quote-mappings/${encodeURIComponent(platformSymbol)}`,
      body,
    ),

  listGroupVisibility: (q: SymbolGroupVisibilityQuery) =>
    adminApi.get<SymbolGroupVisibilityListResponse>(`/admin/symbols/group-visibility?${buildVisibilityQuery(q)}`),

  listGroupVisibilityGrouped: (q: SymbolGroupVisibilityGroupedQuery) => {
    const params = new URLSearchParams();
    if (q.groupCodeLike) params.set("groupCodeLike", q.groupCodeLike);
    params.set("page", String(q.page ?? 0));
    params.set("size", String(q.size ?? 20));
    return adminApi.get<SymbolGroupVisibilityGroupedListResponse>(
      `/admin/symbols/group-visibility/grouped?${params.toString()}`,
    );
  },

  upsertGroupVisibility: (groupCode: string, symbol: string, body: SymbolGroupVisibilityUpdateRequest) =>
    adminApi.put<SymbolGroupVisibilityItem>(
      `/admin/symbols/group-visibility/${encodeURIComponent(groupCode)}/${encodeURIComponent(symbol)}`,
      body,
    ),

  bulkUpsertGroupVisibility: (groupCode: string, body: SymbolGroupVisibilityBulkUpdateRequest) =>
    adminApi.put<SymbolGroupVisibilityItem[]>(
      `/admin/symbols/group-visibility/${encodeURIComponent(groupCode)}/bulk`,
      body,
    ),
};
