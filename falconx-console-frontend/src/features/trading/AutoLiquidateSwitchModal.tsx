import { message } from "antd";
import { tradingApi } from "./tradingApi";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { ApiError } from "../../lib/api/apiClient";

interface Props {
  open: boolean;
  currentEnabled: boolean;
  targetEnabled: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 全局自动强平开关：平台级风控影响所有 OPEN 持仓，三重门档位。
 * reason ≥10 字符 + admin 用户名挑战 + confirm checkbox。
 *
 * 错误码 90655（开关已是该状态）等价于成功：吞掉并 toast warning，让 modal 关闭。
 */
export function AutoLiquidateSwitchModal({ open, currentEnabled, targetEnabled, onClose, onSuccess }: Props) {
  const adminUser = useAdminAuthStore((s) => s.user);
  if (!open) return null;
  const actionLabel = targetEnabled ? "启用" : "暂停";
  return (
    <HighRiskConfirmModal<{ enabled: boolean } | null>
      open={true}
      title={`${actionLabel}自动强平`}
      okText={`确认${actionLabel}`}
      description={
        targetEnabled
          ? "启用后 trading-core 将恢复对所有 OPEN 持仓的自动强平。"
          : "暂停后 trading-core 不再触发自动强平，请仅在维护窗口使用。"
      }
      details={[
        ["当前状态", currentEnabled ? "已启用" : "已暂停"],
        ["目标状态", targetEnabled ? "启用" : "暂停"],
      ]}
      requireConfirmCheckbox
      usernameChallenge={
        adminUser
          ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
          : undefined
      }
      onSubmit={async (reason) => {
        try {
          return await tradingApi.updateAutoLiquidateSwitch(targetEnabled, reason);
        } catch (err) {
          if (err instanceof ApiError && err.code === "90655") {
            void message.warning("开关已是该状态，无需操作");
            return null;
          }
          throw err;
        }
      }}
      onSuccess={(result) => {
        if (result) {
          void message.success(`自动强平已${result.enabled ? "启用" : "暂停"}`);
        }
        onClose();
        onSuccess();
      }}
      onCancel={onClose}
    />
  );
}
