import type { ReactNode } from "react";

export interface BottomNavItem<K extends string = string> {
  key: K;
  label: string;
  icon: ReactNode;
  badge?: number;
  disabled?: boolean;
}

export interface BottomNavProps<K extends string = string> {
  items: BottomNavItem<K>[];
  active: K;
  onChange: (key: K) => void;
  className?: string;
}

/**
 * 底部 tab 导航。仅 `< md` 显示（≥ md 自动 `display: none`）。
 */
export function BottomNav<K extends string>({
  items,
  active,
  onChange,
  className,
}: BottomNavProps<K>) {
  return (
    <nav className={`fx-bottom-nav ${className ?? ""}`} aria-label="底部导航">
      {items.map((item) => {
        const isActive = item.key === active;
        return (
          <button
            key={item.key}
            type="button"
            className={`fx-bottom-nav__item ${isActive ? "active" : ""}`}
            disabled={item.disabled}
            aria-current={isActive ? "page" : undefined}
            aria-label={item.label}
            onClick={() => !item.disabled && onChange(item.key)}
          >
            {item.icon}
            <span>{item.label}</span>
            {typeof item.badge === "number" && item.badge > 0 && (
              <span className="fx-bottom-nav__badge">{item.badge}</span>
            )}
          </button>
        );
      })}
    </nav>
  );
}
