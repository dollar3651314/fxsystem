/** §00 订单数据看板的纯展示格式化（独立文件以满足 react-refresh 仅导出组件约束）。 */

/**
 * 净值实时合成 = 余额 + 未实现盈亏（账户币口径）。
 *
 * WS account.update 仅在余额/保证金事件时推送，不随行情 tick 走；而未实现盈亏由
 * user.position.summary 每 500ms 直推。用「REST 余额 + WS 实时盈亏」本地相加，
 * 让净值跟随每条盈亏推送跳动。任一源缺失（未登录/首屏未就绪/非数值）返回 null，
 * 由调用方退回 WS account.update 快照或 REST equity 兜底。返回字符串供 formatMoney 消费。
 */
export function composeEquity(
  balance: string | null | undefined,
  unrealizedPnl: string | null | undefined
): string | null {
  if (balance == null || unrealizedPnl == null) {
    return null;
  }
  const balanceNum = Number(balance);
  const pnlNum = Number(unrealizedPnl);
  if (!Number.isFinite(balanceNum) || !Number.isFinite(pnlNum)) {
    return null;
  }
  return String(balanceNum + pnlNum);
}

/** 保证金水平展示：千分位 + 固定 2 位小数；非有限值原样返回（后端字符串口径）。 */
export function trimPercent(value: string): string {
  const num = Number(value);
  if (!Number.isFinite(num)) {
    return value;
  }
  // 大数（无持仓时可达千万 %）千分位分组便于读
  return num.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function marginStatusLabel(status: string): string {
  switch (status) {
    case "HEALTHY":
      return "健康";
    case "MARGIN_CALL":
      return "预警";
    case "STOP_OUT":
      return "强平";
    default:
      return status;
  }
}
