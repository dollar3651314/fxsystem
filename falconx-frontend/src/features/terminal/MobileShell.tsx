import { useState, type ReactNode } from "react";
import { Menu } from "lucide-react";
import { useBreakpoint } from "../../lib/responsive";
import { BottomNav, Drawer, type BottomNavItem } from "../../components/mobile";
import { ThemeToggle } from "../settings/ThemeToggle";

export interface MobileShellProps<K extends string> {
  activeKey: K;
  onSelectKey: (key: K) => void;
  navItems: BottomNavItem<K>[];
  /** 抽屉内容；render-prop 形式可拿到 close 主动关闭抽屉（保持 close-after-action 的标准移动端行为）。 */
  drawerContent: ReactNode | ((opts: { close: () => void }) => ReactNode);
  children: ReactNode;
  header?: ReactNode;
}

/**
 * `< md` 时包 children 进 mobile shell（header + BottomNav + Drawer）。
 * `≥ md` 时直接 return children，桌面体验 0 影响。
 */
export function MobileShell<K extends string>({
  activeKey,
  onSelectKey,
  navItems,
  drawerContent,
  children,
  header,
}: MobileShellProps<K>) {
  const { isMobile } = useBreakpoint();
  const [drawerOpen, setDrawerOpen] = useState(false);

  if (!isMobile) return <>{children}</>;

  return (
    <div className="fx-mobile-shell">
      <header className="fx-mobile-header">
        <button
          type="button"
          className="fx-mobile-header__hamburger"
          aria-label="打开菜单"
          onClick={() => setDrawerOpen(true)}
        >
          <Menu size={22} strokeWidth={1.8} />
        </button>
        <div style={{ flex: 1, textAlign: "center" }}>{header ?? null}</div>
        <ThemeToggle variant="compact" />
      </header>
      <main className="fx-mobile-shell__main">{children}</main>
      <BottomNav items={navItems} active={activeKey} onChange={onSelectKey} />
      <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        {typeof drawerContent === "function"
          ? drawerContent({ close: () => setDrawerOpen(false) })
          : drawerContent}
      </Drawer>
    </div>
  );
}
