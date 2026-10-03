import { useEffect, useRef, useState } from "react";
import type { KlineInterval, MarketSymbol } from "../marketTypes";
import { createTvDatafeed } from "./tvDatafeed";
import { intervalToResolution, resolutionToInterval } from "./tvResolution";
import type {
  ChartingLibraryWidgetOptions,
  IChartingLibraryWidget,
  LanguageCode,
  ResolutionString,
  ThemeName
} from "./charting_library";

declare global {
  interface Window {
    /** charting_library.standalone.js 加载后挂载的全局命名空间 */
    TradingView?: {
      widget: new (options: ChartingLibraryWidgetOptions) => IChartingLibraryWidget;
    };
  }
}

type TvAdvancedChartProps = {
  /** 当前选中 symbol（必须已在 symbols 列表内）。 */
  symbol: string;
  /** 当前 symbol 元数据（精度/币对），驱动 resolveSymbol。 */
  symbolMeta: MarketSymbol | undefined;
  /** 终端当前 K 线周期（tick 已由父组件归一为 1m）。 */
  interval: KlineInterval;
  /**
   * TV 内部切换周期时回传终端，让 useMarketSocket 跟随订阅对应 kline.{interval}
   * 频道 —— datafeed 的实时推送依赖这条同步链路。
   */
  onIntervalChange: (interval: KlineInterval) => void;
};

/** 静态资源路径：library 整包置于 public/charting_library（iframe 自加载 bundles）。 */
const LIBRARY_PATH = "/charting_library/";
const SCRIPT_SRC = `${LIBRARY_PATH}charting_library.standalone.js`;

let libraryLoader: Promise<void> | null = null;

/** 按需注入 standalone bundle（~?MB），整个应用只加载一次；失败后允许重试。 */
function loadChartingLibrary(): Promise<void> {
  if (typeof window !== "undefined" && window.TradingView?.widget) {
    return Promise.resolve();
  }
  if (!libraryLoader) {
    libraryLoader = new Promise<void>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = SCRIPT_SRC;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => {
        libraryLoader = null;
        script.remove();
        reject(new Error("charting_library 脚本加载失败"));
      };
      document.head.appendChild(script);
    });
  }
  return libraryLoader;
}

/** 当前项目主题（themeStore 写在 <html data-theme>，缺省按 dark）→ TV ThemeName。 */
function resolveTvTheme(): ThemeName {
  return typeof document !== "undefined" &&
    document.documentElement.getAttribute("data-theme") === "light"
    ? "light"
    : "dark";
}

/** 从 :root CSS variables 读 TV 主题色（与经典图表 readChartTheme 同源 token，随 data-theme 翻转）。 */
function readTvTheme() {
  const cs = typeof window !== "undefined" ? getComputedStyle(document.documentElement) : null;
  const get = (name: string, fallback: string) => {
    const value = cs?.getPropertyValue(name).trim();
    return value && value.length > 0 ? value : fallback;
  };
  const dark = resolveTvTheme() === "dark";
  return {
    background: get("--fx-surface-1", dark ? "#0b0e12" : "#fbfaf7"),
    candleUp: get("--fx-chart-candle-up", dark ? "#a8ff5c" : "#16a34a"),
    candleDown: get("--fx-chart-candle-down", dark ? "#ff6378" : "#dc2626"),
    grid: get("--fx-chart-grid", dark ? "rgba(255, 255, 255, 0.05)" : "rgba(0, 0, 0, 0.06)")
  };
}

/** TV overrides：蜡烛/背景贴项目 token。changeTheme 会重置 overrides，热切换后必须重新 apply。 */
function buildTvOverrides() {
  const theme = readTvTheme();
  return {
    "paneProperties.background": theme.background,
    "paneProperties.backgroundType": "solid" as const,
    "mainSeriesProperties.candleStyle.upColor": theme.candleUp,
    "mainSeriesProperties.candleStyle.downColor": theme.candleDown,
    "mainSeriesProperties.candleStyle.borderUpColor": theme.candleUp,
    "mainSeriesProperties.candleStyle.borderDownColor": theme.candleDown,
    "mainSeriesProperties.candleStyle.wickUpColor": theme.candleUp,
    "mainSeriesProperties.candleStyle.wickDownColor": theme.candleDown
  };
}

/**
 * TradingView 高级图表（Advanced Charts 31.2.0）容器组件。
 *
 * 与经典 lightweight-charts K 线并列，由 MarketChart 的图表引擎 Tab 切换：
 * - 数据走 {@link createTvDatafeed}（REST 历史 + 终端 WS 实时，组加点同口径）；
 * - 符号搜索关闭，切品种沿用终端 watchlist（setSymbol 跟随 props）；
 * - TV 工具栏切周期 → onIntervalChange 回传终端（保持 WS kline 频道同步）。
 */
export function TvAdvancedChart({
  symbol,
  symbolMeta,
  interval,
  onIntervalChange
}: TvAdvancedChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const widgetRef = useRef<IChartingLibraryWidget | null>(null);
  const [chartReady, setChartReady] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // resolveSymbol / 回调读 ref 而非闭包快照，避免 widget 重建（effect 内同步，满足 react-hooks/refs）
  const symbolMetaRef = useRef(symbolMeta);
  useEffect(() => {
    symbolMetaRef.current = symbolMeta;
  }, [symbolMeta]);
  const onIntervalChangeRef = useRef(onIntervalChange);
  useEffect(() => {
    onIntervalChangeRef.current = onIntervalChange;
  }, [onIntervalChange]);
  // widget 仅在挂载时创建一次，初始 symbol/interval 取 useRef 初值即可；
  // 脚本异步加载期间若 props 已变化，由下方 chartReady 增量 effect 纠正。
  const initialSymbolRef = useRef(symbol);
  const initialIntervalRef = useRef(interval);

  // widget 生命周期：挂载创建一次，卸载销毁（symbol/interval 变化走下方增量 effect）
  useEffect(() => {
    let disposed = false;

    loadChartingLibrary()
      .then(() => {
        if (disposed || !containerRef.current || !window.TradingView) {
          return;
        }
        const theme = readTvTheme();
        const widget = new window.TradingView.widget({
          container: containerRef.current,
          library_path: LIBRARY_PATH,
          symbol: initialSymbolRef.current,
          interval: intervalToResolution(initialIntervalRef.current) as ResolutionString,
          datafeed: createTvDatafeed({
            getSymbolMeta: (name) =>
              symbolMetaRef.current?.symbol === name ? symbolMetaRef.current : undefined
          }),
          locale: resolveTvLocale(),
          autosize: true,
          theme: resolveTvTheme(),
          timezone: "Etc/UTC",
          disabled_features: [
            // 符号域闭环在终端 watchlist：关闭 TV 内符号搜索/对比
            "header_symbol_search",
            "symbol_search_hot_key",
            "header_compare",
            // 屏幕截图/弹窗类与终端布局无关
            "header_screenshot",
            "popup_hints",
            // 默认成交量直方图强制叠在主图（与蜡烛重合），关掉后默认 Volume 落独立副图
            "volume_force_overlay"
          ],
          loading_screen: { backgroundColor: theme.background },
          // volumePaneSize 仅建 widget 时可配（独立成交量副图的高度档位）
          overrides: { ...buildTvOverrides(), volumePaneSize: "medium" }
        });
        widgetRef.current = widget;

        widget.onChartReady(() => {
          if (disposed) {
            return;
          }
          setChartReady(true);
          // 挂载对齐主题：widget 把主题持久化在 localStorage(tradingview.current_theme.name)，
          // 重建时会优先用持久值而非构造参数 theme。若用户在「不在 TV 页面」时切了系统主题
          // （组件已卸载、错过 falconx:theme:changed），重新挂载会显示旧主题。ready 后比对
          // 当前 widget 主题与 data-theme，不一致则强制 changeTheme 对齐（并重应用 token overrides）。
          const target = resolveTvTheme();
          try {
            if (widget.getTheme() !== target) {
              void widget
                .changeTheme(target)
                .then(() => widget.applyOverrides(buildTvOverrides()))
                .catch(() => {
                  /* widget 销毁中忽略 */
                });
            }
          } catch {
            /* getTheme/changeTheme 在极端竞态下可能抛错，忽略 */
          }
          // TV 工具栏切周期 → 回传终端 timeframe（驱动 WS kline 频道跟随）
          widget
            .activeChart()
            .onIntervalChanged()
            .subscribe(null, (resolution) => {
              const nextInterval = resolutionToInterval(resolution);
              if (nextInterval) {
                onIntervalChangeRef.current(nextInterval);
              }
            });
        });
      })
      .catch((error: unknown) => {
        if (!disposed) {
          setLoadError(error instanceof Error ? error.message : "TradingView 图表加载失败");
        }
      });

    return () => {
      disposed = true;
      setChartReady(false);
      try {
        widgetRef.current?.remove();
      } catch {
        /* widget 初始化中断时 remove 可能抛错，忽略 */
      }
      widgetRef.current = null;
    };
  }, []);

  // 终端切品种 → TV 跟随
  useEffect(() => {
    if (!chartReady || !widgetRef.current) {
      return;
    }
    const chart = widgetRef.current.activeChart();
    if (chart.symbol() !== symbol) {
      void chart.setSymbol(symbol);
    }
  }, [chartReady, symbol]);

  // 项目主题切换（themeStore 已先翻转 <html data-theme> 再派发事件）→ TV changeTheme，
  // changeTheme 会重置 overrides，完成后必须重新 apply token 色票
  useEffect(() => {
    if (!chartReady) {
      return;
    }
    const onThemeChange = () => {
      const widget = widgetRef.current;
      if (!widget) {
        return;
      }
      void widget
        .changeTheme(resolveTvTheme())
        .then(() => widget.applyOverrides(buildTvOverrides()))
        .catch(() => {
          /* widget 销毁中触发时忽略 */
        });
    };
    window.addEventListener("falconx:theme:changed", onThemeChange);
    return () => window.removeEventListener("falconx:theme:changed", onThemeChange);
  }, [chartReady]);

  // 终端（或 TV 自身回传后的受控值）切周期 → TV 跟随；同值 no-op 防回环
  useEffect(() => {
    if (!chartReady || !widgetRef.current) {
      return;
    }
    const chart = widgetRef.current.activeChart();
    const resolution = intervalToResolution(interval) as ResolutionString;
    if (chart.resolution() !== resolution) {
      void chart.setResolution(resolution);
    }
  }, [chartReady, interval]);

  return (
    <div className="market-chart__tv" data-testid="tv-advanced-chart">
      {loadError ? (
        <div className="panel-empty market-chart__overlay">
          TradingView 图表加载失败：{loadError}
        </div>
      ) : null}
      <div ref={containerRef} className="market-chart__tv__container" />
    </div>
  );
}

/** 终端当前为中文文案，TV locale 固定 zh；后续接入多语言时改读 preferencesStore。 */
function resolveTvLocale(): LanguageCode {
  return "zh";
}
