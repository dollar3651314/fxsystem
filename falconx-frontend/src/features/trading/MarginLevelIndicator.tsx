import { useId, useState } from "react";
import { formatPercent } from "../../lib/precision";

/**
 * STAGE-14E1 Task6：账户级 MarginLevel 浮窗（展示组件）。
 *
 * <p>展示账户级保证金水平（C1/D2 三态）：百分比数值 + 三态颜色 badge + hover/click 浮窗
 * （含中文说明）。数据来自 WS account.update（TradingTerminal state，经 props 透传）。
 *
 * <p>三态：
 *  - HEALTHY     → 绿 (--fx-long)     健康
 *  - MARGIN_CALL → 橙 (--fx-risk)     保证金预警
 *  - STOP_OUT    → 红 (--fx-danger)   强平触发
 *
 * <p>marginLevel 是百分比数值字符串（如 "184.00"，单位 %）。
 * null（无持仓 / FX 降级）优雅显示 "—"，不报错。
 */

interface Props {
  /** 保证金水平百分比数值字符串（如 "184.00"），null = 无数据。 */
  marginLevel: string | null;
  /** 三态：HEALTHY / MARGIN_CALL / STOP_OUT，null = 无数据。 */
  marginLevelStatus: string | null;
}

interface StatusMeta {
  /** badge 修饰类，对应三态 token。 */
  modifier: string;
  /** 中文短标签（badge 文案）。 */
  label: string;
  /** 浮窗中文说明。 */
  desc: string;
}

const STATUS_META: Record<string, StatusMeta> = {
  HEALTHY: {
    modifier: "margin-level-indicator__badge--healthy",
    label: "健康",
    desc: "账户保证金充足，无强平风险。",
  },
  MARGIN_CALL: {
    modifier: "margin-level-indicator__badge--call",
    label: "保证金预警",
    desc: "保证金水平偏低，已触发预警，请尽快追加保证金或减仓以避免强平。",
  },
  STOP_OUT: {
    modifier: "margin-level-indicator__badge--stopout",
    label: "强平触发",
    desc: "保证金水平已达强平线，系统将按风控规则对仓位执行强制平仓。",
  },
};

const UNKNOWN_META: StatusMeta = {
  modifier: "margin-level-indicator__badge--unknown",
  label: "无数据",
  desc: "暂无账户保证金水平数据（无持仓或汇率暂不可用）。",
};

function formatMarginLevel(marginLevel: string | null): string {
  if (marginLevel == null || marginLevel === "") return "—";
  const num = Number(marginLevel);
  if (!Number.isFinite(num)) return "—";
  return formatPercent(num, 2);
}

export function MarginLevelIndicator({ marginLevel, marginLevelStatus }: Props) {
  const [open, setOpen] = useState(false);
  const popId = useId();

  const meta = (marginLevelStatus && STATUS_META[marginLevelStatus]) || UNKNOWN_META;
  const percentText = formatMarginLevel(marginLevel);

  return (
    <div
      className="margin-level-indicator"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        className={`margin-level-indicator__badge ${meta.modifier}`}
        aria-label={`保证金水平 ${percentText} ${meta.label}`}
        aria-expanded={open}
        aria-describedby={open ? popId : undefined}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
      >
        <span className="margin-level-indicator__value fx-num">{percentText}</span>
        <span className="margin-level-indicator__status">{meta.label}</span>
      </button>

      {open && (
        <div className="margin-level-indicator__pop" id={popId} role="tooltip">
          <div className="margin-level-indicator__pop-head">
            <span className={`margin-level-indicator__dot ${meta.modifier}`} aria-hidden="true" />
            <span className="margin-level-indicator__pop-title">{meta.label}</span>
          </div>
          <div className="margin-level-indicator__pop-row">
            <span className="margin-level-indicator__pop-label">保证金水平</span>
            <span className="margin-level-indicator__pop-num fx-num">{percentText}</span>
          </div>
          <p className="margin-level-indicator__pop-desc">{meta.desc}</p>
        </div>
      )}
    </div>
  );
}
