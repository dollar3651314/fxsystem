import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { ChevronDown, Check } from "lucide-react";

export interface FxSelectOption<T extends string> {
  value: T;
  label: string;
  hint?: string;
}

interface FxSelectProps<T extends string> {
  value: T;
  onChange: (next: T) => void;
  options: FxSelectOption<T>[];
  disabled?: boolean;
  placeholder?: string;
  className?: string;
  ariaLabel?: string;
}

export function FxSelect<T extends string>({
  value,
  onChange,
  options,
  disabled,
  placeholder = "请选择",
  className,
  ariaLabel,
}: FxSelectProps<T>) {
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState<number>(-1);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLUListElement | null>(null);
  const listboxId = useId();

  const currentIndex = useMemo(
    () => options.findIndex((o) => o.value === value),
    [options, value],
  );
  const currentLabel = currentIndex >= 0 ? options[currentIndex].label : placeholder;

  const close = useCallback(() => {
    setOpen(false);
    setActiveIndex(-1);
  }, []);

  const openMenu = useCallback(() => {
    if (disabled) return;
    setOpen(true);
    setActiveIndex(currentIndex >= 0 ? currentIndex : 0);
  }, [disabled, currentIndex]);

  useEffect(() => {
    if (!open) return;
    const handleDocClick = (e: MouseEvent) => {
      if (!containerRef.current) return;
      if (!containerRef.current.contains(e.target as Node)) close();
    };
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        close();
      } else if (e.key === "ArrowDown") {
        e.preventDefault();
        setActiveIndex((i) => (i + 1) % options.length);
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        setActiveIndex((i) => (i - 1 + options.length) % options.length);
      } else if (e.key === "Enter") {
        e.preventDefault();
        if (activeIndex >= 0 && activeIndex < options.length) {
          onChange(options[activeIndex].value);
          close();
        }
      } else if (e.key === "Tab") {
        close();
      }
    };
    document.addEventListener("mousedown", handleDocClick);
    document.addEventListener("keydown", handleKey);
    return () => {
      document.removeEventListener("mousedown", handleDocClick);
      document.removeEventListener("keydown", handleKey);
    };
  }, [open, options, activeIndex, onChange, close]);

  useEffect(() => {
    if (!open) return;
    const el = listRef.current?.querySelector<HTMLLIElement>(
      `[data-fx-select-index="${activeIndex}"]`,
    );
    el?.scrollIntoView({ block: "nearest" });
  }, [activeIndex, open]);

  const handleSelect = (next: T) => {
    onChange(next);
    close();
  };

  return (
    <div
      ref={containerRef}
      className={`fx-select${open ? " fx-select--open" : ""}${disabled ? " fx-select--disabled" : ""}${className ? ` ${className}` : ""}`}
    >
      <button
        type="button"
        className="fx-select__trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={() => (open ? close() : openMenu())}
      >
        <span className={`fx-select__value${currentIndex < 0 ? " fx-select__value--placeholder" : ""}`}>
          {currentLabel}
        </span>
        <ChevronDown size={14} className="fx-select__chevron" />
      </button>
      {open && (
        <ul
          ref={listRef}
          id={listboxId}
          className="fx-select__menu"
          role="listbox"
        >
          {options.map((opt, idx) => {
            const selected = opt.value === value;
            const active = idx === activeIndex;
            return (
              <li
                key={opt.value}
                data-fx-select-index={idx}
                role="option"
                aria-selected={selected}
                className={`fx-select__option${selected ? " fx-select__option--selected" : ""}${active ? " fx-select__option--active" : ""}`}
                onMouseEnter={() => setActiveIndex(idx)}
                onMouseDown={(e) => {
                  e.preventDefault();
                  handleSelect(opt.value);
                }}
              >
                <span className="fx-select__option-label">{opt.label}</span>
                {opt.hint && <span className="fx-select__option-hint">{opt.hint}</span>}
                {selected && <Check size={13} className="fx-select__option-check" />}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
