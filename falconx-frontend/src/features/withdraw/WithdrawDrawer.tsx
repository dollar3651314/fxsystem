import { type FormEvent, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FalconApiError } from "../../lib/api";
import { FxSelect } from "../../components/FxSelect";
import { useAuthStore } from "../auth/authStore";
import { getAccount } from "../trading/tradingApi";
import {
  addWhitelist,
  cancelWithdraw,
  deleteWhitelist,
  listWhitelists,
  listWithdraws,
  newIdempotencyKey,
  submitWithdraw,
} from "./withdrawApi";
import {
  describeWithdrawError,
  WHITELIST_STATUS_LABEL,
  WITHDRAW_STATUS_LABEL,
  type WithdrawErrorHint,
  type WithdrawNetwork,
  type WithdrawOrder,
  type WithdrawWhitelistItem,
} from "./types";

type WithdrawDrawerProps = {
  open: boolean;
  onClose: () => void;
  /** 错误码 30043 / 30056 命中时回跳到 KYC drawer 让用户重新认证。 */
  onOpenKyc?: () => void;
};

const ERC20_RE = /^0x[0-9a-fA-F]{40}$/;
const TRC20_RE = /^T[1-9A-HJ-NP-Za-km-z]{33}$/;

function validateAddress(network: WithdrawNetwork, address: string): boolean {
  if (!address) return false;
  return network === "ERC20" ? ERC20_RE.test(address) : TRC20_RE.test(address);
}

function shortenAddress(addr: string): string {
  if (!addr || addr.length <= 14) return addr;
  return `${addr.slice(0, 8)}…${addr.slice(-6)}`;
}

export function WithdrawDrawer({ open, onClose, onOpenKyc }: WithdrawDrawerProps) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<"submit" | "history" | "whitelist">("submit");

  // 提交表单
  const [selectedWhitelistId, setSelectedWhitelistId] = useState<string>("");
  const [amount, setAmount] = useState("");
  const [submitError, setSubmitError] = useState<WithdrawErrorHint | null>(null);
  const [submitOk, setSubmitOk] = useState<string | null>(null);

  // 白名单添加
  const [wlNetwork, setWlNetwork] = useState<WithdrawNetwork>("ERC20");
  const [wlAddress, setWlAddress] = useState("");
  const [wlLabel, setWlLabel] = useState("");
  const [wlAddError, setWlAddError] = useState<string | null>(null);

  const accountQuery = useQuery({
    queryKey: ["trading", "account"],
    queryFn: () => getAccount(token!),
    enabled: Boolean(token) && open,
    staleTime: 5_000,
  });

  const whitelistQuery = useQuery<WithdrawWhitelistItem[]>({
    queryKey: ["withdraw", "whitelist"],
    queryFn: () => listWhitelists(token!),
    enabled: Boolean(token) && open,
    staleTime: 5_000,
  });

  const historyQuery = useQuery({
    queryKey: ["withdraw", "list"],
    queryFn: () => listWithdraws(token!, 1, 20),
    enabled: Boolean(token) && open && tab === "history",
    staleTime: 5_000,
  });

  const activeWhitelists = useMemo(
    () => (whitelistQuery.data ?? []).filter((w) => w.status === "ACTIVE"),
    [whitelistQuery.data],
  );
  // 派生「实际选中」：用户显式选择优先；显式选择无效或为空时回落到第一个 ACTIVE。
  // 不用 useEffect 同步 selectedWhitelistId，避免 set-state-in-effect 触发 cascading renders。
  const effectiveSelectedId =
    activeWhitelists.find((w) => w.id === selectedWhitelistId)?.id ??
    activeWhitelists[0]?.id ??
    "";
  const selectedWhitelist =
    activeWhitelists.find((w) => w.id === effectiveSelectedId) ?? null;

  // 切换 drawer 重置反馈，但不重置选中的白名单（避免每次重开都重选）。
  // 仅响应 open false→true 的瞬间，effect 内部三次 setState 不会触发其他 effect 链。
  useEffect(() => {
    if (open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSubmitError(null);
      setSubmitOk(null);
      setWlAddError(null);
    }
  }, [open]);

  const submitMutation = useMutation({
    mutationFn: async () => {
      if (!token || !selectedWhitelist) throw new Error("missing-token-or-whitelist");
      return submitWithdraw(
        token,
        {
          amount,
          currency: "USDT",
          network: selectedWhitelist.network,
          targetAddress: selectedWhitelist.address,
          whitelistId: selectedWhitelist.id,
        },
        newIdempotencyKey(),
      );
    },
    onSuccess: (order) => {
      setSubmitOk(`已提交，进入冷静期。订单号 ${order.withdrawId.slice(-8)}`);
      setSubmitError(null);
      setAmount("");
      void queryClient.invalidateQueries({ queryKey: ["withdraw"] });
      void queryClient.invalidateQueries({ queryKey: ["trading", "account"] });
      window.dispatchEvent(
        new CustomEvent("falconx:toast", { detail: { kind: "ok", text: "出金已提交，等待审核" } }),
      );
    },
    onError: (cause) => {
      setSubmitOk(null);
      if (cause instanceof FalconApiError) {
        setSubmitError(describeWithdrawError(cause.code, cause.message));
      } else {
        setSubmitError({ text: "提交失败，请稍后重试" });
      }
    },
  });

  const addWhitelistMutation = useMutation({
    mutationFn: () => {
      if (!token) throw new Error("missing-token");
      const trimmed = wlAddress.trim();
      if (!validateAddress(wlNetwork, trimmed)) {
        throw new FalconApiError("30045", "地址格式不合法", "", 400, null);
      }
      return addWhitelist(token, {
        network: wlNetwork,
        address: trimmed,
        label: wlLabel.trim() || undefined,
      });
    },
    onSuccess: () => {
      setWlAddress("");
      setWlLabel("");
      setWlAddError(null);
      void queryClient.invalidateQueries({ queryKey: ["withdraw", "whitelist"] });
      window.dispatchEvent(
        new CustomEvent("falconx:toast", {
          detail: { kind: "ok", text: "白名单已添加，24 小时冷静期后可用于出金" },
        }),
      );
    },
    onError: (cause) => {
      if (cause instanceof FalconApiError) {
        setWlAddError(`${cause.message}（${cause.code}）`);
      } else {
        setWlAddError("添加失败");
      }
    },
  });

  const deleteWhitelistMutation = useMutation({
    mutationFn: (id: string) => {
      if (!token) throw new Error("missing-token");
      return deleteWhitelist(token, id);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["withdraw", "whitelist"] });
    },
    onError: (cause) => {
      const msg = cause instanceof FalconApiError ? `${cause.message}（${cause.code}）` : "删除失败";
      window.dispatchEvent(new CustomEvent("falconx:toast", { detail: { kind: "err", text: msg } }));
    },
  });

  const cancelMutation = useMutation({
    mutationFn: (id: string) => {
      if (!token) throw new Error("missing-token");
      return cancelWithdraw(token, id);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["withdraw"] });
      void queryClient.invalidateQueries({ queryKey: ["trading", "account"] });
      window.dispatchEvent(
        new CustomEvent("falconx:toast", { detail: { kind: "ok", text: "出金已撤销，余额已退回" } }),
      );
    },
    onError: (cause) => {
      const msg = cause instanceof FalconApiError ? `${cause.message}（${cause.code}）` : "撤销失败";
      window.dispatchEvent(new CustomEvent("falconx:toast", { detail: { kind: "err", text: msg } }));
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitError(null);
    setSubmitOk(null);
    if (!selectedWhitelist) {
      setSubmitError({ text: "请先选择已生效的白名单地址", actionHint: "addWhitelist" });
      return;
    }
    const num = Number(amount);
    if (!Number.isFinite(num) || num <= 0) {
      setSubmitError({ text: "请输入有效的出金金额" });
      return;
    }
    submitMutation.mutate();
  }

  if (!open) return null;

  // 2026-05-20 重设计：从 .fx-modal-backdrop 切到 .fx-modal-mask 体系 + createPortal 挂 body。
  // 标签栏改用 console activity-tabs 编号 + 下划线 active 风格。
  return createPortal(
    <div className="fx-modal-mask fx-modal-mask--withdraw" onClick={() => !submitMutation.isPending && onClose()}>
      <div className="fx-modal fx-modal--wide" onClick={(e) => e.stopPropagation()}>
        <div className="fx-modal-title fx-modal-title--row">
          <span>钱包出金</span>
          <span className="fx-modal-title__hint">USDT · ERC20 / TRC20</span>
          <button type="button" className="fx-modal-close" onClick={onClose} aria-label="关闭">×</button>
        </div>

        <div className="fx-modal-body">
          <nav className="withdraw-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={tab === "submit"}
              className={tab === "submit" ? "withdraw-tab active" : "withdraw-tab"}
              onClick={() => setTab("submit")}
            >
              <span className="withdraw-tab__num">01</span>
              发起出金
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === "history"}
              className={tab === "history" ? "withdraw-tab active" : "withdraw-tab"}
              onClick={() => setTab("history")}
            >
              <span className="withdraw-tab__num">02</span>
              出金记录
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === "whitelist"}
              className={tab === "whitelist" ? "withdraw-tab active" : "withdraw-tab"}
              onClick={() => setTab("whitelist")}
            >
              <span className="withdraw-tab__num">03</span>
              白名单
              <span className="withdraw-tab__badge">{activeWhitelists.length}</span>
            </button>
          </nav>

          {tab === "submit" && (
            <SubmitSection
              accountAvailable={accountQuery.data?.available ?? null}
              accountLoading={accountQuery.isLoading}
              whitelists={activeWhitelists}
              whitelistLoading={whitelistQuery.isLoading}
              selectedWhitelistId={effectiveSelectedId}
              onSelectWhitelist={setSelectedWhitelistId}
              amount={amount}
              onAmountChange={setAmount}
              submitting={submitMutation.isPending}
              submitError={submitError}
              submitOk={submitOk}
              onSubmit={handleSubmit}
              onOpenKyc={onOpenKyc}
              onSwitchToWhitelist={() => setTab("whitelist")}
            />
          )}

          {tab === "history" && (
            <HistorySection
              isLoading={historyQuery.isLoading}
              items={historyQuery.data?.items ?? []}
              cancelingId={cancelMutation.isPending ? (cancelMutation.variables as string) : null}
              onCancel={(id) => cancelMutation.mutate(id)}
            />
          )}

          {tab === "whitelist" && (
            <WhitelistSection
              items={whitelistQuery.data ?? []}
              isLoading={whitelistQuery.isLoading}
              network={wlNetwork}
              address={wlAddress}
              label={wlLabel}
              addError={wlAddError}
              adding={addWhitelistMutation.isPending}
              deletingId={
                deleteWhitelistMutation.isPending ? (deleteWhitelistMutation.variables as string) : null
              }
              onChangeNetwork={setWlNetwork}
              onChangeAddress={setWlAddress}
              onChangeLabel={setWlLabel}
              onAdd={() => addWhitelistMutation.mutate()}
              onDelete={(id) => deleteWhitelistMutation.mutate(id)}
            />
          )}
        </div>

        <div className="fx-modal-actions">
          <button type="button" className="fx-btn-secondary" onClick={onClose}>
            关闭
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

interface SubmitSectionProps {
  accountAvailable: string | null;
  accountLoading: boolean;
  whitelists: WithdrawWhitelistItem[];
  whitelistLoading: boolean;
  selectedWhitelistId: string;
  onSelectWhitelist: (id: string) => void;
  amount: string;
  onAmountChange: (v: string) => void;
  submitting: boolean;
  submitError: WithdrawErrorHint | null;
  submitOk: string | null;
  onSubmit: (e: FormEvent<HTMLFormElement>) => void;
  onOpenKyc?: () => void;
  onSwitchToWhitelist: () => void;
}

function SubmitSection(props: SubmitSectionProps) {
  const {
    accountAvailable,
    accountLoading,
    whitelists,
    whitelistLoading,
    selectedWhitelistId,
    onSelectWhitelist,
    amount,
    onAmountChange,
    submitting,
    submitError,
    submitOk,
    onSubmit,
    onOpenKyc,
    onSwitchToWhitelist,
  } = props;

  return (
    <form className="profile-form" onSubmit={onSubmit}>
      <fieldset disabled={submitting}>
        <legend>账户</legend>
        <div className="profile-form__row">
          <label>
            可用余额（USDT）
            <input
              value={accountLoading ? "加载中…" : accountAvailable ?? "0.00"}
              disabled
              readOnly
            />
          </label>
        </div>
      </fieldset>

      <fieldset disabled={submitting}>
        <legend>目标地址</legend>
        {whitelistLoading && <p className="fx-modal-empty">加载白名单…</p>}
        {!whitelistLoading && whitelists.length === 0 && (
          <div className="fx-modal-error" style={{ marginBottom: 12 }}>
            尚无已生效的白名单。请先在「白名单」标签页添加并等待 24 小时冷静期。
            <button
              type="button"
              className="fx-btn-secondary fx-btn-xs"
              onClick={onSwitchToWhitelist}
              style={{ marginLeft: 8 }}
            >
              去添加
            </button>
          </div>
        )}
        {!whitelistLoading && whitelists.length > 0 && (
          <div className="profile-form__row">
            <label>
              白名单地址
              <FxSelect
                value={selectedWhitelistId}
                onChange={onSelectWhitelist}
                ariaLabel="选择白名单地址"
                options={whitelists.map((w) => ({
                  value: w.id,
                  label: (w.label ? `${w.label} · ` : "") + w.network + " · " + shortenAddress(w.address),
                }))}
              />
            </label>
          </div>
        )}
      </fieldset>

      <fieldset disabled={submitting || whitelists.length === 0}>
        <legend>金额</legend>
        <div className="profile-form__row">
          <label>
            金额（USDT，最小 $10，单笔上限 $10,000）
            <input
              value={amount}
              onChange={(e) => onAmountChange(e.target.value)}
              inputMode="decimal"
              placeholder="例如 100"
            />
          </label>
        </div>
        <p className="profile-form__hint" style={{ fontSize: 12, opacity: 0.7 }}>
          提交后会进入 2 小时冷静期，期间可在「出金记录」中撤销。≥$3,000 的出金审核后还需 6 小时延迟。
        </p>
      </fieldset>

      {submitError && (
        <div className="fx-modal-error" style={{ marginTop: 8 }}>
          {submitError.text}
          {submitError.actionHint === "openKyc" && onOpenKyc && (
            <button
              type="button"
              className="fx-btn-secondary fx-btn-xs"
              onClick={onOpenKyc}
              style={{ marginLeft: 8 }}
            >
              去 KYC
            </button>
          )}
          {submitError.actionHint === "addWhitelist" && (
            <button
              type="button"
              className="fx-btn-secondary fx-btn-xs"
              onClick={onSwitchToWhitelist}
              style={{ marginLeft: 8 }}
            >
              管理白名单
            </button>
          )}
        </div>
      )}
      {submitOk && (
        <div className="profile-form__verified-banner" style={{ background: "rgba(34,197,94,0.1)", color: "#16a34a" }}>
          {submitOk}
        </div>
      )}

      <div style={{ marginTop: 16, textAlign: "right" }}>
        <button
          type="submit"
          className="fx-btn-primary"
          disabled={submitting || whitelistLoading || whitelists.length === 0}
        >
          {submitting ? "提交中…" : "提交出金"}
        </button>
      </div>
    </form>
  );
}

interface HistorySectionProps {
  isLoading: boolean;
  items: WithdrawOrder[];
  cancelingId: string | null;
  onCancel: (id: string) => void;
}

function HistorySection({ isLoading, items, cancelingId, onCancel }: HistorySectionProps) {
  if (isLoading) return <p className="fx-modal-empty">加载中…</p>;
  if (items.length === 0) return <p className="fx-modal-empty">暂无出金记录</p>;

  return (
    <table className="fx-table">
      <thead>
        <tr>
          <th>提交时间</th>
          <th>金额</th>
          <th>网络</th>
          <th>目标地址</th>
          <th>状态</th>
          <th>链上交易</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        {items.map((it) => {
          const isCooling = it.status === "COOLING";
          const explorerHref = it.txHash
            ? it.network === "ERC20"
              ? `https://sepolia.etherscan.io/tx/${it.txHash}`
              : `https://tronscan.org/#/transaction/${it.txHash}`
            : null;
          return (
            <tr key={it.withdrawId}>
              <td>{it.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
              <td className="fx-num">{it.amount}</td>
              <td>{it.network}</td>
              <td className="fx-mono fx-truncate" title={it.targetAddress}>
                {shortenAddress(it.targetAddress)}
              </td>
              <td>
                {WITHDRAW_STATUS_LABEL[it.status]}
                {it.rejectReason && <span title={it.rejectReason}> · 已拒</span>}
                {it.failureReason && <span title={it.failureReason}> · 链上失败</span>}
              </td>
              <td className="fx-mono fx-truncate" title={it.txHash ?? ""}>
                {explorerHref ? (
                  <a href={explorerHref} target="_blank" rel="noreferrer">
                    {shortenAddress(it.txHash!)}
                  </a>
                ) : (
                  "—"
                )}
              </td>
              <td>
                {isCooling && (
                  <button
                    type="button"
                    className="fx-btn-secondary fx-btn-xs"
                    disabled={cancelingId === it.withdrawId}
                    onClick={() => onCancel(it.withdrawId)}
                  >
                    {cancelingId === it.withdrawId ? "撤销中…" : "撤销"}
                  </button>
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

interface WhitelistSectionProps {
  items: WithdrawWhitelistItem[];
  isLoading: boolean;
  network: WithdrawNetwork;
  address: string;
  label: string;
  addError: string | null;
  adding: boolean;
  deletingId: string | null;
  onChangeNetwork: (n: WithdrawNetwork) => void;
  onChangeAddress: (a: string) => void;
  onChangeLabel: (l: string) => void;
  onAdd: () => void;
  onDelete: (id: string) => void;
}

function WhitelistSection(props: WhitelistSectionProps) {
  const {
    items,
    isLoading,
    network,
    address,
    label,
    addError,
    adding,
    deletingId,
    onChangeNetwork,
    onChangeAddress,
    onChangeLabel,
    onAdd,
    onDelete,
  } = props;

  return (
    <div className="profile-form">
      <fieldset disabled={adding}>
        <legend>添加白名单</legend>
        <div className="profile-form__row">
          <label>
            网络
            <FxSelect<WithdrawNetwork>
              value={network}
              onChange={onChangeNetwork}
              ariaLabel="选择网络"
              options={[
                { value: "ERC20", label: "ERC20", hint: "Ethereum" },
                { value: "TRC20", label: "TRC20", hint: "TRON" },
              ]}
            />
          </label>
          <label style={{ flex: 2 }}>
            地址
            <input
              value={address}
              onChange={(e) => onChangeAddress(e.target.value)}
              placeholder={network === "ERC20" ? "0x..." : "T..."}
              maxLength={64}
            />
          </label>
          <label>
            备注（可选）
            <input
              value={label}
              onChange={(e) => onChangeLabel(e.target.value)}
              placeholder="如 Ledger 主钱包"
              maxLength={64}
            />
          </label>
        </div>
        {addError && <div className="fx-modal-error">{addError}</div>}
        <div style={{ marginTop: 8, textAlign: "right" }}>
          <button type="button" className="fx-btn-primary" onClick={onAdd} disabled={adding || !address.trim()}>
            {adding ? "添加中…" : "添加"}
          </button>
        </div>
        <p className="profile-form__hint" style={{ fontSize: 12, opacity: 0.7 }}>
          添加后需要 24 小时冷静期才能用于出金（防止账户被劫持后即刻提现）。最多 10 条已生效白名单。
        </p>
      </fieldset>

      <fieldset>
        <legend>已有白名单</legend>
        {isLoading && <p className="fx-modal-empty">加载中…</p>}
        {!isLoading && items.length === 0 && <p className="fx-modal-empty">暂无白名单</p>}
        {!isLoading && items.length > 0 && (
          <div className="fx-table-scroll">
          <table className="fx-table">
            <thead>
              <tr>
                <th>网络</th>
                <th>地址</th>
                <th>备注</th>
                <th>状态</th>
                <th>添加时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((w) => (
                <tr key={w.id}>
                  <td>{w.network}</td>
                  <td className="fx-mono fx-truncate" title={w.address}>
                    {shortenAddress(w.address)}
                  </td>
                  <td>{w.label ?? "—"}</td>
                  <td>{WHITELIST_STATUS_LABEL[w.status]}</td>
                  <td>{w.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                  <td>
                    {w.status !== "REMOVED" && (
                      <button
                        type="button"
                        className="fx-btn-secondary fx-btn-xs"
                        disabled={deletingId === w.id}
                        onClick={() => onDelete(w.id)}
                      >
                        {deletingId === w.id ? "删除中…" : "删除"}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </fieldset>
    </div>
  );
}
