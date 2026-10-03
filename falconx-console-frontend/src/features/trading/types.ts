export type SnowflakeId = string;

export interface OrderListItem {
  id: SnowflakeId;
  orderNo: string;
  userId: SnowflakeId;
  symbol: string;
  side: number;
  orderType: number;
  quantity: string;
  requestedPrice: string | null;
  filledPrice: string | null;
  leverage: string;
  margin: string;
  fee: string;
  openFeeRate: string;
  clientOrderId: string;
  status: number;
  rejectReason: string | null;
  createdAt: string;
  updatedAt: string;
  /** symbol 价格显示精度（来源 SymbolSpec.pricePrecision）；缺失为 null，formatPrice 缺省回退。 */
  pricePrecision: number | null;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

export interface OrderListResponse {
  items: OrderListItem[];
  total: number;
  page: number;
  size: number;
}

export interface PositionListItem {
  id: SnowflakeId;
  openingOrderId: SnowflakeId;
  userId: SnowflakeId;
  symbol: string;
  side: number;
  quantity: string;
  entryPrice: string;
  leverage: string;
  margin: string;
  marginMode: number;
  liquidationPrice: string | null;
  takeProfitPrice: string | null;
  stopLossPrice: string | null;
  closePrice: string | null;
  closeReason: number | null;
  realizedPnl: string | null;
  openFeeRate: string;
  status: number;
  openedAt: string;
  closedAt: string | null;
  updatedAt: string;
  /** OPEN 持仓基于 quote snapshot 计算的实时 markPrice；非 OPEN 或缺 quote 为 null */
  markPrice: string | null;
  /** OPEN 持仓的浮动盈亏；非 OPEN 或缺 quote 为 null */
  unrealizedPnl: string | null;
  /** symbol 价格显示精度（来源 SymbolSpec.pricePrecision）；缺失为 null，formatPrice 缺省回退。 */
  pricePrecision: number | null;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

/** Swap 资金费率聚合摘要（持仓维度） */
export interface AdminSwapSummary {
  totalCharge: string;
  totalIncome: string;
  net: string;
  chargeCount: number;
  incomeCount: number;
  firstAt: string | null;
  lastAt: string | null;
}

/** 平台运营指标聚合（admin dashboard） */
export interface AdminPlatformMetrics {
  positions: {
    openCount: number;
    longCount: number;
    shortCount: number;
    closedCount: number;
    liquidatedCount: number;
    totalUserRealizedPnl: string;
  };
  revenue: {
    feeIncomeAllTime: string;
    feeIncome30d: string;
    swapChargeAllTime: string;
    swapIncomeAllTime: string;
    swapNetForPlatformAllTime: string;
    swapCharge30d: string;
    swapIncome30d: string;
    swapNetForPlatform30d: string;
    platformFeeRevenueAllTime: string;
    platformFeeRevenue30d: string;
  };
  userPnl: {
    winningUsers: number;
    losingUsers: number;
    evenUsers: number;
    biggestWinner: { userId: string | null; symbol: string | null; amount: string };
    biggestLoser: { userId: string | null; symbol: string | null; amount: string };
  };
  flows: {
    totalDepositAllTime: string;
    totalDeposit30d: string;
    totalWithdrawAllTime: string;
    totalWithdraw30d: string;
    netFlowAllTime: string;
    netFlow30d: string;
  };
  orders: {
    totalFilledOrders: number;
    filledOrdersToday: number;
  };
  computedAt: string;
}

/** 平台 OPEN 持仓汇总（GET /admin/trading/positions/summary） */
export interface PositionSummary {
  openPositionCount: number;
  totalMarginUsed: string;
  totalUnrealizedPnl: string;
  positionsWithoutQuote: number;
  computedAt: string;
}

export interface PositionListResponse {
  items: PositionListItem[];
  total: number;
  page: number;
  size: number;
}

export interface ExposureItem {
  symbol: string;
  /** STAGE-14E2：per-symbol 报价币（QC，来源 SymbolSpec）；过渡期可能为 null。 */
  quoteCurrency: string | null;
  totalLongQty: string;
  totalShortQty: string;
  netExposure: string;
  netExposureUsd: string;
  updatedAt: string;
}

export interface ExposureListResponse {
  items: ExposureItem[];
}

export interface RiskSwitchItem {
  key: string;
  enabled: boolean;
  reason: string | null;
  updatedBy: string | null;
  updatedAt: string;
}

export interface RiskSwitchListResponse {
  items: RiskSwitchItem[];
}

export interface OrderListQuery {
  userId?: number;
  symbol?: string;
  status?: number;
  fromCreatedAt?: string;
  toCreatedAt?: string;
  page?: number;
  size?: number;
}

export interface PositionListQuery {
  userId?: number;
  symbol?: string;
  status?: number;
  fromOpenedAt?: string;
  toOpenedAt?: string;
  page?: number;
  size?: number;
}

export interface ManualLiquidateResponse {
  positionId: SnowflakeId;
  liquidationLogId: SnowflakeId;
  closedAt: string;
  realizedPnl: string;
}

export interface RiskSwitchUpdateResponse {
  key: string;
  enabled: boolean;
  updatedAt: string;
  updatedBy: string;
}

// STAGE-3-PENDING-ORDER：管理端挂单类型

export interface AdminPendingOrderItem {
  id: string; // 雪花 ID 需用 string 避免 JSON.parse 精度损失
  orderNo: string;
  symbol: string;
  orderType: "LIMIT" | "STOP" | "STOP_LIMIT" | "SL_TP";
  side: "BUY" | "SELL";
  quantity: string;
  triggerPrice: string;
  limitPrice: string | null;
  leverage: string;
  marginMode: string;
  frozenMargin: string;
  frozenFee: string;
  status: "PENDING" | "TRIGGERED" | "CANCELLED" | "EXPIRED" | "REJECTED";
  parentPositionId: string | null;
  triggerKind: "TAKE_PROFIT" | "STOP_LOSS" | null;
  clientOrderId: string | null;
  triggeredOrderId: string | null;
  triggeredAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  createdAt: string;
  updatedAt: string;
  /** symbol 价格显示精度（来源 SymbolSpec.pricePrecision）；缺失为 null，formatPrice 缺省回退。 */
  pricePrecision: number | null;
}

export interface AdminPendingOrderListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminPendingOrderItem[];
}

export interface AdminPendingOrderListQuery {
  userId?: number;
  symbol?: string;
  status?: number;
  includeSlTp?: boolean;
  page?: number;
  size?: number;
}

// STAGE-4-PRICE-ALERT：管理端价格告警类型

export interface AdminPriceAlertItem {
  id: string;
  userId: string;
  symbol: string;
  direction: "ABOVE" | "BELOW";
  targetPrice: string;
  status: "ACTIVE" | "EXHAUSTED" | "CANCELLED" | "ADMIN_DELETED";
  note: string | null;
  basePrice: string | null;
  triggerCount: number;
  remainingTriggers: number;
  lastTriggeredAt: string | null;
  lastTriggeredPrice: string | null;
  cancelledAt: string | null;
  cancelSource: string | null;
  createdAt: string;
  updatedAt: string;
  /** symbol 价格显示精度（来源 SymbolSpec.pricePrecision）；缺失为 null，formatPrice 缺省回退。 */
  pricePrecision: number | null;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

export interface AdminPriceAlertListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminPriceAlertItem[];
}

export interface AdminPriceAlertListQuery {
  userId?: number;
  symbol?: string;
  status?: number;
  page?: number;
  size?: number;
}
