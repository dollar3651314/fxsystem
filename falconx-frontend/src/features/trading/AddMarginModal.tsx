import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { FalconApiError } from "../../lib/api";
import { addMargin } from "./tradingApi";
import type { PositionItem } from "./tradingTypes";
import { useSymbolPrecision } from "../market/useSymbolPrecision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";
import { formatMoney, formatPrice } from "../../lib/precision";

interface Props {
  open: boolean;
  position: PositionItem | null;
  onClose: () => void;
}

export function AddMarginModal({ open, position, onClose }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const symbolPrecision = useSymbolPrecision();
  const { pricePrecision } = symbolPrecision(position?.symbol);

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (open) {
      setAmount("");
      setError(null);
    }
  }, [open]);
  /* eslint-enable react-hooks/set-state-in-effect */

  const mutation = useMutation({
    mutationFn: () => {
      if (!token || !position) throw new Error("缺少认证或持仓");
      return addMargin(token, position.positionId, { amount });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["trading"] });
      onClose();
      window.dispatchEvent(
        new CustomEvent("falconx:toast", { detail: { kind: "ok", text: `已补保证金 ${amount}` } })
      );
    },
    onError: (err) => {
      const msg = err instanceof FalconApiError ? `${err.code}：${err.message}` : (err as Error).message;
      setError(msg);
    },
  });

  if (!open || !position) return null;

  const canSubmit = Number(amount) > 0 && !mutation.isPending;

  return createPortal(
    <div className="fx-modal-backdrop" onClick={onClose}>
      <div className="fx-modal" onClick={(e) => e.stopPropagation()}>
        <div className="fx-modal-header">
          <h3>补充保证金</h3>
          <button type="button" className="fx-modal-close" onClick={onClose}>×</button>
        </div>
        <div className="fx-modal-body">
          <dl className="fx-modal-dl">
            <dt>持仓 ID</dt><dd>{position.positionId}</dd>
            <dt>Symbol</dt><dd>{position.symbol}</dd>
            <dt>当前保证金</dt><dd>{formatMoney(position.margin, ACCOUNT_CURRENCY)}</dd>
            <dt>强平价</dt><dd>{position.liquidationPrice != null ? formatPrice(position.liquidationPrice, pricePrecision) : "—"}</dd>
          </dl>
          <label className="fx-modal-field">
            补充金额 (USD)
            <input
              type="number"
              step="0.01"
              min="0"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="例 100"
              autoFocus
            />
          </label>
          {error && <div className="fx-modal-error" role="alert">{error}</div>}
        </div>
        <div className="fx-modal-footer">
          <button type="button" className="fx-btn-secondary" onClick={onClose} disabled={mutation.isPending}>取消</button>
          <button type="button" className="fx-btn-primary" onClick={() => mutation.mutate()} disabled={!canSubmit}>
            {mutation.isPending ? <Loader2 size={14} className="spin" /> : null} 确认
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
