import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { listTrades } from "./tradingApi";
import { TablePaginationFooter } from "./TablePaginationFooter";
import { OrderDetailModal, type DetailSection } from "./OrderDetailModal";
import type { TradeItem } from "./tradingTypes";
import { useBreakpoint } from "../../lib/responsive";
import { formatMoney, formatPnl, formatPrice, formatQty } from "../../lib/precision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";
import { useSymbolPrecision } from "../market/useSymbolPrecision";
import { TradeCard } from "./TradeCard";

interface Props {
  active: boolean;
}

const PAGE_SIZE = 30;

function buildTradeDetail(
  t: TradeItem,
  pricePrecision: number | undefined,
  qtyPrecision: number | undefined,
): DetailSection[] {
  return [
    {
      num: "01",
      title: "成交标识",
      fields: [
        { label: "成交 ID", value: t.tradeId, mono: true, fullWidth: true },
        { label: "订单 ID", value: t.orderId, mono: true, fullWidth: true },
        { label: "持仓 ID", value: t.positionId, mono: true, fullWidth: true },
      ],
    },
    {
      num: "02",
      title: "交易要素",
      fields: [
        { label: "品种", value: t.symbol, mono: true },
        {
          label: "方向",
          value: t.side === "BUY" ? "多 (BUY)" : "空 (SELL)",
          emphasis: t.side === "BUY" ? "long" : "short",
        },
        { label: "成交类型", value: t.tradeType },
        { label: "数量", value: formatQty(t.quantity, qtyPrecision), mono: true },
        { label: "价格", value: formatPrice(t.price, pricePrecision), mono: true, emphasis: "cyan" },
      ],
    },
    {
      num: "03",
      title: "费用与盈亏",
      fields: [
        { label: "手续费", value: t.fee != null ? formatMoney(t.fee, ACCOUNT_CURRENCY, 4) : null, emphasis: "fee" },
        {
          label: "已实现盈亏",
          value: t.realizedPnl == null ? null : formatPnl(t.realizedPnl),
          mono: true,
          emphasis: t.realizedPnl != null && Number(t.realizedPnl) >= 0 ? "long" : "short",
        },
      ],
    },
    {
      num: "04",
      title: "时间",
      fields: [
        { label: "发生时间", value: t.tradedAt?.replace("T", " ").slice(0, 19), mono: true },
      ],
    },
  ];
}

export function TradesTable({ active }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const [page, setPage] = useState(1);

  // 不轮询：mutate invalidateQueries 覆盖主动操作；WS PositionClosed 推送 → invalidate
  const query = useQuery({
    queryKey: ["trading", "trades", page],
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return listTrades(token, page, PAGE_SIZE);
    },
    enabled: active && Boolean(token),
  });

  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const { isMobile } = useBreakpoint();
  const symbolPrecision = useSymbolPrecision();
  const [detailTarget, setDetailTarget] = useState<TradeItem | null>(null);

  return (
    <div className="fx-tab-content">
      {query.isLoading && <div className="fx-tab-empty">加载中…</div>}
      {!query.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无成交</div>}
      {items.length > 0 && (
        <>
          {!isMobile && (
            <table className="fx-table">
              <thead>
                <tr>
                  <th>成交 ID</th>
                  <th>品种</th>
                  <th>方向</th>
                  <th>类型</th>
                  <th>数量</th>
                  <th>价格</th>
                  <th>手续费</th>
                  <th>已实现盈亏</th>
                  <th>发生时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((t) => {
                  const pnl = t.realizedPnl ? Number(t.realizedPnl) : null;
                  const pnlClass = pnl == null ? "" : pnl < 0 ? "fx-pnl-neg" : pnl > 0 ? "fx-pnl-pos" : "";
                  const { pricePrecision, qtyPrecision } = symbolPrecision(t.symbol);
                  return (
                    <tr key={t.tradeId}>
                      <td className="fx-mono">{t.tradeId}</td>
                      <td>{t.symbol}</td>
                      <td className={t.side === "BUY" ? "fx-long" : "fx-short"}>{t.side === "BUY" ? "多" : "空"}</td>
                      <td>{t.tradeType}</td>
                      <td className="fx-num">{formatQty(t.quantity, qtyPrecision)}</td>
                      <td className="fx-num">{formatPrice(t.price, pricePrecision)}</td>
                      <td className="fx-num">{formatMoney(t.fee, ACCOUNT_CURRENCY, 4)}</td>
                      <td className={`fx-num ${pnlClass}`}>{pnl == null ? "—" : formatPnl(pnl)}</td>
                      <td>{t.tradedAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                      <td className="fx-actions">
                        <button
                          type="button"
                          className="fx-btn-secondary fx-btn-xs"
                          onClick={() => setDetailTarget(t)}
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
            <ul className="fx-history-cards" aria-label="成交列表">
              {items.map((t) => (
                <TradeCard
                  key={t.tradeId}
                  item={t}
                  pricePrecision={symbolPrecision(t.symbol).pricePrecision}
                  qtyPrecision={symbolPrecision(t.symbol).qtyPrecision}
                  onDetail={setDetailTarget}
                />
              ))}
            </ul>
          )}
          <TablePaginationFooter
            page={page}
            total={total}
            pageSize={PAGE_SIZE}
            fetching={query.isFetching}
            onChange={setPage}
          />
        </>
      )}
      <OrderDetailModal
        open={detailTarget != null}
        title={detailTarget ? `成交 ${detailTarget.tradeId}` : ""}
        subtitle={
          detailTarget
            ? `${detailTarget.symbol} · ${detailTarget.side === "BUY" ? "多" : "空"} · ${detailTarget.tradeType}`
            : undefined
        }
        sections={
          detailTarget
            ? buildTradeDetail(
                detailTarget,
                symbolPrecision(detailTarget.symbol).pricePrecision,
                symbolPrecision(detailTarget.symbol).qtyPrecision,
              )
            : []
        }
        onClose={() => setDetailTarget(null)}
      />
    </div>
  );
}
