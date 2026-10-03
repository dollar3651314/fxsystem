import { useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { useMarketStore } from "../market/marketStore";
import { cancelPriceAlert, createPriceAlert, listPriceAlerts } from "./tradingApi";
import { TablePaginationFooter } from "./TablePaginationFooter";
import { useBreakpoint } from "../../lib/responsive";
import { PriceAlertCard } from "./PriceAlertCard";

const PAGE_SIZE = 30;

const DIRECTION_LABEL: Record<string, string> = {
  ABOVE: "向上穿透",
  BELOW: "向下穿透",
};

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: "等待中",
  EXHAUSTED: "已触发 3 次",
  CANCELLED: "已撤销",
  ADMIN_DELETED: "管理员删除",
};

interface Props {
  active: boolean;
}

export function PriceAlertsTable({ active }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();
  const { isMobile } = useBreakpoint();
  const [createOpen, setCreateOpen] = useState(false);
  const [page, setPage] = useState(1);

  const query = useQuery({
    queryKey: ["trading", "price-alerts", page],
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return listPriceAlerts(token, undefined, undefined, page, PAGE_SIZE);
    },
    enabled: active && Boolean(token),
  });

  const cancelMutation = useMutation({
    mutationFn: (id: string) => {
      if (!token) throw new Error("Not authenticated");
      return cancelPriceAlert(token, id);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["trading", "price-alerts"] }),
  });

  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;

  return (
    <div className="fx-tab-content">
      <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 8 }}>
        <button type="button" className="fx-btn-primary fx-btn-xs" onClick={() => setCreateOpen(true)}>
          新建告警
        </button>
      </div>
      {query.isLoading && <div className="fx-tab-empty">加载中…</div>}
      {!query.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无告警</div>}
      {!query.isLoading && items.length > 0 && !isMobile && (
        <>
        <table className="fx-table">
          <thead>
            <tr>
              <th>品种</th>
              <th>方向</th>
              <th>触发价</th>
              <th>创建时基准价</th>
              <th>触发次数</th>
              <th>剩余</th>
              <th>最近触发</th>
              <th>状态</th>
              <th>备注</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((a) => (
              <tr key={a.id}>
                <td>{a.symbol}</td>
                <td>{DIRECTION_LABEL[a.direction] ?? a.direction}</td>
                <td className="fx-num">{a.targetPrice}</td>
                <td className="fx-num">{a.basePrice ?? "—"}</td>
                <td className="fx-num">{a.triggerCount} / 3</td>
                <td className="fx-num">{a.remainingTriggers}</td>
                <td>{a.lastTriggeredAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                <td>{STATUS_LABEL[a.status] ?? a.status}</td>
                <td>{a.note ?? "—"}</td>
                <td>{a.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                <td className="fx-actions">
                  {a.status === "ACTIVE" && (
                    <button
                      type="button"
                      className="fx-btn-danger fx-btn-xs"
                      disabled={cancelMutation.isPending}
                      onClick={() => cancelMutation.mutate(a.id)}
                    >
                      撤销
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <TablePaginationFooter
          page={page}
          total={total}
          pageSize={PAGE_SIZE}
          fetching={query.isFetching}
          onChange={setPage}
        />
        </>
      )}
      {!query.isLoading && items.length > 0 && isMobile && (
        <ul className="fx-history-cards" aria-label="价格告警列表">
          {items.map((a) => (
            <PriceAlertCard
              key={a.id}
              item={a}
              onCancel={(item) => cancelMutation.mutate(item.id)}
              cancelDisabled={cancelMutation.isPending}
            />
          ))}
        </ul>
      )}
      <CreatePriceAlertModal open={createOpen} onClose={() => setCreateOpen(false)} />
    </div>
  );
}

interface ModalProps {
  open: boolean;
  onClose: () => void;
}

function CreatePriceAlertModal({ open, onClose }: ModalProps) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();
  const selectedSymbol = useMarketStore((s) => s.selectedSymbol);
  const currentQuote = useMarketStore((s) =>
    selectedSymbol ? s.quotes[selectedSymbol] ?? null : null,
  );

  const [symbol, setSymbol] = useState("");
  const [targetPrice, setTargetPrice] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  // 打开时默认 symbol 取当前选中
  if (open && !symbol && selectedSymbol) {
    setSymbol(selectedSymbol);
  }

  const refMark = currentQuote?.mark ?? currentQuote?.mid ?? null;

  const mutation = useMutation({
    mutationFn: () => {
      if (!token) throw new Error("Not authenticated");
      return createPriceAlert(token, {
        symbol,
        targetPrice,
        note: note || undefined,
        // direction 不传，由后端按 targetPrice vs mark 自动推导
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["trading", "price-alerts"] });
      setSymbol(""); setTargetPrice(""); setNote(""); setError(null);
      onClose();
    },
    onError: (err) => setError((err as Error).message),
  });

  if (!open) return null;

  return createPortal(
    <div className="fx-modal-mask" role="dialog" aria-modal="true">
      <div className="fx-modal">
        <div className="fx-modal-title">新建价格告警</div>
        <div className="fx-modal-body">
          <label>
            交易品种
            <input type="text" value={symbol} onChange={(e) => setSymbol(e.target.value)} placeholder="如 AUDCAD" />
          </label>
          {refMark && symbol === selectedSymbol && (
            <div className="order-spec-line">
              当前 Mark Price <b>{refMark}</b>
              <br />
              触发价 &gt; Mark → 向上穿透（ABOVE）；&lt; Mark → 向下穿透（BELOW）
            </div>
          )}
          <label>
            触发价
            <input
              type="number" step="0.00000001"
              value={targetPrice}
              onChange={(e) => setTargetPrice(e.target.value)}
              placeholder="目标价格"
            />
          </label>
          <label>
            备注（可选）
            <input
              type="text" maxLength={200}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="比如：突破 5000 通知我"
            />
          </label>
          <div className="order-spec-line">
            告警规则：达到触发价后通过 WebSocket 实时推送；每条告警最多触发 <b>3</b> 次，相邻触发 ≥ <b>5</b> 分钟；用完自动结束。
          </div>
          {error && <div className="fx-modal-error">{error}</div>}
        </div>
        <div className="fx-modal-actions">
          <button type="button" className="fx-btn-secondary" onClick={() => { setSymbol(""); setTargetPrice(""); setNote(""); setError(null); onClose(); }}>取消</button>
          <button
            type="button"
            className="fx-btn-primary"
            disabled={mutation.isPending || !symbol || !targetPrice}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? "创建中…" : "创建告警"}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
