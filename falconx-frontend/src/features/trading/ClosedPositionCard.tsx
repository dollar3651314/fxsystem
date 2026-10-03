import type { PositionItem } from "./tradingTypes";
import { formatPnl, formatPrice, formatQty } from "../../lib/precision";

export interface ClosedPositionCardProps {
  item: PositionItem;
  pricePrecision?: number;
  qtyPrecision?: number;
  onDetail: (item: PositionItem) => void;
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

export function ClosedPositionCard({ item, pricePrecision, qtyPrecision, onDetail }: ClosedPositionCardProps) {
  const sideLabel = item.side === "BUY" ? "多" : "空";
  const sideClass = item.side === "BUY" ? "fx-long" : "fx-short";

  return (
    <li className="fx-history-card" onClick={() => onDetail(item)}>
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-history-card__qty">{formatQty(item.quantity, qtyPrecision)}</span>
        <span className="fx-history-card__type">{item.leverage}x</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">开</span>
        <span>{formatPrice(item.entryPrice, pricePrecision)}</span>
        <span className="fx-history-card__arrow">→</span>
        <span className="fx-history-card__label">平</span>
        <span>{item.closePrice != null ? formatPrice(item.closePrice, pricePrecision) : "—"}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">已实现</span>
        <span className={pnlClass(item.realizedPnl)}>{item.realizedPnl == null ? "—" : formatPnl(item.realizedPnl)}</span>
        {item.closeReason && (
          <>
            <span className="fx-history-card__label">原因</span>
            <span>{item.closeReason}</span>
          </>
        )}
      </div>
      <div className="fx-history-card__footer" onClick={(e) => e.stopPropagation()}>
        <span className="fx-history-card__time">
          {formatTime(item.closedAt ?? item.openedAt)}
        </span>
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
