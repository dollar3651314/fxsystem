import { useEffect, useState } from "react";
import { Alert, Checkbox, Form, Input, InputNumber, Modal, Switch, message } from "antd";
import { riskApi } from "./riskApi";
import type { RiskMarketConfigItem } from "./types";

interface Props {
  open: boolean;
  config: RiskMarketConfigItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

interface FormValues {
  concentrationThresholdUsd: number;
  isEnabled: boolean;
  reason: string;
}

export function RiskMarketConfigModal({ open, config, onClose, onSuccess }: Props) {
  const [form] = Form.useForm<FormValues>();
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open && config) {
      form.setFieldsValue({
        concentrationThresholdUsd: Number(config.concentrationThresholdUsd),
        isEnabled: config.enabled,
        reason: "",
      });
    }
  }, [open, config, form]);

  const handleClose = () => {
    form.resetFields();
    setAcknowledged(false);
    onClose();
  };

  const handleSubmit = async () => {
    if (!config) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await riskApi.updateMarketConfig(config.marketCode, {
        concentrationThresholdUsd: String(values.concentrationThresholdUsd),
        isEnabled: values.isEnabled,
        reason: values.reason,
      });
      void message.success(`${config.marketCode} 已更新`);
      handleClose();
      onSuccess();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      if (e.code === "90809") {
        void message.error("集中度阈值不能为负");
      } else if (e.code) {
        void message.error(`操作失败 ${e.code}：${e.message ?? ""}`);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={`编辑 ${config?.marketCode ?? ""} risk_market_config`}
      open={open}
      onCancel={handleClose}
      onOk={handleSubmit}
      okText="保存"
      okType="danger"
      okButtonProps={{ disabled: !acknowledged, loading: submitting }}
      cancelText="取消"
      destroyOnHidden
    >
      <Alert
        message="高危操作"
        description="集中度阈值与开关均影响跨品种风控触发条件。"
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Form form={form} layout="vertical">
        <Form.Item label="Market Code">
          <Input value={config?.marketCode} disabled />
        </Form.Item>
        <Form.Item name="concentrationThresholdUsd" label="集中度阈值 USD" rules={[{ required: true }]}>
          <InputNumber min={0} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item name="isEnabled" label="启用" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item name="reason" label="原因（必填）" rules={[{ required: true, max: 200 }]}>
          <Input.TextArea rows={3} maxLength={200} showCount />
        </Form.Item>
      </Form>
      <Checkbox checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)}>
        我已确认该修改
      </Checkbox>
    </Modal>
  );
}
