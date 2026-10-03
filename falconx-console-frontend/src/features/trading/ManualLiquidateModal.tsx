import { message } from "antd";
import { tradingApi } from "./tradingApi";
import type { PositionListItem } from "./types";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { ApiError } from "../../lib/api/apiClient";
import { formatSignedPnl } from "../../lib/precision";

interface Props {
  open: boolean;
  position: PositionListItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 手动强平：与 admin 余额调整 / 出金紧急取消同款档位
 * reason ≥10 字符 + 用户名挑战 + confirm checkbox（HighRiskConfirmModal 内置）。
 *
 * 错误码 90651（持仓已平）等价于成功：在 onSubmit 内吞掉并 toast warning，
 * 让 HighRiskConfirmModal 走 onSuccess 关闭弹窗。
 */
export function ManualLiquidateModal({ open, position, onClose, onSuccess }: Props) {
  const adminUser = useAdminAuthStore((s) => s.user);
  if (!open || !position) return null;
  return (
    <HighRiskConfirmModal<{ realizedPnl: string } | null>
      open={true}
      title="手动强平二次确认"
      okText="确认强平"
      description="此操作将以当前最新报价立即关闭该持仓，无法撤销。"
      details={[
        ["持仓 ID", String(position.id)],
        ["用户 ID", String(position.userId)],
        ["Symbol", position.symbol],
        ["方向", position.side === 1 ? "买" : "卖"],
        ["数量", String(position.quantity)],
        ["开仓价", String(position.entryPrice)],
        ["保证金", String(position.margin)],
      ]}
      requireConfirmCheckbox
      usernameChallenge={
        adminUser
          ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
          : undefined
      }
      onSubmit={async (reason) => {
        try {
          return await tradingApi.manualLiquidate(position.id, reason);
        } catch (err) {
          if (err instanceof ApiError && err.code === "90651") {
            void message.warning("该持仓已被平仓，可能其他管理员或自动强平已处理");
            return null; // 视作成功结束流程
          }
          if (err instanceof ApiError && err.code === "90652") {
            void message.warning("持仓锁定中，请稍后重试");
            // 抛回让 HighRiskConfirmModal 渲染错误 banner，保留 modal 打开供再试
            throw err;
          }
          throw err;
        }
      }}
      onSuccess={(result) => {
        if (result) {
          void message.success(`持仓 #${position.id} 已强平（实现盈亏 ${formatSignedPnl(result.realizedPnl)}）`);
        }
        onClose();
        onSuccess();
      }}
      onCancel={onClose}
    />
  );
}
