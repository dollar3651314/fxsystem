import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ChevronDown, LogOut, Moon, Sun, UserCircle } from "lucide-react";
import { logout } from "../auth/authApi";
import { useAuthStore } from "../auth/authStore";
import { usePreferencesStore, type LanguagePreference } from "../settings/preferencesStore";
import { useThemeStore } from "../settings/themeStore";

type AccountMenuProps = {
  onOpenProfile?: () => void;
};

const LANGUAGE_OPTIONS: Array<{ value: LanguagePreference; label: string }> = [
  { value: "zh-CN", label: "中文" },
  { value: "en-US", label: "EN" }
];

/**
 * 顶栏账户下拉（2026-06-04 重设计）。
 *
 * 把原本平铺在顶栏右侧的「个人资料 / 语言 / 主题 / 登出」收进一个账户菜单，
 * 顶栏右侧只剩「入金（主操作）+ 账户头像」两件，清爽有层次。
 *
 * Portal 到 body：父级 .terminal-topbar 有 backdrop-filter 会困住 position:fixed
 * （与 NotificationCenter 同坑），portal 出去后按触发按钮的视口坐标右对齐定位。
 */
export function AccountMenu({ onOpenProfile }: AccountMenuProps) {
  const clearSession = useAuthStore((s) => s.clearSession);
  const session = useAuthStore((s) => s.session);
  const language = usePreferencesStore((s) => s.language);
  const setLanguage = usePreferencesStore((s) => s.setLanguage);
  const theme = useThemeStore((s) => s.theme);
  const setTheme = useThemeStore((s) => s.setTheme);

  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const [anchor, setAnchor] = useState<{ top: number; right: number } | null>(null);

  // 打开时按触发按钮视口坐标定位面板（右对齐、下方 8px）
  useLayoutEffect(() => {
    if (!open || !buttonRef.current) {
      return;
    }
    const rect = buttonRef.current.getBoundingClientRect();
    setAnchor({ top: rect.bottom + 8, right: window.innerWidth - rect.right });
  }, [open]);

  // ESC 关闭；滚动/缩放时关闭（避免面板与按钮脱锚）
  useEffect(() => {
    if (!open) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    const onReflow = () => setOpen(false);
    window.addEventListener("keydown", onKey);
    window.addEventListener("resize", onReflow);
    window.addEventListener("scroll", onReflow, true);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("resize", onReflow);
      window.removeEventListener("scroll", onReflow, true);
    };
  }, [open]);

  async function handleLogout() {
    setOpen(false);
    if (session) {
      await logout(session.accessToken).catch(() => undefined);
    }
    clearSession();
  }

  const isDark = theme === "dark";

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={`fx-account-trigger${open ? " fx-account-trigger--open" : ""}`}
        aria-haspopup="menu"
        aria-expanded={open}
        title="账户"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="fx-account-trigger__avatar" aria-hidden="true">
          <UserCircle size={18} strokeWidth={1.6} />
        </span>
        <ChevronDown size={13} strokeWidth={2} aria-hidden="true" className="fx-account-trigger__chevron" />
      </button>

      {open && anchor
        ? createPortal(
            <div className="fx-account-overlay" onClick={() => setOpen(false)}>
              <div
                className="fx-account-menu"
                role="menu"
                aria-label="账户菜单"
                style={{ top: anchor.top, right: anchor.right }}
                onClick={(e) => e.stopPropagation()}
              >
                <div className="fx-account-menu__head">
                  <span className="fx-account-menu__avatar" aria-hidden="true">
                    <UserCircle size={28} strokeWidth={1.5} />
                  </span>
                  <div className="fx-account-menu__id">
                    <strong>我的账户</strong>
                    <span>{session?.emailVerified ? "邮箱已验证" : "已登录"}</span>
                  </div>
                </div>

                <button
                  type="button"
                  role="menuitem"
                  className="fx-account-menu__item"
                  onClick={() => {
                    setOpen(false);
                    onOpenProfile?.();
                  }}
                >
                  <UserCircle size={16} strokeWidth={1.8} aria-hidden="true" />
                  个人资料
                </button>

                <div className="fx-account-menu__sep" role="separator" />

                <div className="fx-account-menu__row">
                  <span className="fx-account-menu__row-label">语言</span>
                  <div className="fx-account-menu__segmented" role="group" aria-label="语言">
                    {LANGUAGE_OPTIONS.map((opt) => (
                      <button
                        key={opt.value}
                        type="button"
                        className={opt.value === language ? "active" : undefined}
                        aria-pressed={opt.value === language}
                        onClick={() => setLanguage(opt.value)}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="fx-account-menu__row">
                  <span className="fx-account-menu__row-label">主题</span>
                  <div className="fx-account-menu__segmented" role="group" aria-label="主题">
                    <button
                      type="button"
                      className={isDark ? "active" : undefined}
                      aria-pressed={isDark}
                      onClick={() => setTheme("dark")}
                    >
                      <Moon size={13} strokeWidth={1.9} aria-hidden="true" />
                      深色
                    </button>
                    <button
                      type="button"
                      className={!isDark ? "active" : undefined}
                      aria-pressed={!isDark}
                      onClick={() => setTheme("light")}
                    >
                      <Sun size={13} strokeWidth={1.9} aria-hidden="true" />
                      浅色
                    </button>
                  </div>
                </div>

                <div className="fx-account-menu__sep" role="separator" />

                <button
                  type="button"
                  role="menuitem"
                  className="fx-account-menu__item fx-account-menu__item--danger"
                  onClick={handleLogout}
                >
                  <LogOut size={16} strokeWidth={1.8} aria-hidden="true" />
                  登出
                </button>
              </div>
            </div>,
            document.body
          )
        : null}
    </>
  );
}
