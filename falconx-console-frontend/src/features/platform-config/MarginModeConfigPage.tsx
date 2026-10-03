import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Typography,
  message,
} from "antd";
import { RequiresPermission } from "../../components/RequiresPermission";
import { marginModeApi } from "./marginModeApi";

const { Title } = Typography;

const MIN_SECONDS = 60;
const MAX_SECONDS = 604800; // 7d

// STAGE-14D3b Task5：保证金模式冷静期配置页。
//
// 单 Form 编辑 coolingPeriodSeconds（60s ~ 7d）；对接 console-service D3b Task2
//   GET/PUT /admin/trading/margin-mode-config。
// · 读：进入页面拉当前值填表 + Descriptions 展示当前生效值。
// · 写：高危——保存按钮按 margin-mode-config:edit 权限渲染（无权限隐藏，非 disabled，
//   对齐 RequiresPermission / DESIGN §11）；点保存弹二次确认 Modal（reason 必填 +
//   acknowledge 勾选后方可提交，参考 TierFormModal），确认后 PUT 成功 message + refetch。
// · 范围前端即时校验（60-604800）仅提示；越界最终由 trading-core 裁决（console 翻 90950）。
interface FormValues {
  coolingPeriodSeconds: number;
}

export function MarginModeConfigPage() {
  const [form] = Form.useForm<FormValues>();
  const [confirmForm] = Form.useForm<{ reason: string }>();
  const [current, setCurrent] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [pendingValue, setPendingValue] = useState<number | null>(null);

  const fetchConfig = () => {
    setLoading(true);
    marginModeApi
      .get()
      .then((cfg) => {
        setCurrent(cfg.coolingPeriodSeconds);
        form.setFieldsValue({ coolingPeriodSeconds: cfg.coolingPeriodSeconds });
      })
      .catch((err) => {
        const e = err as { code?: string; message?: string };
        void message.error(`加载失败 ${e.code ?? ""}：${e.message ?? ""}`);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchConfig();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 第一步：校验主表单（含范围），通过后开二次确认 Modal。
  const handleSave = async () => {
    const values = await form.validateFields();
    setPendingValue(values.coolingPeriodSeconds);
    setAcknowledged(false);
    confirmForm.resetFields();
    setConfirmOpen(true);
  };

  const handleConfirmClose = () => {
    setConfirmOpen(false);
    setAcknowledged(false);
    confirmForm.resetFields();
  };

  // 第二步：校验 reason 后 PUT。
  const handleConfirmSubmit = async () => {
    try {
      const { reason } = await confirmForm.validateFields();
      if (pendingValue == null) return;
      setSubmitting(true);
      await marginModeApi.update(pendingValue, reason);
      void message.success("冷静期配置已更新");
      handleConfirmClose();
      fetchConfig();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      // 表单本地校验失败（validateFields reject）无 code，不弹错误。
      if (e.code) {
        void message.error(`更新失败 ${e.code}：${e.message ?? ""}`);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <Title level={3}>保证金模式冷静期配置</Title>
      <Alert
        message="保证金模式切换冷静期"
        description={
          <span>
            用户切换全仓/逐仓（CROSS/ISOLATED）后进入冷静期，期间禁止再次切换。
            范围 60s ~ 7d（60-604800 秒）；越界由 trading-core 拒绝。修改后无需重启服务。
          </span>
        }
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Card title="当前配置" style={{ maxWidth: 600, marginBottom: 16 }} loading={loading}>
        <Descriptions column={1}>
          <Descriptions.Item label="当前冷静期">
            {current == null ? "—" : `${current} 秒`}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title="设置冷静期" style={{ maxWidth: 600 }}>
        <Form form={form} layout="vertical">
          <Form.Item
            label="冷静期秒数"
            name="coolingPeriodSeconds"
            rules={[
              { required: true, message: "必填" },
              {
                type: "number",
                min: MIN_SECONDS,
                max: MAX_SECONDS,
                message: `范围 ${MIN_SECONDS} ~ ${MAX_SECONDS} 秒（60s ~ 7d）`,
              },
            ]}
            extra="范围 60s ~ 7d（60 - 604800 秒）"
          >
            <InputNumber
              style={{ width: "100%" }}
              min={MIN_SECONDS}
              max={MAX_SECONDS}
              precision={0}
              suffix="秒"
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <RequiresPermission code="margin-mode-config:edit">
                <Button type="primary" danger onClick={handleSave}>
                  保存
                </Button>
              </RequiresPermission>
              <Button onClick={fetchConfig}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Modal
        title="确认更新冷静期配置"
        open={confirmOpen}
        onCancel={handleConfirmClose}
        onOk={handleConfirmSubmit}
        okText="确认更新"
        okType="danger"
        okButtonProps={{ disabled: !acknowledged, loading: submitting }}
        cancelText="取消"
        destroyOnHidden
        width={520}
      >
        <Alert
          message="高危操作"
          description={`冷静期将更新为 ${pendingValue ?? "—"} 秒，影响全部用户的保证金模式切换闸门。`}
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
        <Form form={confirmForm} layout="vertical">
          <Form.Item
            name="reason"
            label="原因（必填）"
            rules={[{ required: true, max: 200, message: "请填写原因（≤200）" }]}
          >
            <Input.TextArea rows={3} maxLength={200} showCount placeholder="审计字段，必填" />
          </Form.Item>
        </Form>
        <Checkbox checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)}>
          我已确认该改动会影响全部用户的保证金模式切换
        </Checkbox>
      </Modal>
    </div>
  );
}
