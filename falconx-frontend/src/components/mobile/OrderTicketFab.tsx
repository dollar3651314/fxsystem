import { ArrowRight } from "lucide-react";

export interface OrderTicketFabProps {
  symbol: string | null;
  priceLabel: string | null;
  onClick: () => void;
}

export function OrderTicketFab({ symbol, priceLabel, onClick }: OrderTicketFabProps) {
  const disabled = !symbol;
  const ariaLabel = symbol ? `${symbol} 下单` : "选择品种后下单";
  return (
    <button
      type="button"
      className="order-ticket-fab"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
    >
      <span className="order-ticket-fab__info">
        {symbol ? (
          <>
            <strong className="order-ticket-fab__symbol">{symbol}</strong>
            {priceLabel && (
              <span className="order-ticket-fab__price">{priceLabel}</span>
            )}
          </>
        ) : (
          <span className="order-ticket-fab__empty">选择品种下单</span>
        )}
      </span>
      <span className="order-ticket-fab__cta">
        下单
        <ArrowRight size={14} strokeWidth={2} aria-hidden="true" />
      </span>
    </button>
  );
}
