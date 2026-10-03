// STAGE-7-WITHDRAW 客户端：契约见 docs/api/REST接口规范.md §9.2
// 雪花 ID 统一用 string 防 JSON.parse 精度丢失（后端 WithdrawOrderResponse / WithdrawWhitelistResponse 已字符串化）

export type WithdrawNetwork = "ERC20" | "TRC20";

export type WithdrawStatus =
  | "COOLING"
  | "PENDING"
  | "APPROVED"
  | "APPROVED_DELAYED"
  | "PROCESSING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELED"
  | "REJECTED";

export interface WithdrawOrder {
  withdrawId: string;
  userId: string;
  amount: string;
  currency: string;
  network: WithdrawNetwork;
  targetAddress: string;
  status: WithdrawStatus;
  coolingUntil: string | null;
  delayedUntil: string | null;
  rejectReason: string | null;
  txHash: string | null;
  confirmations: number;
  failureReason: string | null;
  createdAt: string;
}

export interface WithdrawListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: WithdrawOrder[];
}

export type WhitelistStatus = "PENDING" | "ACTIVE" | "REMOVED";

export interface WithdrawWhitelistItem {
  id: string;
  userId: string;
  network: WithdrawNetwork;
  address: string;
  label: string | null;
  status: WhitelistStatus;
  activatedAt: string | null;
  createdAt: string;
}

export interface SubmitWithdrawRequest {
  amount: string;
  currency: string;
  network: WithdrawNetwork;
  targetAddress: string;
  whitelistId: string;
}

export interface AddWhitelistRequest {
  network: WithdrawNetwork;
  address: string;
  label?: string;
}

/**
 * 出金错误码 → 友好中文文案 + 可选的"下一步动作"
 * - openKyc：要求跳转到 KYC drawer
 * - addWhitelist：提示用户先添加白名单
 * （UI 拿到 actionHint 后决定是否渲染额外按钮，未识别时回落到通用提示。）
 */
export interface WithdrawErrorHint {
  text: string;
  actionHint?: "openKyc" | "addWhitelist";
}

export function describeWithdrawError(code: string, fallbackMessage: string): WithdrawErrorHint {
  switch (code) {
    case "30040":
      return { text: "金额非法：请确认金额大于最低限额且精度不超过 8 位" };
    case "30041":
      return { text: "单笔出金不能超过 $10,000" };
    case "30042":
      return { text: "已达单日累计上限 $30,000，请明日再试" };
    case "30043":
      return { text: "出金前需完成 KYC 实名认证", actionHint: "openKyc" };
    case "30044":
      return { text: "暂不支持该网络，仅支持 ERC20 / TRC20" };
    case "30045":
      return { text: "地址格式不合法，请核对" };
    case "30046":
      return { text: "目标地址不在白名单中或与白名单不匹配", actionHint: "addWhitelist" };
    case "30054":
      return { text: "可用余额不足（可用 = 余额 - 已冻结 - 占用保证金）" };
    case "30055":
      return { text: "白名单需要 24 小时冷静期后才能用于出金，请稍后再试" };
    case "30056":
      return {
        // 历史无入金 vs 历史不含目标地址，UI 端没有 historySize 元数据，文案统一兜底
        text: "本次出金地址不在您历史成功入金的地址集合中。出于安全保护，请使用您曾经入金过的地址，或重新提交 KYC 后再试",
        actionHint: "openKyc",
      };
    default:
      return { text: fallbackMessage || "出金失败，请稍后重试" };
  }
}

export const WITHDRAW_STATUS_LABEL: Record<WithdrawStatus, string> = {
  COOLING: "冷静期中",
  PENDING: "等待审核",
  APPROVED: "已审核",
  APPROVED_DELAYED: "延迟放款",
  PROCESSING: "链上广播中",
  COMPLETED: "已到账",
  FAILED: "失败",
  CANCELED: "已取消",
  REJECTED: "已拒绝",
};

export const WHITELIST_STATUS_LABEL: Record<WhitelistStatus, string> = {
  PENDING: "冷静期中",
  ACTIVE: "已生效",
  REMOVED: "已删除",
};
