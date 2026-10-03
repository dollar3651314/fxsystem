export type PriceStatus = "LIVE" | "REFERENCE" | "MISSING";

export type MarketConnectionState =
  | "idle"
  | "connecting"
  | "open"
  | "reconnecting"
  | "closed"
  | "auth_expired"
  | "error";

export type MarketChannel =
  | "price.tick"
  | "kline.1m"
  | "kline.5m"
  | "kline.15m"
  | "kline.1h"
  | "kline.4h"
  | "kline.1d";

export type KlineInterval = "1m" | "5m" | "15m" | "1h" | "4h" | "1d";

export type MarketSymbol = {
  symbol: string;
  marketCode?: string;
  category?: number | string;
  baseCurrency?: string;
  quoteCurrency?: string;
  pricePrecision?: number;
  qtyPrecision?: number;
  quantityPrecision?: number;
  minQty?: string;
  maxQty?: string;
  minNotional?: string;
  maxLeverage?: number;
  takerFeeRate?: string;
  spread?: string;
  bid?: string | null;
  ask?: string | null;
  mid?: string | null;
  mark?: string | null;
  quoteTs?: string | null;
  quoteSource?: string | null;
  priceStatus?: PriceStatus;
  tradable?: boolean;
};

export type Quote = {
  symbol: string;
  /** 用户视角（含用户组 markup）的报价。撮合 fillPrice / ticker 展示用 */
  bid: string;
  ask: string;
  mid: string;
  mark: string;
  /**
   * STAGE-12-GROUP-MARKUP: 平台基准价（不含用户组 markup）。
   * 后端 K 线由基准价生成（全组共用一份）；2026-06-03 起图表用 mid−baseMid 作常数 offset
   * 把蜡烛平移到本组用户价口径（marketChartData.resolveMarkupOffset），对齐 MT4/MT5
   * 「图表跟实际可交易流」范式（OKX/Binance 无组级加点概念，不可比）。
   * 当 hasMarkup=false 时 base* === bid/ask/mid → offset=0。
   */
  baseBid?: string;
  baseAsk?: string;
  baseMid?: string;
  hasMarkup?: boolean;
  ts: string;
  receivedAt?: string;
  source: string;
  stale: boolean;
  priceStatus?: PriceStatus;
  quoteStatus?: string;
  qualityReason?: string | null;
};

export type Kline = {
  symbol: string;
  interval: KlineInterval;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
  openTime: string;
  closeTime: string;
  isFinal: boolean;
};

export type SubscribeMessage = {
  type: "subscribe";
  requestId: string;
  channels: MarketChannel[];
  symbols: string[];
};

export type UnsubscribeMessage = {
  type: "unsubscribe";
  requestId: string;
  channels: MarketChannel[];
  symbols: string[];
};

export type PriceTickMessage = Quote & {
  type: "price.tick";
  quoteStatus?: string;
};

export type KlineMessage = Kline & {
  type: `kline.${KlineInterval}`;
};

export type MarketServerMessage =
  | PriceTickMessage
  | KlineMessage
  | {
      type?: string;
      [key: string]: unknown;
    };

export type MarketDataState = {
  quotes: Record<string, Quote>;
  quoteHistory: Record<string, Quote[]>;
  // 2026-05-26 K 线断层修复：原来 (symbol, interval) → 1 根 Kline 会被 wss 推送一直覆盖
  // 导致已收盘 K 线丢失。改成 Kline[]，按 openTime upsert。
  klines: Record<string, Partial<Record<KlineInterval, Kline[]>>>;
};
