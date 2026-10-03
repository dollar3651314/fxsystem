import { useEffect, type ReactNode } from "react";
import { X } from "lucide-react";

export interface SheetProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  title?: string;
  className?: string;
}

/**
 * 半屏 sheet（mobile）/ 居中 modal（桌面）。
 * 2026-05-24: header 加 X 关闭按钮（mobile 无 ESC，overlay click 区域用户
 * 不易发现，必须有显式 close UI 避免用户卡在 OrderTicket 出不来）。
 */
export function Sheet({ open, onClose, children, title, className }: SheetProps) {
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, onClose]);

  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  return (
    <>
      <div
        className={`fx-sheet-overlay ${open ? "open" : ""}`}
        onClick={onClose}
        aria-hidden={!open}
      />
      <section
        className={`fx-sheet ${open ? "open" : ""} ${className ?? ""}`}
        role="dialog"
        aria-modal="true"
        aria-hidden={!open}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="fx-sheet__handle" aria-hidden="true" />
        <header className="fx-sheet__header">
          <span className="fx-sheet__title">{title ?? ""}</span>
          <button
            type="button"
            className="fx-sheet__close"
            onClick={onClose}
            aria-label="关闭"
          >
            <X size={18} strokeWidth={2} />
          </button>
        </header>
        <div className="fx-sheet__body">{children}</div>
      </section>
    </>
  );
}
