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
import { riskThresholdApi } from "./riskThresholdApi";
import { formatPercent } from "../../lib/precision";

const { Title } = Typography;

const STOP_OUT_MIN = 0.05;
const STOP_OUT_MAX = 0.95;
const MARGIN_CALL_MIN = 0.5;
const MARGIN_CALL_MAX = 2.0;

// 阈值（0~N 的保证金率）展示为百分比提示，如 0.3 → 「30%」。
function toPercent(level: string): string {
  const n = Number(level);
  if (!Number.isFinite(n)) return "—";
  return formatPercent(n * 100, 0);
}

// STAGE-14D3b Task6：StopOut/MarginCall 阈值配置页。
//
// 单 Form 编辑 stopOutLevel（0.05-0.95）+ marginCallLevel（0.50-2.00）；对接 console-service
//   D3b Task2 GET/PUT /admin/trading/risk-thresholds。
// · 读：进入页面拉当前值填表 + Descriptions 展示当前生效值（含百分比提示）。
// · 写：高危——保存按钮按 risk-threshold:edit 权限渲染（无权限隐藏，非 disabled，
//   对齐 RequiresPermission / DESIGN §11）；点保存弹二次确认 Modal（reason 必填 +
//   acknowledge 勾选后方可提交），确认后 PUT 成功 message + refetch。
// · 范围前端即时校验仅提示；越界最终由 trading-core 裁决（console 翻 90952）。
// · 阈值用 string 承载保精度：表单用 number 编辑，提交时 String() 转回。
interface FormValues {
  stopOutLevel: number;
  marginCallLevel: number;
}

export function RiskThresholdConfigPage() {
  const [form] = Form.useForm<FormValues>();
  const [confirmForm] = Form.useForm<{ reason: string }>();
  const [current, setCurrent] = useState<{ stopOutLevel: string; marginCallLevel: string } | null>(
    null,
  );
  const [loading, setLoading] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [pending, setPending] = useState<{ stopOutLevel: string; marginCallLevel: string } | null>(
    null,
  );

  const fetchConfig = () => {
    setLoading(true);
    riskThresholdApi
      .get()
      .then((cfg) => {
        // GET 可能返回 number，统一 String() 兼容并保精度。
        const stopOutLevel = String(cfg.stopOutLevel);
        const marginCallLevel = String(cfg.marginCallLevel);
        setCurrent({ stopOutLevel, marginCallLevel });
        form.setFieldsValue({
          stopOutLevel: Number(stopOutLevel),
          marginCallLevel: Number(marginCallLevel),
        });
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
    setPending({
      stopOutLevel: String(values.stopOutLevel),
      marginCallLevel: String(values.marginCallLevel),
    });
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
      if (pending == null) return;
      setSubmitting(true);
      await riskThresholdApi.update(pending.stopOutLevel, pending.marginCallLevel, reason);
      void message.success("风控阈值已更新");
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
      <Title level={3}>强平 / 追保阈值配置</Title>
      <Alert
        message="StopOut / MarginCall 保证金率阈值"
        description={
          <span>
            强平阈值（stopOut）触发逐仓/全仓强制平仓；追保阈值（marginCall）触发追加保证金通知。
            范围 stopOut 0.05~0.95、marginCall 0.50~2.00；越界由 trading-core 拒绝。修改后无需重启服务。
          </span>
        }
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Card title="当前配置" style={{ maxWidth: 600, marginBottom: 16 }} loading={loading}>
        <Descriptions column={1}>
          <Descriptions.Item label="强平阈值 stopOut">
            {current == null ? "—" : `${current.stopOutLevel}（${toPercent(current.stopOutLevel)}）`}
          </Descriptions.Item>
          <Descriptions.Item label="追保阈值 marginCall">
            {current == null
              ? "—"
              : `${current.marginCallLevel}（${toPercent(current.marginCallLevel)}）`}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title="设置阈值" style={{ maxWidth: 600 }}>
        <Form form={form} layout="vertical">
          <Form.Item
            label="强平阈值 stopOutLevel"
            name="stopOutLevel"
            rules={[
              { required: true, message: "必填" },
              {
                type: "number",
                min: STOP_OUT_MIN,
                max: STOP_OUT_MAX,
                message: `范围 ${STOP_OUT_MIN} ~ ${STOP_OUT_MAX}`,
              },
            ]}
            extra="保证金率低于该值触发强平，范围 0.05 ~ 0.95（如 0.30 = 30%）"
          >
            <InputNumber
              style={{ width: "100%" }}
              min={STOP_OUT_MIN}
              max={STOP_OUT_MAX}
              step={0.01}
              precision={2}
            />
          </Form.Item>
          <Form.Item
            label="追保阈值 marginCallLevel"
            name="marginCallLevel"
            rules={[
              { required: true, message: "必填" },
              {
                type: "number",
                min: MARGIN_CALL_MIN,
                max: MARGIN_CALL_MAX,
                message: `范围 ${MARGIN_CALL_MIN} ~ ${MARGIN_CALL_MAX}`,
              },
            ]}
            extra="保证金率低于该值触发追保通知，范围 0.50 ~ 2.00（如 1.00 = 100%）"
          >
            <InputNumber
              style={{ width: "100%" }}
              min={MARGIN_CALL_MIN}
              max={MARGIN_CALL_MAX}
              step={0.01}
              precision={2}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <RequiresPermission code="risk-threshold:edit">
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
        title="确认更新风控阈值"
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
          description={`阈值将更新为 stopOut=${pending?.stopOutLevel ?? "—"}、marginCall=${
            pending?.marginCallLevel ?? "—"
          }，影响全部用户的强平 / 追保触发。`}
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
          我已确认该改动会影响全部用户的强平 / 追保触发
        </Checkbox>
      </Modal>
    </div>
  );
}
