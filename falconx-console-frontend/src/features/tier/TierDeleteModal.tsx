import { useState } from "react";
import { Alert, Checkbox, Descriptions, Input, Modal, message } from "antd";
import { tierApi } from "./tierApi";
import type { TierItem } from "./types";

interface Props {
  open: boolean;
  tier: TierItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

// STAGE-14C2 Task 9 R10：tier 软删确认 Modal（高危）。
// 软删（trading-core 置 enabled=0）；reason 必填 + acknowledge 二次确认（参考 RiskConfigDeleteModal）。
export function TierDeleteModal({ open, tier, onClose, onSuccess }: Props) {
  const [reason, setReason] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const handleClose = () => {
    setReason("");
    setAcknowledged(false);
    onClose();
  };

  const handleSubmit = async () => {
    if (!tier) return;
    if (!reason.trim()) {
      void message.error("请填写原因");
      return;
    }
    setSubmitting(true);
    try {
      await tierApi.deleteTier(tier.id, reason.trim());
      void message.success(`${tier.symbol} #${tier.tierNo} 档位已删除`);
      handleClose();
      onSuccess();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      void message.error(`删除失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={`删除档位 ${tier?.symbol ?? ""} #${tier?.tierNo ?? ""}`}
      open={open}
      onCancel={handleClose}
      onOk={handleSubmit}
      okText="确认删除"
      okType="danger"
      okButtonProps={{ disabled: !acknowledged || !reason.trim(), loading: submitting }}
      cancelText="取消"
      destroyOnHidden
    >
      <Alert
        message="危险操作"
        description="软删后该档位停用（enabled=0），开仓将按剩余档位匹配杠杆/维持保证金。"
        type="error"
        showIcon
        style={{ marginBottom: 16 }}
      />
      {tier && (
        <Descriptions size="small" column={1} bordered style={{ marginBottom: 16 }}>
          <Descriptions.Item label="Symbol">{tier.symbol}</Descriptions.Item>
          <Descriptions.Item label="客户组">{tier.groupCode}</Descriptions.Item>
          <Descriptions.Item label="档位序号">{tier.tierNo}</Descriptions.Item>
          <Descriptions.Item label="名义区间">
            {tier.notionalLower} ~ {tier.notionalUpper ?? "∞"}
          </Descriptions.Item>
          <Descriptions.Item label="最大杠杆">{tier.maxLeverage}</Descriptions.Item>
          <Descriptions.Item label="维持保证金率">{tier.mmRate}</Descriptions.Item>
        </Descriptions>
      )}
      <Input.TextArea
        placeholder="删除原因（必填）"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        maxLength={200}
        rows={3}
        showCount
        style={{ marginBottom: 12 }}
      />
      <Checkbox checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)}>
        我已确认软删该档位
      </Checkbox>
    </Modal>
  );
}
