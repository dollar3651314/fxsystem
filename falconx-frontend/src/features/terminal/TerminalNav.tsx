import { BarChart3, LayoutDashboard, ListChecks, Settings, WalletCards } from "lucide-react";
import { FalconMark } from "../../components/brand/FalconMark";

export type TerminalView = "dashboard" | "market" | "activity" | "wallet" | "settings";

interface NavItem {
  label: string;
  icon: typeof LayoutDashboard;
  key: TerminalView;
  enabled: boolean;
}

const navItems: NavItem[] = [
  { label: "首页", icon: LayoutDashboard, key: "dashboard", enabled: true },
  { label: "市场", icon: BarChart3, key: "market", enabled: true },
  { label: "活动", icon: ListChecks, key: "activity", enabled: true },
  { label: "钱包", icon: WalletCards, key: "wallet", enabled: true },
  { label: "设置", icon: Settings, key: "settings", enabled: true },
];

interface Props {
  activeKey: TerminalView;
  onSelectKey: (key: TerminalView) => void;
}

export function TerminalNav({ activeKey, onSelectKey }: Props) {
  return (
    <nav className="terminal-nav" aria-label="主导航">
      <FalconMark className="terminal-nav__logo" title="FalconX 交易终端" />
      <div className="terminal-nav__items">
        {navItems.map((item) => {
          const active = item.enabled && item.key === activeKey;
          return (
            <button
              className={active ? "terminal-nav__item active" : "terminal-nav__item"}
              type="button"
              key={item.label}
              disabled={!item.enabled}
              aria-label={item.enabled ? item.label : `${item.label}（未接入）`}
              title={item.enabled ? item.label : `${item.label}（未接入）`}
              onClick={() => {
                if (item.enabled) onSelectKey(item.key);
              }}
            >
              <item.icon size={20} strokeWidth={1.8} />
            </button>
          );
        })}
      </div>
    </nav>
  );
}
