import { useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { cancelPendingOrder, listPendingOrders, modifyPendingOrder } from "./tradingApi";
import { TablePaginationFooter } from "./TablePaginationFooter";
import { OrderDetailModal, type DetailSection } from "./OrderDetailModal";
import type { PendingOrderItem } from "./tradingTypes";
import { useBreakpoint } from "../../lib/responsive";
import { PendingOrderCard } from "./PendingOrderCard";
import { useSymbolPrecision } from "../market/useSymbolPrecision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";
import { formatMoney, formatPrice, formatQty } from "../../lib/precision";

function buildPendingDetail(
  o: PendingOrderItem,
  pricePrecision: number | undefined,
  qtyPrecision: number | undefined,
): DetailSection[] {
  return [
    {
      num: "01",
      title: "挂单标识",
      fields: [
        { label: "订单号", value: o.orderNo, mono: true, fullWidth: true },
        { label: "客户端 ID", value: o.clientOrderId, mono: true, fullWidth: true },
        { label: "父持仓 ID", value: o.parentPositionId, mono: true, fullWidth: true },
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
        { label: "挂单类型", value: ORDER_TYPE_LABEL[o.orderType] ?? o.orderType },
        { label: "数量", value: formatQty(o.quantity, qtyPrecision), mono: true },
      ],
    },
    {
      num: "03",
      title: "触发与价格",
      fields: [
        { label: "触发价", value: formatPrice(o.triggerPrice, pricePrecision), mono: true, emphasis: "cyan" },
        { label: "限价", value: o.limitPrice != null ? formatPrice(o.limitPrice, pricePrecision) : null, mono: true },
        { label: "杠杆", value: `${o.leverage}x`, mono: true },
        { label: "保证金模式", value: o.marginMode },
        o.triggerKind
          ? { label: "触发类型", value: o.triggerKind === "TAKE_PROFIT" ? "止盈" : "止损" }
          : null,
      ].filter(Boolean) as DetailSection["fields"],
    },
    {
      num: "04",
      title: "冻结资金",
      fields: [
        { label: "冻结保证金", value: formatMoney(o.frozenMargin, ACCOUNT_CURRENCY), mono: true },
        { label: "冻结手续费", value: o.frozenFee != null ? formatMoney(o.frozenFee, ACCOUNT_CURRENCY, 4) : null, emphasis: "fee" },
      ],
    },
    {
      num: "05",
      title: "状态与时间",
      fields: [
        { label: "状态", value: STATUS_LABEL[o.status] ?? o.status, emphasis: "cyan" },
        { label: "创建时间", value: o.createdAt?.replace("T", " ").slice(0, 19), mono: true },
        { label: "更新时间", value: o.updatedAt?.replace("T", " ").slice(0, 19), mono: true },
        o.triggeredAt
          ? { label: "触发时间", value: o.triggeredAt.replace("T", " ").slice(0, 19), mono: true }
          : null,
        o.triggeredOrderId
          ? { label: "触发订单 ID", value: o.triggeredOrderId, mono: true, fullWidth: true }
          : null,
        o.cancelledAt
          ? { label: "撤销时间", value: o.cancelledAt.replace("T", " ").slice(0, 19), mono: true }
          : null,
        o.cancelReason
          ? { label: "撤销原因", value: o.cancelReason, emphasis: "short", fullWidth: true }
          : null,
      ].filter(Boolean) as DetailSection["fields"],
    },
  ];
}

const PAGE_SIZE = 30;

const ORDER_TYPE_LABEL: Record<string, string> = {
  LIMIT: "限价",
  STOP: "止损",
  STOP_LIMIT: "止损限价",
};

const STATUS_LABEL: Record<string, string> = {
  PENDING: "等待中",
  TRIGGERED: "已触发",
  CANCELLED: "已撤销",
  EXPIRED: "已过期",
  REJECTED: "已拒绝",
};

interface Props {
  active: boolean;
}

export function PendingOrdersTable({ active }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();

  const [page, setPage] = useState(1);
  const query = useQuery({
    queryKey: ["trading", "pending", page],
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return listPendingOrders(token, page, PAGE_SIZE);
    },
    enabled: active && Boolean(token),
  });

  const cancelMutation = useMutation({
    mutationFn: (id: string) => {
      if (!token) throw new Error("Not authenticated");
      return cancelPendingOrder(token, id);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["trading"] }),
  });

  const { isMobile } = useBreakpoint();
  const symbolPrecision = useSymbolPrecision();
  const [editTarget, setEditTarget] = useState<PendingOrderItem | null>(null);
  const [detailTarget, setDetailTarget] = useState<PendingOrderItem | null>(null);

  // STAGE-3-PENDING-ORDER：只显示开仓挂单；SL_TP 显示在持仓行（后端已过滤）
  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;

  return (
    <div className="fx-tab-content">
      {query.isLoading && <div className="fx-tab-empty">加载中…</div>}
      {!query.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无挂单</div>}
      {items.length > 0 && (
        <>
        {!isMobile && (
        <table className="fx-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>品种</th>
              <th>类型</th>
              <th>方向</th>
              <th>数量</th>
              <th>触发价</th>
              <th>限价</th>
              <th>杠杆</th>
              <th>冻结保证金</th>
              <th>冻结手续费</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((o) => {
              const { pricePrecision, qtyPrecision } = symbolPrecision(o.symbol);
              return (
              <tr key={o.id}>
                <td className="fx-mono">{o.orderNo}</td>
                <td>{o.symbol}</td>
                <td>{ORDER_TYPE_LABEL[o.orderType] ?? o.orderType}</td>
                <td className={o.side === "BUY" ? "fx-long" : "fx-short"}>{o.side === "BUY" ? "多" : "空"}</td>
                <td className="fx-num">{formatQty(o.quantity, qtyPrecision)}</td>
                <td className="fx-num">{formatPrice(o.triggerPrice, pricePrecision)}</td>
                <td className="fx-num">{o.limitPrice != null ? formatPrice(o.limitPrice, pricePrecision) : "—"}</td>
                <td className="fx-num">{o.leverage}</td>
                <td className="fx-num">{formatMoney(o.frozenMargin, ACCOUNT_CURRENCY)}</td>
                <td className="fx-num">{formatMoney(o.frozenFee, ACCOUNT_CURRENCY, 4)}</td>
                <td>{STATUS_LABEL[o.status] ?? o.status}</td>
                <td>{o.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                <td className="fx-actions">
                  <button type="button" className="fx-btn-secondary fx-btn-xs" onClick={() => setDetailTarget(o)}>详情</button>
                  {o.status === "PENDING" && (
                    <>
                      <button type="button" className="fx-btn-secondary fx-btn-xs" onClick={() => setEditTarget(o)}>修改</button>
                      <button
                        type="button"
                        className="fx-btn-danger fx-btn-xs"
                        disabled={cancelMutation.isPending}
                        onClick={() => cancelMutation.mutate(o.id)}
                      >
                        撤销
                      </button>
                    </>
                  )}
                </td>
              </tr>
              );
            })}
          </tbody>
        </table>
        )}
        {isMobile && (
          <ul className="fx-history-cards" aria-label="挂单列表">
            {items.map((o) => (
              <PendingOrderCard
                key={o.id}
                item={o}
                pricePrecision={symbolPrecision(o.symbol).pricePrecision}
                qtyPrecision={symbolPrecision(o.symbol).qtyPrecision}
                onDetail={setDetailTarget}
                onEdit={setEditTarget}
                onCancel={(item) => cancelMutation.mutate(item.id)}
                cancelDisabled={cancelMutation.isPending}
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
      <EditPendingOrderModal
        open={editTarget != null}
        target={editTarget}
        onClose={() => setEditTarget(null)}
      />
      <OrderDetailModal
        open={detailTarget != null}
        title={detailTarget ? `挂单 ${detailTarget.orderNo}` : ""}
        subtitle={
          detailTarget
            ? `${detailTarget.symbol} · ${detailTarget.side === "BUY" ? "多" : "空"} · ${ORDER_TYPE_LABEL[detailTarget.orderType] ?? detailTarget.orderType}`
            : undefined
        }
        sections={
          detailTarget
            ? buildPendingDetail(
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

interface ModalProps {
  open: boolean;
  target: PendingOrderItem | null;
  onClose: () => void;
}

function EditPendingOrderModal({ open, target, onClose }: ModalProps) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();
  const [triggerPrice, setTriggerPrice] = useState("");
  const [limitPrice, setLimitPrice] = useState("");
  const [quantity, setQuantity] = useState("");
  const [error, setError] = useState<string | null>(null);

  // 打开时初始化字段
  if (open && target && triggerPrice === "" && limitPrice === "" && quantity === "") {
    setTriggerPrice(target.triggerPrice);
    setLimitPrice(target.limitPrice ?? "");
    setQuantity(target.quantity);
  }

  const mutation = useMutation({
    mutationFn: () => {
      if (!token || !target) throw new Error("Not authenticated");
      return modifyPendingOrder(token, target.id, {
        triggerPrice: triggerPrice || undefined,
        limitPrice: limitPrice || undefined,
        quantity: quantity || undefined,
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["trading", "pending"] });
      setTriggerPrice(""); setLimitPrice(""); setQuantity("");
      setError(null);
      onClose();
    },
    onError: (err) => setError((err as Error).message),
  });

  if (!open || !target) return null;

  return createPortal(
    <div className="fx-modal-mask" role="dialog" aria-modal="true">
      <div className="fx-modal">
        <div className="fx-modal-title">修改挂单 {target.orderNo}</div>
        <div className="fx-modal-body">
          <label>
            触发价
            <input
              type="number" step="0.00000001"
              value={triggerPrice}
              onChange={(e) => setTriggerPrice(e.target.value)}
            />
          </label>
          {target.orderType === "STOP_LIMIT" && (
            <label>
              限价
              <input
                type="number" step="0.00000001"
                value={limitPrice}
                onChange={(e) => setLimitPrice(e.target.value)}
              />
            </label>
          )}
          <label>
            数量
            <input
              type="number" step="0.00000001"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </label>
          {error && <div className="fx-modal-error">{error}</div>}
        </div>
        <div className="fx-modal-actions">
          <button type="button" className="fx-btn-secondary" onClick={() => { setTriggerPrice(""); setLimitPrice(""); setQuantity(""); onClose(); }}>取消</button>
          <button type="button" className="fx-btn-primary" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
            {mutation.isPending ? "保存中…" : "保存"}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
