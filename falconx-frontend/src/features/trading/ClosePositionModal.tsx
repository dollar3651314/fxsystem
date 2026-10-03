import { useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { FalconApiError } from "../../lib/api";
import { closePosition } from "./tradingApi";
import { formatPnl, formatPrice, formatQty, formatSignedPnl } from "../../lib/precision";
import type { PositionItem } from "./tradingTypes";
import { useSymbolPrecision } from "../market/useSymbolPrecision";

interface Props {
  open: boolean;
  position: PositionItem | null;
  onClose: () => void;
}

export function ClosePositionModal({ open, position, onClose }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const symbolPrecision = useSymbolPrecision();
  const { pricePrecision, qtyPrecision } = symbolPrecision(position?.symbol);

  const mutation = useMutation({
    mutationFn: () => {
      if (!token || !position) throw new Error("缺少认证或持仓");
      return closePosition(token, position.positionId);
    },
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ["trading"] });
      setError(null);
      onClose();
      window.dispatchEvent(
        new CustomEvent("falconx:toast", {
          detail: { kind: "ok", text: `持仓 #${result.positionId} 已平 @ ${result.closePrice}，盈亏 ${formatSignedPnl(result.realizedPnl)}` },
        })
      );
    },
    onError: (err) => {
      const msg = err instanceof FalconApiError ? `${err.code}：${err.message}` : (err as Error).message;
      setError(msg);
    },
  });

  if (!open || !position) return null;

  // 用 portal 挂 document.body：父级 .market-chart / .fx-trading-tabs 有 backdrop-filter，
  // 会创建 position: fixed 的 containing block 把弹窗困在 panel 矩形内
  return createPortal(
    <div className="fx-modal-backdrop" onClick={onClose}>
      <div className="fx-modal" onClick={(e) => e.stopPropagation()}>
        <div className="fx-modal-header">
          <h3>平仓二次确认</h3>
          <button type="button" className="fx-modal-close" onClick={onClose}>×</button>
        </div>
        <div className="fx-modal-body">
          <p>该操作将以当前最新报价立即关闭以下持仓：</p>
          <dl className="fx-modal-dl">
            <dt>持仓 ID</dt><dd>{position.positionId}</dd>
            <dt>Symbol</dt><dd>{position.symbol}</dd>
            <dt>方向</dt><dd>{position.side === "BUY" ? "做多" : "做空"}</dd>
            <dt>数量</dt><dd>{formatQty(position.quantity, qtyPrecision)}</dd>
            <dt>开仓价</dt><dd>{formatPrice(position.entryPrice, pricePrecision)}</dd>
            <dt>未实现盈亏</dt><dd>{position.unrealizedPnlInAccount == null ? "—" : formatPnl(position.unrealizedPnlInAccount)}</dd>
          </dl>
          {error && <div className="fx-modal-error" role="alert">{error}</div>}
        </div>
        <div className="fx-modal-footer">
          <button type="button" className="fx-btn-secondary" onClick={onClose} disabled={mutation.isPending}>取消</button>
          <button type="button" className="fx-btn-danger" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
            {mutation.isPending ? <Loader2 size={14} className="spin" /> : null} 确认平仓
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
