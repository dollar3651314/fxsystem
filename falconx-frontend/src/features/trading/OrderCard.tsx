import type { OrderItem } from "./tradingTypes";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";
import { formatMoney, formatPrice, formatQty } from "../../lib/precision";

export interface OrderCardProps {
  item: OrderItem;
  pricePrecision?: number;
  qtyPrecision?: number;
  onDetail: (item: OrderItem) => void;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

export function OrderCard({ item, pricePrecision, qtyPrecision, onDetail }: OrderCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";
  const rawPrice = item.filledPrice ?? item.requestedPrice;
  const priceLabel = rawPrice != null ? formatPrice(rawPrice, pricePrecision) : "—";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__type">{item.orderType}</span>
        <span className="fx-history-card__status">{item.status}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">价</span>
        <span>{priceLabel}</span>
        <span className="fx-history-card__label">量</span>
        <span>{formatQty(item.quantity, qtyPrecision)}</span>
        <span className="fx-history-card__label">杠杆</span>
        <span>{item.leverage}x</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">保证金</span>
        <span>{formatMoney(item.margin, ACCOUNT_CURRENCY)}</span>
        <span className="fx-history-card__label">手续费</span>
        <span>{formatMoney(item.fee, ACCOUNT_CURRENCY, 4)}</span>
        {item.rejectReason && (
          <>
            <span className="fx-history-card__label">原因</span>
            <span className="fx-short">{item.rejectReason}</span>
          </>
        )}
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">{formatTime(item.createdAt)}</span>
        <div className="fx-history-card__actions">
          <button
            type="button"
            className="fx-history-card__action-btn"
            onClick={() => onDetail(item)}
          >
            详情
          </button>
        </div>
      </div>
    </li>
  );
}
