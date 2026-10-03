import { MoreHorizontal, X } from "lucide-react";
import { useState } from "react";
import type { PositionItem } from "./tradingTypes";
import type { PositionPnlUpdate } from "./useTradingSocket";
import { ACCOUNT_CURRENCY, pnlSideClass, shouldShowQuoteRow } from "./pnlDisplay";
import { formatMoney, formatPnl, formatPrice, formatQty } from "../../lib/precision";

export interface PositionItemCardProps {
  position: PositionItem;
  pnl: PositionPnlUpdate | undefined;
  pricePrecision?: number;
  qtyPrecision?: number;
  onDetail: (p: PositionItem) => void;
  onClose: (p: PositionItem) => void;
  onAddMargin: (p: PositionItem) => void;
  onEditRiskControls: (p: PositionItem) => void;
}

export function PositionItemCard({
  position: p,
  pnl,
  pricePrecision,
  qtyPrecision,
  onDetail,
  onClose,
  onAddMargin,
  onEditRiskControls,
}: PositionItemCardProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const sideLabel = p.side === "BUY" ? "多" : "空";
  const sideClass = p.side === "BUY" ? "fx-long" : "fx-short";
  const markPrice = pnl?.markPrice ?? p.markPrice;
  // STAGE-14E1 Task7：双币 —— 主行账户币（USDT）+ 小字副行报价币（quoteCurrency）。
  const unrealizedPnl = pnl?.unrealizedPnlInAccount ?? p.unrealizedPnlInAccount;
  const unrealizedPnlInQuote = pnl?.unrealizedPnlInQuote ?? p.unrealizedPnlInQuote;
  const quoteCurrency = pnl?.quoteCurrency ?? p.quoteCurrency;
  const showQuoteRow = shouldShowQuoteRow(quoteCurrency, unrealizedPnlInQuote);

  return (
    <li className="fx-position-card" onClick={() => onDetail(p)}>
      <div className="fx-position-card__row1">
        <strong className="fx-position-card__symbol">{p.symbol}</strong>
        <span className={`fx-position-card__side ${sideClass}`}>{sideLabel}</span>
        <span className="fx-position-card__qty">{formatQty(p.quantity, qtyPrecision)}</span>
        <span className="fx-position-card__lev">{p.leverage}x</span>
      </div>
      <div className="fx-position-card__row2">
        <span className="fx-num">{formatPrice(p.entryPrice, pricePrecision)}</span>
        <span className="fx-position-card__arrow">→</span>
        <span className="fx-num">{markPrice != null ? formatPrice(markPrice, pricePrecision) : "—"}</span>
        <span className="fx-position-card__mark-label">标记</span>
      </div>
      <div className="fx-position-card__row3">
        <span className="fx-position-card__label">未实现</span>
        <span className={`fx-num ${pnlSideClass(unrealizedPnl)}`}>
          {unrealizedPnl == null ? "—" : formatPnl(unrealizedPnl)}
          <span className="fx-position-card__ccy">{ACCOUNT_CURRENCY}</span>
          {showQuoteRow && (
            <span className="fx-position-card__pnl-quote">
              {formatPnl(unrealizedPnlInQuote)} {quoteCurrency}
            </span>
          )}
        </span>
        <span className="fx-position-card__label">净盈</span>
        <span className={`fx-num ${pnlSideClass(p.realizedPnl)}`}>{p.realizedPnl == null ? "—" : formatPnl(p.realizedPnl)}</span>
      </div>
      <div className="fx-position-card__row4">
        <span className="fx-position-card__label">保证金</span>
        <span className="fx-num">{formatMoney(p.margin, ACCOUNT_CURRENCY)}</span>
        <span className="fx-position-card__label">强平</span>
        <span className="fx-num fx-short">{p.liquidationPrice != null ? formatPrice(p.liquidationPrice, pricePrecision) : "—"}</span>
      </div>
      <div className="fx-position-card__row5" onClick={(e) => e.stopPropagation()}>
        <span className="fx-position-card__label">TP</span>
        <span className="fx-num">{p.takeProfitPrice != null ? formatPrice(p.takeProfitPrice, pricePrecision) : "—"}</span>
        <span className="fx-position-card__sep">·</span>
        <span className="fx-position-card__label">SL</span>
        <span className="fx-num">{p.stopLossPrice != null ? formatPrice(p.stopLossPrice, pricePrecision) : "—"}</span>
        <div className="fx-position-card__actions">
          <button
            type="button"
            className="fx-position-card__more"
            aria-label="更多操作"
            onClick={() => setMenuOpen((v) => !v)}
          >
            <MoreHorizontal size={16} strokeWidth={1.8} />
          </button>
          <button
            type="button"
            className="fx-position-card__close"
            aria-label="平仓"
            onClick={() => onClose(p)}
          >
            <X size={16} strokeWidth={2} />
            平仓
          </button>
        </div>
      </div>
      {menuOpen && (
        <div className="fx-position-card__menu" onClick={(e) => e.stopPropagation()}>
          <button
            type="button"
            onClick={() => {
              setMenuOpen(false);
              onAddMargin(p);
            }}
          >
            补保
          </button>
          <button
            type="button"
            onClick={() => {
              setMenuOpen(false);
              onEditRiskControls(p);
            }}
          >
            改 TP/SL
          </button>
        </div>
      )}
    </li>
  );
}
