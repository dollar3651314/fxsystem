/** 阶段 2.3 行情品种管理类型，对齐管理端接口规范 §6 + R2 三轮 §6.13。 */

export type SnowflakeId = string;

export type SymbolStatus = 1 | 2; // 1=TRADING, 2=SUSPENDED

/**
 * LP 源 symbol 主表项。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10：字段集裁剪 6 交易字段已下沉到 mapping；
 * 新增 lastTickAt（ClickHouse `quote_tick.event_time` 聚合，从未收到 tick 时为 null）。
 */
export interface SymbolListItem {
  id: SnowflakeId;
  lpCode: string;
  symbol: string;
  category: number;          // 1=crypto,2=forex,3=metal,4=index,5=energy
  marketCode: string;
  baseCurrency: string;
  quoteCurrency: string;
  pricePrecision: number;
  qtyPrecision: number;
  status: SymbolStatus;
  /** STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10：从 ClickHouse 聚合返回；null 表示从未收到 tick。 */
  lastTickAt: string | null;
  createdAt: string;
}

export interface SymbolListResponse {
  items: SymbolListItem[];
  total: number;
  page: number;
  size: number;
}

export interface SymbolListQuery {
  category?: number;
  marketCode?: string;
  status?: SymbolStatus;
  lpCode?: string;
  symbolLike?: string;
  page?: number;
  size?: number;
}

/**
 * Quote mapping 列表项。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10 + V15：字段集扩展。category / marketCode /
 * 6 交易字段 / precision 均为 mapping 上的系统级配置。
 */
export interface SymbolQuoteMappingItem {
  platformSymbol: string;
  sourceProvider: string;
  sourceLpCode: string;
  sourceSymbol: string;
  category: number;
  marketCode: string;
  priceMultiplier: string;
  bidAdjustment: string;
  askAdjustment: string;
  enabled: 0 | 1;
  lpSubscribeEnabled: 0 | 1;
  maxLeverage: number;
  takerFeeRate: string;
  spread: string;
  minQty: string;
  maxQty: string;
  minNotional: string;
  pricePrecision: number;
  qtyPrecision: number;
  sourceStatus: SymbolStatus | null;
  createdAt: string;
  updatedAt: string;
}

export interface SymbolQuoteMappingListResponse {
  items: SymbolQuoteMappingItem[];
  total: number;
  page: number;
  size: number;
}

export interface SymbolQuoteMappingQuery {
  platformSymbolLike?: string;
  sourceLpCode?: string;
  sourceSymbolLike?: string;
  enabled?: 0 | 1;
  lpSubscribeEnabled?: 0 | 1;
  page?: number;
  size?: number;
}

export interface SymbolQuoteMappingCreateRequest {
  platformSymbol: string;
  sourceProvider?: string;
  sourceLpCode?: string;
  sourceSymbol: string;
  category: number;
  marketCode: string;
  priceMultiplier: string;
  bidAdjustment?: string;
  askAdjustment?: string;
  enabled: 0 | 1;
  lpSubscribeEnabled: 0 | 1;
  maxLeverage: number;
  takerFeeRate: string;
  spread: string;
  minQty: string;
  maxQty: string;
  minNotional: string;
  pricePrecision: number;
  qtyPrecision: number;
  reason: string;
}

export interface SymbolQuoteMappingUpdateRequest {
  sourceProvider?: string;
  sourceLpCode?: string;
  sourceSymbol?: string;
  category?: number;
  marketCode?: string;
  priceMultiplier?: string;
  bidAdjustment?: string;
  askAdjustment?: string;
  enabled?: 0 | 1;
  lpSubscribeEnabled?: 0 | 1;
  maxLeverage?: number;
  takerFeeRate?: string;
  spread?: string;
  minQty?: string;
  maxQty?: string;
  minNotional?: string;
  pricePrecision?: number;
  qtyPrecision?: number;
  reason: string;
}

export interface SymbolGroupVisibilityItem {
  groupCode: string;
  symbol: string;
  visible: 0 | 1;
  createdAt: string;
  updatedAt: string;
}

export interface SymbolGroupVisibilityListResponse {
  items: SymbolGroupVisibilityItem[];
  total: number;
  page: number;
  size: number;
}

export interface SymbolGroupVisibilityQuery {
  groupCode?: string;
  symbolLike?: string;
  visible?: 0 | 1;
  page?: number;
  size?: number;
}

export interface SymbolGroupVisibilityUpdateRequest {
  visible: 0 | 1;
  reason: string;
}

export interface SymbolGroupVisibilityBulkUpdateRequest {
  symbols: string[];
  visible: 0 | 1;
  reason: string;
}

/**
 * STAGE-2-SYMBOL：用户组可见性聚合视图（每个 groupCode 一行，包含该组所有可见 symbol）。
 */
export interface SymbolGroupVisibilityGroupedItem {
  groupCode: string;
  visibleCount: number;
  visibleSymbols: string[];
  lastModifiedAt: string | null;
}

export interface SymbolGroupVisibilityGroupedListResponse {
  items: SymbolGroupVisibilityGroupedItem[];
  total: number;
  page: number;
  size: number;
}

export interface SymbolGroupVisibilityGroupedQuery {
  groupCodeLike?: string;
  page?: number;
  size?: number;
}

export interface SwapRateItem {
  id: SnowflakeId;
  symbol: string;
  longRate: string;
  shortRate: string;
  rolloverTime: string;
  effectiveFrom: string;
  createdAt: string;
}

export interface TradingSessionItem {
  id: SnowflakeId;
  symbol?: string;
  dayOfWeek: number;
  sessionNo: number;
  openTime: string;
  closeTime: string;
  timezone: string;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface SymbolDetail {
  symbol: SymbolListItem;
  currentSwapRate: SwapRateItem | null;
  sessions: TradingSessionItem[];
}

/**
 * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10：编辑 source 元数据请求（字段裁剪）。
 */
export interface SymbolUpdateRequest {
  category?: number;
  marketCode?: string;
  pricePrecision?: number;
  qtyPrecision?: number;
  reason: string;
}

/**
 * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10：新建 LP 源 symbol 请求。
 */
export interface SymbolSourceCreateRequest {
  lpCode?: string;
  symbol: string;
  category: number;
  marketCode: string;
  baseCurrency: string;
  quoteCurrency: string;
  pricePrecision: number;
  qtyPrecision: number;
  reason: string;
}

export interface SymbolStatusRequest {
  reason: string;
}

export interface SwapRateResponse {
  /** platform symbol，对应 t_symbol_quote_mapping.platform_symbol。 */
  symbol: string;
  current: SwapRateItem | null;
  history: SwapRateItem[];
}

export interface SwapRateUpdateRequest {
  longRate: string;
  shortRate: string;
  rolloverTime?: string;
  effectiveFrom: string;
  reason: string;
}

export interface TradingHoursResponse {
  /** platform symbol，对应 t_symbol_quote_mapping.platform_symbol。 */
  symbol: string;
  marketCode: string;
  sessions: TradingSessionItem[];
  exceptions: Array<{
    id: SnowflakeId;
    symbol: string;
    tradeDate: string;
    exceptionType: number;
    sessionNo: number | null;
    openTime: string | null;
    closeTime: string | null;
    timezone: string;
    reason: string | null;
  }>;
  holidays: Array<{
    id: SnowflakeId;
    marketCode: string;
    holidayDate: string;
    holidayType: number;
    openTime: string | null;
    closeTime: string | null;
    timezone: string;
    holidayName: string;
    countryCode: string | null;
  }>;
}

export interface TradingSessionUpsertRequest {
  dayOfWeek: number;
  sessionNo: number;
  openTime: string;
  closeTime: string;
  timezone: string;
  enabled: 0 | 1;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface TradingExceptionUpsertRequest {
  tradeDate: string;
  exceptionType: number;
  sessionNo?: number | null;
  openTime?: string | null;
  closeTime?: string | null;
  timezone: string;
  ruleReason?: string | null;
  reason: string;
}

export interface SymbolTradingRuleDeleteRequest {
  reason: string;
}

export interface MarketHolidayItem {
  id: SnowflakeId;
  marketCode: string;
  holidayDate: string;
  holidayType: number;
  openTime: string | null;
  closeTime: string | null;
  timezone: string;
  holidayName: string;
  countryCode: string | null;
}

export interface MarketHolidayListResponse {
  items: MarketHolidayItem[];
  total: number;
  page: number;
  size: number;
}

export interface MarketHolidayQuery {
  marketCode?: string;
  page?: number;
  size?: number;
}

export interface MarketHolidayUpsertRequest {
  marketCode: string;
  holidayDate: string;
  holidayType: number;
  openTime?: string | null;
  closeTime?: string | null;
  timezone: string;
  holidayName: string;
  countryCode?: string | null;
  reason: string;
}

export const CATEGORY_NAMES: Record<number, string> = {
  1: "crypto",
  2: "forex",
  3: "metal",
  4: "index",
  5: "energy",
  6: "stock",
  7: "etf",
  8: "other",
};

export const MARKET_CODE_OPTIONS = [
  "CRYPTO",
  "FX",
  "METAL",
  "INDEX",
  "ENERGY",
  "US_STOCK",
  "HK_STOCK",
  "JP_STOCK",
  "ETF",
  "OTHER",
];

export const STATUS_NAMES: Record<SymbolStatus, string> = {
  1: "TRADING",
  2: "SUSPENDED",
};
