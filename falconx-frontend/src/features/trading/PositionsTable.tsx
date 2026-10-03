import { memo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { getPositionSwapSummary, listPositions } from "./tradingApi";
import { ClosePositionModal } from "./ClosePositionModal";
import { EditRiskControlsModal } from "./EditRiskControlsModal";
import { AddMarginModal } from "./AddMarginModal";
import { OrderDetailModal, type DetailSection } from "./OrderDetailModal";
import type { PositionItem } from "./tradingTypes";
import type { PositionPnlUpdate } from "./useTradingSocket";
import { useBreakpoint } from "../../lib/responsive";
import { PositionItemCard } from "./PositionItemCard";
import { buildSwapSection } from "./positionDetailSections";
import { ACCOUNT_CURRENCY, pnlColorClass, shouldShowQuoteRow } from "./pnlDisplay";
import { formatMoney, formatPercent, formatPnl, formatPrice, formatQty, formatSignedPnl } from "../../lib/precision";
import { useSymbolPrecision } from "../market/useSymbolPrecision";

/** STAGE-12-GROUP-MARKUP: 冻结 markup 数字着色（正绿负红零灰）。 */
function markupEmphasis(v: string | null): "long" | "short" | "muted" {
  if (v == null) return "muted";
  const n = Number(v);
  if (!Number.isFinite(n) || n === 0) return "muted";
  return n > 0 ? "long" : "short";
}

function buildOpenPositionDetail(
  p: PositionItem,
  liveMarkPrice: string | null | undefined,
  liveUnrealizedPnl: string | null | undefined,
  pricePrecision: number | undefined,
  qtyPrecision: number | undefined,
): DetailSection[] {
  const pnl = liveUnrealizedPnl != null ? Number(liveUnrealizedPnl) : null;
  const openFeeNum = p.openFee ? Number(p.openFee) : null;
  const netPnl = pnl != null && openFeeNum != null ? pnl - openFeeNum : null;
  return [
    {
      num: "01",
      title: "持仓标识",
      fields: [
        { label: "持仓 ID", value: p.positionId, mono: true, fullWidth: true },
        { label: "开仓订单 ID", value: p.openingOrderId, mono: true, fullWidth: true },
      ],
    },
    {
      num: "02",
      title: "交易要素",
      fields: [
        { label: "品种", value: p.symbol, mono: true },
        {
          label: "方向",
          value: p.side === "BUY" ? "多 (BUY)" : "空 (SELL)",
          emphasis: p.side === "BUY" ? "long" : "short",
        },
        { label: "数量", value: formatQty(p.quantity, qtyPrecision), mono: true },
        { label: "杠杆", value: `${p.leverage}x`, mono: true },
        { label: "保证金", value: formatMoney(p.margin, ACCOUNT_CURRENCY), mono: true },
        { label: "保证金模式", value: p.marginMode },
      ],
    },
    {
      num: "03",
      title: "价格",
      fields: [
        {
          label: "开仓价",
          value: formatPrice(p.entryPrice, pricePrecision),
          mono: true,
          tooltip: "用户实际成交价 = LP base + 平台基准加点 + 用户组 markup 冻结值（开仓瞬间冻结，永不变化）。",
        },
        {
          label: "标记价",
          value: liveMarkPrice != null ? formatPrice(liveMarkPrice, pricePrecision) : null,
          mono: true,
          emphasis: "cyan",
          tooltip: "标记价 = (base bid + base ask) / 2 中间价，不含任何用户组 markup；用于风险计算 / 强平判定。",
        },
        {
          label: "有效标记价",
          value: p.effectiveMarkPrice != null ? formatPrice(p.effectiveMarkPrice, pricePrecision) : null,
          mono: true,
          emphasis: "cyan",
          tooltip: "= 公允 mid + 持仓方向冻结 markup。PnL 实际用此价对比开仓价（同口径），所以 PnL 反映价格变化、不反映运营改 markup 配置（存量持仓口径稳定）。",
        },
        { label: "强平价", value: p.liquidationPrice != null ? formatPrice(p.liquidationPrice, pricePrecision) : null, mono: true, emphasis: "short" },
        { label: "止盈 TP", value: p.takeProfitPrice != null ? formatPrice(p.takeProfitPrice, pricePrecision) : null, mono: true },
        { label: "止损 SL", value: p.stopLossPrice != null ? formatPrice(p.stopLossPrice, pricePrecision) : null, mono: true },
      ],
    },
    // STAGE-12-GROUP-MARKUP: 开仓 markup 快照（冻结值，永不变化）
    ...(p.bidExtraAtOpen || p.askExtraAtOpen || p.groupCodeAtOpen
      ? [{
          num: "04" as const,
          title: "开仓 markup 快照（冻结）",
          fields: [
            { label: "用户组 (开仓时)", value: p.groupCodeAtOpen, mono: true },
            {
              label: "bid_extra 冻结值",
              value: p.bidExtraAtOpen,
              mono: true,
              emphasis: markupEmphasis(p.bidExtraAtOpen),
              tooltip: "BUY 持仓平仓时走 base_bid + bidExtraAtOpen 算有效平仓价。即使运营事后改 markup，position 永远按此冻结值算 PnL。",
            },
            {
              label: "ask_extra 冻结值",
              value: p.askExtraAtOpen,
              mono: true,
              emphasis: markupEmphasis(p.askExtraAtOpen),
              tooltip: "SELL 持仓平仓时走 base_ask + askExtraAtOpen 算有效平仓价。冻结策略保证：运营改配置不影响存量持仓 PnL。",
            },
          ],
        }]
      : []),
    {
      num: "05",
      title: "费用与盈亏",
      fields: [
        { label: "开仓手续费", value: p.openFee != null ? formatMoney(p.openFee, ACCOUNT_CURRENCY, 4) : null, emphasis: "fee" },
        {
          label: "费率",
          value: p.openFeeRate ? formatPercent(Number(p.openFeeRate) * 100, 3) : null,
          mono: true,
          emphasis: "muted",
        },
        {
          label: "未实现盈亏",
          value: liveUnrealizedPnl != null ? formatPnl(liveUnrealizedPnl) : null,
          mono: true,
          emphasis: pnl != null && pnl >= 0 ? "long" : "short",
        },
        {
          label: "净盈亏",
          value: netPnl == null ? null : formatSignedPnl(netPnl),
          mono: true,
          emphasis: netPnl != null && netPnl >= 0 ? "long" : "short",
          tooltip: "净盈亏 = 未实现盈亏 - 已扣开仓手续费",
        },
      ],
    },
    {
      num: "06",
      title: "状态与时间",
      fields: [
        { label: "状态", value: "持仓中", emphasis: "cyan" },
        { label: "行情来源", value: p.quoteSource, emphasis: "muted" },
        { label: "开仓时间", value: p.openedAt?.replace("T", " ").slice(0, 19), mono: true },
        { label: "更新时间", value: p.updatedAt?.replace("T", " ").slice(0, 19), mono: true },
      ],
    },
  ];
}

interface Props {
  active: boolean;
  pnlMap?: Map<string, PositionPnlUpdate>;
  /**
   * 后端聚合并 500ms 节流推送的「总未实现盈亏」。
   * 优先使用该值显示；null 时 fallback 当前可见 items + pnlMap 客户端累加。
   */
  serverTotalUnrealizedPnl?: string | null;
}

export function PositionsTable({ active, pnlMap, serverTotalUnrealizedPnl }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;

  // 不轮询：mutate onSuccess 的 invalidateQueries 覆盖所有用户主动操作；
  // 价格驱动的 unrealizedPnl 实时刷新留给后续 WS 推送（trading-core 已有 TradingUserRealtimePushService，
  // 待 gateway 补 /ws/v1/trading 代理后启用 WS 监听 OrderFilled/PositionClosed/MarkPriceTick 触发 invalidate）
  const query = useQuery({
    queryKey: ["trading", "positions", "OPEN"],
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return listPositions(token, 1, 50, "OPEN");
    },
    enabled: active && Boolean(token),
  });

  const { isMobile } = useBreakpoint();
  const symbolPrecision = useSymbolPrecision();
  const [closeTarget, setCloseTarget] = useState<PositionItem | null>(null);
  const [riskTarget, setRiskTarget] = useState<PositionItem | null>(null);
  const [marginTarget, setMarginTarget] = useState<PositionItem | null>(null);
  const [detailTarget, setDetailTarget] = useState<PositionItem | null>(null);
  const swapQuery = useQuery({
    queryKey: ["trading", "swap-summary", "position", detailTarget?.positionId],
    queryFn: () => getPositionSwapSummary(token!, detailTarget!.positionId),
    enabled: Boolean(token) && detailTarget != null,
    staleTime: 30_000,
  });

  const items = query.data?.items ?? [];

  // 总保证金：仍按当前可见 items 累加（不需要后端聚合）
  let totalMargin = 0;
  for (const p of items) {
    const margin = Number(p.margin);
    if (Number.isFinite(margin)) totalMargin += margin;
  }
  // 总未实现盈亏：优先用后端推的 user.position.summary（500ms 节流，跨 symbol 聚合）；
  // 后端 push 未到达前 fallback 当前可见 items + pnlMap 客户端累加
  let totalPnl: number | null = null;
  if (serverTotalUnrealizedPnl != null) {
    const n = Number(serverTotalUnrealizedPnl);
    if (Number.isFinite(n)) totalPnl = n;
  }
  if (totalPnl == null) {
    let fallback = 0;
    let available = false;
    for (const p of items) {
      const patch = pnlMap?.get(p.positionId);
      const pnlStr = patch?.unrealizedPnlInAccount ?? p.unrealizedPnlInAccount;
      if (pnlStr != null) {
        const num = Number(pnlStr);
        if (Number.isFinite(num)) {
          fallback += num;
          available = true;
        }
      }
    }
    totalPnl = available ? fallback : null;
  }
  const pnlAvailable = totalPnl != null;
  const totalPnlNum = totalPnl ?? 0;
  const totalPnlClass = !pnlAvailable ? "" : totalPnlNum > 0 ? "fx-pnl-pos" : totalPnlNum < 0 ? "fx-pnl-neg" : "";

  return (
    <div className="fx-tab-content">
      {query.isLoading && <div className="fx-tab-empty">加载中…</div>}
      {!query.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无持仓</div>}
      {items.length > 0 && (
        <div className="fx-positions-summary">
          <span>持仓数 <b>{items.length}</b></span>
          <span>总保证金 <b className="fx-num">{formatMoney(totalMargin, ACCOUNT_CURRENCY)}</b></span>
          <span>
            总未实现盈亏{" "}
            <b className={`fx-num ${totalPnlClass}`}>
              {pnlAvailable ? formatSignedPnl(totalPnlNum) : "—"}
            </b>
          </span>
        </div>
      )}
      {items.length > 0 && !isMobile && (
        <table className="fx-table">
          <thead>
            <tr>
              <th>持仓 ID</th>
              <th>品种</th>
              <th>方向</th>
              <th>数量</th>
              <th title="实际成交价（含 LP 报价 × 平台基准加点 + 用户组 markup 冻结值）">开仓价</th>
              <th title="标记价 = (bid + ask) / 2 中间价，用于风险计算 / 强平判定 / PnL；不含用户组 markup。">
                标记价 <span style={{ opacity: 0.4, fontSize: 11 }}>ⓘ</span>
              </th>
              <th title="使用含 markup 的有效价（与开仓口径对齐）计算的浮动盈亏，反映价格变化而非 markup 差。">未实现盈亏</th>
              <th>净盈亏</th>
              <th>杠杆</th>
              <th>保证金</th>
              <th>强平价</th>
              <th>TP</th>
              <th>SL</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((p) => (
              <PositionRow
                key={p.positionId}
                position={p}
                patch={pnlMap?.get(p.positionId)}
                pricePrecision={symbolPrecision(p.symbol).pricePrecision}
                qtyPrecision={symbolPrecision(p.symbol).qtyPrecision}
                onDetail={setDetailTarget}
                onClose={setCloseTarget}
                onRisk={setRiskTarget}
                onMargin={setMarginTarget}
              />
            ))}
          </tbody>
        </table>
      )}
      {items.length > 0 && isMobile && (
        <ul className="fx-positions-cards" aria-label="持仓列表">
          {items.map((p) => (
            <PositionItemCard
              key={p.positionId}
              position={p}
              pnl={pnlMap?.get(p.positionId)}
              pricePrecision={symbolPrecision(p.symbol).pricePrecision}
              qtyPrecision={symbolPrecision(p.symbol).qtyPrecision}
              onDetail={setDetailTarget}
              onClose={setCloseTarget}
              onAddMargin={setMarginTarget}
              onEditRiskControls={setRiskTarget}
            />
          ))}
        </ul>
      )}

      <ClosePositionModal open={closeTarget != null} position={closeTarget} onClose={() => setCloseTarget(null)} />
      <EditRiskControlsModal open={riskTarget != null} position={riskTarget} onClose={() => setRiskTarget(null)} />
      <AddMarginModal open={marginTarget != null} position={marginTarget} onClose={() => setMarginTarget(null)} />
      <OrderDetailModal
        open={detailTarget != null}
        title={detailTarget ? `持仓 ${detailTarget.symbol}` : ""}
        subtitle={
          detailTarget
            ? `${detailTarget.side === "BUY" ? "多" : "空"} · ${formatQty(detailTarget.quantity, symbolPrecision(detailTarget.symbol).qtyPrecision)} · ${detailTarget.leverage}x`
            : undefined
        }
        sections={
          detailTarget
            ? [
                ...buildOpenPositionDetail(
                  detailTarget,
                  pnlMap?.get(detailTarget.positionId)?.markPrice ?? detailTarget.markPrice,
                  pnlMap?.get(detailTarget.positionId)?.unrealizedPnlInAccount ?? detailTarget.unrealizedPnlInAccount,
                  symbolPrecision(detailTarget.symbol).pricePrecision,
                  symbolPrecision(detailTarget.symbol).qtyPrecision,
                ),
                buildSwapSection(swapQuery.data, swapQuery.isLoading),
              ]
            : []
        }
        onClose={() => setDetailTarget(null)}
      />
    </div>
  );
}

/** 雪花 ID 中段省略：保留前 6 + 后 4，hover title 可见完整 ID。 */
function shortenId(id: string): string {
  if (!id || id.length <= 12) return id;
  return `${id.slice(0, 6)}…${id.slice(-4)}`;
}

interface PositionRowProps {
  position: PositionItem;
  patch: PositionPnlUpdate | undefined;
  pricePrecision: number | undefined;
  qtyPrecision: number | undefined;
  onDetail: (p: PositionItem) => void;
  onClose: (p: PositionItem) => void;
  onRisk: (p: PositionItem) => void;
  onMargin: (p: PositionItem) => void;
}

/**
 * 单行持仓组件，用 React.memo 浅比较 + 自定义 areEqual。
 *
 * 性能动机：WS position.pnl 每 100ms 推送整张 pnlMap，TradingTerminal 父级 setState 会
 * 触发 PositionsTable 全表 re-render。把每行抽出 + memo 后只有「patch 变化的行」会
 * 重渲染，1000 持仓 + 50 品种场景下 re-render 工作量从 1000 → 平均 ~20。
 *
 * patch 比较：reference equality 即可，因为 useTradingSocket 把整张 pnlMap
 * 重建为新 Map 时，未变化的 (positionId → patch) 引用保持不变（hook 内复用旧对象）。
 * position 比较：reference equality —— useQuery staleTime=Infinity 下 items 数组在
 * invalidate 之间稳定，items[i] 引用也稳定。
 */
const PositionRow = memo(function PositionRow({
  position: p,
  patch,
  pricePrecision,
  qtyPrecision,
  onDetail,
  onClose,
  onRisk,
  onMargin,
}: PositionRowProps) {
  const markPrice = patch?.markPrice ?? p.markPrice;
  // STAGE-14E1 Task7：双币双行 —— 主行账户币（USDT）+ 副行报价币（quoteCurrency）。
  const unrealizedPnlStr = patch?.unrealizedPnlInAccount ?? p.unrealizedPnlInAccount;
  const unrealizedPnlInQuoteStr = patch?.unrealizedPnlInQuote ?? p.unrealizedPnlInQuote;
  const quoteCurrency = patch?.quoteCurrency ?? p.quoteCurrency;
  const showQuoteRow = shouldShowQuoteRow(quoteCurrency, unrealizedPnlInQuoteStr);
  const pnl = unrealizedPnlStr ? Number(unrealizedPnlStr) : null;
  const pnlClass = pnlColorClass(unrealizedPnlStr);
  const openFeeNum = p.openFee ? Number(p.openFee) : null;
  const netPnl = pnl != null && openFeeNum != null ? pnl - openFeeNum : null;
  const netClass = netPnl == null ? "" : netPnl < 0 ? "fx-pnl-neg" : netPnl > 0 ? "fx-pnl-pos" : "";
  // 费率保留「去尾零」特殊观感（如 0.05% 而非 0.0500%），不套 formatPercent 以免破坏现有展示。
  const feeRatePct = p.openFeeRate ? (Number(p.openFeeRate) * 100).toFixed(4).replace(/\.?0+$/, "") : null;
  const rowTooltip = openFeeNum != null
    ? `开仓已扣手续费 ${formatMoney(openFeeNum, ACCOUNT_CURRENCY, 4)} USDT${feeRatePct ? ` (按 ${feeRatePct}% 收取)` : ""}\n未实现盈亏（不含手续费）${pnl == null ? "—" : formatPnl(pnl)}\n净盈亏 = 未实现盈亏 - 已扣手续费`
    : "尚未读到本仓位手续费数据";
  return (
    <tr title={rowTooltip}>
      <td className="fx-mono fx-truncate" title={p.positionId}>{shortenId(p.positionId)}</td>
      <td>{p.symbol}</td>
      <td className={p.side === "BUY" ? "fx-long" : "fx-short"}>{p.side === "BUY" ? "多" : "空"}</td>
      <td className="fx-num">{formatQty(p.quantity, qtyPrecision)}</td>
      <td className="fx-num">{formatPrice(p.entryPrice, pricePrecision)}</td>
      <td className="fx-num">{markPrice != null ? formatPrice(markPrice, pricePrecision) : "—"}</td>
      <td className="fx-num">
        <div className={`fx-pnl-dual ${pnlClass}`}>
          <span className="fx-pnl-dual__main">
            {unrealizedPnlStr != null ? formatPnl(unrealizedPnlStr) : "—"}
            <span className="fx-pnl-dual__ccy">{ACCOUNT_CURRENCY}</span>
          </span>
          {showQuoteRow && (
            <span className="fx-pnl-dual__sub" title="以持仓报价币计的未实现盈亏（账户币副口径）">
              {formatPnl(unrealizedPnlInQuoteStr)}
              <span className="fx-pnl-dual__ccy">{quoteCurrency}</span>
            </span>
          )}
        </div>
      </td>
      <td className={`fx-num ${netClass}`} title={openFeeNum != null ? `扣除手续费 ${formatMoney(openFeeNum, ACCOUNT_CURRENCY, 4)}` : ""}>
        {netPnl == null ? "—" : formatSignedPnl(netPnl)}
      </td>
      <td className="fx-num">{p.leverage}</td>
      <td className="fx-num">{formatMoney(p.margin, ACCOUNT_CURRENCY)}</td>
      <td className="fx-num fx-pnl-neg">{p.liquidationPrice != null ? formatPrice(p.liquidationPrice, pricePrecision) : "—"}</td>
      <td className="fx-num">{p.takeProfitPrice != null ? formatPrice(p.takeProfitPrice, pricePrecision) : "—"}</td>
      <td className="fx-num">{p.stopLossPrice != null ? formatPrice(p.stopLossPrice, pricePrecision) : "—"}</td>
      <td className="fx-actions">
        <button type="button" className="fx-btn-secondary fx-btn-xs" onClick={() => onDetail(p)}>详情</button>
        <button type="button" className="fx-btn-danger fx-btn-xs" onClick={() => onClose(p)}>平仓</button>
        <button type="button" className="fx-btn-secondary fx-btn-xs" onClick={() => onRisk(p)}>TP/SL</button>
        <button type="button" className="fx-btn-secondary fx-btn-xs" onClick={() => onMargin(p)}>补保</button>
      </td>
    </tr>
  );
});
