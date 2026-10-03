import type { DetailSection } from "./OrderDetailModal";
import type { SwapSummary } from "./tradingApi";
import { formatMoney, formatSignedMoney } from "../../lib/precision";
import { ACCOUNT_CURRENCY } from "./pnlDisplay";

/** Swap §06 段 builder，复用给 OPEN + CLOSED 详情。 */
export function buildSwapSection(summary: SwapSummary | undefined, loading: boolean): DetailSection {
  if (loading) {
    return {
      num: "06",
      title: "Swap 资金费率（加载中…）",
      fields: [{ label: "状态", value: "加载中…", emphasis: "muted", fullWidth: true }],
    };
  }
  if (!summary || (summary.chargeCount === 0 && summary.incomeCount === 0)) {
    return {
      num: "06",
      title: "Swap 资金费率",
      fields: [
        { label: "累计支付", value: "0.0000", mono: true, emphasis: "muted" },
        { label: "累计收入", value: "0.0000", mono: true, emphasis: "muted" },
        { label: "净影响", value: "0.0000", mono: true, emphasis: "muted" },
        { label: "结算次数", value: "0 次", emphasis: "muted" },
      ],
    };
  }
  const charge = Number(summary.totalCharge);
  const income = Number(summary.totalIncome);
  const net = Number(summary.net);
  return {
    num: "06",
    title: "Swap 资金费率",
    fields: [
      {
        label: "累计支付",
        value: `-${formatMoney(charge, ACCOUNT_CURRENCY, 4)}`,
        mono: true,
        emphasis: charge > 0 ? "short" : "muted",
        tooltip: `${summary.chargeCount} 次 SWAP_CHARGE`,
      },
      {
        label: "累计收入",
        value: `+${formatMoney(income, ACCOUNT_CURRENCY, 4)}`,
        mono: true,
        emphasis: income > 0 ? "long" : "muted",
        tooltip: `${summary.incomeCount} 次 SWAP_INCOME`,
      },
      {
        label: "净影响",
        value: formatSignedMoney(net, ACCOUNT_CURRENCY, 4),
        mono: true,
        emphasis: net >= 0 ? "long" : "short",
      },
      {
        label: "结算次数",
        value: `${summary.chargeCount + summary.incomeCount} 次`,
      },
      summary.firstAt
        ? {
            label: "首次结算",
            value: summary.firstAt.replace("T", " ").slice(0, 19),
            mono: true,
            emphasis: "muted",
          }
        : null,
      summary.lastAt
        ? {
            label: "最近结算",
            value: summary.lastAt.replace("T", " ").slice(0, 19),
            mono: true,
            emphasis: "muted",
          }
        : null,
    ].filter(Boolean) as DetailSection["fields"],
  };
}
