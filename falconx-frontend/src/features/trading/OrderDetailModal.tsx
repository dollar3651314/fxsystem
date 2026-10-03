import { useEffect } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";

export interface DetailField {
  label: string;
  value: string | number | null | undefined;
  mono?: boolean;
  emphasis?: "long" | "short" | "cyan" | "muted" | "fee";
  tooltip?: string;
  fullWidth?: boolean;
}

export interface DetailSection {
  num: string;
  title: string;
  fields: DetailField[];
}

interface Props {
  open: boolean;
  title: string;
  subtitle?: string;
  sections: DetailSection[];
  onClose: () => void;
}

export function OrderDetailModal({ open, title, subtitle, sections, onClose }: Props) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div
      className="fx-modal-mask fx-modal-mask--detail"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <div className="fx-modal fx-modal--detail" onClick={(e) => e.stopPropagation()}>
        <div className="fx-detail-header">
          <div className="fx-detail-header__main">
            <span className="fx-detail-header__eyebrow">DETAIL · INSPECT</span>
            <h2 className="fx-detail-header__title">{title}</h2>
            {subtitle && <p className="fx-detail-header__subtitle">{subtitle}</p>}
          </div>
          <button
            type="button"
            className="fx-detail-close"
            onClick={onClose}
            aria-label="关闭详情"
          >
            <X size={16} strokeWidth={2.2} />
          </button>
        </div>

        <div className="fx-detail-body">
          {sections.map((s) => (
            <section key={s.num} className="fx-detail-section">
              <header className="fx-detail-section__head">
                <span className="fx-detail-section__num">§{s.num}</span>
                <span className="fx-detail-section__title">{s.title}</span>
                <span className="fx-detail-section__rule" />
              </header>
              <dl className="fx-detail-grid">
                {s.fields.map((f, i) => {
                  const valueClass = [
                    "fx-detail-grid__value",
                    f.mono ? "fx-detail-grid__value--mono" : "",
                    f.emphasis ? `fx-detail-grid__value--${f.emphasis}` : "",
                  ]
                    .filter(Boolean)
                    .join(" ");
                  return (
                    <div
                      key={`${s.num}-${i}`}
                      className={`fx-detail-grid__row${f.fullWidth ? " fx-detail-grid__row--wide" : ""}`}
                      title={f.tooltip}
                    >
                      <dt className="fx-detail-grid__label">{f.label}</dt>
                      <dd
                        className={valueClass}
                        title={
                          typeof f.value === "string" && f.value.length > 12
                            ? f.value
                            : undefined
                        }
                      >
                        {f.value === null || f.value === undefined || f.value === "" ? (
                          <span className="fx-detail-grid__value--empty">—</span>
                        ) : (
                          f.value
                        )}
                      </dd>
                    </div>
                  );
                })}
              </dl>
            </section>
          ))}
        </div>

        <div className="fx-detail-footer">
          <button type="button" className="fx-btn-secondary" onClick={onClose}>
            关闭
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
