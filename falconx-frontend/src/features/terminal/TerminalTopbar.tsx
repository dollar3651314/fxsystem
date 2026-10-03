import type { ReactNode } from "react";
import { ArrowDownToLine, ShieldAlert, ShieldCheck } from "lucide-react";
import { NotificationCenter } from "../trading/NotificationCenter";
import { AccountMenu } from "./AccountMenu";

type TerminalTopbarProps = {
  onOpenProfile?: () => void;
  onOpenKyc?: () => void;
  /** 跳转到钱包页（入金地址 + 余额 + 流水）。 */
  onNavigateToWallet?: () => void;
  kycStatus?: "NONE" | "PENDING" | "APPROVED" | "REJECTED";
  /** §00 订单数据看板，嵌入观察组与操作组之间占据中段伸展区（窄屏横滚）。 */
  orderBoard?: ReactNode;
  /** 热门产品实时报价跑马灯，占据看板与右侧控件之间的空白区（移动端隐藏）。 */
  ticker?: ReactNode;
};

/**
 * 精简版账户栏（2026-05-20 重设计）：
 *
 * - 搜索框下沉到市场页 §01 (MarketWatchlist) — 只有市场页才需要它
 * - 行情连接状态指示移到 §02 行情图表 panel hint — 跟 LIVE/MISSING 状态信息一起
 * - 本栏只保留账户身份相关：通知 + KYC + 资金 + 个人资料 + 退出
 * - 按功能分两组用 `terminal-topbar__divider` 隔开：观察组（通知/KYC）+ 操作组（入金/个人/退出）
 * - 2026-05-27：原「出金」按钮改为「入金」，点击跳转钱包页（入金地址在那里）；
 *   出金入口保留在钱包页内。
 */
export function TerminalTopbar({
  onOpenProfile,
  onOpenKyc,
  onNavigateToWallet,
  kycStatus = "NONE",
  orderBoard,
  ticker,
}: TerminalTopbarProps) {
  return (
    <header className="terminal-topbar terminal-topbar--compact">
      {/* 左：§00 订单数据看板（内容宽） */}
      {orderBoard ? (
        <div className="terminal-topbar__board">{orderBoard}</div>
      ) : null}

      {/* 中：热门产品跑马灯，吃掉看板与右侧控件之间的空白区（移动端隐藏） */}
      {ticker ? <div className="terminal-topbar__ticker">{ticker}</div> : null}

      {/* 右：状态组（通知 + KYC chip）+ 操作组（入金主操作 + 账户下拉） */}
      <div className="terminal-topbar__group terminal-topbar__group--observe">
        <NotificationCenter />
        <button
          className={`fx-kyc-chip fx-kyc-chip--${kycStatus.toLowerCase()}`}
          type="button"
          onClick={onOpenKyc}
          title={kycButtonTitle(kycStatus)}
        >
          {kycStatus === "APPROVED" ? (
            <ShieldCheck size={13} aria-hidden="true" />
          ) : (
            <ShieldAlert size={13} aria-hidden="true" />
          )}
          {kycButtonLabel(kycStatus)}
        </button>
      </div>

      <span className="terminal-topbar__divider" aria-hidden="true" />

      <div className="terminal-topbar__group terminal-topbar__group--actions">
        <button
          className="fx-topbar-btn fx-topbar-btn--primary"
          type="button"
          onClick={onNavigateToWallet}
          title="入金 · 前往钱包查看入金地址"
        >
          <ArrowDownToLine size={14} aria-hidden="true" />
          入金
        </button>
        <AccountMenu onOpenProfile={onOpenProfile} />
      </div>
    </header>
  );
}

function kycButtonLabel(status: "NONE" | "PENDING" | "APPROVED" | "REJECTED"): string {
  switch (status) {
    case "APPROVED":
      return "KYC 已通过";
    case "PENDING":
      return "KYC 审核中";
    case "REJECTED":
      return "KYC 未通过";
    default:
      return "KYC 认证";
  }
}

function kycButtonTitle(status: "NONE" | "PENDING" | "APPROVED" | "REJECTED"): string {
  switch (status) {
    case "APPROVED":
      return "KYC 已通过";
    case "PENDING":
      return "KYC 审核中，等待运营审核";
    case "REJECTED":
      return "KYC 未通过，点击重新提交";
    default:
      return "完成 KYC 认证";
  }
}
