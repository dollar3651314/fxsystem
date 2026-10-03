import type { TradeItem } from "./tradingTypes";
import { formatMoney, formatPnl, formatPrice, formatQty } from "../../lib/precision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";

export interface TradeCardProps {
  item: TradeItem;
  pricePrecision?: number;
  qtyPrecision?: number;
  onDetail: (item: TradeItem) => void;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

function pnlClass(value: string | null): string {
  if (value == null) return "";
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n > 0 ? "fx-long" : n < 0 ? "fx-short" : "";
}

export function TradeCard({ item, pricePrecision, qtyPrecision, onDetail }: TradeCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__type">{item.tradeType}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">价</span>
        <span>{formatPrice(item.price, pricePrecision)}</span>
        <span className="fx-history-card__label">量</span>
        <span>{formatQty(item.quantity, qtyPrecision)}</span>
        <span className="fx-history-card__label">费</span>
        <span>{formatMoney(item.fee, ACCOUNT_CURRENCY, 4)}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">已实现盈亏</span>
        <span className={pnlClass(item.realizedPnl)}>{item.realizedPnl == null ? "—" : formatPnl(item.realizedPnl)}</span>
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">{formatTime(item.tradedAt)}</span>
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
