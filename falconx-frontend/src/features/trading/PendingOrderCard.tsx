import type { PendingOrderItem } from "./tradingTypes";
import { formatMoney, formatPrice, formatQty } from "../../lib/precision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";

export interface PendingOrderCardProps {
  item: PendingOrderItem;
  pricePrecision?: number;
  qtyPrecision?: number;
  onDetail: (item: PendingOrderItem) => void;
  onEdit: (item: PendingOrderItem) => void;
  onCancel: (item: PendingOrderItem) => void;
  cancelDisabled: boolean;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

export function PendingOrderCard({
  item,
  pricePrecision,
  qtyPrecision,
  onDetail,
  onEdit,
  onCancel,
  cancelDisabled,
}: PendingOrderCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";
  const isPending = item.status === "PENDING";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__type">{item.orderType}</span>
        <span className="fx-history-card__status">{item.status}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">触发</span>
        <span>{formatPrice(item.triggerPrice, pricePrecision)}</span>
        <span className="fx-history-card__arrow">→</span>
        <span className="fx-history-card__label">限价</span>
        <span>{item.limitPrice != null ? formatPrice(item.limitPrice, pricePrecision) : "—"}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">数量</span>
        <span>{formatQty(item.quantity, qtyPrecision)}</span>
        <span className="fx-history-card__label">杠杆</span>
        <span>{item.leverage}x</span>
        <span className="fx-history-card__label">冻结</span>
        <span>{formatMoney(item.frozenMargin, ACCOUNT_CURRENCY)}</span>
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">{formatTime(item.createdAt)}</span>
        <div className="fx-history-card__actions">
          {isPending && (
            <>
              <button
                type="button"
                className="fx-history-card__action-btn"
                onClick={() => onEdit(item)}
              >
                修改
              </button>
              <button
                type="button"
                className="fx-history-card__action-btn fx-history-card__action-btn--danger"
                disabled={cancelDisabled}
                onClick={() => onCancel(item)}
              >
                撤销
              </button>
            </>
          )}
        </div>
      </div>
    </li>
  );
}
