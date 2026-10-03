import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { getPositionSwapSummary, listPositions } from "./tradingApi";
import { OrderDetailModal, type DetailSection } from "./OrderDetailModal";
import { buildSwapSection } from "./positionDetailSections";
import type { PositionItem } from "./tradingTypes";
import { useBreakpoint } from "../../lib/responsive";
import { formatMoney, formatPercent, formatPnl, formatPrice, formatQty } from "../../lib/precision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";
import { useSymbolPrecision } from "../market/useSymbolPrecision";
import { ClosedPositionCard } from "./ClosedPositionCard";

interface Props {
  active: boolean;
}

const PAGE_SIZE = 30;

function buildPositionDetail(
  p: PositionItem,
  pricePrecision: number | undefined,
  qtyPrecision: number | undefined,
): DetailSection[] {
  const pnl = p.realizedPnl != null ? Number(p.realizedPnl) : null;
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
        { label: "保证金模式", value: p.marginMode },
      ],
    },
    {
      num: "03",
      title: "开仓 / 平仓",
      fields: [
        { label: "开仓价", value: formatPrice(p.entryPrice, pricePrecision), mono: true },
        { label: "平仓价", value: p.closePrice != null ? formatPrice(p.closePrice, pricePrecision) : null, mono: true, emphasis: "cyan" },
        { label: "强平价", value: p.liquidationPrice != null ? formatPrice(p.liquidationPrice, pricePrecision) : null, mono: true, emphasis: "muted" },
        { label: "保证金", value: formatMoney(p.margin, ACCOUNT_CURRENCY), mono: true },
      ],
    },
    {
      num: "04",
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
          label: "已实现盈亏",
          value: pnl == null ? null : formatPnl(pnl),
          mono: true,
          emphasis: pnl != null && pnl >= 0 ? "long" : "short",
        },
      ],
    },
    {
      num: "05",
      title: "状态与时间",
      fields: [
        {
          label: "状态",
          value: p.status === "LIQUIDATED" ? "已强平" : "已平仓",
          emphasis: p.status === "LIQUIDATED" ? "short" : "cyan",
        },
        {
          label: "平仓原因",
          value: p.closeReason,
          emphasis: "muted",
          fullWidth: true,
        },
        { label: "开仓时间", value: p.openedAt?.replace("T", " ").slice(0, 19), mono: true },
        { label: "平仓时间", value: p.closedAt?.replace("T", " ").slice(0, 19), mono: true },
      ],
    },
  ];
}

/**
 * 历史持仓 tab：列出 CLOSED + LIQUIDATED 持仓，按 updated_at DESC 分页。
 * 与「持仓」tab 共享 listPositions API，仅 status 过滤不同。
 */
export function ClosedPositionsTable({ active }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const [page, setPage] = useState(1);

  const query = useQuery({
    queryKey: ["trading", "positions", "HISTORY", page],
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return listPositions(token, page, PAGE_SIZE, "CLOSED,LIQUIDATED");
    },
    enabled: active && Boolean(token),
  });

  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const { isMobile } = useBreakpoint();
  const symbolPrecision = useSymbolPrecision();
  const [detailTarget, setDetailTarget] = useState<PositionItem | null>(null);
  const swapQuery = useQuery({
    queryKey: ["trading", "swap-summary", "position", detailTarget?.positionId],
    queryFn: () => getPositionSwapSummary(token!, detailTarget!.positionId),
    enabled: Boolean(token) && detailTarget != null,
    staleTime: 30_000,
  });

  return (
    <div className="fx-tab-content">
      {query.isLoading && <div className="fx-tab-empty">加载中…</div>}
      {!query.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无历史持仓</div>}
      {items.length > 0 && (
        <>
          {!isMobile && (
            <table className="fx-table">
              <thead>
                <tr>
                  <th>品种</th>
                  <th>方向</th>
                  <th>数量</th>
                  <th>开仓价</th>
                  <th>平仓价</th>
                  <th>杠杆</th>
                  <th>开仓手续费</th>
                  <th>已实现盈亏</th>
                  <th>平仓原因</th>
                  <th>开仓时间</th>
                  <th>平仓时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((p) => {
                  const pnl = p.realizedPnl ? Number(p.realizedPnl) : null;
                  const pnlClass = pnl == null ? "" : pnl < 0 ? "fx-pnl-neg" : pnl > 0 ? "fx-pnl-pos" : "";
                  const { pricePrecision, qtyPrecision } = symbolPrecision(p.symbol);
                  return (
                    <tr key={p.positionId}>
                      <td>{p.symbol}</td>
                      <td className={p.side === "BUY" ? "fx-long" : "fx-short"}>{p.side === "BUY" ? "多" : "空"}</td>
                      <td className="fx-num">{formatQty(p.quantity, qtyPrecision)}</td>
                      <td className="fx-num">{formatPrice(p.entryPrice, pricePrecision)}</td>
                      <td className="fx-num">{p.closePrice != null ? formatPrice(p.closePrice, pricePrecision) : "—"}</td>
                      <td className="fx-num">{p.leverage}</td>
                      <td className="fx-num">{p.openFee != null ? formatMoney(p.openFee, ACCOUNT_CURRENCY, 4) : "—"}</td>
                      <td className={`fx-num ${pnlClass}`}>{pnl == null ? "—" : formatPnl(pnl)}</td>
                      <td>{renderCloseReason(p.closeReason, p.status)}</td>
                      <td>{p.openedAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                      <td>{p.closedAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                      <td className="fx-actions">
                        <button
                          type="button"
                          className="fx-btn-secondary fx-btn-xs"
                          onClick={() => setDetailTarget(p)}
                        >
                          详情
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {isMobile && (
            <ul className="fx-history-cards" aria-label="已平仓列表">
              {items.map((p) => (
                <ClosedPositionCard
                  key={p.positionId}
                  item={p}
                  pricePrecision={symbolPrecision(p.symbol).pricePrecision}
                  qtyPrecision={symbolPrecision(p.symbol).qtyPrecision}
                  onDetail={setDetailTarget}
                />
              ))}
            </ul>
          )}
          {totalPages > 1 && (
            <div style={{ display: "flex", gap: 12, alignItems: "center", padding: "8px 0", justifyContent: "flex-end" }}>
              <button
                type="button"
                className="fx-btn-secondary fx-btn-xs"
                disabled={page <= 1 || query.isFetching}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
              >
                上一页
              </button>
              <span className="fx-mono">{page} / {totalPages}</span>
              <button
                type="button"
                className="fx-btn-secondary fx-btn-xs"
                disabled={page >= totalPages || query.isFetching}
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              >
                下一页
              </button>
              <span className="fx-mono">共 {total} 条</span>
            </div>
          )}
        </>
      )}
      <OrderDetailModal
        open={detailTarget != null}
        title={detailTarget ? `持仓 ${detailTarget.symbol}` : ""}
        subtitle={
          detailTarget
            ? `${detailTarget.side === "BUY" ? "多" : "空"} · ${formatQty(detailTarget.quantity, symbolPrecision(detailTarget.symbol).qtyPrecision)} · ${detailTarget.status === "LIQUIDATED" ? "已强平" : "已平仓"}`
            : undefined
        }
        sections={
          detailTarget
            ? [
                ...buildPositionDetail(
                  detailTarget,
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

function renderCloseReason(reason: string | null, status: string): string {
  if (status === "LIQUIDATED") return reason ?? "强平";
  if (status === "CLOSED") return reason ?? "手动平仓";
  return reason ?? "—";
}
