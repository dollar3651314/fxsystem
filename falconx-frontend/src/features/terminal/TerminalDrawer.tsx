import { User, ShieldCheck, ArrowDownToLine } from "lucide-react";

export interface TerminalDrawerProps {
  onOpenProfile: () => void;
  onOpenKyc: () => void;
  /** 跳转到钱包页（入金地址 + 余额 + 流水）。 */
  onNavigateToWallet: () => void;
}

/**
 * H5 抽屉菜单。
 *
 * 2026-05-28：
 * - 「出金」改「入金」 + 跳钱包页（与桌面顶栏对齐）；出金入口仍保留在钱包页内。
 * - 点击 item 关抽屉的责任在调用方（render-prop 拿 close 包一层），TerminalDrawer 自身只负责语义，
 *   不持有 drawer 状态。这样 trader terminal、settings page、其它入口都能复用。
 */
export function TerminalDrawer({ onOpenProfile, onOpenKyc, onNavigateToWallet }: TerminalDrawerProps) {
  return (
    <nav className="terminal-drawer" aria-label="账户菜单">
      <button type="button" className="terminal-drawer__item" onClick={onOpenProfile}>
        <User size={16} strokeWidth={1.8} aria-hidden="true" />
        <span>账户资料</span>
      </button>
      <button type="button" className="terminal-drawer__item" onClick={onOpenKyc}>
        <ShieldCheck size={16} strokeWidth={1.8} aria-hidden="true" />
        <span>身份认证</span>
      </button>
      <button type="button" className="terminal-drawer__item" onClick={onNavigateToWallet}>
        <ArrowDownToLine size={16} strokeWidth={1.8} aria-hidden="true" />
        <span>入金</span>
      </button>
    </nav>
  );
}
