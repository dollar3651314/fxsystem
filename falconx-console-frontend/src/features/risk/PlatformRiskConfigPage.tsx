import { useState } from "react";
import { Alert, Button, Card, Form, Input, InputNumber, Space, Typography, message } from "antd";
import { riskApi } from "./riskApi";
import type { PlatformRiskConfigUpdateRequest } from "./types";

const { Title } = Typography;

/**
 * BBOOK-RISK-CONTROL-01：平台总敞口阈值管理页。
 *
 * <p>当前阶段写入是单向的：列表/明细从 t_risk_config symbol=NULL 行读不暴露，
 * 该页只提供"更新阈值"的入口；后续可加只读展示。
 */
export function PlatformRiskConfigPage() {
  const [form] = Form.useForm<PlatformRiskConfigUpdateRequest>();
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = (values: PlatformRiskConfigUpdateRequest) => {
    setSubmitting(true);
    riskApi.updatePlatformRiskConfig({
      hedgeThresholdUsd: String(values.hedgeThresholdUsd),
      reason: values.reason,
    }).then(() => {
      message.success("平台阈值已更新；trading-core 下一次 5s scheduler 周期生效");
      form.resetFields();
    }).catch((err) => message.error(`更新失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setSubmitting(false));
  };

  return (
    <div>
      <Title level={3}>平台总敞口阈值</Title>
      <Alert
        message="平台总敞口阈值"
        description={
          <span>
            trading-core 每 5 秒累计全部 symbol 的 |net_exposure_usd|；
            超过阈值时自动激活 <b>GLOBAL_PAUSE</b>（拒所有开仓）；恢复时自动停用。
            修改后无需重启服务。
          </span>
        }
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Card title="设置阈值" style={{ maxWidth: 600 }}>
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            label="平台 hedge_threshold_usd"
            name="hedgeThresholdUsd"
            rules={[{ required: true, message: "必填" }]}
            extra="单位：USD；推荐范围 1000000 - 50000000"
          >
            <InputNumber style={{ width: "100%" }} min={1} />
          </Form.Item>
          <Form.Item
            label="变更原因"
            name="reason"
            rules={[{ required: true, message: "必填" }]}
          >
            <Input.TextArea rows={2} placeholder="审计字段，必填" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={submitting}>提交</Button>
              <Button onClick={() => form.resetFields()}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
