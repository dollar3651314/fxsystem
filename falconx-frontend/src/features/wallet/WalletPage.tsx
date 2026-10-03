import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  AlertTriangle,
  ArrowDownToLine,
  Check,
  Copy,
  ExternalLink,
  RefreshCw,
  Wallet as WalletIcon,
} from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { buildTxExplorerUrl } from "../../lib/config";
import { formatMoney } from "../../lib/precision";
import { getAccount } from "../trading/tradingApi";
import { useBreakpoint } from "../../lib/responsive/useBreakpoint";
import { LedgerCard } from "./LedgerCard";
import { formatLedgerAmount } from "./ledgerFormat";
import {
  LEDGER_BIZ_TYPE_LABEL,
  ensureDepositAddresses,
  listLedger,
  type DepositAddressItem,
  type LedgerEntry,
} from "./walletApi";

interface Props {
  onOpenWithdraw: () => void;
}

// 资金流水筛选下拉选项 —— 与后端 TradingLedgerBizType 严格对齐（18 个枚举键）。
// 历史 / 兼容键（TRADE_FEE / DEPOSIT / ADJUST / SWAP 等）不放在筛选下拉里。
const LEDGER_FILTER_OPTIONS: { value: string; label: string }[] = [
  { value: "DEPOSIT_CREDIT", label: LEDGER_BIZ_TYPE_LABEL.DEPOSIT_CREDIT },
  { value: "DEPOSIT_REVERSAL", label: LEDGER_BIZ_TYPE_LABEL.DEPOSIT_REVERSAL },
  { value: "ORDER_MARGIN_RESERVED", label: LEDGER_BIZ_TYPE_LABEL.ORDER_MARGIN_RESERVED },
  { value: "ORDER_FEE_CHARGED", label: LEDGER_BIZ_TYPE_LABEL.ORDER_FEE_CHARGED },
  { value: "ORDER_MARGIN_CONFIRMED", label: LEDGER_BIZ_TYPE_LABEL.ORDER_MARGIN_CONFIRMED },
  { value: "ISOLATED_MARGIN_SUPPLEMENT", label: LEDGER_BIZ_TYPE_LABEL.ISOLATED_MARGIN_SUPPLEMENT },
  { value: "PENDING_ORDER_RELEASED", label: LEDGER_BIZ_TYPE_LABEL.PENDING_ORDER_RELEASED },
  { value: "SWAP_CHARGE", label: LEDGER_BIZ_TYPE_LABEL.SWAP_CHARGE },
  { value: "SWAP_INCOME", label: LEDGER_BIZ_TYPE_LABEL.SWAP_INCOME },
  { value: "REALIZED_PNL", label: LEDGER_BIZ_TYPE_LABEL.REALIZED_PNL },
  { value: "LIQUIDATION_PNL", label: LEDGER_BIZ_TYPE_LABEL.LIQUIDATION_PNL },
  { value: "ADMIN_BALANCE_ADJUST", label: LEDGER_BIZ_TYPE_LABEL.ADMIN_BALANCE_ADJUST },
  { value: "WITHDRAW_FREEZE", label: LEDGER_BIZ_TYPE_LABEL.WITHDRAW_FREEZE },
  { value: "WITHDRAW_REFUND_CANCEL", label: LEDGER_BIZ_TYPE_LABEL.WITHDRAW_REFUND_CANCEL },
  { value: "WITHDRAW_REFUND_REJECT", label: LEDGER_BIZ_TYPE_LABEL.WITHDRAW_REFUND_REJECT },
  { value: "WITHDRAW_REFUND_EMERGENCY", label: LEDGER_BIZ_TYPE_LABEL.WITHDRAW_REFUND_EMERGENCY },
  { value: "WITHDRAW_SETTLE", label: LEDGER_BIZ_TYPE_LABEL.WITHDRAW_SETTLE },
  { value: "WITHDRAW_REFUND_CHAIN_FAILED", label: LEDGER_BIZ_TYPE_LABEL.WITHDRAW_REFUND_CHAIN_FAILED },
];

/**
 * 钱包页（console 风格）：hero 余额 + 入金地址清单 + 资金流水。
 * 出金操作仍走 topbar WithdrawDrawer，本页只挂一个跳转按钮。
 */
export function WalletPage({ onOpenWithdraw }: Props) {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;

  const accountQuery = useQuery({
    queryKey: ["wallet", "account"],
    queryFn: () => getAccount(token!),
    enabled: Boolean(token),
    staleTime: 5_000,
  });

  const addressesQuery = useQuery({
    queryKey: ["wallet", "deposit-addresses"],
    queryFn: () => ensureDepositAddresses(token!),
    enabled: Boolean(token),
    staleTime: 60 * 60 * 1000,
  });

  const [page, setPage] = useState(1);
  const [filterBizType, setFilterBizType] = useState<string>("");
  const [filterFrom, setFilterFrom] = useState<string>("");
  const [filterTo, setFilterTo] = useState<string>("");
  // 应用中的 filter（点查询后才生效），与编辑中的分开避免每次输入都触发请求
  const [appliedFilter, setAppliedFilter] = useState<{ bizType: string; from: string; to: string }>({
    bizType: "",
    from: "",
    to: "",
  });
  const ledgerQuery = useQuery({
    queryKey: ["wallet", "ledger", page, appliedFilter],
    queryFn: () =>
      listLedger(token!, page, 20, {
        bizType: appliedFilter.bizType || undefined,
        // datetime-local input 给的是 "YYYY-MM-DDTHH:mm"，后端按 UTC 兜底解析
        from: appliedFilter.from ? `${appliedFilter.from}:00` : undefined,
        to: appliedFilter.to ? `${appliedFilter.to}:59` : undefined,
      }),
    enabled: Boolean(token),
    staleTime: 5_000,
  });
  const applyFilter = () => {
    setPage(1);
    setAppliedFilter({ bizType: filterBizType, from: filterFrom, to: filterTo });
  };
  const resetFilter = () => {
    setFilterBizType("");
    setFilterFrom("");
    setFilterTo("");
    setPage(1);
    setAppliedFilter({ bizType: "", from: "", to: "" });
  };
  const filterActive = appliedFilter.bizType || appliedFilter.from || appliedFilter.to;

  const addresses = addressesQuery.data?.addresses ?? [];
  const ledgerItems = ledgerQuery.data?.items ?? [];
  const accountLoading = accountQuery.isLoading;
  const currency = accountQuery.data?.currency ?? "USDT";

  return (
    <main className="fx-console-page">
      <header className="fx-console-header">
        <div className="fx-console-header__row">
          <div className="fx-console-route">
            <span className="fx-console-route__icon">
              <WalletIcon size={13} strokeWidth={2.2} aria-hidden="true" />
            </span>
            <span>FalconX</span>
            <span className="fx-console-route__sep">/</span>
            <span>钱包</span>
            <span className="fx-console-route__sep">/</span>
            <span className="fx-console-route__current">概览</span>
          </div>
          <div className="fx-console-meta">
            <button
              type="button"
              className="fx-ghost-btn"
              disabled={accountQuery.isFetching || addressesQuery.isFetching || ledgerQuery.isFetching}
              onClick={() => {
                void accountQuery.refetch();
                void addressesQuery.refetch();
                void ledgerQuery.refetch();
              }}
              title="刷新"
            >
              <RefreshCw
                size={11}
                strokeWidth={2.2}
                aria-hidden="true"
                className={
                  accountQuery.isFetching || addressesQuery.isFetching || ledgerQuery.isFetching
                    ? "spin"
                    : ""
                }
              />
              {accountQuery.isFetching || addressesQuery.isFetching || ledgerQuery.isFetching
                ? "刷新中…"
                : "刷新"}
            </button>
            <button type="button" className="fx-ghost-btn fx-ghost-btn--primary" onClick={onOpenWithdraw}>
              <ArrowDownToLine size={11} strokeWidth={2.4} aria-hidden="true" />
              出金
            </button>
          </div>
        </div>
      </header>

      <div className="fx-console-page__body">
        {/* ----------- §01 账户余额 ----------- */}
        <section className="fx-console-section">
          <div className="fx-console-section__head">
            <span className="fx-console-section__num">§01</span>
            <h2 className="fx-console-section__title">账户余额</h2>
            <span className="fx-console-section__hint">
              {accountLoading ? "加载中…" : `1 ${currency} = 1.00 USD · 实时刷新`}
            </span>
          </div>
          <div className="wallet-hero">
            <div className="fx-stat fx-stat--hero">
              <span className="fx-stat__label">可用</span>
              <span>
                <span className="fx-stat__num">{fmtNum(accountQuery.data?.available)}</span>
                <span className="fx-stat__unit">{currency}</span>
              </span>
              <span className="fx-stat__hint">下单 / 出金可用余额</span>
            </div>
            <div className="wallet-hero__metrics">
              <div className="fx-stat">
                <span className="fx-stat__label">总余额</span>
                <span>
                  <span className="fx-stat__num">{fmtNum(accountQuery.data?.balance)}</span>
                  <span className="fx-stat__unit">{currency}</span>
                </span>
                <span className="fx-stat__hint">账户总余额</span>
              </div>
              <div className="fx-stat">
                <span className="fx-stat__label">已冻结</span>
                <span>
                  <span className="fx-stat__num">{fmtNum(accountQuery.data?.frozen)}</span>
                  <span className="fx-stat__unit">{currency}</span>
                </span>
                <span className="fx-stat__hint">出金冷静期等</span>
              </div>
              <div className="fx-stat">
                <span className="fx-stat__label">占用保证金</span>
                <span>
                  <span className="fx-stat__num">{fmtNum(accountQuery.data?.marginUsed)}</span>
                  <span className="fx-stat__unit">{currency}</span>
                </span>
                <span className="fx-stat__hint">持仓占用保证金</span>
              </div>
            </div>
          </div>
        </section>

        {/* ----------- §02 入金地址 ----------- */}
        <section className="fx-console-section">
          <div className="fx-console-section__head">
            <span className="fx-console-section__num">§02</span>
            <h2 className="fx-console-section__title">入金地址</h2>
            <span className="fx-console-section__hint">{addresses.length} 条链 · 永久</span>
          </div>
          {addressesQuery.isLoading ? (
            <p className="fx-tab-empty">加载中…</p>
          ) : addresses.length === 0 ? (
            <p className="fx-tab-empty">尚未派生入金地址。请联系运营或管理员。</p>
          ) : (
            <div className="wallet-net-list">
              {addresses.map((a) => (
                <DepositAddressRow key={`${a.network}:${a.address}`} item={a} />
              ))}
            </div>
          )}
          <div className="wallet-warn-strip">
            <AlertTriangle size={16} strokeWidth={2} aria-hidden="true" />
            <span>
              务必转入<b style={{ color: "#fff" }}>对应网络</b>的 USDT。转错网络（如 ERC20 地址收 TRC20）会导致资金<b style={{ color: "#fff" }}>永久丢失</b>，本平台不承担追回责任。
            </span>
          </div>
        </section>

        {/* ----------- §03 资金流水 ----------- */}
        <section className="fx-console-section">
          <div className="fx-console-section__head">
            <span className="fx-console-section__num">§03</span>
            <h2 className="fx-console-section__title">资金流水</h2>
            <span className="fx-console-section__hint">
              {filterActive ? "筛选 · " : ""}共 {ledgerQuery.data?.total ?? 0} 条 · 第 {page} 页
            </span>
          </div>
          <div className="ledger-filter">
            <label className="ledger-filter__field">
              <span className="ledger-filter__label">类型</span>
              <select
                className="ledger-filter__select"
                value={filterBizType}
                onChange={(e) => setFilterBizType(e.target.value)}
              >
                <option value="">全部</option>
                {LEDGER_FILTER_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="ledger-filter__field">
              <span className="ledger-filter__label">起始</span>
              <input
                className="ledger-filter__input"
                type="datetime-local"
                value={filterFrom}
                onChange={(e) => setFilterFrom(e.target.value)}
              />
            </label>
            <label className="ledger-filter__field">
              <span className="ledger-filter__label">结束</span>
              <input
                className="ledger-filter__input"
                type="datetime-local"
                value={filterTo}
                onChange={(e) => setFilterTo(e.target.value)}
              />
            </label>
            <div className="ledger-filter__actions">
              <button type="button" className="fx-btn-primary fx-btn-sm" onClick={applyFilter}>
                查询
              </button>
              <button type="button" className="fx-btn-secondary fx-btn-sm" onClick={resetFilter}>
                重置
              </button>
            </div>
          </div>
          <LedgerTable
            loading={ledgerQuery.isLoading}
            items={ledgerItems}
            currency={currency}
            page={ledgerQuery.data?.page ?? page}
            pageSize={ledgerQuery.data?.pageSize ?? 20}
            total={ledgerQuery.data?.total ?? 0}
            onPageChange={setPage}
          />
        </section>
      </div>
    </main>
  );
}

function fmtNum(value?: string): string {
  if (value == null) return "—";
  const n = Number(value);
  if (!Number.isFinite(n)) return value;
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function DepositAddressRow({ item }: { item: DepositAddressItem }) {
  const [copied, setCopied] = useState(false);
  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(item.address);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // ignore
    }
  };
  return (
    <div className="wallet-net-row">
      <div className="wallet-net-row__chain">
        <span className="wallet-net-row__net">{item.network}</span>
        <span className="wallet-net-row__token">{item.token}</span>
      </div>
      <div className="wallet-net-row__addr">{item.address}</div>
      <button
        type="button"
        className={copied ? "fx-copy-chip fx-copy-chip--done" : "fx-copy-chip"}
        onClick={onCopy}
        title="复制地址"
      >
        {copied ? <Check size={11} aria-hidden="true" /> : <Copy size={11} aria-hidden="true" />}
        {copied ? "已复制" : "复制"}
      </button>
    </div>
  );
}

interface LedgerTableProps {
  loading: boolean;
  items: LedgerEntry[];
  currency: string;
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (next: number) => void;
}

function LedgerTable({ loading, items, currency, page, pageSize, total, onPageChange }: LedgerTableProps) {
  const totalPages = useMemo(() => Math.max(1, Math.ceil(total / pageSize)), [total, pageSize]);
  const { isMobile } = useBreakpoint();
  if (loading) return <p className="fx-tab-empty">加载中…</p>;
  if (items.length === 0) return <p className="fx-tab-empty">暂无流水</p>;
  return (
    <div className="wallet-ledger">
      {!isMobile && (
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>类型</th>
              <th style={{ textAlign: "right" }}>金额</th>
              <th>关联</th>
              <th>余额变动</th>
            </tr>
          </thead>
          <tbody>
            {items.map((it) => {
              // 用「余额变化」决定符号 / 颜色，而不是看 amount 字段本身的正负 ——
              // 后端 amount 都是正数 + bizType 隐含方向，会让手续费类显示成 +X 让用户误解。
              const before = Number(it.balanceBefore);
              const after = Number(it.balanceAfter);
              const balanceDelta = Number.isFinite(before) && Number.isFinite(after) ? after - before : null;
              const rawAmount = Number(it.amount);
              // 按 bizType 分类格式化「量级」（手续费 4 位 / 盈亏有效数字 / 其余币种 2 位）；
              // 符号交给下方 balanceDelta 方向逻辑（后端 amount 恒正、方向藏在 bizType）。
              const absAmount = Number.isFinite(rawAmount)
                ? formatLedgerAmount(Math.abs(rawAmount), it.bizType, currency, false)
                : it.amount;
              let sign: string;
              let cls: string;
              if (balanceDelta != null && Math.abs(balanceDelta) > 1e-9) {
                // 真有余额变化：按余额方向显示
                sign = balanceDelta > 0 ? "+" : "-";
                cls = balanceDelta > 0 ? "fx-pnl-pos" : "fx-pnl-neg";
              } else {
                // 余额未变（保证金 / 冻结内部移动）→ 不加符号，灰色
                sign = "";
                cls = "wallet-ledger__amount--neutral";
              }
              return (
                <tr key={it.ledgerId}>
                  <td>{it.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                  <td>{LEDGER_BIZ_TYPE_LABEL[it.bizType] ?? it.bizType}</td>
                  <td className={`wallet-ledger__amount ${cls}`} style={{ textAlign: "right" }}>
                    {sign}
                    {absAmount}
                  </td>
                  <td className="wallet-ledger__ref" title={it.referenceNo ?? ""}>
                    <LedgerReference referenceNo={it.referenceNo} />
                  </td>
                  <td className="wallet-ledger__balance">
                    {Number.isFinite(before) && Number.isFinite(after)
                      ? `${formatMoney(before, currency)} → ${formatMoney(after, currency)}`
                      : "—"}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {isMobile && (
        <ul className="wallet-ledger-cards" aria-label="资金流水列表">
          {items.map((entry) => (
            <LedgerCard
              key={entry.ledgerId}
              item={entry}
              currency={currency}
              typeLabel={LEDGER_BIZ_TYPE_LABEL[entry.bizType] ?? entry.bizType}
            />
          ))}
        </ul>
      )}
      <div className="wallet-pagination">
        <button
          type="button"
          className="fx-ghost-btn"
          disabled={page <= 1}
          onClick={() => onPageChange(page - 1)}
        >
          上一页
        </button>
        <span className="wallet-pagination__info">
          第 {page} / {totalPages} 页 · 共 {total} 条
        </span>
        <button
          type="button"
          className="fx-ghost-btn"
          disabled={page >= totalPages}
          onClick={() => onPageChange(page + 1)}
        >
          下一页
        </button>
      </div>
    </div>
  );
}

/**
 * Ledger reference 单元格。referenceNo 是 txHash（DEPOSIT/WITHDRAW）或业务 id（其他）。
 * txHash 形态识别成功就给出 explorer 跳转链接；非链上引用则纯文本显示。
 */
function LedgerReference({ referenceNo }: { referenceNo: string | null }) {
  if (!referenceNo) return <span>—</span>;
  const url = buildTxExplorerUrl(referenceNo);
  if (!url) return <span>{referenceNo}</span>;
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="wallet-ledger__ref-link"
      title={`在区块浏览器查看 ${referenceNo}`}
    >
      <span>{referenceNo}</span>
      <ExternalLink size={11} strokeWidth={2} aria-hidden="true" />
    </a>
  );
}
