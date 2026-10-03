import { useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { FalconApiError } from "../../lib/api";
import { getMarginMode, setMarginMode } from "./tradingApi";
import type { AccountMarginMode } from "./tradingApi";
import { MARGIN_MODE_QUERY_KEY, CROSS_DISABLED_HINT } from "./marginModeShared";

/**
 * STAGE-14E1 Task5：账户级 margin mode 切换（对接 D1 GET/POST /api/v1/me/margin-mode）。
 *
 * <p>这是「账户级」保证金模式（后端权威），区分 settings 页的本地偏好 defaultMarginMode
 * （仅影响下单默认值，不改账户）。命名统一用 accountMarginMode 语义。
 *
 * <p>canSwitch=false 时切换控件 disabled，并按 blockers + coolingUntil 给中文原因；
 * 切换为高危操作（影响所有后续持仓保证金口径），点目标 mode 弹 fx-modal 二次确认，
 * 失败按 30080-30088 错误码映射中文。
 */

const MODE_LABEL: Record<AccountMarginMode, string> = {
  ISOLATED: "逐仓",
  CROSS: "全仓",
};

/** blockers 标识 → 中文原因（与 D1 MarginModeSwitchApplicationService BLOCKER_* 一致）。 */
const BLOCKER_LABEL: Record<string, string> = {
  OPEN_POSITIONS: "有未平仓持仓",
  ACTIVE_PENDING: "有未触发挂单",
  COOLING: "处于切换冷静期",
};

/** POST 失败错误码 → 中文（后端返回英文 message，前端按 code 映射，沿 REJECTION_LABEL 惯例）。 */
const SWITCH_ERROR_LABEL: Record<string, string> = {
  "30080": "存在未平仓持仓，请先平仓后再切换保证金模式",
  "30081": "存在未触发挂单，请先撤销挂单后再切换保证金模式",
  "30082": "处于切换冷静期，请稍后再试",
  "30083": "已是该保证金模式，无需切换",
  "30088": "全仓模式暂未开放",
  "40010": "不支持的保证金模式",
};

function describeBlocker(blocker: string): string {
  return BLOCKER_LABEL[blocker] ?? blocker;
}

/** coolingUntil 剩余时间（粗到分钟），用于冷静期 tooltip。 */
function formatRemaining(coolingUntil: string | null): string | null {
  if (!coolingUntil) return null;
  const ms = new Date(coolingUntil).getTime() - Date.now();
  if (!Number.isFinite(ms) || ms <= 0) return null;
  const totalMin = Math.ceil(ms / 60_000);
  if (totalMin < 60) return `约剩 ${totalMin} 分钟`;
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return m > 0 ? `约剩 ${h} 小时 ${m} 分钟` : `约剩 ${h} 小时`;
}

export function MarginModeToggle() {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();

  const [pendingTarget, setPendingTarget] = useState<AccountMarginMode | null>(null);
  const [error, setError] = useState<string | null>(null);

  const query = useQuery({
    queryKey: MARGIN_MODE_QUERY_KEY,
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return getMarginMode(token);
    },
    enabled: Boolean(token),
  });

  const accountMarginMode = query.data?.currentMode ?? null;
  const canSwitch = query.data?.canSwitch ?? false;
  // 平台是否开放 CROSS。loading / 拿不到时保守按 false（disable 全仓），避免误放行被后端 30088 拒。
  const crossModeEnabled = query.data?.crossModeEnabled ?? false;

  const blockedReason = useMemo(() => {
    if (canSwitch || !query.data) return null;
    const blockers = query.data.blockers ?? [];
    const parts = blockers.map(describeBlocker);
    const remaining = blockers.includes("COOLING") ? formatRemaining(query.data.coolingUntil) : null;
    if (remaining && parts.length > 0) {
      // 把冷静期剩余时间附在原因后
      return `${parts.join("、")}（${remaining}）`;
    }
    return parts.length > 0 ? parts.join("、") : "当前不可切换";
  }, [canSwitch, query.data]);

  const mutation = useMutation({
    mutationFn: (target: AccountMarginMode) => {
      if (!token) throw new Error("Not authenticated");
      return setMarginMode(token, target);
    },
    onSuccess: (result) => {
      setError(null);
      setPendingTarget(null);
      window.dispatchEvent(
        new CustomEvent("falconx:toast", {
          detail: {
            kind: "ok",
            text: `保证金模式已切换为${MODE_LABEL[result.newMode]}`,
            level: "critical",
          },
        }),
      );
      void queryClient.invalidateQueries({ queryKey: MARGIN_MODE_QUERY_KEY });
      void queryClient.invalidateQueries({ queryKey: ["trading", "account-me"] });
      void queryClient.invalidateQueries({ queryKey: ["trading"] });
    },
    onError: (err) => {
      const text =
        err instanceof FalconApiError
          ? `${SWITCH_ERROR_LABEL[err.code] ?? err.message}（${err.code}）`
          : (err as Error).message;
      setError(text);
    },
  });

  const handlePick = (target: AccountMarginMode) => {
    if (!canSwitch || target === accountMarginMode || mutation.isPending) return;
    // CROSS 平台未开放：直接 return，不弹确认 modal。
    if (target === "CROSS" && !crossModeEnabled) return;
    setError(null);
    setPendingTarget(target);
  };

  const confirmSwitch = () => {
    if (pendingTarget) mutation.mutate(pendingTarget);
  };

  const cancelSwitch = () => {
    if (mutation.isPending) return;
    setPendingTarget(null);
    setError(null);
  };

  const modes: AccountMarginMode[] = ["ISOLATED", "CROSS"];

  return (
    <div className="margin-mode-toggle" aria-label="账户保证金模式">
      <span className="margin-mode-toggle__label">保证金模式</span>
      <div
        className="fx-segmented"
        role="group"
        aria-label="账户保证金模式切换"
        title={!canSwitch && blockedReason ? `暂不可切换：${blockedReason}` : undefined}
      >
        {modes.map((mode) => {
          const isActive = accountMarginMode === mode;
          const crossBlocked = mode === "CROSS" && !crossModeEnabled;
          const disabled =
            query.isLoading || isActive || !canSwitch || mutation.isPending || crossBlocked;
          return (
            <button
              key={mode}
              type="button"
              className={`fx-segmented__btn${isActive ? " is-active" : ""}`}
              aria-pressed={isActive}
              disabled={disabled}
              onClick={() => handlePick(mode)}
            >
              {MODE_LABEL[mode]}
            </button>
          );
        })}
      </div>
      {!canSwitch && blockedReason && (
        <span className="margin-mode-toggle__hint" role="note">
          暂不可切换：{blockedReason}
        </span>
      )}
      {/* CROSS 门禁叠加在 canSwitch 之上：canSwitch=true（无持仓/挂单）但平台未开放全仓时，
          上面 blockedReason 不会显示，这里单独给「全仓暂未开放」提示。query 加载完才显示，
          避免初始 loading 闪现。 */}
      {!query.isLoading && !crossModeEnabled && (
        <span className="margin-mode-toggle__hint" role="note">
          {CROSS_DISABLED_HINT}
        </span>
      )}

      {pendingTarget &&
        createPortal(
          <div className="fx-modal-backdrop" onClick={cancelSwitch}>
            <div className="fx-modal" onClick={(e) => e.stopPropagation()}>
              <div className="fx-modal-header">
                <h3>切换保证金模式</h3>
                <button type="button" className="fx-modal-close" onClick={cancelSwitch}>
                  ×
                </button>
              </div>
              <div className="fx-modal-body">
                <p>
                  将账户保证金模式从{" "}
                  <b>{accountMarginMode ? MODE_LABEL[accountMarginMode] : "—"}</b> 切换为{" "}
                  <b>{MODE_LABEL[pendingTarget]}</b>。
                </p>
                <p>
                  切换后所有新开仓位将按
                  {pendingTarget === "CROSS"
                    ? "全仓口径共享账户保证金，单一仓位风险可能波及全账户。"
                    : "逐仓口径独立计算保证金与强平价。"}
                  切换将进入冷静期，期间无法再次切换。
                </p>
                {error && (
                  <div className="fx-modal-error" role="alert">
                    {error}
                  </div>
                )}
              </div>
              <div className="fx-modal-footer">
                <button
                  type="button"
                  className="fx-btn-secondary"
                  onClick={cancelSwitch}
                  disabled={mutation.isPending}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="fx-btn-primary"
                  onClick={confirmSwitch}
                  disabled={mutation.isPending}
                >
                  {mutation.isPending ? <Loader2 size={14} className="spin" /> : null} 确认切换
                </button>
              </div>
            </div>
          </div>,
          document.body,
        )}
    </div>
  );
}
