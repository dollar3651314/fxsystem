import { Lock } from "lucide-react";

export function OrderTicketPreview() {
  return (
    <aside className="order-ticket" aria-label="下单面板">
      <div className="panel-heading">
        <span>市价单</span>
        <b>Preview</b>
      </div>
      <div className="ticket-amount">$1,500</div>
      <div className="ticket-switch" role="group" aria-label="方向">
        <button type="button" className="long">
          Long
        </button>
        <button type="button" className="short">
          Short
        </button>
      </div>
      <label>
        数量
        <input value="0.25" readOnly />
      </label>
      <label>
        杠杆
        <input value="10x" readOnly />
      </label>
      <label>
        TP/SL
        <input value="未启用" readOnly />
      </label>
      <button className="disabled-submit" type="button" disabled>
        <Lock size={16} aria-hidden="true" />
        交易接入未启用
      </button>
    </aside>
  );
}
