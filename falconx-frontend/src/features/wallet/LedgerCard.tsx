import type { LedgerEntry } from "./walletApi";
import { formatMoney } from "../../lib/precision";
import { formatLedgerAmount } from "./ledgerFormat";

export interface LedgerCardProps {
  item: LedgerEntry;
  typeLabel: string;
  currency: string;
}

function formatTime(value: string): string {
  return value.replace("T", " ").slice(0, 19);
}

/** 按余额变化方向着色（与桌面表一致）；无变化（内部移动）灰色。 */
function deltaClass(item: LedgerEntry): string {
  const before = Number(item.balanceBefore);
  const after = Number(item.balanceAfter);
  if (!Number.isFinite(before) || !Number.isFinite(after)) return "";
  const delta = after - before;
  if (Math.abs(delta) <= 1e-9) return "";
  return delta > 0 ? "fx-long" : "fx-short";
}

export function LedgerCard({ item, typeLabel, currency }: LedgerCardProps) {
  return (
    <li className="wallet-ledger-card">
      <div className="wallet-ledger-card__row1">
        <span className="wallet-ledger-card__type">{typeLabel}</span>
        <span className={`wallet-ledger-card__amount ${deltaClass(item)}`}>
          {formatLedgerAmount(item.amount, item.bizType, currency)}
        </span>
      </div>
      <div className="wallet-ledger-card__row2">
        <span className="wallet-ledger-card__time">{formatTime(item.createdAt)}</span>
        {item.referenceNo && (
          <span className="wallet-ledger-card__ref">{item.referenceNo}</span>
        )}
        <span className="wallet-ledger-card__balance">余 {formatMoney(item.balanceAfter, currency)}</span>
      </div>
    </li>
  );
}
