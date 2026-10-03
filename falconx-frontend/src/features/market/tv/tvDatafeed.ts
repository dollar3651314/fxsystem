import { useAuthStore } from "../../auth/authStore";
import { getKlines } from "../marketApi";
import { resolveMarkupOffset } from "../marketChartData";
import { useMarketStore } from "../marketStore";
import type { Kline, KlineInterval, MarketSymbol } from "../marketTypes";
import { resolutionToInterval, SUPPORTED_TV_RESOLUTIONS } from "./tvResolution";
import type {
  Bar,
  DatafeedConfiguration,
  IBasicDataFeed,
  LibrarySymbolInfo,
  ResolutionString
} from "./charting_library";

/** TV 高级图表 Datafeed（JS API）构造参数。 */
export type TvDatafeedOptions = {
  /**
   * 取 symbol 元数据（精度/币对/市场分类）。用 getter 而非快照：
   * 父组件每次渲染更新 ref，resolveSymbol 总是读到最新 meta。
   */
  getSymbolMeta: (symbol: string) => MarketSymbol | undefined;
};

/** 单次历史请求条数 — 与经典图表同源后端 `GET /klines`（默认 200），多拉一些给 TV 留缩放余量。 */
const HISTORY_BAR_LIMIT = 500;

const DATAFEED_CONFIGURATION: DatafeedConfiguration = {
  supported_resolutions: SUPPORTED_TV_RESOLUTIONS as ResolutionString[],
  supports_marks: false,
  supports_timescale_marks: false,
  supports_time: false
};

type BarSubscription = {
  unsubscribe: () => void;
};

/**
 * FalconX TradingView 高级图表 Datafeed。
 *
 * - 历史：REST `GET /api/v1/market/klines/{symbol}`（与经典 K 线同源，仅最近 N 根；
 *   更早区间返回 noData，TV 即停止回溯分页）。
 * - 实时：订阅 {@link useMarketStore} 的 `klines[symbol][interval]`（由终端
 *   `useMarketSocket` 按当前周期订阅 `kline.{interval}` 频道灌入），datafeed 不自开连接。
 *   前提：TV 当前 resolution 与终端 timeframe 保持同步（TvAdvancedChart 负责回传）。
 * - 组加点：蜡烛沿用经典图表口径整体平移 markupOffset（mid − baseMid），
 *   与报价/成交同处「本组价格世界」；offset 每次推送按最新 quote 重算。
 */
export function createTvDatafeed(options: TvDatafeedOptions): IBasicDataFeed {
  const subscriptions = new Map<string, BarSubscription>();
  // getBars 最后一根 bar 时间（key = symbol#interval）：subscribeBars 据此只向前推进，
  // 满足 TV「onTick 时间不得回退」约束。
  const lastBarTimeMs = new Map<string, number>();

  return {
    onReady: (callback) => {
      // TV 要求 onReady 异步回调
      setTimeout(() => callback(DATAFEED_CONFIGURATION), 0);
    },

    // 符号搜索按产品决策关闭（disabled_features: header_symbol_search），
    // 切换品种走终端 watchlist，这里恒返回空。
    searchSymbols: (_userInput, _exchange, _symbolType, onResult) => {
      onResult([]);
    },

    resolveSymbol: (symbolName, onResolve, onError) => {
      const meta = options.getSymbolMeta(symbolName);
      if (!meta) {
        setTimeout(() => onError(`unknown_symbol:${symbolName}`), 0);
        return;
      }
      const pricePrecision = meta.pricePrecision ?? 5;
      const symbolInfo: LibrarySymbolInfo = {
        name: meta.symbol,
        ticker: meta.symbol,
        description:
          meta.baseCurrency && meta.quoteCurrency
            ? `${meta.baseCurrency}/${meta.quoteCurrency}`
            : meta.symbol,
        type: meta.marketCode?.toLowerCase() ?? "forex",
        exchange: "FalconX",
        listed_exchange: "FalconX",
        format: "price",
        // LP 行情按 UTC 时间戳下发；交易时段由后端交易日历控制，图表侧不再裁剪
        session: "24x7",
        timezone: "Etc/UTC",
        minmov: 1,
        pricescale: 10 ** pricePrecision,
        has_intraday: true,
        intraday_multipliers: ["1", "5", "15", "60", "240"] as ResolutionString[],
        has_daily: true,
        daily_multipliers: ["1"] as ResolutionString[],
        has_weekly_and_monthly: false,
        volume_precision: 2,
        data_status: "streaming",
        supported_resolutions: SUPPORTED_TV_RESOLUTIONS as ResolutionString[]
      };
      setTimeout(() => onResolve(symbolInfo), 0);
    },

    getBars: (symbolInfo, resolution, periodParams, onResult, onError) => {
      const interval = resolutionToInterval(resolution);
      if (!interval) {
        onError(`unsupported_resolution:${resolution}`);
        return;
      }
      // 后端只提供「最近 N 根」窗口（与经典图表一致），更早的回溯分页直接 noData
      if (!periodParams.firstDataRequest) {
        onResult([], { noData: true });
        return;
      }
      const token = useAuthStore.getState().session?.accessToken;
      if (!token) {
        onError("auth_token_missing");
        return;
      }

      getKlines(token, symbolInfo.name, interval, HISTORY_BAR_LIMIT)
        .then((klineHistory) => {
          const state = useMarketStore.getState();
          const markupOffset = resolveMarkupOffset(state.quotes[symbolInfo.name]);
          // wss 累积 K 线优先覆盖 REST 同 openTime 旧版本（与经典图表 buildCandlesticks 同口径），
          // 避免 REST 快照与首条推送之间出现断层
          const merged = new Map<number, Bar>();
          for (const kline of [
            ...klineHistory,
            ...(state.klines[symbolInfo.name]?.[interval] ?? [])
          ]) {
            const bar = klineToBar(kline, markupOffset);
            if (bar) {
              merged.set(bar.time, bar);
            }
          }
          const bars = [...merged.values()].sort((left, right) => left.time - right.time);
          if (bars.length > 0) {
            lastBarTimeMs.set(barStreamKey(symbolInfo.name, interval), bars[bars.length - 1].time);
          }
          onResult(bars, { noData: bars.length === 0 });
        })
        .catch((error: unknown) => {
          onError(error instanceof Error ? error.message : "klines_request_failed");
        });
    },

    subscribeBars: (symbolInfo, resolution, onTick, listenerGuid) => {
      const interval = resolutionToInterval(resolution);
      if (!interval) {
        return;
      }
      const streamKey = barStreamKey(symbolInfo.name, interval);
      let previousKlines = useMarketStore.getState().klines[symbolInfo.name]?.[interval];

      const unsubscribe = useMarketStore.subscribe((state) => {
        const nextKlines = state.klines[symbolInfo.name]?.[interval];
        if (!nextKlines || nextKlines === previousKlines) {
          return;
        }
        previousKlines = nextKlines;

        const markupOffset = resolveMarkupOffset(state.quotes[symbolInfo.name]);
        const lastDelivered = lastBarTimeMs.get(streamKey) ?? 0;
        // 只向前推进：>= lastDelivered 的 bar 按时间升序逐根回调
        //（同 time 重复回调 = 更新当前活跃 bar，TV 语义允许）
        const pending = nextKlines
          .map((kline) => klineToBar(kline, markupOffset))
          .filter((bar): bar is Bar => bar !== null && bar.time >= lastDelivered)
          .sort((left, right) => left.time - right.time);
        for (const bar of pending) {
          onTick(bar);
          lastBarTimeMs.set(streamKey, bar.time);
        }
      });

      subscriptions.set(listenerGuid, { unsubscribe });
    },

    unsubscribeBars: (listenerGuid) => {
      subscriptions.get(listenerGuid)?.unsubscribe();
      subscriptions.delete(listenerGuid);
    }
  };
}

function barStreamKey(symbol: string, interval: KlineInterval): string {
  return `${symbol}#${interval}`;
}

/** Kline → TV Bar（time 为毫秒 UTC；OHLC 含组加点平移，口径同经典图表 klineToCandle）。 */
export function klineToBar(kline: Kline, markupOffset = 0): Bar | null {
  const timeMs = Date.parse(kline.openTime);
  const open = Number(kline.open) + markupOffset;
  const high = Number(kline.high) + markupOffset;
  const low = Number(kline.low) + markupOffset;
  const close = Number(kline.close) + markupOffset;
  if (!Number.isFinite(timeMs) || ![open, high, low, close].every(Number.isFinite)) {
    return null;
  }
  const volume = Number(kline.volume);
  return {
    time: timeMs,
    open,
    high,
    low,
    close,
    volume: Number.isFinite(volume) ? volume : undefined
  };
}
