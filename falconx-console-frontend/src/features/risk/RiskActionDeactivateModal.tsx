import { message } from "antd";
import { riskApi } from "./riskApi";
import type { RiskActionItem } from "./types";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { ApiError } from "../../lib/api/apiClient";

interface Props {
  open: boolean;
  action: RiskActionItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 停用风控动作走 HighRiskConfirmModal 三重门：reason ≥10 字符 + 用户名挑战 + confirm checkbox。
 *
 * 错误码兜底：
 * - 90801（记录不存在）→ 视作成功结束
 * - 90802（非 MANUAL_ADMIN 触发的记录）→ 抛回让 modal 显示错误 banner
 */
export function RiskActionDeactivateModal({ open, action, onClose, onSuccess }: Props) {
  const adminUser = useAdminAuthStore((s) => s.user);
  if (!open || !action) return null;
  return (
    <HighRiskConfirmModal<void>
      open={true}
      title="停用风控动作"
      okText="确认停用"
      description="停用后该风控动作即失效。"
      details={[
        ["ID", String(action.id)],
        ["Symbol", action.symbol ?? "*global*"],
        ["类型", action.actionType],
        ["触发来源", action.triggerSource],
        ["触发原因", action.triggerReason ?? "—"],
      ]}
      requireConfirmCheckbox
      usernameChallenge={
        adminUser
          ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
          : undefined
      }
      onSubmit={async (reason) => {
        try {
          await riskApi.deactivateAction(action.id, reason);
        } catch (err) {
          if (err instanceof ApiError && err.code === "90801") {
            void message.warning("记录已不存在");
            return;
          }
          throw err;
        }
      }}
      onSuccess={() => {
        void message.success(`风控动作 #${action.id} 已停用`);
        onClose();
        onSuccess();
      }}
      onCancel={onClose}
    />
  );
}
