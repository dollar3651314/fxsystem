import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { FalconApiError } from "../../lib/api";
import { updateRiskControls } from "./tradingApi";
import type { PositionItem, UpdateRiskControlsRequest } from "./tradingTypes";
import { useSymbolPrecision } from "../market/useSymbolPrecision";
import { formatPrice } from "../../lib/precision";

interface Props {
  open: boolean;
  position: PositionItem | null;
  onClose: () => void;
}

export function EditRiskControlsModal({ open, position, onClose }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();

  const [tp, setTp] = useState("");
  const [sl, setSl] = useState("");
  const [error, setError] = useState<string | null>(null);
  const symbolPrecision = useSymbolPrecision();
  const { pricePrecision } = symbolPrecision(position?.symbol);

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (open && position) {
      // 类型上 TP/SL 是 string | null，但实际后端 / WS 推送回来偶尔是 number（JSON 数字未字符串化）。
      // 用 toInputString 兜底转 string，避免下面 trim() 抛 TypeError。
      setTp(toInputString(position.takeProfitPrice));
      setSl(toInputString(position.stopLossPrice));
      setError(null);
    }
  }, [open, position]);
  /* eslint-enable react-hooks/set-state-in-effect */

  const mutation = useMutation({
    mutationFn: (req: UpdateRiskControlsRequest) => {
      if (!token || !position) throw new Error("缺少认证或持仓");
      return updateRiskControls(token, position.positionId, req);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["trading"] });
      setError(null);
      onClose();
      window.dispatchEvent(
        new CustomEvent("falconx:toast", { detail: { kind: "ok", text: "TP/SL 已更新" } })
      );
    },
    onError: (err) => {
      const msg = err instanceof FalconApiError ? `${err.code}：${err.message}` : (err as Error).message;
      setError(msg);
    },
  });

  if (!open || !position) return null;

  const handleSubmit = () => {
    // 兜底用 toInputString：防御 React strict 重渲染或第三方逻辑把 state 改成 non-string。
    const tpStr = toInputString(tp);
    const slStr = toInputString(sl);
    const req: UpdateRiskControlsRequest = {
      takeProfitPrice: tpStr.trim() === "" ? null : tpStr,
      stopLossPrice: slStr.trim() === "" ? null : slStr,
    };
    mutation.mutate(req);
  };

  return createPortal(
    <div className="fx-modal-backdrop" onClick={onClose}>
      <div className="fx-modal" onClick={(e) => e.stopPropagation()}>
        <div className="fx-modal-header">
          <h3>修改 TP / SL</h3>
          <button type="button" className="fx-modal-close" onClick={onClose}>×</button>
        </div>
        <div className="fx-modal-body">
          <dl className="fx-modal-dl">
            <dt>持仓 ID</dt><dd>{position.positionId}</dd>
            <dt>Symbol</dt><dd>{position.symbol}</dd>
            <dt>方向</dt><dd>{position.side === "BUY" ? "做多" : "做空"}</dd>
            <dt>开仓价</dt><dd>{formatPrice(position.entryPrice, pricePrecision)}</dd>
            <dt>强平价</dt><dd>{position.liquidationPrice != null ? formatPrice(position.liquidationPrice, pricePrecision) : "—"}</dd>
          </dl>
          <label className="fx-modal-field">
            止盈价 (TP)
            <input
              type="number"
              step="0.00000001"
              value={tp}
              onChange={(e) => setTp(e.target.value)}
              placeholder="留空清除"
            />
          </label>
          <label className="fx-modal-field">
            止损价 (SL)
            <input
              type="number"
              step="0.00000001"
              value={sl}
              onChange={(e) => setSl(e.target.value)}
              placeholder="留空清除"
            />
          </label>
          {error && <div className="fx-modal-error" role="alert">{error}</div>}
        </div>
        <div className="fx-modal-footer">
          <button type="button" className="fx-btn-secondary" onClick={onClose} disabled={mutation.isPending}>取消</button>
          <button type="button" className="fx-btn-primary" onClick={handleSubmit} disabled={mutation.isPending}>
            {mutation.isPending ? <Loader2 size={14} className="spin" /> : null} 保存
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

/**
 * 防御性把 unknown 转 string：null / undefined → ""；number / boolean → String()；string → 原值。
 * 用于 modal 回显 number 字段（如服务端推过来 4700）后用户再次打开 modal 时调 .trim() 不抛错。
 */
function toInputString(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  return String(value);
}
