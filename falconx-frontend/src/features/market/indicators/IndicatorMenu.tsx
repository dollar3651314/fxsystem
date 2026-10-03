import { useEffect, useRef, useState } from "react";
import { ChevronDown, SlidersHorizontal } from "lucide-react";
import {
  MAIN_INDICATORS,
  SUB_INDICATORS,
  useIndicatorsStore,
  type MainIndicator,
  type SubIndicator,
} from "./indicatorsStore";

/**
 * K 线指标切换菜单。
 *
 * 渲染为 chart panel head 右侧的一个 "指标 ▾" 按钮，点击打开下拉，
 * 内含两列：主图叠加 + 副图面板，各 chip 可点开关。
 * 持久化通过 useIndicatorsStore.persist。
 */
export function IndicatorMenu() {
  const main = useIndicatorsStore((s) => s.main);
  const sub = useIndicatorsStore((s) => s.sub);
  const toggleMain = useIndicatorsStore((s) => s.toggleMain);
  const toggleSub = useIndicatorsStore((s) => s.toggleSub);
  const setAll = useIndicatorsStore((s) => s.setAll);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement | null>(null);

  // 点外面关闭
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const mainCount = main.length;
  const subCount = sub.length;

  return (
    <div className="fx-indicator-menu" ref={ref}>
      <button
        type="button"
        className="fx-indicator-menu__trigger"
        onClick={() => setOpen((v) => !v)}
        title="指标设置"
        aria-expanded={open}
      >
        <SlidersHorizontal size={12} strokeWidth={2.2} aria-hidden="true" />
        指标
        {mainCount + subCount > 0 ? (
          <span className="fx-indicator-menu__count">{mainCount + subCount}</span>
        ) : null}
        <ChevronDown size={11} strokeWidth={2.2} aria-hidden="true" />
      </button>
      {open ? (
        <div className="fx-indicator-menu__panel" role="dialog" aria-label="指标设置">
          <section className="fx-indicator-menu__col">
            <header>
              <span>主图叠加</span>
              <button
                type="button"
                className="fx-indicator-menu__clear"
                onClick={() => setAll([], sub)}
                disabled={main.length === 0}
              >
                清空
              </button>
            </header>
            <ul>
              {MAIN_INDICATORS.map((it) => {
                const active = main.includes(it.key as MainIndicator);
                return (
                  <li key={it.key}>
                    <button
                      type="button"
                      className={active ? "active" : ""}
                      onClick={() => toggleMain(it.key)}
                      title={it.desc}
                    >
                      <span className="fx-indicator-menu__dot" aria-hidden="true" />
                      <span className="fx-indicator-menu__label">{it.label}</span>
                      <span className="fx-indicator-menu__desc">{it.desc}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
          <section className="fx-indicator-menu__col">
            <header>
              <span>副图面板</span>
              <button
                type="button"
                className="fx-indicator-menu__clear"
                onClick={() => setAll(main, [])}
                disabled={sub.length === 0}
              >
                清空
              </button>
            </header>
            <ul>
              {SUB_INDICATORS.map((it) => {
                const active = sub.includes(it.key as SubIndicator);
                return (
                  <li key={it.key}>
                    <button
                      type="button"
                      className={active ? "active" : ""}
                      onClick={() => toggleSub(it.key)}
                      title={it.desc}
                    >
                      <span className="fx-indicator-menu__dot" aria-hidden="true" />
                      <span className="fx-indicator-menu__label">{it.label}</span>
                      <span className="fx-indicator-menu__desc">{it.desc}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        </div>
      ) : null}
    </div>
  );
}
