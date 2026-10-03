import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpRight, Info, Loader2 } from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { formatPrice } from "../market/marketFormat";
import { formatMoney, truncToScale } from "../../lib/precision";
import { useMarketStore } from "../market/marketStore";
import type { MarketSymbol } from "../market/marketTypes";
import { FalconApiError } from "../../lib/api";
import { usePreferencesStore } from "../settings/preferencesStore";
import { getAccount, getLeverageTiers, getMarginMode, newClientOrderId, placeMarketOrder, placeLimitOrder, placeStopOrder } from "./tradingApi";
import { resolveTierLeverageCap } from "./leverageTiers";
import { MarginModeToggle } from "./MarginModeToggle";
import { MARGIN_MODE_QUERY_KEY } from "./marginModeShared";
import type { OrderSide, PlaceMarketOrderRequest, PlaceLimitOrderRequest, PlaceStopOrderRequest } from "./tradingTypes";

// STAGE-3-PENDING-ORDER：下单类型
type OrderTypeTab = "MARKET" | "LIMIT" | "STOP";

/** 拒单 reason 中文化（trading-core 在 PlaceMarketOrderResponse.rejectionReason 返回这些 code）。 */
const REJECTION_LABEL: Record<string, string> = {
  QTY_BELOW_MIN: "数量小于该品种最小限额",
  QTY_ABOVE_MAX: "数量超出该品种最大限额",
  NOTIONAL_BELOW_MIN: "名义金额低于最小要求（数量 × 价格）",
  INSUFFICIENT_AVAILABLE_BALANCE: "账户可用余额不足",
  POSITION_LIMIT_REACHED: "已达个人持仓上限",
  PLATFORM_POSITION_LIMIT_REACHED: "已达平台持仓上限",
  SYMBOL_TRADING_SUSPENDED: "该品种暂停交易",
  SYMBOL_SPEC_NOT_FOUND: "该品种暂未启用",
  MARGIN_MODE_NOT_SUPPORTED: "保证金模式不支持",
  MARKET_QUOTE_NOT_FOUND: "暂无报价",
  MARKET_QUOTE_STALE: "报价已过期",
  BBOOK_RISK_OPEN_REJECTED: "风控拒单",
  BBOOK_RISK_REDUCE_ONLY: "该品种当前仅允许减仓",
  BBOOK_RISK_GLOBAL_PAUSE: "全局风控暂停",
  BBOOK_RISK_USER_EXPOSURE_LIMIT: "已达用户级敞口阈值",
  PENDING_ORDER_TOO_CLOSE: "挂单价距当前价过近",
  PENDING_ORDER_INSUFFICIENT_FUNDS: "可用余额不足以冻结挂单保证金",
  // B 切片（2026-06-03）：杠杆 tier 护栏相关
  LEVERAGE_EXCEEDED: "杠杆超出该品种上限",
  LEVERAGE_EXCEEDS_TIER: "杠杆超出当前名义价值档位上限（仓位越大可用杠杆越低）",
  TIER_CONFIG_NOT_FOUND: "该品种杠杆档位未配置，暂不可开仓",
  LIQUIDATION_DISTANCE_TOO_CLOSE: "强平价距离过近（开仓即面临强平风险），请降低杠杆",
  FX_RATE_UNAVAILABLE: "汇率暂不可用，请稍后重试",
};

interface Props {
  symbolMeta: MarketSymbol | null;
}

export function OrderTicket({ symbolMeta }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();

  const selectedSymbol = useMarketStore((s) => s.selectedSymbol);
  const currentQuote = useMarketStore((s) =>
    selectedSymbol ? s.quotes[selectedSymbol] ?? null : null
  );

  const minQty = symbolMeta?.minQty ? Number(symbolMeta.minQty) : null;
  // 股票 (cat 6) / ETF (cat 7) / 指数 (cat 4) 整手 100；其它 (FX/METAL/ENERGY/CRYPTO) 0.01 手。
  // 用 category 判断而非 minQty——后端 minQty 偶有未配置默认 0 的情况。
  const qtyStep = (() => {
    const cat = Number(symbolMeta?.category ?? 0);
    return cat === 4 || cat === 6 || cat === 7 ? 100 : 0.01;
  })();
  const maxQty = symbolMeta?.maxQty ? Number(symbolMeta.maxQty) : null;
  const minNotional = symbolMeta?.minNotional ? Number(symbolMeta.minNotional) : null;
  const maxLeverage = symbolMeta?.maxLeverage ?? null;
  // symbol 精度（缺省回退）：整手品种 qtyStep≥1 → 0 位，否则 2 位；价格缺省 2 位。
  const qtyPrecision =
    typeof symbolMeta?.qtyPrecision === "number" && symbolMeta.qtyPrecision >= 0
      ? symbolMeta.qtyPrecision
      : qtyStep >= 1
        ? 0
        : 2;
  const pricePrecision =
    typeof symbolMeta?.pricePrecision === "number" && symbolMeta.pricePrecision >= 0
      ? symbolMeta.pricePrecision
      : 2;

  // 账户余额查询：WS account.update 事件触发 invalidateQueries(["trading"]) 时这条也会 refetch
  const accountQuery = useQuery({
    queryKey: ["trading", "account-me"],
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return getAccount(token);
    },
    enabled: Boolean(token),
  });
  const available = accountQuery.data ? Number(accountQuery.data.available) : null;
  const accountCurrency = accountQuery.data?.currency ?? null;

  // 偏好（leverage / marginMode）从 Settings 页 zustand persist 读取。
  // 用 getState() 静态读，避免订阅触发的 re-render 覆盖用户在 ticket 内的手动调整。
  // 用户在 Settings 改完后切回 dashboard，OrderTicket 重新 mount 时即读到新值。
  const initialPrefs = usePreferencesStore.getState();

  // 账户级 margin mode（复用 MarginModeToggle 的 queryKey 共享缓存），用于 CROSS 门禁防御。
  const marginModeQuery = useQuery({
    queryKey: MARGIN_MODE_QUERY_KEY,
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return getMarginMode(token);
    },
    enabled: Boolean(token),
  });
  // loading / 拿不到时保守按 false（disable 全仓），避免脏偏好按 CROSS 提交被后端 40010 拒。
  const crossModeEnabled = marginModeQuery.data?.crossModeEnabled ?? false;

  const [orderType, setOrderType] = useState<OrderTypeTab>("MARKET");
  const [side, setSide] = useState<OrderSide>("BUY");
  const [quantity, setQuantity] = useState(() => (minQty && minQty > 0 ? String(minQty) : "0.01"));
  const [leverage, setLeverage] = useState(() => String(initialPrefs.defaultLeverage));
  const [marginMode] = useState(() => initialPrefs.defaultMarginMode);
  // 提交用的 marginMode：偏好为 CROSS 但平台未开放全仓时强制回退 ISOLATED，
  // 避免历史脏偏好导致下单被 40010 拒。crossModeEnabled 解析完成后实时生效。
  const effectiveMarginMode = marginMode === "CROSS" && !crossModeEnabled ? "ISOLATED" : marginMode;
  const [tpEnabled, setTpEnabled] = useState(false);
  const [tpPrice, setTpPrice] = useState("");
  const [slEnabled, setSlEnabled] = useState(false);
  const [slPrice, setSlPrice] = useState("");
  // STAGE-3-PENDING-ORDER：LIMIT/STOP 触发价 + STOP_LIMIT 限价
  const [triggerPrice, setTriggerPrice] = useState("");
  const [stopLimitPrice, setStopLimitPrice] = useState("");
  // 走 TradingTabs 的 falconx:toast 事件总线；保留本地 errBanner 显示底部红条挽留用户视线
  const [errBanner, setErrBanner] = useState<string | null>(null);
  const pushToast = (kind: "ok" | "err", text: string, level: "default" | "critical" = "default") => {
    window.dispatchEvent(new CustomEvent("falconx:toast", { detail: { kind, text, level } }));
  };

  // 切换品种时默认数量 = 最低手数 max(minQty, qtyStep)，对齐 qtyStep。
  // minNotional 仅作提交校验保留（line ~215），不再用于拉高默认值。
  // lastInitSymbolRef 避免覆盖用户后续输入。
  const lastInitSymbolRef = useRef<string | null>(null);
  useEffect(() => {
    if (!selectedSymbol) return;
    if (lastInitSymbolRef.current === selectedSymbol) return;
    const nextQty = Math.max(minQty ?? 0, qtyStep);
    setQuantity(qtyStep >= 1 ? String(Math.round(nextQty)) : nextQty.toFixed(qtyPrecision));
    lastInitSymbolRef.current = selectedSymbol;
  }, [selectedSymbol, minQty, qtyStep, qtyPrecision]);

  const marketMutation = useMutation({
    mutationFn: (req: PlaceMarketOrderRequest) => {
      if (!token) throw new Error("Not authenticated");
      return placeMarketOrder(token, req);
    },
    onSuccess: (result) => {
      if (result.rejectionReason) {
        const label = REJECTION_LABEL[result.rejectionReason] ?? result.rejectionReason;
        setErrBanner(`订单被拒：${label}`);
        pushToast("err", `订单被拒：${label}`, "critical");
        return;
      }
      setErrBanner(null);
      pushToast(
        "ok",
        `✓ 已成交 ${result.symbol} ${result.side} ${result.quantity} @ ${result.filledPrice ?? "—"}`,
        "critical"
      );
      void queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: handleMutationError,
  });

  const limitMutation = useMutation({
    mutationFn: (req: PlaceLimitOrderRequest) => {
      if (!token) throw new Error("Not authenticated");
      return placeLimitOrder(token, req);
    },
    onSuccess: (item) => {
      setErrBanner(null);
      pushToast("ok", `LIMIT 挂单已创建 ${item.orderNo} @ ${item.triggerPrice}`, "critical");
      void queryClient.invalidateQueries({ queryKey: ["trading", "pending"] });
    },
    onError: handleMutationError,
  });

  const stopMutation = useMutation({
    mutationFn: (req: PlaceStopOrderRequest) => {
      if (!token) throw new Error("Not authenticated");
      return placeStopOrder(token, req);
    },
    onSuccess: (item) => {
      const typeLabel = item.orderType === "STOP_LIMIT" ? "STOP_LIMIT" : "STOP";
      setErrBanner(null);
      pushToast("ok", `${typeLabel} 挂单已创建 ${item.orderNo} @ ${item.triggerPrice}`, "critical");
      void queryClient.invalidateQueries({ queryKey: ["trading", "pending"] });
    },
    onError: handleMutationError,
  });

  function handleMutationError(err: unknown) {
    let text: string;
    if (err instanceof FalconApiError) {
      const reason = (err.data as { rejectionReason?: string } | undefined)?.rejectionReason;
      const label = reason ? (REJECTION_LABEL[reason] ?? reason) : (REJECTION_LABEL[err.message] ?? err.message);
      text = `订单被拒：${label}（${err.code}）`;
    } else {
      text = `下单失败：${(err as Error).message}`;
    }
    setErrBanner(text);
    pushToast("err", text, "critical");
  }

  const isPending = marketMutation.isPending || limitMutation.isPending || stopMutation.isPending;

  // 市价单参考价 = 按方向的实际成交价：BUY→Ask、SELL→Bid（currentQuote 已含组 markup，
  // 即后端开仓 fillPrice 口径，见 DefaultTradingRiskService）。tier/preCheck/明细三处统一取此价，
  // 与后端 notional=fillPrice×qty、initialMargin=notional/leverage 完全对齐（不再用 MID 低估）。
  // ask/bid 缺失时回退 mid/mark 兜底。
  const marketFillPrice = useMemo(() => {
    const q = currentQuote;
    const raw = side === "BUY"
      ? (q?.ask ?? q?.mid ?? q?.mark)
      : (q?.bid ?? q?.mid ?? q?.mark);
    return Number(raw ?? 0);
  }, [side, currentQuote]);

  // B 切片（2026-06-03）：杠杆/MM 档位查询——按名义价值动态降档（与开仓风控 30070 同源）。
  // staleTime 30s 对齐后端 tier 缓存刷新粒度；失败/未加载时 tierCap.cap=null 回退 SymbolSpec.maxLeverage。
  const tiersQuery = useQuery({
    queryKey: ["trading", "leverage-tiers", selectedSymbol],
    queryFn: () => {
      if (!token || !selectedSymbol) throw new Error("Not ready");
      return getLeverageTiers(token, selectedSymbol);
    },
    enabled: Boolean(token && selectedSymbol),
    staleTime: 30_000,
  });

  // 当前名义价值（QC 口径，与下方 preCheck/calc 同取价逻辑）→ 档位杠杆上限。
  const tierCap = useMemo(() => {
    const qty = Number(quantity);
    let refPriceNum = marketFillPrice;
    if (orderType === "LIMIT" || orderType === "STOP") {
      const tp = Number(triggerPrice);
      const slp = orderType === "STOP" && stopLimitPrice ? Number(stopLimitPrice) : 0;
      refPriceNum = slp > 0 ? slp : tp > 0 ? tp : marketFillPrice;
    }
    const notionalQc = qty > 0 && refPriceNum > 0 ? qty * refPriceNum : null;
    return resolveTierLeverageCap(tiersQuery.data ?? null, notionalQc);
  }, [tiersQuery.data, quantity, marketFillPrice, orderType, triggerPrice, stopLimitPrice]);

  // 生效杠杆上限 = min(SymbolSpec 全局上限, 当前档位上限)；档位未知时回退全局上限。
  const effectiveMaxLeverage =
    tierCap.cap != null && maxLeverage != null
      ? Math.min(maxLeverage, tierCap.cap)
      : tierCap.cap ?? maxLeverage;
  const tierTightened =
    tierCap.cap != null && (maxLeverage == null || tierCap.cap < maxLeverage) && tierCap.tierNo != null && tierCap.tierNo > 1;

  // 前端预检：避免无效请求打到后端
  const preCheck = useMemo(() => {
    const errors: string[] = [];
    if (!selectedSymbol) errors.push("请先选择品种");
    if (!token) errors.push("未登录");
    const qty = Number(quantity);
    const lev = Number(leverage);
    if (!(qty > 0)) errors.push("数量必须 > 0");
    if (!(lev > 0)) errors.push("杠杆必须 > 0");
    const effectiveMinQty = Math.max(minQty ?? qtyStep, qtyStep);
    if (qty > 0 && qty < effectiveMinQty) errors.push(`数量需 ≥ ${effectiveMinQty}`);
    if (maxQty != null && qty > maxQty) errors.push(`数量需 ≤ ${maxQty}`);
    // 浮点取模有精度问题，乘到整数后再比，容差 1e-9
    if (qty > 0 && Math.abs((qty / qtyStep) - Math.round(qty / qtyStep)) > 1e-9) {
      errors.push(`数量必须是 ${qtyStep} 的整数倍`);
    }
    if (effectiveMaxLeverage != null && lev > effectiveMaxLeverage) {
      errors.push(
        tierTightened
          ? `杠杆需 ≤ ${effectiveMaxLeverage}（当前名义价值落档位 ${tierCap.tierNo}，仓位越大可用杠杆越低）`
          : `杠杆需 ≤ ${effectiveMaxLeverage}`
      );
    }
    // 名义金额 / 估算保证金按订单类型取价：
    //   MARKET 用现价；LIMIT 用用户填的限价；STOP 用触发价（STOP_LIMIT 则用 stopLimitPrice）。
    //   之前一律用现价会让远离市价的挂单误判，比如 BTCUSD 现价 100000 但用户挂 50000 LIMIT，
    //   按现价算出的 notional 跟实际成交差一倍。
    let refPriceNum = marketFillPrice;
    if (orderType === "LIMIT" || orderType === "STOP") {
      const tp = Number(triggerPrice);
      const slp = orderType === "STOP" && stopLimitPrice ? Number(stopLimitPrice) : 0;
      refPriceNum = slp > 0 ? slp : tp > 0 ? tp : marketFillPrice;
    }
    let estMargin = 0;
    if (qty > 0 && lev > 0 && refPriceNum > 0) {
      estMargin = (qty * refPriceNum) / lev;
    }
    if (minNotional != null && qty > 0 && refPriceNum > 0) {
      const notional = qty * refPriceNum;
      if (notional < minNotional) errors.push(`名义金额（≈${formatMoney(notional, accountCurrency)}）需 ≥ ${minNotional}`);
    }
    if (available != null && estMargin > 0 && estMargin > available) {
      errors.push(`所需保证金 ≈${formatMoney(estMargin, accountCurrency)} 大于可用余额 ${formatMoney(available, accountCurrency)}`);
    }
    // 挂单（LIMIT/STOP）需要 triggerPrice
    if (orderType !== "MARKET") {
      const tp = Number(triggerPrice);
      if (!(tp > 0)) errors.push(`${orderType === "LIMIT" ? "限价" : "触发价"}必须 > 0`);
      // STOP_LIMIT 用 stopLimitPrice 作为触发后挂的 LIMIT 限价
      if (orderType === "STOP" && stopLimitPrice) {
        const lp = Number(stopLimitPrice);
        if (!(lp > 0)) errors.push("限价必须 > 0");
      }
    }
    return errors;
  }, [selectedSymbol, token, quantity, leverage, minQty, qtyStep, maxQty, effectiveMaxLeverage, tierTightened, tierCap.tierNo, minNotional, marketFillPrice, available, accountCurrency, orderType, triggerPrice, stopLimitPrice]);

  const canSubmit = preCheck.length === 0 && !isPending;

  // 下单计算明细悬浮窗：仅小感叹号(ⓘ)图标 hover/聚焦触发；portal 到 body 用 position:fixed 定位，
  // 避免被祖先 overflow / backdrop-filter 裁切（与 AccountMenu / NotificationCenter 同坑）。
  const calcIconRef = useRef<HTMLButtonElement | null>(null);
  const calcPopRef = useRef<HTMLDivElement | null>(null);
  const calcCloseTimer = useRef<number | undefined>(undefined);
  const [calcOpen, setCalcOpen] = useState(false);
  const [calcPos, setCalcPos] = useState<{ left: number; top: number } | null>(null);
  const openCalc = () => {
    window.clearTimeout(calcCloseTimer.current);
    setCalcOpen(true);
  };
  const closeCalc = () => {
    // 留短暂延时，允许鼠标从图标移入悬浮窗而不闪退
    calcCloseTimer.current = window.setTimeout(() => setCalcOpen(false), 120);
  };
  // 打开后按图标视口坐标定位悬浮窗：优先向左浮于图表上方；放不下则夹入视口（窄屏）。
  useLayoutEffect(() => {
    if (!calcOpen || !calcIconRef.current) {
      setCalcPos(null);
      return;
    }
    const r = calcIconRef.current.getBoundingClientRect();
    const margin = 8;
    const w = calcPopRef.current?.offsetWidth ?? 280;
    const h = calcPopRef.current?.offsetHeight ?? 260;
    let left = r.left - 10 - w; // 优先左侧（浮于图表）
    if (left < margin) {
      // 窄屏放不下左侧：夹入视口（尽量贴近图标右缘，再夹边）
      left = Math.min(Math.max(margin, r.right - w), window.innerWidth - w - margin);
      if (left < margin) left = margin;
    }
    let top = r.top + r.height / 2 - h / 2; // 垂直居中于图标
    top = Math.min(Math.max(margin, top), window.innerHeight - h - margin);
    setCalcPos({ left, top });
  }, [calcOpen]);
  // 滚动 / 缩放 / ESC 时关闭，避免悬浮窗与图标脱锚
  useEffect(() => {
    if (!calcOpen) return;
    const onReflow = () => setCalcOpen(false);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setCalcOpen(false);
    };
    window.addEventListener("scroll", onReflow, true);
    window.addEventListener("resize", onReflow);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("scroll", onReflow, true);
      window.removeEventListener("resize", onReflow);
      window.removeEventListener("keydown", onKey);
    };
  }, [calcOpen]);

  // 下单计算明细：把名义价值 / 所需保证金的公式 + 代入值显式列在面板上。
  // 取价口径与 preCheck/tier 完全一致，且对齐后端实际开仓 fillPrice：
  //   MARKET → 方向成交价（BUY=Ask / SELL=Bid，含组 markup）；LIMIT → 限价；STOP → 触发价/止损限价。
  // 关键：杠杆「不」参与名义价值（数量 × 价格），只在所需保证金里做除数 —— 与后端
  //   notional=fillPrice×qty、initialMargin=notional/leverage 一致。
  const calc = useMemo(() => {
    const qty = Number(quantity);
    const lev = Number(leverage);
    let refPriceNum = marketFillPrice;
    let priceSource = side === "BUY" ? "买入价（Ask）" : "卖出价（Bid）";
    if (orderType === "LIMIT") {
      const tp = Number(triggerPrice);
      if (tp > 0) {
        refPriceNum = tp;
        priceSource = "限价";
      }
    } else if (orderType === "STOP") {
      const slp = stopLimitPrice ? Number(stopLimitPrice) : 0;
      const tp = Number(triggerPrice);
      if (slp > 0) {
        refPriceNum = slp;
        priceSource = "止损限价";
      } else if (tp > 0) {
        refPriceNum = tp;
        priceSource = "触发价";
      }
    }
    const hasPrice = refPriceNum > 0;
    const hasQty = qty > 0;
    const hasLev = lev > 0;
    const notional = hasQty && hasPrice ? qty * refPriceNum : null;
    const estMargin = notional != null && hasLev ? notional / lev : null;
    return { qty, lev, refPriceNum, priceSource, notional, estMargin, hasQty, hasPrice, hasLev };
  }, [quantity, leverage, marketFillPrice, side, orderType, triggerPrice, stopLimitPrice]);

  const fmtMoney = (n: number) =>
    n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const calcPriceLabel = calc.hasPrice
    ? formatPrice(calc.refPriceNum, symbolMeta?.pricePrecision)
    : "—";

  // 提交前按 symbol 精度吸附（UI 不允许提交超精度值，呼应后端校验、避免被拒）：
  //   数量 → qtyPrecision；价格 → pricePrecision。一律向零截断（与显示口径一致）。
  const snapQty = (raw: string): string => {
    const n = Number(raw);
    if (!Number.isFinite(n)) return raw;
    return truncToScale(n, qtyPrecision).toFixed(qtyPrecision);
  };
  const snapPrice = (raw: string): string => {
    const n = Number(raw);
    if (!Number.isFinite(n)) return raw;
    return truncToScale(n, pricePrecision).toFixed(pricePrecision);
  };

  const handleSubmit = () => {
    if (!selectedSymbol || !canSubmit) return;
    setErrBanner(null);
    const clientOrderId = newClientOrderId();
    const snappedQty = snapQty(quantity);
    if (orderType === "MARKET") {
      const req: PlaceMarketOrderRequest = {
        symbol: selectedSymbol,
        side,
        quantity: snappedQty,
        leverage,
        marginMode: effectiveMarginMode,
        clientOrderId,
      };
      if (tpEnabled && tpPrice) req.takeProfitPrice = snapPrice(tpPrice);
      if (slEnabled && slPrice) req.stopLossPrice = snapPrice(slPrice);
      marketMutation.mutate(req);
    } else if (orderType === "LIMIT") {
      limitMutation.mutate({
        symbol: selectedSymbol, side, quantity: snappedQty,
        limitPrice: snapPrice(triggerPrice), leverage,
        marginMode: effectiveMarginMode, clientOrderId,
      });
    } else {
      stopMutation.mutate({
        symbol: selectedSymbol, side, quantity: snappedQty,
        stopPrice: snapPrice(triggerPrice),
        limitPrice: stopLimitPrice ? snapPrice(stopLimitPrice) : undefined,
        leverage,
        marginMode: effectiveMarginMode, clientOrderId,
      });
    }
  };

  // 按 symbol.pricePrecision 截断尾零，XAUUSD 3 位、FX 5 位、加密 2 位等，避免 4468.97000000 这种长拖尾
  const rawRefPrice = currentQuote?.mid ?? currentQuote?.mark ?? currentQuote?.bid;
  const refPrice = rawRefPrice ? formatPrice(rawRefPrice, symbolMeta?.pricePrecision) : "—";
  // STAGE-12: 买卖按钮上显示实时 ask/bid 价，让用户预期成交价
  // BUY 走 ask（买入需付 ask 价），SELL 走 bid（卖出收到 bid 价）
  const askPriceDisplay = currentQuote?.ask
    ? formatPrice(currentQuote.ask, symbolMeta?.pricePrecision)
    : "—";
  const bidPriceDisplay = currentQuote?.bid
    ? formatPrice(currentQuote.bid, symbolMeta?.pricePrecision)
    : "—";

  return (
    <aside className="order-ticket" aria-label="下单面板">
      <div className="fx-panel-head">
        <span className="fx-panel-head__num">§03</span>
        <h2 className="fx-panel-head__title">
          {orderType === "MARKET" ? "市价单" : orderType === "LIMIT" ? "限价单" : "止损单"}
        </h2>
        <span className="fx-panel-head__hint">
          <b>{selectedSymbol ?? "未选择品种"}</b>
        </span>
      </div>

      {/* STAGE-14E1 Task5：账户级保证金模式切换（账户级，区分下单偏好 defaultMarginMode） */}
      <MarginModeToggle />

      {/* STAGE-3-PENDING-ORDER：订单类型 tab */}
      <div className="ticket-order-type-tabs" role="tablist" aria-label="订单类型">
        {(["MARKET", "LIMIT", "STOP"] as OrderTypeTab[]).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={orderType === t}
            className={orderType === t ? "active" : ""}
            onClick={() => setOrderType(t)}
          >
            {t === "MARKET" ? "市价" : t === "LIMIT" ? "限价" : "止损"}
          </button>
        ))}
      </div>

      <div
        className="ticket-amount"
        title="MID = (Bid + Ask) / 2，市场中间价参考。实际成交：买入按 Ask、卖出按 Bid（见下方买卖按钮与下单计算明细）。"
      >
        {refPrice}
        <span
          style={{
            marginLeft: 10,
            fontSize: "0.32em",
            fontWeight: 500,
            letterSpacing: "0.12em",
            color: "var(--fx-cyan, #54e6ff)",
            verticalAlign: "0.8em",
            fontFamily: "var(--fx-mono-stack)",
            opacity: 0.85,
          }}
        >
          MID
        </span>
      </div>
      {accountQuery.data && (
        <div className="order-balance-line">
          可用 <b>{formatMoney(accountQuery.data.available, accountQuery.data.currency)}</b>
          {" / "}余额 {formatMoney(accountQuery.data.balance, accountQuery.data.currency)}
          {" "}{accountQuery.data.currency}
        </div>
      )}

      <div className="ticket-switch" role="group" aria-label="方向">
        <button
          type="button"
          className={`long ${side === "BUY" ? "active" : ""}`}
          onClick={() => setSide("BUY")}
          aria-pressed={side === "BUY"}
          title="BUY 走 Ask 价（买入需付卖一价）"
        >
          <span style={{ display: "block", lineHeight: 1.2 }}>买入 / 做多</span>
          <span
            style={{
              display: "block",
              fontFamily: "var(--fx-mono-stack)",
              fontSize: 12,
              fontWeight: 600,
              letterSpacing: "0.04em",
              marginTop: 2,
              opacity: 0.92,
            }}
          >
            @ {askPriceDisplay}
          </span>
        </button>
        <button
          type="button"
          className={`short ${side === "SELL" ? "active" : ""}`}
          onClick={() => setSide("SELL")}
          aria-pressed={side === "SELL"}
          title="SELL 走 Bid 价（卖出收到买一价）"
        >
          <span style={{ display: "block", lineHeight: 1.2 }}>卖出 / 做空</span>
          <span
            style={{
              display: "block",
              fontFamily: "var(--fx-mono-stack)",
              fontSize: 12,
              fontWeight: 600,
              letterSpacing: "0.04em",
              marginTop: 2,
              opacity: 0.92,
            }}
          >
            @ {bidPriceDisplay}
          </span>
        </button>
      </div>

      <label>
        数量
        <span className="order-spec-hint">
          步进 {qtyStep}
          {minQty != null ? ` · 最小 ${Math.max(minQty, qtyStep)}` : ` · 最小 ${qtyStep}`}
          {maxQty != null ? ` / 最大 ${maxQty}` : ""}
        </span>
        <input
          type="number"
          step={qtyStep}
          min={Math.max(minQty ?? qtyStep, qtyStep)}
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
        />
      </label>
      <label>
        杠杆
        {effectiveMaxLeverage != null && (
          <span className="order-spec-hint">
            最大 {effectiveMaxLeverage}x
            {tierTightened ? `（当前名义价值档位 ${tierCap.tierNo}）` : ""}
          </span>
        )}
        <input
          type="number"
          step="1"
          min="1"
          max={effectiveMaxLeverage ?? undefined}
          value={leverage}
          onChange={(e) => setLeverage(e.target.value)}
        />
      </label>

      {/* 下单计算明细：整行仅文字摘要，只有小感叹号(ⓘ)图标触发；悬浮窗 portal 到 body 用
          position:fixed 按图标坐标定位（优先浮于左侧图表，窄屏夹入视口），不被祖先裁切、不挤压布局。 */}
      <div className="order-calc-line">
        <span className="order-calc-line__label">下单计算明细</span>
        <button
          ref={calcIconRef}
          type="button"
          className="order-calc-icon"
          aria-label="查看下单计算明细"
          aria-expanded={calcOpen}
          onMouseEnter={openCalc}
          onMouseLeave={closeCalc}
          onFocus={openCalc}
          onBlur={closeCalc}
        >
          <Info size={14} strokeWidth={2} aria-hidden="true" />
        </button>
        <span className="order-calc-line__hint">
          {calc.estMargin != null ? `保证金 ≈ ${fmtMoney(calc.estMargin)} USDT` : ""}
        </span>
      </div>
      {calcOpen &&
        createPortal(
          <div
            ref={calcPopRef}
            className="order-calc-popover"
            role="tooltip"
            style={{
              left: calcPos?.left ?? -9999,
              top: calcPos?.top ?? 0,
              visibility: calcPos ? "visible" : "hidden"
            }}
            onMouseEnter={openCalc}
            onMouseLeave={closeCalc}
          >
            <div className="order-calc" aria-label="下单计算明细">
              <div className="order-calc__title">下单计算明细</div>
              <div className="order-calc__row">
                <span className="order-calc__label">成交价</span>
                <span className="order-calc__formula">
                  {orderType === "MARKET" ? calc.priceSource : `按${calc.priceSource}`}
                </span>
                <span className="order-calc__value">{calcPriceLabel}</span>
              </div>
              <div className="order-calc__row">
                <span className="order-calc__label">名义价值</span>
                <span className="order-calc__formula">
                  数量 × 价格 = {calc.hasQty ? calc.qty : "—"} × {calcPriceLabel}
                </span>
                <span className="order-calc__value">
                  {calc.notional != null ? `${fmtMoney(calc.notional)} USDT` : "—"}
                </span>
              </div>
              <div className="order-calc__row">
                <span className="order-calc__label">所需保证金</span>
                <span className="order-calc__formula">
                  名义价值 ÷ 杠杆 ={" "}
                  {calc.notional != null ? fmtMoney(calc.notional) : "—"} ÷{" "}
                  {calc.hasLev ? `${calc.lev}x` : "—"}
                </span>
                <span className="order-calc__value">
                  {calc.estMargin != null ? `${fmtMoney(calc.estMargin)} USDT` : "—"}
                </span>
              </div>
              <p className="order-calc__note">
                杠杆<b>不</b>参与名义价值计算（名义价值 = 数量 × 价格）；杠杆只在所需保证金里作除数，
                倍数越高占用保证金越少。
              </p>
              {minNotional != null && (
                <div className="order-calc__min">
                  名义价值需 ≥ {minNotional}
                  {calc.notional != null && calc.notional < minNotional ? "（当前不足）" : ""}
                </div>
              )}
            </div>
          </div>,
          document.body
        )}

      {/* STAGE-3-PENDING-ORDER：LIMIT/STOP 触发价 */}
      {orderType === "LIMIT" && (
        <label>
          限价
          <span className="order-spec-hint">触达即按市价成交</span>
          <input
            type="number"
            step="0.00000001"
            value={triggerPrice}
            onChange={(e) => setTriggerPrice(e.target.value)}
            placeholder={side === "BUY" ? "低于当前价（买入捡漏）" : "高于当前价（卖出锁利）"}
          />
        </label>
      )}
      {orderType === "STOP" && (
        <>
          <label>
            触发价（StopPrice）
            <span className="order-spec-hint">突破时触发</span>
            <input
              type="number"
              step="0.00000001"
              value={triggerPrice}
              onChange={(e) => setTriggerPrice(e.target.value)}
              placeholder={side === "BUY" ? "高于当前价（追多）" : "低于当前价（追空）"}
            />
          </label>
          <label>
            限价（可选；不填走市价）
            <span className="order-spec-hint">填则转 STOP_LIMIT</span>
            <input
              type="number"
              step="0.00000001"
              value={stopLimitPrice}
              onChange={(e) => setStopLimitPrice(e.target.value)}
              placeholder="可留空"
            />
          </label>
        </>
      )}

      {/* TP/SL 仅市价单允许同时设置；挂单暂不支持开仓即挂 SL/TP */}
      {orderType === "MARKET" && (
      <>
      <label className="ticket-checkbox-row">
        <input type="checkbox" checked={tpEnabled} onChange={(e) => setTpEnabled(e.target.checked)} />
        启用止盈（TP）
      </label>
      {tpEnabled && (
        <label>
          止盈价
          <input
            type="number"
            step="0.00000001"
            value={tpPrice}
            onChange={(e) => setTpPrice(e.target.value)}
            placeholder="高于开仓价"
          />
        </label>
      )}
      <label className="ticket-checkbox-row">
        <input type="checkbox" checked={slEnabled} onChange={(e) => setSlEnabled(e.target.checked)} />
        启用止损（SL）
      </label>
      {slEnabled && (
        <label>
          止损价
          <input
            type="number"
            step="0.00000001"
            value={slPrice}
            onChange={(e) => setSlPrice(e.target.value)}
            placeholder="低于开仓价"
          />
        </label>
      )}
      </>
      )}

      {preCheck.length > 0 && (
        <div className="order-precheck" role="alert">
          {preCheck.map((e, i) => (
            <div key={i}>· {e}</div>
          ))}
        </div>
      )}

      <button
        type="button"
        className={`order-submit ${side === "BUY" ? "long" : "short"}`}
        disabled={!canSubmit}
        onClick={handleSubmit}
      >
        {isPending ? <Loader2 size={16} className="spin" /> : <ArrowUpRight size={16} />}
        {isPending
          ? "下单中…"
          : `${side === "BUY" ? "买入" : "卖出"}${orderType === "MARKET" ? "市价单" : orderType === "LIMIT" ? "限价单" : (stopLimitPrice ? "止损限价单" : "止损单")}`}
      </button>

      {errBanner && (
        <div className="order-toast err" role="alert">
          {errBanner}
        </div>
      )}
    </aside>
  );
}
