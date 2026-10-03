import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  AreaSeries,
  CandlestickSeries,
  ColorType,
  CrosshairMode,
  createChart,
  HistogramSeries,
  LineSeries,
  LineStyle,
  LineType,
  type BarPrice,
  type CandlestickData,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type LineData,
  type PriceFormat
} from "lightweight-charts";
import { IndicatorMenu } from "./indicators/IndicatorMenu";
import {
  useIndicatorsStore,
  type SubIndicator,
} from "./indicators/indicatorsStore";
import {
  boll,
  ema,
  kdj,
  macd,
  rsi,
  sar,
  sma,
  vol,
  wr,
  zigzag,
  type IndicatorBar,
} from "./indicators/math";
import {
  CHART_TIMEFRAME_OPTIONS,
  isKlineTimeframe,
  type ChartTimeframe
} from "./chartTimeframes";
import {
  buildCandlesticks,
  buildTickQuoteLineData,
  resolveMarkupOffset,
  symbolToQuote
} from "./marketChartData";
import {
  INITIAL_VISIBLE_BARS,
  RIGHT_OFFSET_BARS,
  resolveInitialVisibleLogicalRange
} from "./marketChartViewport";
import {
  formatPrice,
  formatPriceStatusLabel,
  formatTimestamp,
  isTradable,
  resolveQuoteDisplayTimestamp,
  resolveQuoteTimestamp,
  resolveDisplayPrice,
  resolvePriceStatus
} from "./marketFormat";
import type {
  Kline,
  KlineInterval,
  MarketConnectionState,
  MarketSymbol,
  Quote
} from "./marketTypes";
import { TvAdvancedChart } from "./tv/TvAdvancedChart";

type MarketChartProps = {
  symbol: MarketSymbol | undefined;
  quote: Quote | undefined;
  quoteHistory: Quote[];
  klineHistory: Kline[];
  liveKlines: Kline[];
  selectedSymbol: string | null;
  connectionState: MarketConnectionState;
  isLoading: boolean;
  timeframe: ChartTimeframe;
  onTimeframeChange: (timeframe: ChartTimeframe) => void;
  /** 注入到 §02 panel-head 右侧的扩展槽（如 §04 跳转 chip）。仅桌面端用。 */
  headerExtras?: ReactNode;
};

/** 图表引擎：经典 lightweight-charts K 线 / TradingView 高级图表（Tab 切换，选择持久化）。 */
type ChartEngine = "classic" | "tradingview";

const CHART_ENGINE_OPTIONS: Array<{ value: ChartEngine; label: string }> = [
  { value: "classic", label: "K线" },
  { value: "tradingview", label: "TradingView" }
];

const CHART_ENGINE_STORAGE_KEY = "falconx.chart.engine";

/**
 * 从 :root CSS variables 读出当前主题（dark/light）下的图表色票。
 * 让 series.applyOptions / chart.applyOptions 在 falconx:theme:changed 事件后调用即可热切。
 */
function readChartTheme() {
  const cs = typeof window !== "undefined" ? getComputedStyle(document.documentElement) : null;
  const get = (name: string, fallback: string) => {
    const v = cs?.getPropertyValue(name).trim();
    return v && v.length > 0 ? v : fallback;
  };
  return {
    bid: get("--fx-chart-bid", "#54e6ff"),
    ask: get("--fx-chart-ask", "#ff8ca1"),
    mid: get("--fx-chart-mid", "#facc15"),
    candleUp: get("--fx-chart-candle-up", "#a8ff5c"),
    candleDown: get("--fx-chart-candle-down", "#ff6378"),
    text: get("--fx-chart-text", "#8d99a8"),
    grid: get("--fx-chart-grid", "rgba(255, 255, 255, 0.05)"),
    gridStrong: get("--fx-chart-grid-strong", "rgba(255, 255, 255, 0.025)"),
    axisBorder: get("--fx-chart-axis-border", "rgba(255, 255, 255, 0.06)"),
    crosshair: get("--fx-chart-crosshair", "rgba(84, 230, 255, 0.45)"),
  };
}

/** color-mix(cssvar, alpha) 兜底：lightweight-charts 不直接支持 var()，
 * 手工把 hex/rgb 加 alpha 做成 rgba。简化版只处理 #rrggbb / rgb()，其它保持原值。 */
function withAlpha(color: string, alpha: number): string {
  const hex = color.match(/^#([0-9a-fA-F]{6})$/);
  if (hex) {
    const num = parseInt(hex[1], 16);
    const r = (num >> 16) & 255;
    const g = (num >> 8) & 255;
    const b = num & 255;
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }
  const rgb = color.match(/^rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/);
  if (rgb) return `rgba(${rgb[1]}, ${rgb[2]}, ${rgb[3]}, ${alpha})`;
  return color;
}

export function MarketChart({
  symbol,
  quote,
  quoteHistory,
  klineHistory,
  liveKlines,
  selectedSymbol,
  connectionState,
  isLoading,
  timeframe,
  onTimeframeChange,
  headerExtras
}: MarketChartProps) {
  const displaySymbol = selectedSymbol ?? symbol?.symbol ?? "选择市场";
  const displayPrice = resolveDisplayPrice(symbol, quote);
  const status = resolvePriceStatus(symbol, quote);
  const tradable = isTradable(symbol, quote);
  const statusLabel = formatPriceStatusLabel(tradable, status);
  const quoteTimestamp = resolveQuoteTimestamp(symbol, quote);
  const displayTimestamp = resolveQuoteDisplayTimestamp(symbol, quote);
  const displayTimestampLabel = quote?.receivedAt ? "收到" : "报价";
  const chartQuote = useMemo(() => quote ?? symbolToQuote(symbol), [quote, symbol]);
  const chartMode = timeframe === "tick" ? "line" : "candlestick";
  const chartPricePrecision = useMemo(
    () => resolveDisplayPrecision(symbol, quote),
    [symbol, quote]
  );
  const chartPriceFormat = useMemo(
    () => createChartPriceFormat(chartPricePrecision),
    [chartPricePrecision]
  );
  const usesBackendKline = isKlineTimeframe(timeframe);
  // 2026-06-03 组加点平移：蜡烛由基准 K 线 + 本组 markup 常数 offset（mid − baseMid，
  // 流内自洽）整体平移到「本组价格世界」，MID 线/报价/成交与图表同口径（MT4/MT5 范式）。
  // offset 在 markup 配置不变时为常数 → useMemo 依赖按值比较，不会随每条 tick 重建蜡烛。
  const markupOffset = resolveMarkupOffset(chartQuote);
  const candles = useMemo(
    () =>
      buildCandlesticks({
        timeframe,
        klineHistory,
        liveKlines,
        markupOffset
      }),
    [timeframe, klineHistory, liveKlines, markupOffset]
  );
  const tickBidLineData = useMemo(
    () =>
      buildTickQuoteLineData({
        quoteHistory,
        quote: chartQuote,
        side: "bid"
      }),
    [quoteHistory, chartQuote]
  );
  const tickAskLineData = useMemo(
    () =>
      buildTickQuoteLineData({
        quoteHistory,
        quote: chartQuote,
        side: "ask"
      }),
    [quoteHistory, chartQuote]
  );
  const currentQuotePrices = useMemo(
    () => ({
      bid: toFinitePrice(quote?.bid ?? symbol?.bid),
      ask: toFinitePrice(quote?.ask ?? symbol?.ask),
      mid: toFinitePrice(displayPrice)
    }),
    [quote, symbol, displayPrice]
  );
  // STAGE-12-GROUP-MARKUP → 2026-06-03 更新：蜡烛已按本组 markup offset 平移到用户价口径，
  // MID 线与 K 线天然贴合；Bid/Ask 横线与蜡烛的间距即真实半点差，不再"脱节"。
  // 默认仍只画 mid 参考线（视觉简洁），用户可主动叠加 Bid/Ask 看实际成交价。
  const [showBidAsk, setShowBidAsk] = useState<boolean>(() => {
    try {
      return window.localStorage.getItem("falconx.chart.showBidAsk") === "1";
    } catch {
      return false;
    }
  });
  useEffect(() => {
    try {
      window.localStorage.setItem("falconx.chart.showBidAsk", showBidAsk ? "1" : "0");
    } catch {
      /* SSR / privacy mode 时静默忽略 */
    }
  }, [showBidAsk]);
  const bidLineData = chartMode === "line" ? tickBidLineData : [];
  const askLineData = chartMode === "line" ? tickAskLineData : [];
  const hasQuoteLineData = bidLineData.length > 0 || askLineData.length > 0;
  const hasChartData =
    chartMode === "line" ? hasQuoteLineData : candles.length > 0;

  // 图表引擎 Tab：经典 K 线（lightweight-charts）↔ TradingView 高级图表，选择持久化
  const [chartEngine, setChartEngine] = useState<ChartEngine>(() => {
    try {
      return window.localStorage.getItem(CHART_ENGINE_STORAGE_KEY) === "tradingview"
        ? "tradingview"
        : "classic";
    } catch {
      return "classic";
    }
  });
  useEffect(() => {
    try {
      window.localStorage.setItem(CHART_ENGINE_STORAGE_KEY, chartEngine);
    } catch {
      /* SSR / privacy mode 时静默忽略 */
    }
  }, [chartEngine]);
  // TV 高级图表无 tick 线模式：切到 TV 时把 tick 归一为 1m，
  // 让 useMarketSocket 订阅有效的 kline.1m 频道（datafeed 实时推送依赖它）
  useEffect(() => {
    if (chartEngine === "tradingview" && timeframe === "tick") {
      onTimeframeChange("1m");
    }
  }, [chartEngine, timeframe, onTimeframeChange]);
  const tvInterval: KlineInterval = isKlineTimeframe(timeframe) ? timeframe : "1m";

  return (
    <section className="market-chart" aria-label="行情图表">
      <div className="fx-panel-head">
        <span className="fx-panel-head__num">§02</span>
        <h2 className="fx-panel-head__title">行情图表</h2>
        {/* 引擎 Tab 锚定在标题旁：其前只有定宽 num+title，宽度随模式变化的 hint 文案
          * 与条件渲染的 IndicatorMenu 全部排在其后由 hint 的 flex:1 吸收 → 切换不漂移 */}
        <div
          className="market-chart__engine-tabs"
          role="tablist"
          aria-label="图表引擎"
        >
          {CHART_ENGINE_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              role="tab"
              className={option.value === chartEngine ? "active" : undefined}
              aria-selected={option.value === chartEngine}
              onClick={() => setChartEngine(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <span className="fx-panel-head__hint">
          {chartEngine === "tradingview"
            ? "TradingView 高级图表"
            : hasChartData
              ? chartTitle(chartMode)
              : chartLabel(connectionState, status)}
        </span>
        {/* 指标菜单仅经典蜡烛模式可用；其余模式 visibility:hidden 占位（不可点）——
          * 否则窄屏下它的出现/消失会让 panel-head 换行高度变化，引擎切换时图表区跳动 */}
        <span
          className={
            chartEngine === "classic" && chartMode === "candlestick"
              ? "market-chart__indicator-slot"
              : "market-chart__indicator-slot market-chart__indicator-slot--ghost"
          }
        >
          <IndicatorMenu />
        </span>
        {headerExtras}
      </div>
      <div className="market-chart__header">
        <div className="market-chart__identity">
          <h1>{displaySymbol}</h1>
          {symbol?.baseCurrency || symbol?.maxLeverage || symbol?.marketCode ? (
            <div className="market-chart__identity__meta">
              {symbol?.baseCurrency && symbol?.quoteCurrency ? (
                <span className="market-chart__identity__chip">
                  {symbol.baseCurrency}
                  <i aria-hidden="true">/</i>
                  {symbol.quoteCurrency}
                </span>
              ) : null}
              {symbol?.marketCode ? (
                <span className="market-chart__identity__chip market-chart__identity__chip--accent">
                  {symbol.marketCode}
                </span>
              ) : null}
              {symbol?.maxLeverage ? (
                <span className="market-chart__identity__chip">
                  {symbol.maxLeverage}
                  <i aria-hidden="true">×</i>
                </span>
              ) : null}
            </div>
          ) : null}
        </div>
        <div className="market-chart__actions">
          <div className="market-chart__price">
            <strong
              className="market-chart__price__big"
              title="MID = (Bid + Ask) / 2 市场中间价参考"
            >
              {formatPrice(displayPrice, chartPricePrecision)}
              <em className="market-chart__price__tag">MID</em>
            </strong>
            <div className="market-chart__price-meta">
              <span
                aria-label={`行情状态：${statusLabel}`}
                className={
                  tradable
                    ? "status-pill live"
                    : `status-pill ${status.toLowerCase()}`
                }
              >
                {tradable ? (
                  <>
                    <i className="status-pill__dot" aria-hidden="true" />
                    <span>{statusLabel}</span>
                    <small>可交易</small>
                  </>
                ) : (
                  statusLabel
                )}
              </span>
              <time dateTime={displayTimestamp}>
                <span className="market-chart__price-meta__time-label">
                  {displayTimestampLabel}
                </span>
                {formatTimestamp(displayTimestamp)}
              </time>
            </div>
          </div>
          {/* 周期行两种引擎常驻（与 TV 工具栏双向同步，TV 内切周期会回传点亮本行），
            * 保持 header 两模式同构 → 图表区高度一致、切换零跳动。
            * 仅 Tick 为经典图表专属（TV 无 tick 线模式），TV 模式下滤掉。 */}
          <div
            className="market-chart__timeframes"
            role="tablist"
            aria-label="K线周期"
          >
            {CHART_TIMEFRAME_OPTIONS.filter(
              (option) => chartEngine === "classic" || option.value !== "tick"
            ).map((option) => (
              <button
                key={option.value}
                type="button"
                className={option.value === timeframe ? "active" : undefined}
                aria-selected={option.value === timeframe}
                role="tab"
                onClick={() => onTimeframeChange(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>
          {chartEngine === "classic" && chartMode === "candlestick" ? (
            <button
              type="button"
              className={
                showBidAsk
                  ? "market-chart__bidask-toggle active"
                  : "market-chart__bidask-toggle"
              }
              aria-pressed={showBidAsk}
              onClick={() => setShowBidAsk((v) => !v)}
              title={
                showBidAsk
                  ? "隐藏 Bid/Ask 横线（K 线只显示 mid 公允价）"
                  : "叠加 Bid/Ask 横线 — 显示你的实际成交价（含组级 markup）"
              }
            >
              Bid/Ask
            </button>
          ) : null}
        </div>
      </div>

      <div className="market-chart__canvas">
        {chartEngine === "tradingview" ? (
          selectedSymbol ? (
            <TvAdvancedChart
              symbol={selectedSymbol}
              symbolMeta={symbol}
              interval={tvInterval}
              onIntervalChange={onTimeframeChange}
            />
          ) : (
            <div className="panel-empty">选择市场...</div>
          )
        ) : (
          <>
            {isLoading ? <div className="panel-empty">读取历史行情...</div> : null}
            {!isLoading && !hasChartData ? (
              <div className="panel-empty market-chart__overlay">
                {usesBackendKline ? "暂无后端 K 线数据" : "等待实时报价..."}
              </div>
            ) : null}
            <LightweightChart
              mode={chartMode}
              candles={candles}
              bidLineData={bidLineData}
              askLineData={askLineData}
              currentQuotePrices={currentQuotePrices}
              priceFormat={chartPriceFormat}
              pricePrecision={chartPricePrecision}
              resetKey={`${displaySymbol}:${timeframe}`}
              showBidAsk={showBidAsk}
            />
          </>
        )}
      </div>

      <div className="market-chart__metrics">
        <Metric label="Bid" value={formatPrice(quote?.bid ?? symbol?.bid, chartPricePrecision)} />
        <Metric label="Ask" value={formatPrice(quote?.ask ?? symbol?.ask, chartPricePrecision)} />
        <Metric label="Mid" value={formatPrice(quote?.mid ?? symbol?.mid, chartPricePrecision)} />
        <Metric label="报价" value={formatTimestamp(quoteTimestamp)} />
        <Metric label="收到" value={formatTimestamp(displayTimestamp)} />
      </div>
    </section>
  );
}

type ChartMode = "candlestick" | "line";

// 经典图表引擎（lightweight-charts）。原名 TradingViewChart，引入 TV 高级图表后
// 改名 LightweightChart 消歧——两者同属 TradingView 出品，但这是轻量自绘版。
function LightweightChart({
  mode,
  candles,
  bidLineData,
  askLineData,
  currentQuotePrices,
  priceFormat,
  pricePrecision,
  resetKey,
  showBidAsk
}: {
  mode: ChartMode;
  candles: CandlestickData[];
  bidLineData: LineData[];
  askLineData: LineData[];
  currentQuotePrices: QuotePriceLines;
  priceFormat: PriceFormat;
  pricePrecision: number;
  resetKey: string;
  showBidAsk: boolean;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  // 技术指标 store 订阅（仅在 LightweightChart 内层用，外层 MarketChart 不需要）
  const activeMainIndicators = useIndicatorsStore((s) => s.main);
  const activeSubIndicators = useIndicatorsStore((s) => s.sub);
  const chartRef = useRef<IChartApi | null>(null);
  // 用户正按住拖动 chart（鼠标/触摸）时为 true —— 期间禁止一切自动滚动，
  // 否则高频 tick 触发的 scrollToRealTime 会把拖动量逐段吃掉（慢速拖动被锁死在实时区）
  const isUserInteractingRef = useRef(false);
  const candlestickSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  // STAGE-12 美化：Tick 模式 LineSeries → AreaSeries，让线下方变成"光柱"
  // ts 类型对 v5 AreaSeries 是 "Area"。candlestick 模式下不挂这两个 ref。
  const bidLineSeriesRef = useRef<ISeriesApi<"Area"> | null>(null);
  const askLineSeriesRef = useRef<ISeriesApi<"Area"> | null>(null);
  const bidPriceLineRef = useRef<IPriceLine | null>(null);
  const askPriceLineRef = useRef<IPriceLine | null>(null);
  const midPriceLineRef = useRef<IPriceLine | null>(null);
  // 技术指标 series + pane 追踪：indicator key → 它创建的 series 数组；sub indicator key → pane index
  const indicatorSeriesRef = useRef<Map<string, ISeriesApi<"Line" | "Histogram">[]>>(new Map());
  const subPaneRef = useRef<Map<SubIndicator, number>>(new Map());
  // 记录"上一次 effect 时的 chart 实例"。React StrictMode 双 mount 会重建 chart，
  // 但 seriesMap 还指着旧 chart 的 series → 后续 setData 静默无效。
  // chart 变了就清空 map，让 ensureSeries 在新 chart 上重新 addSeries。
  const lastChartRef = useRef<IChartApi | null>(null);
  const modeRef = useRef<ChartMode | null>(null);
  const resetKeyRef = useRef<string | null>(null);
  const updateSpreadOverlayRef = useRef<() => void>(() => undefined);
  // 价格轴 formatter 是闭包；createChart useEffect 不依赖 pricePrecision，
  // 所以走 ref 让 formatter 读最新精度，切换 symbol 时价格刻度立刻按新精度对齐。
  const priceFormatPrecisionRef = useRef<number>(pricePrecision);
  // STAGE-12 fix：lightweight-charts 5 的 createPriceLine() 创建的横线不会自动计入
  // priceScale autoScale 范围。如果用户的 markup 把 bid/ask 拉到 K 线区间之外
  // （如 XAUUSD ±10），mid/bid/ask 横线就会被裁掉，axis label 也看不到。
  // 解法：用 autoscaleInfoProvider 闭包读最新 bid/ask/mid，强制扩张 priceRange。
  const currentQuotePricesRef = useRef<QuotePriceLines>(currentQuotePrices);
  // 2026-05-26 性能加固（Sprint 2 S2 / 性能分析报告 §8 P0 续）：
  // c7ec2cf 修了 candles 数据源稳定性，但此 effect 仍每条报价 tick 触发 3 次
  // priceScale().applyOptions({autoScale:true})。lightweight-charts 内部会
  // 重新计算坐标区间，K 线 CPU 占用居高。
  // 改用 200ms 后置 debounce：报价快速变动时只在最后一次后 200ms 调一次 autoScale。
  // 设计：用 ref 持有 timer + 即将到来的 autoScale 任务；unmount 时清理。
  const autoScaleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    currentQuotePricesRef.current = currentQuotePrices;
    if (autoScaleTimerRef.current) {
      clearTimeout(autoScaleTimerRef.current);
    }
    autoScaleTimerRef.current = setTimeout(() => {
      // 报价变化 → 通知 priceScale 重新触发 autoScale（applyOptions 调用即可）
      candlestickSeriesRef.current?.priceScale().applyOptions({ autoScale: true });
      bidLineSeriesRef.current?.priceScale().applyOptions({ autoScale: true });
      askLineSeriesRef.current?.priceScale().applyOptions({ autoScale: true });
      autoScaleTimerRef.current = null;
    }, 200);
    return () => {
      if (autoScaleTimerRef.current) {
        clearTimeout(autoScaleTimerRef.current);
        autoScaleTimerRef.current = null;
      }
    };
  }, [currentQuotePrices]);
  useEffect(() => {
    priceFormatPrecisionRef.current = pricePrecision;
    chartRef.current?.applyOptions({
      localization: {
        priceFormatter: (price: BarPrice) => formatPrice(price, priceFormatPrecisionRef.current),
        tickmarksPriceFormatter: (prices: BarPrice[]) =>
          prices.map((p) => formatPrice(p, priceFormatPrecisionRef.current))
      }
    });
  }, [pricePrecision]);
  const [hoverInfo, setHoverInfo] = useState<ChartHoverInfo | null>(null);
  const [spreadOverlay, setSpreadOverlay] = useState<SpreadOverlay | null>(null);

  const removeCurrentPriceLines = useCallback(() => {
    const series = candlestickSeriesRef.current;
    if (series && bidPriceLineRef.current) {
      series.removePriceLine(bidPriceLineRef.current);
    }
    if (series && askPriceLineRef.current) {
      series.removePriceLine(askPriceLineRef.current);
    }
    if (series && midPriceLineRef.current) {
      series.removePriceLine(midPriceLineRef.current);
    }
    bidPriceLineRef.current = null;
    askPriceLineRef.current = null;
    midPriceLineRef.current = null;
  }, []);

  const updateSpreadOverlay = useCallback(() => {
    const container = containerRef.current;
    const chart = chartRef.current;
    const bidSeries = bidLineSeriesRef.current;
    const askSeries = askLineSeriesRef.current;
    if (
      mode !== "line" ||
      !container ||
      !chart ||
      !bidSeries ||
      !askSeries ||
      bidLineData.length < 2 ||
      askLineData.length < 2
    ) {
      setSpreadOverlay(null);
      return;
    }

    const bidByTime = new Map(bidLineData.map((point) => [Number(point.time), point.value]));
    const points: SpreadOverlayPoint[] = [];
    for (const askPoint of askLineData) {
      const bidValue = bidByTime.get(Number(askPoint.time));
      const x = chart.timeScale().timeToCoordinate(askPoint.time);
      const bidY =
        typeof bidValue === "number" ? bidSeries.priceToCoordinate(bidValue) : null;
      const askY = askSeries.priceToCoordinate(askPoint.value);

      if (typeof bidValue !== "number" || x === null || bidY === null || askY === null) {
        continue;
      }

      points.push({ x: Number(x), bidY: Number(bidY), askY: Number(askY) });
    }

    if (points.length < 2) {
      setSpreadOverlay(null);
      return;
    }

    const askPath = points.map((point) => `${point.x.toFixed(2)} ${point.askY.toFixed(2)}`);
    const bidPath = [...points]
      .reverse()
      .map((point) => `${point.x.toFixed(2)} ${point.bidY.toFixed(2)}`);
    setSpreadOverlay({
      width: Math.max(container.clientWidth, 1),
      height: Math.max(container.clientHeight, 1),
      path: `M ${askPath.join(" L ")} L ${bidPath.join(" L ")} Z`
    });
  }, [askLineData, bidLineData, mode]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }

    const t = readChartTheme();
    const chart = createChart(container, {
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: t.text,
        // 字体改 mono — 价格刻度 / 时间轴一律等宽，机构终端感
        fontFamily:
          'ui-monospace, SFMono-Regular, "JetBrains Mono", "Fira Code", "SF Mono", Menlo, Consolas, monospace',
        fontSize: 11,
        // 隐藏 TradingView attribution logo（左下角 T7）— v5+ 支持
        attributionLogo: false
      },
      localization: {
        locale: "zh-CN",
        priceFormatter: (price: BarPrice) => formatPrice(price, priceFormatPrecisionRef.current),
        tickmarksPriceFormatter: (prices: BarPrice[]) =>
          prices.map((p) => formatPrice(p, priceFormatPrecisionRef.current)),
        timeFormatter: (time: unknown) => formatAxisTimestamp(time)
      },
      grid: {
        // 极淡 dotted 网格 — 不抢戏，仅作参考
        vertLines: { color: t.gridStrong, style: LineStyle.Dotted },
        horzLines: { color: t.grid, style: LineStyle.Dotted }
      },
      crosshair: {
        mode: CrosshairMode.Normal,
        // hover 时 cyan 大虚线 + 半透明背景标签
        vertLine: {
          color: t.crosshair,
          width: 1,
          style: LineStyle.LargeDashed,
          labelBackgroundColor: t.bid
        },
        horzLine: {
          color: t.crosshair,
          width: 1,
          style: LineStyle.LargeDashed,
          labelBackgroundColor: t.bid
        }
      },
      rightPriceScale: {
        borderColor: t.axisBorder,
        // 上下各留 15% 余量，避免线贴边
        scaleMargins: { top: 0.12, bottom: 0.12 }
      },
      timeScale: {
        borderColor: t.axisBorder,
        timeVisible: true,
        secondsVisible: true,
        tickMarkFormatter: (time: unknown) => formatAxisTimestamp(time),
        rightOffset: RIGHT_OFFSET_BARS,
        barSpacing: 9
      },
      // 2026-05-28：用户反馈 K 线不能左右拖。lightweight-charts 默认 handleScroll/handleScale
      // 都开但有些 build 下被关，且 touch 设备的 horzTouchDrag 经常被父 scroll 截胡。这里把
      // 4 个 scroll 维度 + 4 个 scale 维度都显式声明 true，确保移动端横向 drag + 桌面 press-drag
      // + 滚轮缩放 + 双指捏合 + 双击重置 都到位。
      handleScroll: {
        mouseWheel: true,
        pressedMouseMove: true,
        horzTouchDrag: true,
        vertTouchDrag: true,
      },
      handleScale: {
        mouseWheel: true,
        pinch: true,
        axisPressedMouseMove: true,
        axisDoubleClickReset: true,
      },
      kineticScroll: {
        touch: true,
        mouse: false,
      },
    });
    // 拖动手势追踪：pointerdown 落在 chart 上即视为交互开始，up/cancel 结束。
    // 数据 effect 据此跳过 scrollToRealTime（修「K 线无法左右拖动」：
    // 拖动期间每条 WS tick 都会重建 candles → effect 把视图拽回实时位 → 拖动量被吃掉）
    const onChartPointerDown = () => {
      isUserInteractingRef.current = true;
    };
    const onChartPointerUp = () => {
      isUserInteractingRef.current = false;
    };
    container.addEventListener("pointerdown", onChartPointerDown);
    window.addEventListener("pointerup", onChartPointerUp);
    window.addEventListener("pointercancel", onChartPointerUp);
    const scheduleSpreadOverlayUpdate = () => {
      requestAnimationFrame(() => updateSpreadOverlayRef.current());
    };
    chart.timeScale().subscribeVisibleLogicalRangeChange(scheduleSpreadOverlayUpdate);
    chart.subscribeCrosshairMove((param) => {
      if (!param.time || !param.point) {
        setHoverInfo(null);
        return;
      }

      if (
        param.point.x < 0 ||
        param.point.y < 0 ||
        param.point.x > container.clientWidth ||
        param.point.y > container.clientHeight
      ) {
        setHoverInfo(null);
        return;
      }

      if (modeRef.current === "candlestick" && candlestickSeriesRef.current) {
        const data = param.seriesData.get(candlestickSeriesRef.current);
        if (isCandlestickDatum(data)) {
          setHoverInfo(toCandlestickHoverInfo(data));
          return;
        }
      }

      const bidData = bidLineSeriesRef.current
        ? param.seriesData.get(bidLineSeriesRef.current)
        : undefined;
      const askData = askLineSeriesRef.current
        ? param.seriesData.get(askLineSeriesRef.current)
        : undefined;
      const lineHoverInfo = toQuoteLineHoverInfo(bidData, askData);
      if (lineHoverInfo) {
        setHoverInfo(lineHoverInfo);
        return;
      }

      setHoverInfo(null);
    });
    const resizeObserver = new ResizeObserver(([entry]) => {
      if (!entry) {
        return;
      }
      chart.resize(
        Math.max(Math.floor(entry.contentRect.width), 1),
        Math.max(Math.floor(entry.contentRect.height), 1)
      );
      requestAnimationFrame(() => updateSpreadOverlayRef.current());
    });

    resizeObserver.observe(container);
    chartRef.current = chart;
    // STAGE-12 DEBUG：暴露到 window 方便 e2e 验证拖动 / API。生产环境无副作用。
    (window as unknown as { __fxChart?: unknown }).__fxChart = chart;

    // 2026-05-28：监听全局主题切换，重新拉 CSS 变量值热应用到 chart options 与各 series。
    // 不重建 chart 实例（保 visible range / zoom / hover state），只 patch 颜色配置。
    const onThemeChange = () => {
      const c = chartRef.current;
      if (!c) return;
      const nt = readChartTheme();
      c.applyOptions({
        layout: { textColor: nt.text },
        grid: {
          vertLines: { color: nt.gridStrong, style: LineStyle.Dotted },
          horzLines: { color: nt.grid, style: LineStyle.Dotted },
        },
        crosshair: {
          vertLine: { color: nt.crosshair, labelBackgroundColor: nt.bid },
          horzLine: { color: nt.crosshair, labelBackgroundColor: nt.bid },
        },
        rightPriceScale: { borderColor: nt.axisBorder },
        timeScale: { borderColor: nt.axisBorder },
      });
      candlestickSeriesRef.current?.applyOptions({
        upColor: nt.candleUp,
        downColor: nt.candleDown,
        wickUpColor: nt.candleUp,
        wickDownColor: nt.candleDown,
      });
      bidLineSeriesRef.current?.applyOptions({
        lineColor: nt.bid,
        topColor: withAlpha(nt.bid, 0.08),
        bottomColor: withAlpha(nt.bid, 0),
        priceLineColor: withAlpha(nt.bid, 0.55),
        crosshairMarkerBackgroundColor: nt.bid,
      });
      askLineSeriesRef.current?.applyOptions({
        lineColor: nt.ask,
        topColor: withAlpha(nt.ask, 0.08),
        bottomColor: withAlpha(nt.ask, 0),
        priceLineColor: withAlpha(nt.ask, 0.55),
        crosshairMarkerBackgroundColor: nt.ask,
      });
      // priceLines (MID/BID/ASK 横线) 由下方 useEffect [currentQuotePrices/showBidAsk] 重建时
      // 自动读最新主题 —— 这里 dispatch 一个 noop state update 触发那个 effect。
      window.dispatchEvent(new CustomEvent("falconx:chart:redraw-price-lines"));
    };
    window.addEventListener("falconx:theme:changed", onThemeChange);

    return () => {
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(scheduleSpreadOverlayUpdate);
      window.removeEventListener("falconx:theme:changed", onThemeChange);
      container.removeEventListener("pointerdown", onChartPointerDown);
      window.removeEventListener("pointerup", onChartPointerUp);
      window.removeEventListener("pointercancel", onChartPointerUp);
      isUserInteractingRef.current = false;
      resizeObserver.disconnect();
      removeCurrentPriceLines();
      chart.remove();
      chartRef.current = null;
      candlestickSeriesRef.current = null;
      bidLineSeriesRef.current = null;
      askLineSeriesRef.current = null;
      bidPriceLineRef.current = null;
      askPriceLineRef.current = null;
      midPriceLineRef.current = null;
      modeRef.current = null;
      resetKeyRef.current = null;
      setHoverInfo(null);
      setSpreadOverlay(null);
    };
  }, [removeCurrentPriceLines]);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || modeRef.current === mode) {
      return;
    }

    if (candlestickSeriesRef.current) {
      removeCurrentPriceLines();
      chart.removeSeries(candlestickSeriesRef.current);
      candlestickSeriesRef.current = null;
    }
    if (bidLineSeriesRef.current) {
      chart.removeSeries(bidLineSeriesRef.current);
      bidLineSeriesRef.current = null;
    }
    if (askLineSeriesRef.current) {
      chart.removeSeries(askLineSeriesRef.current);
      askLineSeriesRef.current = null;
    }

    const t2 = readChartTheme();
    if (mode === "candlestick") {
      candlestickSeriesRef.current = chart.addSeries(CandlestickSeries, {
        upColor: t2.candleUp,
        downColor: t2.candleDown,
        borderVisible: false,
        wickUpColor: t2.candleUp,
        wickDownColor: t2.candleDown,
        // candle 自带 hover legend（顶部 OHLC overlay），右轴的最后价位标签由我们手工
        // 用 MID priceLine 显示，所以这里禁用，避免出现重复的"最新价"
        lastValueVisible: false,
        priceLineVisible: false,
        priceFormat,
        // STAGE-12 fix：把 mid/bid/ask 手动 priceLine 价位纳入 autoScale 范围，
        // 否则 markup 大时（XAUUSD ±10）这些横线会被 chart 区间裁掉。
        autoscaleInfoProvider: makeAutoscaleProvider(currentQuotePricesRef)
      });
    } else {
      // 2026-05-28 Tick 模式美化（七轮）：
      //   - 线宽 2 → 1（lightweight-charts 的 lineWidth 是离散值；1 是最细可读，配 Curved 抗锯齿够顺）
      //   - 面积渐变顶部不透明度 0.22 → 0.08：让填色像「线下气晕」而不是亮色光柱
      //   - crosshair marker 5 → 3.5：hover 圆点不再喧宾夺主
      //   - priceLine 由 Dotted 改 SparseDotted：右轴 lastValue 横线密度更轻
      //   - 颜色保持 BID cyan #54e6ff / ASK pink #ff8ca1 不变（与桌面端 §02 标签 / hover legend 同源），
      //     重点是让"墨水量"下来，不动调色板
      bidLineSeriesRef.current = chart.addSeries(AreaSeries, {
        lineColor: t2.bid,
        topColor: withAlpha(t2.bid, 0.08),
        bottomColor: withAlpha(t2.bid, 0),
        lineWidth: 1,
        lineType: LineType.Curved,
        lastValueVisible: true,
        priceLineVisible: true,
        priceLineColor: withAlpha(t2.bid, 0.55),
        priceLineStyle: LineStyle.SparseDotted,
        priceLineWidth: 1,
        crosshairMarkerVisible: true,
        crosshairMarkerRadius: 3.5,
        crosshairMarkerBorderColor: "rgba(5, 6, 8, 1)",
        crosshairMarkerBorderWidth: 1.5,
        crosshairMarkerBackgroundColor: t2.bid,
        title: "Bid",
        priceFormat,
        autoscaleInfoProvider: makeAutoscaleProvider(currentQuotePricesRef)
      });
      askLineSeriesRef.current = chart.addSeries(AreaSeries, {
        lineColor: t2.ask,
        topColor: withAlpha(t2.ask, 0.08),
        bottomColor: withAlpha(t2.ask, 0),
        lineWidth: 1,
        lineType: LineType.Curved,
        lastValueVisible: true,
        priceLineVisible: true,
        priceLineColor: withAlpha(t2.ask, 0.55),
        priceLineStyle: LineStyle.SparseDotted,
        priceLineWidth: 1,
        crosshairMarkerVisible: true,
        crosshairMarkerRadius: 3.5,
        crosshairMarkerBorderColor: "rgba(5, 6, 8, 1)",
        crosshairMarkerBorderWidth: 1.5,
        crosshairMarkerBackgroundColor: t2.ask,
        title: "Ask",
        priceFormat,
        autoscaleInfoProvider: makeAutoscaleProvider(currentQuotePricesRef)
      });
    }

    modeRef.current = mode;
    resetKeyRef.current = null;
  }, [mode, priceFormat, removeCurrentPriceLines]);

  useEffect(() => {
    candlestickSeriesRef.current?.applyOptions({ priceFormat });
    bidLineSeriesRef.current?.applyOptions({ priceFormat });
    askLineSeriesRef.current?.applyOptions({ priceFormat });
  }, [priceFormat]);

  useEffect(() => {
    const series = candlestickSeriesRef.current;
    if (mode !== "candlestick" || !series) {
      removeCurrentPriceLines();
      return;
    }

    // 三线 (BID/ASK/MID) 颜色全部走 CSS 变量；dark/light 切换时读 :root 当前值。
    const tLines = readChartTheme();
    midPriceLineRef.current = syncPriceLine(series, midPriceLineRef.current, {
      price: currentQuotePrices.mid,
      title: "MID",
      color: tLines.mid
    });

    if (showBidAsk) {
      bidPriceLineRef.current = syncPriceLine(series, bidPriceLineRef.current, {
        price: currentQuotePrices.bid,
        title: "Bid",
        color: tLines.bid
      });
      askPriceLineRef.current = syncPriceLine(series, askPriceLineRef.current, {
        price: currentQuotePrices.ask,
        title: "Ask",
        color: tLines.ask
      });
    } else {
      if (bidPriceLineRef.current) {
        series.removePriceLine(bidPriceLineRef.current);
        bidPriceLineRef.current = null;
      }
      if (askPriceLineRef.current) {
        series.removePriceLine(askPriceLineRef.current);
        askPriceLineRef.current = null;
      }
    }
  }, [currentQuotePrices, mode, removeCurrentPriceLines, showBidAsk]);

  useEffect(() => {
    updateSpreadOverlayRef.current = updateSpreadOverlay;
    requestAnimationFrame(updateSpreadOverlay);
  }, [updateSpreadOverlay]);

  useEffect(() => {
    if (mode === "candlestick") {
      candlestickSeriesRef.current?.setData(candles);
    } else {
      bidLineSeriesRef.current?.setData(bidLineData);
      askLineSeriesRef.current?.setData(askLineData);
    }
    // STAGE-12 fix：setData 之后强制 priceScale 重新 autoScale，避免
    //  - 用户曾在价格轴上拖动 → autoScale 被 disable → K 线急跌时底部被裁
    //  - candle 新数据已写入但旧 priceRange 还没更新
    candlestickSeriesRef.current?.priceScale().applyOptions({ autoScale: true });
    bidLineSeriesRef.current?.priceScale().applyOptions({ autoScale: true });
    askLineSeriesRef.current?.priceScale().applyOptions({ autoScale: true });

    const dataLength = Math.max(
      mode === "candlestick" ? candles.length : 0,
      mode === "line" ? bidLineData.length : 0,
      mode === "line" ? askLineData.length : 0
    );
    const hasData = dataLength > 0;
    if (!hasData) {
      return;
    }

    const timeScale = chartRef.current?.timeScale();
    // 三种情况触发"重置可视范围"，否则保留用户当前的缩放/平移：
    // 1) symbol/timeframe 切换（resetKey 变） —— 经典首次定位
    // 2) 当前可视范围明显比 INITIAL_VISIBLE_BARS 窄（如 HMR 后 chart resize 残留小 range，candle 只剩 2-3 根）
    // 3) 数据刚刚从极少（< RIGHT_OFFSET_BARS）涨到大量（≥ INITIAL_VISIBLE_BARS） —— 历史数据延迟到达
    const visible = timeScale?.getVisibleLogicalRange();
    const visibleWidth = visible ? Math.abs(visible.to - visible.from) : 0;
    const needsResize =
      resetKeyRef.current !== resetKey ||
      (dataLength >= INITIAL_VISIBLE_BARS && visibleWidth > 0 && visibleWidth < INITIAL_VISIBLE_BARS / 2);

    if (needsResize) {
      timeScale?.setVisibleLogicalRange(resolveInitialVisibleLogicalRange(dataLength));
      resetKeyRef.current = resetKey;
      requestAnimationFrame(() => updateSpreadOverlayRef.current());
      return;
    }

    // STAGE-12 fix → 2026-06-03 二修：之前每次新数据都强制 scrollToRealTime → 用户拖到左边
    // 看历史 K 线时立刻被拽回最右，等同"无法看历史"。
    // 二修两点（修「K 线无法左右拖动」）：
    // 1) 拖动手势进行中（isUserInteractingRef）绝不自动滚动 —— 高频 tick 会在拖动途中
    //    反复把视图拽回实时位，慢速拖动的位移被逐段吃掉，表现为完全拖不动；
    // 2) 跟随判定收紧到「右沿仍贴实时位（差 ≤ RIGHT_OFFSET_BARS/2 根）」—— 旧阈值
    //    distanceFromEnd<=3 把"已拖离 ≤11 根"的小幅拖动也吸回，松手即弹回。
    //    实时跟随态 to = 末根索引 + rightOffset(8)，distanceFromEnd = -8。
    if (visible && !isUserInteractingRef.current) {
      const distanceFromEnd = dataLength - 1 - visible.to;
      const isFollowingRealtime = distanceFromEnd <= -(RIGHT_OFFSET_BARS / 2);
      if (isFollowingRealtime) {
        timeScale?.scrollToRealTime();
      }
    }
    requestAnimationFrame(() => updateSpreadOverlayRef.current());
  }, [askLineData, bidLineData, candles, mode, resetKey]);

  // === 技术指标同步 ===
  // 监听 (candles, 激活的主图/副图指标列表, mode) 变化，做 series 增删 + 数据 setData。
  // tick 线模式 (mode='line') 没有 OHLC，所有指标停掉；切回 candlestick 再恢复。
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    // 检测 chart 实例换了（StrictMode 双 mount / hot reload）→ map 全部作废
    if (lastChartRef.current !== chart) {
      indicatorSeriesRef.current.clear();
      subPaneRef.current.clear();
      lastChartRef.current = chart;
    }
    const seriesMap = indicatorSeriesRef.current;
    const paneMap = subPaneRef.current;

    // tick mode 下移除所有指标
    if (mode !== "candlestick") {
      seriesMap.forEach((arr) => arr.forEach((s) => { try { chart.removeSeries(s); } catch { /* series 可能已随 pane 销毁 */ } }));
      seriesMap.clear();
      // 移除所有副图 pane（保留 pane 0 主图）
      paneMap.forEach((idx) => {
        try {
          if (idx > 0) chart.removePane(idx);
        } catch { /* pane 可能已被移除 */ }
      });
      paneMap.clear();
      return;
    }

    // 转换 candles 成 IndicatorBar 输入
    const bars: IndicatorBar[] = candles.map((c) => ({
      time: c.time,
      open: c.open as number,
      high: c.high as number,
      low: c.low as number,
      close: c.close as number,
    }));

    // 色板（FalconX cyan/lime/pink/amber/violet 五色循环用于多线指标）
    const tCol = readChartTheme();
    const palette = {
      cyan: tCol.bid,                       // #54e6ff dark / #0891b2 light
      pink: tCol.ask,                       // #ff8ca1 / #dc2626
      amber: tCol.mid,                      // #facc15 / #d97706
      lime: tCol.candleUp,                  // #a8ff5c / #16a34a
      red: tCol.candleDown,                 // #ff6378 / #dc2626
      violet: "#7c3aed",
    };

    // --- 1) 移除已取消的指标 series ---
    const active = new Set<string>([
      ...activeMainIndicators.map((k) => `main:${k}`),
      ...activeSubIndicators.map((k) => `sub:${k}`),
    ]);
    // 主图（pane 0）的 series 可以独立删除 —— 它们都在同一个 pane，不存在 paneIndex 重排问题
    for (const [key, arr] of seriesMap) {
      if (key.startsWith("main:") && !active.has(key)) {
        arr.forEach((s) => { try { chart.removeSeries(s); } catch { /* series 可能已随 pane 销毁 */ } });
        seriesMap.delete(key);
      }
    }

    // 副图（每个一个 pane）：lightweight-charts 在 removePane(i) 后会把所有更高 idx 的 pane
    // **向上重排**（i+1 变 i, i+2 变 i+1 ...），但我们缓存的 series ref 仍指着原本的 pane，
    // 导致 setData 静默无效。安全策略：只要副图有任何减项，原子地 **清空所有副图 series + pane**，
    // 然后让下方重建步骤按当前活动列表从 pane 1 重新铺。CPU 开销可忽略：每次切换才触发，不是每帧。
    const prevSubKeys = [...paneMap.keys()];
    const hasSubRemoval = prevSubKeys.some((k) => !activeSubIndicators.includes(k));
    if (hasSubRemoval) {
      for (const [key, arr] of seriesMap) {
        if (key.startsWith("sub:")) {
          arr.forEach((s) => { try { chart.removeSeries(s); } catch { /* series 可能已随 pane 销毁 */ } });
          seriesMap.delete(key);
        }
      }
      // 倒序删 pane 防止索引漂移
      const panes = chart.panes();
      for (let i = panes.length - 1; i >= 1; i--) {
        try { chart.removePane(i); } catch { /* pane 可能已被移除 */ }
      }
      paneMap.clear();
    }

    // --- 2) 主图叠加：MA / EMA / BOLL / SAR / ZIG ---
    const ensureSeries = (key: string, build: () => ISeriesApi<"Line" | "Histogram">[]) => {
      let arr = seriesMap.get(key);
      if (!arr) {
        arr = build();
        seriesMap.set(key, arr);
      }
      return arr;
    };

    if (activeMainIndicators.includes("MA")) {
      const arr = ensureSeries("main:MA", () => [
        chart.addSeries(LineSeries, { color: palette.cyan, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "MA5" }, 0),
        chart.addSeries(LineSeries, { color: palette.lime, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "MA10" }, 0),
        chart.addSeries(LineSeries, { color: palette.violet, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "MA30" }, 0),
      ]);
      arr[0].setData(sma(bars, 5));
      arr[1].setData(sma(bars, 10));
      arr[2].setData(sma(bars, 30));
    }
    if (activeMainIndicators.includes("EMA")) {
      const arr = ensureSeries("main:EMA", () => [
        chart.addSeries(LineSeries, { color: palette.cyan, lineWidth: 1, lineStyle: LineStyle.Dashed, priceLineVisible: false, lastValueVisible: true, title: "EMA12" }, 0),
        chart.addSeries(LineSeries, { color: palette.pink, lineWidth: 1, lineStyle: LineStyle.Dashed, priceLineVisible: false, lastValueVisible: true, title: "EMA26" }, 0),
      ]);
      arr[0].setData(ema(bars, 12));
      arr[1].setData(ema(bars, 26));
    }
    if (activeMainIndicators.includes("BOLL")) {
      const arr = ensureSeries("main:BOLL", () => [
        chart.addSeries(LineSeries, { color: palette.violet, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "BOLL UP" }, 0),
        chart.addSeries(LineSeries, { color: palette.amber, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "BOLL MID" }, 0),
        chart.addSeries(LineSeries, { color: palette.violet, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "BOLL LOW" }, 0),
      ]);
      const b = boll(bars, 20, 2);
      arr[0].setData(b.upper);
      arr[1].setData(b.mid);
      arr[2].setData(b.lower);
    }
    if (activeMainIndicators.includes("SAR")) {
      const arr = ensureSeries("main:SAR", () => [
        // SAR 是点位，用 Line + dotted 风格表达
        chart.addSeries(LineSeries, {
          color: palette.amber,
          lineWidth: 1,
          lineStyle: LineStyle.Dotted,
          pointMarkersVisible: true,
          pointMarkersRadius: 2,
          priceLineVisible: false,
          lastValueVisible: true,
          title: "SAR",
        }, 0),
      ]);
      arr[0].setData(sar(bars));
    }
    if (activeMainIndicators.includes("ZIG")) {
      const arr = ensureSeries("main:ZIG", () => [
        chart.addSeries(LineSeries, { color: palette.cyan, lineWidth: 2, priceLineVisible: false, lastValueVisible: true, title: "ZIG" }, 0),
      ]);
      arr[0].setData(zigzag(bars, 0.05));
    }

    // --- 3) 副图 panes：VOL / MACD / RSI / KDJ / WR ---
    // pane index 分配：每 subInd 占一个 pane，按 active 顺序紧凑从 1 开始
    const subOrder: SubIndicator[] = ["VOL", "MACD", "RSI", "KDJ", "WR"].filter((k) =>
      activeSubIndicators.includes(k as SubIndicator),
    ) as SubIndicator[];

    subOrder.forEach((key, i) => {
      const paneIdx = i + 1;
      paneMap.set(key, paneIdx);
      const refKey = `sub:${key}`;
      if (key === "VOL") {
        const arr = ensureSeries(refKey, () => [
          chart.addSeries(HistogramSeries, {
            priceFormat: { type: "volume" },
            priceLineVisible: false,
            lastValueVisible: true,
            title: "VOL",
          }, paneIdx),
        ]);
        arr[0].setData(vol(bars, palette.lime, palette.red));
      } else if (key === "MACD") {
        const arr = ensureSeries(refKey, () => [
          chart.addSeries(HistogramSeries, { priceLineVisible: false, lastValueVisible: true, title: "HIST" }, paneIdx),
          chart.addSeries(LineSeries, { color: palette.cyan, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "DIF" }, paneIdx),
          chart.addSeries(LineSeries, { color: palette.amber, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "DEA" }, paneIdx),
        ]);
        const m = macd(bars, 12, 26, 9, palette.lime, palette.red);
        arr[0].setData(m.hist);
        arr[1].setData(m.dif);
        arr[2].setData(m.dea);
      } else if (key === "RSI") {
        const arr = ensureSeries(refKey, () => [
          chart.addSeries(LineSeries, { color: palette.cyan, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "RSI14" }, paneIdx),
        ]);
        arr[0].setData(rsi(bars, 14));
      } else if (key === "KDJ") {
        const arr = ensureSeries(refKey, () => [
          chart.addSeries(LineSeries, { color: palette.cyan, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "K" }, paneIdx),
          chart.addSeries(LineSeries, { color: palette.pink, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "D" }, paneIdx),
          chart.addSeries(LineSeries, { color: palette.amber, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "J" }, paneIdx),
        ]);
        const k = kdj(bars, 9, 3, 3);
        arr[0].setData(k.k);
        arr[1].setData(k.d);
        arr[2].setData(k.j);
      } else if (key === "WR") {
        const arr = ensureSeries(refKey, () => [
          chart.addSeries(LineSeries, { color: palette.violet, lineWidth: 1, priceLineVisible: false, lastValueVisible: true, title: "WR14" }, paneIdx),
        ]);
        arr[0].setData(wr(bars, 14));
      }
    });
  }, [candles, activeMainIndicators, activeSubIndicators, mode]);

  const hasQuoteLineData = bidLineData.length > 0 || askLineData.length > 0;
  const hasVisibleData =
    mode === "line" ? hasQuoteLineData : candles.length > 0;
  return (
    <div className="market-chart__tradingview-shell">
      <div ref={containerRef} className="market-chart__tradingview" />
      {spreadOverlay ? (
        <svg
          className="market-chart__spread-band"
          width={spreadOverlay.width}
          height={spreadOverlay.height}
          viewBox={`0 0 ${spreadOverlay.width} ${spreadOverlay.height}`}
          aria-hidden="true"
        >
          <defs>
            {/* 2026-05-28 美化：spread band 不透明度大幅下调（0.18/0.12 → 0.07/0.04），
              * 让 bid/ask 两条 1px 线之间的色带像"薄雾"而不是亮带，整体观感细腻得多 */}
            <linearGradient id="fx-spread-band-grad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#ff8ca1" stopOpacity="0.07" />
              <stop offset="50%" stopColor="#b6a4ff" stopOpacity="0.04" />
              <stop offset="100%" stopColor="#54e6ff" stopOpacity="0.07" />
            </linearGradient>
          </defs>
          <path d={spreadOverlay.path} fill="url(#fx-spread-band-grad)" />
        </svg>
      ) : null}
      {/* 2026-05-28 移除右上 BID/ASK 色标 —— 价格右轴 / hover legend / market-chart__metrics
        * 已经把 bid/ask 数值表达清楚，色标重复且与 MID 标签争抢顶部视觉。
        * （原条件保留逻辑、注释掉渲染，方便后续真要时一行恢复） */}
      <ChartHoverLegend info={hasVisibleData ? hoverInfo : null} />
    </div>
  );
}

type ChartHoverInfo =
  | {
      mode: "candlestick";
      time: string;
      open: string;
      high: string;
      low: string;
      close: string;
      change: string;
      direction: "up" | "down" | "flat";
    }
  | {
      mode: "line";
      time: string;
      bid?: string;
      ask?: string;
    };

type QuotePriceLines = {
  bid: number | undefined;
  ask: number | undefined;
  mid: number | undefined;
};

type SpreadOverlay = {
  path: string;
  width: number;
  height: number;
};

type SpreadOverlayPoint = {
  x: number;
  bidY: number;
  askY: number;
};

function ChartHoverLegend({ info }: { info: ChartHoverInfo | null }) {
  if (!info) {
    return null;
  }

  if (info.mode === "line") {
    return (
      <div className="market-chart__hover-legend" aria-live="polite">
        <span>{info.time}</span>
        <div className="market-chart__hover-grid">
          {info.bid ? <b className="bid">Bid {info.bid}</b> : null}
          {info.ask ? <b className="ask">Ask {info.ask}</b> : null}
        </div>
      </div>
    );
  }

  return (
    <div className="market-chart__hover-legend" aria-live="polite">
      <span>{info.time}</span>
      <div className="market-chart__hover-grid">
        <b>O {info.open}</b>
        <b>H {info.high}</b>
        <b>L {info.low}</b>
        <b>C {info.close}</b>
        <b className={info.direction}>Δ {info.change}</b>
      </div>
    </div>
  );
}

function toCandlestickHoverInfo(candle: CandlestickData): ChartHoverInfo {
  const change = candle.close - candle.open;
  return {
    mode: "candlestick",
    time: formatHoverTimestamp(candle.time),
    open: formatPrice(candle.open),
    high: formatPrice(candle.high),
    low: formatPrice(candle.low),
    close: formatPrice(candle.close),
    change: `${change > 0 ? "+" : change < 0 ? "-" : ""}${formatPrice(Math.abs(change))}`,
    direction: change > 0 ? "up" : change < 0 ? "down" : "flat"
  };
}

/**
 * STAGE-12 fix：让 createPriceLine() 的横线价位被 priceScale autoScale 计入。
 * 否则 markup 大的品种（如 XAUUSD 组级 ±10），Bid/Mid 横线会因为低于 chart 区间被裁掉。
 *
 * Lightweight-Charts v5 的 SeriesOptions.autoscaleInfoProvider 是 series 计算可视区间时
 * 的钩子。我们 wrap 默认 priceRange，把 quote.bid/ask/mid 也并进 min/max。
 */
function makeAutoscaleProvider(pricesRef: { current: QuotePriceLines }) {
  return (
    original: () => { priceRange: { minValue: number; maxValue: number }; margins?: { above: number; below: number } } | null
  ) => {
    const defaults = original();
    if (!defaults) {
      return null;
    }
    const p = pricesRef.current;
    const extras = [p.bid, p.ask, p.mid].filter(
      (v): v is number => typeof v === "number" && Number.isFinite(v)
    );
    if (extras.length === 0) {
      return defaults;
    }
    return {
      priceRange: {
        minValue: Math.min(defaults.priceRange.minValue, ...extras),
        maxValue: Math.max(defaults.priceRange.maxValue, ...extras)
      },
      margins: defaults.margins
    };
  };
}

function syncPriceLine(
  series: ISeriesApi<"Candlestick">,
  line: IPriceLine | null,
  {
    price,
    title,
    color
  }: {
    price: number | undefined;
    title: string;
    color: string;
  }
): IPriceLine | null {
  if (price === undefined) {
    if (line) {
      series.removePriceLine(line);
    }
    return null;
  }

  const options = {
    price,
    color,
    lineWidth: 1,
    lineStyle: LineStyle.Dotted,
    lineVisible: true,
    axisLabelVisible: true,
    axisLabelColor: color,
    axisLabelTextColor: title === "Ask" ? "#250710" : "#041014",
    title
  } as const;

  if (line) {
    line.applyOptions(options);
    return line;
  }

  return series.createPriceLine(options);
}

function toQuoteLineHoverInfo(
  bidData: unknown,
  askData: unknown
): ChartHoverInfo | null {
  const bid = isLineDatum(bidData) ? bidData : null;
  const ask = isLineDatum(askData) ? askData : null;

  if (!bid && !ask) {
    return null;
  }

  return {
    mode: "line",
    time: formatHoverTimestamp((bid ?? ask)?.time),
    bid: bid ? formatPrice(bid.value) : undefined,
    ask: ask ? formatPrice(ask.value) : undefined
  };
}

function isCandlestickDatum(value: unknown): value is CandlestickData {
  if (!value || typeof value !== "object") {
    return false;
  }

  const candidate = value as Partial<CandlestickData>;
  return [candidate.open, candidate.high, candidate.low, candidate.close].every(
    (part) => typeof part === "number" && Number.isFinite(part)
  );
}

function isLineDatum(value: unknown): value is LineData {
  if (!value || typeof value !== "object") {
    return false;
  }

  const candidate = value as Partial<LineData>;
  return typeof candidate.value === "number" && Number.isFinite(candidate.value);
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function connectionLabel(state: MarketConnectionState): string {
  const labels: Record<MarketConnectionState, string> = {
    idle: "等待订阅",
    connecting: "连接行情",
    open: "WebSocket 实时",
    reconnecting: "行情重连中",
    closed: "连接已关闭",
    auth_expired: "登录已过期",
    error: "行情异常"
  };

  return labels[state];
}

function chartLabel(state: MarketConnectionState, status: string): string {
  if (status === "REFERENCE") {
    return "参考行情";
  }

  return connectionLabel(state);
}

function chartTitle(mode: ChartMode): string {
  return mode === "line" ? "Tick 分时" : "经典 K线";
}

function formatAxisTimestamp(time: unknown): string {
  const timestamp = toChartDate(time);
  if (!timestamp) {
    return String(time);
  }

  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(timestamp);
}

function formatHoverTimestamp(time: unknown): string {
  const timestamp = toChartDate(time);
  if (!timestamp) {
    return String(time);
  }

  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(timestamp);
}

function toChartDate(time: unknown): Date | null {
  const timestamp =
    typeof time === "number"
      ? new Date(time * 1000)
      : typeof time === "string"
        ? new Date(time)
        : null;

  if (!timestamp || Number.isNaN(timestamp.getTime())) {
    return null;
  }

  return timestamp;
}

function createChartPriceFormat(precision: number): PriceFormat {
  return {
    type: "custom",
    minMove: 10 ** -precision,
    formatter: formatPrice,
    tickmarksFormatter: (prices: BarPrice[]) => prices.map(formatPrice)
  };
}

/**
 * STAGE-12: 行情大字 / Bid / Ask / Mid 一律按 symbol.pricePrecision 渲染
 * （t_symbol_quote_mapping.price_precision 单一真源 — XAUUSD 3、FX 主流 5、加密 2、
 * 股票 2 等都由后端 DB 定义，前端无需推断）。
 *
 * 之前 Math.max(bid/ask/mid 字段实际 scale) 会被后端 BigDecimal trailing 0 干扰
 * （如 bid "0.981960" 6 位 vs mid "0.98198" 5 位 → 大字混乱）。
 *
 * symbol.pricePrecision 缺失（极端 edge case，DB NOT NULL）时降级：
 * 从 quote.mid 字段实际 scale 推断（mid 后端用 RoundingMode.DOWN scale=8 算，
 * 一般和 symbol precision 对齐）。再降级才用 2。
 */
function resolveDisplayPrecision(
  symbol: MarketSymbol | undefined,
  quote: Quote | undefined
): number {
  if (typeof symbol?.pricePrecision === "number" && Number.isFinite(symbol.pricePrecision)) {
    return clampPrecision(symbol.pricePrecision);
  }
  // 极端 edge case: 从 quote.mid 单一字段推断（不再 Math.max 多字段混合）
  const midPrecision = decimalPlacesFromFormattedPrice(quote?.mid ?? symbol?.mid);
  if (midPrecision !== null) {
    return clampPrecision(midPrecision);
  }
  return 2;
}

function decimalPlacesFromFormattedPrice(value: string | number | null | undefined): number | null {
  const formatted = formatPrice(value);
  if (formatted === "--") {
    return null;
  }

  const decimal = formatted.split(".")[1];
  return decimal ? decimal.length : 0;
}

function clampPrecision(precision: number): number {
  return Math.min(Math.max(Math.trunc(precision), 0), 10);
}

function toFinitePrice(value: string | number | null | undefined): number | undefined {
  const price = Number(value);
  return Number.isFinite(price) ? price : undefined;
}
