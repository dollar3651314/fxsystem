export type OrderSide = "BUY" | "SELL";
export type MarginMode = "ISOLATED" | "CROSS";

export type PositionStatus = "OPEN" | "CLOSED" | "LIQUIDATED";
export type OrderStatus = "PENDING" | "FILLED" | "REJECTED" | "CANCELED";

export interface PositionItem {
  positionId: string; // 雪花 ID 超 2^53，必须 string 避免 JSON.parse 精度损失
  openingOrderId: string;
  symbol: string;
  side: OrderSide;
  quantity: string;
  entryPrice: string;
  leverage: string;
  margin: string;
  marginMode: MarginMode;
  liquidationPrice: string | null;
  takeProfitPrice: string | null;
  stopLossPrice: string | null;
  markPrice: string | null;
  /**
   * STAGE-14E1: 持仓结算币（quote currency，如 USD/JPY），区别于 MarketSymbol.quoteCurrency（品种标的币）。
   * 后端硬切双币后随 position.update / snapshot 下发。
   */
  quoteCurrency: string | null;
  /** STAGE-14E1: 以结算币计的未实现盈亏（双币切换后的「原币」口径）。 */
  unrealizedPnlInQuote: string | null;
  /** STAGE-14E1: 以账户币（USDT）计的未实现盈亏，替代旧单币 unrealizedPnl。 */
  unrealizedPnlInAccount: string | null;
  /** STAGE-14E1: 逐仓占用保证金；CROSS 模式为 null。 */
  isolatedMargin: string | null;
  closePrice: string | null;
  closeReason: string | null;
  realizedPnl: string | null;
  status: PositionStatus;
  quoteStale: boolean | null;
  quoteTs: string | null;
  quoteSource: string | null;
  openedAt: string;
  closedAt: string | null;
  updatedAt: string;
  /** 开仓已扣手续费 = openFeeRate × entryPrice × quantity（USDT），用于「净盈亏」列 + tooltip */
  openFee: string | null;
  /** 开仓时的费率快照，用于 tooltip 展示「按 X% 已扣」*/
  openFeeRate: string | null;
  /**
   * STAGE-12-GROUP-MARKUP: 开仓时用户组 code 冻结值。
   * 用户后续可能换组，但 position 永远按 groupCodeAtOpen 算 PnL。
   */
  groupCodeAtOpen: string | null;
  /**
   * STAGE-12-GROUP-MARKUP: 开仓时该组 bid_extra 冻结值。即使运营事后改动 markup 配置，
   * position PnL 仍按此冻结值算（保证存量持仓不受运营改配置影响）。
   */
  bidExtraAtOpen: string | null;
  /** STAGE-12-GROUP-MARKUP: 开仓时 ask_extra 冻结值。 */
  askExtraAtOpen: string | null;
  /**
   * STAGE-12-GROUP-MARKUP: 有效标记价 = 公允 mark + 持仓方向冻结 markup。
   * PnL 实际用此价算（与 entryPrice 同口径），UI 展示帮用户理解 PnL 算法。
   */
  effectiveMarkPrice: string | null;
}

export interface OrderItem {
  orderId: string;
  orderNo: string;
  symbol: string;
  side: OrderSide;
  orderType: string;
  quantity: string;
  requestedPrice: string | null;
  filledPrice: string | null;
  leverage: string;
  margin: string;
  fee: string;
  status: OrderStatus;
  rejectReason: string | null;
  clientOrderId: string;
  createdAt: string;
  updatedAt: string;
}

export interface TradeItem {
  tradeId: string;
  orderId: string | null;
  positionId: string | null;
  symbol: string;
  side: OrderSide;
  tradeType: string;
  quantity: string;
  price: string;
  fee: string;
  realizedPnl: string | null;
  tradedAt: string;
}

export interface PaginatedResponse<T> {
  page: number;
  pageSize: number;
  total: number;
  items: T[];
}

export interface PlaceMarketOrderRequest {
  symbol: string;
  side: OrderSide;
  quantity: string;
  leverage: string;
  marginMode?: MarginMode;
  takeProfitPrice?: string;
  stopLossPrice?: string;
  clientOrderId: string;
}

export interface PlaceMarketOrderResponse {
  orderNo: string;
  orderStatus: string;
  rejectionReason: string | null;
  duplicate: boolean;
  symbol: string;
  side: string;
  quantity: string;
  requestPrice: string | null;
  filledPrice: string | null;
  leverage: string;
  marginMode: string;
  margin: string;
  fee: string;
}

export interface ClosePositionResponse {
  positionId: string;
  closePrice: string;
  realizedPnl: string;
  closedAt: string;
}

export interface UpdateRiskControlsRequest {
  takeProfitPrice?: string | null;
  stopLossPrice?: string | null;
}

export interface AddMarginRequest {
  amount: string;
}

export type TradingSocketEventType = "ORDER_FILLED" | "ORDER_REJECTED" | "POSITION_CLOSED";

export interface TradingSocketEvent {
  type: TradingSocketEventType;
  payload: Record<string, unknown>;
  ts: string;
}

// STAGE-3-PENDING-ORDER：挂单类型

export type PendingOrderType = "LIMIT" | "STOP" | "STOP_LIMIT" | "SL_TP";
export type PendingOrderStatus = "PENDING" | "TRIGGERED" | "CANCELLED" | "EXPIRED" | "REJECTED";
export type PendingOrderTriggerKind = "TAKE_PROFIT" | "STOP_LOSS";

export interface PlaceLimitOrderRequest {
  symbol: string;
  side: OrderSide;
  quantity: string;
  limitPrice: string;
  leverage: string;
  marginMode?: MarginMode;
  clientOrderId: string;
}

export interface PlaceStopOrderRequest {
  symbol: string;
  side: OrderSide;
  quantity: string;
  stopPrice: string;
  limitPrice?: string;
  leverage: string;
  marginMode?: MarginMode;
  clientOrderId: string;
}

export interface ModifyPendingOrderRequest {
  triggerPrice?: string;
  limitPrice?: string;
  quantity?: string;
}

export interface PendingOrderItem {
  id: string; // 雪花 ID 超 2^53，需用 string 避免 JSON.parse 精度损失
  orderNo: string;
  symbol: string;
  orderType: PendingOrderType;
  side: OrderSide;
  quantity: string;
  triggerPrice: string;
  limitPrice: string | null;
  leverage: string;
  marginMode: string;
  frozenMargin: string;
  frozenFee: string;
  status: PendingOrderStatus;
  parentPositionId: string | null;
  triggerKind: PendingOrderTriggerKind | null;
  clientOrderId: string | null;
  triggeredOrderId: string | null;
  triggeredAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  createdAt: string;
  updatedAt: string;
}

// STAGE-4-PRICE-ALERT：价格告警类型

export type PriceAlertDirection = "ABOVE" | "BELOW";
export type PriceAlertStatus = "ACTIVE" | "EXHAUSTED" | "CANCELLED" | "ADMIN_DELETED";

export interface CreatePriceAlertRequest {
  symbol: string;
  direction?: PriceAlertDirection;  // null = 自动按 targetPrice vs mark 推导
  targetPrice: string;
  note?: string;
}

export interface PriceAlertItem {
  id: string;  // 雪花 ID 用 string 避免精度损失
  symbol: string;
  direction: PriceAlertDirection;
  targetPrice: string;
  status: PriceAlertStatus;
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
}

// STAGE-8-NOTIFICATION：站内信

export type NotificationLevel = "INFO" | "WARN" | "CRITICAL";
export type NotificationStatus = "UNREAD" | "READ";

export interface NotificationItem {
  id: string;
  type: string;
  level: NotificationLevel;
  title: string;
  body: string;
  relatedKey: string | null;
  relatedId: string | null;
  payloadJson: string | null;
  status: NotificationStatus;
  readAt: string | null;
  createdAt: string;
}

export interface NotificationListResponse {
  page: number;
  pageSize: number;
  total: number;
  unread: number;
  items: NotificationItem[];
}

export interface NotificationCreatedEvent {
  id: string;
  type: string;
  level: NotificationLevel;
  title: string;
  body: string;
  relatedKey: string | null;
  relatedId: string | null;
  status: NotificationStatus;
  createdAt: string;
}

export interface PriceAlertTriggeredEvent {
  alertId: string;
  symbol: string;
  direction: PriceAlertDirection;
  targetPrice: string;
  triggeredPrice: string;
  triggerCount: number;
  remainingTriggers: number;
  exhausted: boolean;
  note: string | null;
  triggeredAt: string;
}
