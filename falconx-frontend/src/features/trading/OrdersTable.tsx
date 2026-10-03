import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { listOrders } from "./tradingApi";
import { TablePaginationFooter } from "./TablePaginationFooter";
import { OrderDetailModal, type DetailSection } from "./OrderDetailModal";
import type { OrderItem } from "./tradingTypes";
import { useBreakpoint } from "../../lib/responsive";
import { OrderCard } from "./OrderCard";
import { useSymbolPrecision } from "../market/useSymbolPrecision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";
import { formatMoney, formatPrice, formatQty } from "../../lib/precision";

const ORDER_TYPE_LABEL: Record<string, string> = {
  MARKET: "市价",
  LIMIT: "限价",
  STOP: "止损",
  STOP_LIMIT: "止损限价",
};

function buildOrderDetail(
  o: OrderItem,
  pricePrecision: number | undefined,
  qtyPrecision: number | undefined,
): DetailSection[] {
  const status = STATUS_LABEL[o.status] ?? { label: o.status };
  return [
    {
      num: "01",
      title: "订单标识",
      fields: [
        { label: "订单号", value: o.orderNo, mono: true, fullWidth: true },
        { label: "客户端 ID", value: o.clientOrderId, mono: true, fullWidth: true },
      ],
    },
    {
      num: "02",
      title: "交易要素",
      fields: [
        { label: "品种", value: o.symbol, mono: true },
        {
          label: "方向",
          value: o.side === "BUY" ? "多 (BUY)" : "空 (SELL)",
          emphasis: o.side === "BUY" ? "long" : "short",
        },
        { label: "订单类型", value: ORDER_TYPE_LABEL[o.orderType] ?? o.orderType },
        { label: "状态", value: status.label, emphasis: "cyan" },
      ],
    },
    {
      num: "03",
      title: "数量与价格",
      fields: [
        { label: "数量", value: formatQty(o.quantity, qtyPrecision), mono: true },
        { label: "杠杆", value: `${o.leverage}x`, mono: true },
        { label: "委托价", value: o.requestedPrice != null ? formatPrice(o.requestedPrice, pricePrecision) : null, mono: true },
        { label: "成交价", value: o.filledPrice != null ? formatPrice(o.filledPrice, pricePrecision) : null, mono: true, emphasis: "cyan" },
      ],
    },
    {
      num: "04",
      title: "资金与费用",
      fields: [
        { label: "保证金", value: formatMoney(o.margin, ACCOUNT_CURRENCY), mono: true },
        { label: "手续费", value: o.fee != null ? formatMoney(o.fee, ACCOUNT_CURRENCY, 4) : null, emphasis: "fee" },
      ],
    },
    {
      num: "05",
      title: "时间线",
      fields: [
        { label: "创建时间", value: o.createdAt?.replace("T", " ").slice(0, 19), mono: true },
        { label: "更新时间", value: o.updatedAt?.replace("T", " ").slice(0, 19), mono: true },
        o.rejectReason
          ? { label: "拒绝原因", value: o.rejectReason, emphasis: "short", fullWidth: true }
          : null,
      ].filter(Boolean) as DetailSection["fields"],
    },
  ];
}

const PAGE_SIZE = 30;

const STATUS_LABEL: Record<string, { label: string; cls: string }> = {
  PENDING: { label: "待处理", cls: "fx-badge-info" },
  FILLED: { label: "已成交", cls: "fx-badge-ok" },
  REJECTED: { label: "已拒绝", cls: "fx-badge-err" },
  CANCELED: { label: "已撤销", cls: "fx-badge-muted" },
};

interface Props {
  active: boolean;
}

export function OrdersTable({ active }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const [page, setPage] = useState(1);
  const { isMobile } = useBreakpoint();
  const symbolPrecision = useSymbolPrecision();
  const [detailTarget, setDetailTarget] = useState<OrderItem | null>(null);

  // 不轮询：mutate invalidateQueries 覆盖主动操作；WS OrderFilled/OrderRejected 推送 → invalidate
  const query = useQuery({
    queryKey: ["trading", "orders", page],
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return listOrders(token, page, PAGE_SIZE);
    },
    enabled: active && Boolean(token),
  });

  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;

  return (
    <div className="fx-tab-content">
      {query.isLoading && <div className="fx-tab-empty">加载中…</div>}
      {!query.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无订单</div>}
      {items.length > 0 && (
        <>
        {!isMobile && (
          <table className="fx-table">
            <thead>
              <tr>
                <th>订单号</th>
                <th>品种</th>
                <th>方向</th>
                <th>类型</th>
                <th>数量</th>
                <th>成交价</th>
                <th>杠杆</th>
                <th>保证金</th>
                <th>手续费</th>
                <th>状态</th>
                <th>原因</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((o) => {
                const st = STATUS_LABEL[o.status] ?? { label: o.status, cls: "fx-badge-muted" };
                const { pricePrecision, qtyPrecision } = symbolPrecision(o.symbol);
                return (
                  <tr key={o.orderId}>
                    <td className="fx-mono">{o.orderNo}</td>
                    <td>{o.symbol}</td>
                    <td className={o.side === "BUY" ? "fx-long" : "fx-short"}>{o.side === "BUY" ? "多" : "空"}</td>
                    <td>{o.orderType}</td>
                    <td className="fx-num">{formatQty(o.quantity, qtyPrecision)}</td>
                    <td className="fx-num">{o.filledPrice != null ? formatPrice(o.filledPrice, pricePrecision) : "—"}</td>
                    <td className="fx-num">{o.leverage}</td>
                    <td className="fx-num">{formatMoney(o.margin, ACCOUNT_CURRENCY)}</td>
                    <td className="fx-num">{formatMoney(o.fee, ACCOUNT_CURRENCY, 4)}</td>
                    <td><span className={`fx-badge ${st.cls}`}>{st.label}</span></td>
                    <td className="fx-truncate" title={o.rejectReason ?? ""}>{o.rejectReason ?? "—"}</td>
                    <td>{o.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                    <td className="fx-actions">
                      <button
                        type="button"
                        className="fx-btn-secondary fx-btn-xs"
                        onClick={() => setDetailTarget(o)}
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
          <ul className="fx-history-cards" aria-label="订单列表">
            {items.map((o) => (
              <OrderCard
                key={o.orderId}
                item={o}
                pricePrecision={symbolPrecision(o.symbol).pricePrecision}
                qtyPrecision={symbolPrecision(o.symbol).qtyPrecision}
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
        title={detailTarget ? `订单 ${detailTarget.orderNo}` : ""}
        subtitle={detailTarget ? `${detailTarget.symbol} · ${detailTarget.side === "BUY" ? "多" : "空"} · ${ORDER_TYPE_LABEL[detailTarget.orderType] ?? detailTarget.orderType}` : undefined}
        sections={
          detailTarget
            ? buildOrderDetail(
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
