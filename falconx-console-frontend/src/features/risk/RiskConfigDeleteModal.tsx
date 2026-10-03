import { useState } from "react";
import { Alert, Checkbox, Descriptions, Input, Modal, message } from "antd";
import { riskApi } from "./riskApi";
import type { RiskConfigItem } from "./types";

interface Props {
  open: boolean;
  config: RiskConfigItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

export function RiskConfigDeleteModal({ open, config, onClose, onSuccess }: Props) {
  const [reason, setReason] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const handleClose = () => {
    setReason("");
    setAcknowledged(false);
    onClose();
  };

  const handleSubmit = async () => {
    if (!config) return;
    if (!reason.trim()) {
      void message.error("请填写原因");
      return;
    }
    setSubmitting(true);
    try {
      await riskApi.deleteConfig(config.symbol, reason);
      void message.success(`${config.symbol} risk_config 已删除`);
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
      title={`删除 ${config?.symbol ?? ""} risk_config`}
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
        description="删除后该 symbol 的风控配置丢失，trading-core 开仓将走默认行为。"
        type="error"
        showIcon
        style={{ marginBottom: 16 }}
      />
      {config && (
        <Descriptions size="small" column={1} bordered style={{ marginBottom: 16 }}>
          <Descriptions.Item label="Symbol">{config.symbol}</Descriptions.Item>
          <Descriptions.Item label="Market">{config.marketCode ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="最大杠杆">{config.maxLeverage}</Descriptions.Item>
          <Descriptions.Item label="对冲阈值">{config.hedgeThresholdUsd}</Descriptions.Item>
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
        我已确认删除该 risk_config
      </Checkbox>
    </Modal>
  );
}
