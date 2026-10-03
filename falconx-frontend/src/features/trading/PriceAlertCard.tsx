import { ArrowDown, ArrowUp } from "lucide-react";
import type { PriceAlertItem } from "./tradingTypes";

export interface PriceAlertCardProps {
  item: PriceAlertItem;
  onCancel: (item: PriceAlertItem) => void;
  cancelDisabled: boolean;
}

function formatTime(value: string | null): string {
  if (!value) return "—";
  return value.replace("T", " ").slice(0, 19);
}

export function PriceAlertCard({ item, onCancel, cancelDisabled }: PriceAlertCardProps) {
  const isAbove = item.direction === "ABOVE";
  const directionLabel = isAbove ? "上穿" : "下穿";
  const directionClass = isAbove ? "fx-long" : "fx-short";
  const DirectionIcon = isAbove ? ArrowUp : ArrowDown;
  const isActive = item.status === "ACTIVE";

  return (
    <li className="fx-history-card">
      <div className="fx-history-card__row1">
        <strong className="fx-history-card__symbol">{item.symbol}</strong>
        <span className={`fx-history-card__side ${directionClass}`}>
          <DirectionIcon size={12} strokeWidth={2.2} aria-hidden="true" />
          {directionLabel}
        </span>
        <span className="fx-history-card__status">{item.status}</span>
      </div>
      <div className="fx-history-card__row2">
        <span className="fx-history-card__label">触发</span>
        <span>{item.targetPrice}</span>
        <span className="fx-history-card__label">基准</span>
        <span>{item.basePrice ?? "—"}</span>
      </div>
      <div className="fx-history-card__row3">
        <span className="fx-history-card__label">触发</span>
        <span>{item.triggerCount} / 3</span>
        <span className="fx-history-card__label">剩</span>
        <span>{item.remainingTriggers}</span>
        <span className="fx-history-card__label">最近</span>
        <span>{formatTime(item.lastTriggeredAt)}</span>
      </div>
      {item.note && (
        <div className="fx-history-card__note">「{item.note}」</div>
      )}
      <div className="fx-history-card__footer">
        <span className="fx-history-card__time">{formatTime(item.createdAt)}</span>
        <div className="fx-history-card__actions">
          {isActive && (
            <button
              type="button"
              className="fx-history-card__action-btn fx-history-card__action-btn--danger"
              disabled={cancelDisabled}
              onClick={() => onCancel(item)}
            >
              取消
            </button>
          )}
        </div>
      </div>
    </li>
  );
}
