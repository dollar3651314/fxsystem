import { useEffect, useState } from "react";
import { Alert, Checkbox, Form, Input, InputNumber, Modal, message } from "antd";
import { tierApi } from "./tierApi";
import type { TierItem } from "./types";

interface Props {
  open: boolean;
  mode: "create" | "edit";
  existing: TierItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

interface FormValues {
  symbol?: string;
  groupCode?: string;
  tierNo: number;
  notionalLower: number;
  /** 空 = 无上限。 */
  notionalUpper?: number | null;
  maxLeverage: number;
  mmRate: number;
  reason: string;
}

// STAGE-14C2 Task 9 R10：tier 新建/编辑 Modal（高危）。
//
// 前端即时校验（仅提示，最终裁决在 trading-core 90931/90932）：
//   · maxLeverage × mmRate ≤ 1.0（与 DB CHECK / V30 同口径，防止维持保证金率与杠杆互斥）
//   · notionalLower < notionalUpper（填了上限时；空上限=无上限不校验）
// 高危：reason 必填 + acknowledge 二次确认勾选后方可提交（参考 RiskConfigFormModal）。
// symbol/groupCode 为落档键，编辑态只读（对齐 Task 8 AdminTierUpdateRequest 不含 symbol/groupCode）。
export function TierFormModal({ open, mode, existing, onClose, onSuccess }: Props) {
  const [form] = Form.useForm<FormValues>();
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open && mode === "edit" && existing) {
      form.setFieldsValue({
        symbol: existing.symbol,
        groupCode: existing.groupCode,
        tierNo: existing.tierNo,
        notionalLower: Number(existing.notionalLower),
        notionalUpper: existing.notionalUpper == null ? null : Number(existing.notionalUpper),
        maxLeverage: existing.maxLeverage,
        mmRate: Number(existing.mmRate),
        reason: "",
      });
    } else if (open && mode === "create") {
      form.resetFields();
      form.setFieldsValue({ groupCode: "default" });
    }
  }, [open, mode, existing, form]);

  const handleClose = () => {
    form.resetFields();
    setAcknowledged(false);
    onClose();
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const notionalUpper =
        values.notionalUpper == null ? null : String(values.notionalUpper);
      if (mode === "create") {
        await tierApi.createTier({
          symbol: values.symbol!.trim(),
          groupCode: (values.groupCode ?? "default").trim() || "default",
          tierNo: values.tierNo,
          notionalLower: String(values.notionalLower),
          notionalUpper,
          maxLeverage: values.maxLeverage,
          mmRate: String(values.mmRate),
          reason: values.reason,
        });
        void message.success("档位已创建");
      } else if (existing) {
        await tierApi.updateTier(existing.id, {
          tierNo: values.tierNo,
          notionalLower: String(values.notionalLower),
          notionalUpper,
          maxLeverage: values.maxLeverage,
          mmRate: String(values.mmRate),
          reason: values.reason,
        });
        void message.success(`${existing.symbol} #${values.tierNo} 档位已更新`);
      }
      handleClose();
      onSuccess();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      // 后端已翻译 90930-90932 为可读消息，直接展示 ApiError.message。
      // 表单本地校验失败（validateFields reject）无 code，不弹错误。
      if (e.code) {
        void message.error(`操作失败 ${e.code}：${e.message ?? ""}`);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={mode === "create" ? "新建杠杆档位" : `编辑档位 ${existing?.symbol ?? ""} #${existing?.tierNo ?? ""}`}
      open={open}
      onCancel={handleClose}
      onOk={handleSubmit}
      okText={mode === "create" ? "创建" : "保存"}
      okType="danger"
      okButtonProps={{ disabled: !acknowledged, loading: submitting }}
      cancelText="取消"
      destroyOnHidden
      width={560}
    >
      <Alert
        message="高危操作"
        description="改动杠杆/维持保证金档位会影响开仓杠杆与强平阈值，经 30s 缓存惰性失效后生效。"
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Form form={form} layout="vertical">
        <Form.Item name="symbol" label="Symbol" rules={[{ required: mode === "create", message: "请填写 Symbol" }]}>
          <Input disabled={mode === "edit"} placeholder="如 BTCUSDT" />
        </Form.Item>
        <Form.Item name="groupCode" label="客户组（groupCode）" rules={[{ required: mode === "create", message: "请填写客户组" }]}>
          <Input disabled={mode === "edit"} placeholder="默认 default" />
        </Form.Item>
        <Form.Item name="tierNo" label="档位序号（≥1）" rules={[{ required: true, message: "请填写档位序号" }]}>
          <InputNumber min={1} precision={0} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item
          name="notionalLower"
          label="名义价值下界（含，≥0）"
          rules={[{ required: true, message: "请填写下界" }]}
        >
          <InputNumber min={0} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item
          name="notionalUpper"
          label="名义价值上界（不含；留空=无上限）"
          dependencies={["notionalLower"]}
          rules={[
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (value == null || value === "") return Promise.resolve();
                const lower = getFieldValue("notionalLower");
                if (lower != null && Number(value) <= Number(lower)) {
                  return Promise.reject(new Error("上界必须大于下界"));
                }
                return Promise.resolve();
              },
            }),
          ]}
        >
          <InputNumber min={0} style={{ width: "100%" }} placeholder="留空表示无上限" />
        </Form.Item>
        <Form.Item name="maxLeverage" label="最大杠杆（≥1）" rules={[{ required: true, message: "请填写最大杠杆" }]}>
          <InputNumber min={1} precision={0} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item
          name="mmRate"
          label="维持保证金率（mmRate）"
          dependencies={["maxLeverage"]}
          rules={[
            { required: true, message: "请填写维持保证金率" },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (value == null || value === "") return Promise.resolve();
                const lev = getFieldValue("maxLeverage");
                if (lev != null && Number(lev) * Number(value) > 1.0) {
                  return Promise.reject(
                    new Error(`maxLeverage × mmRate 必须 ≤ 1.0（当前 ${(Number(lev) * Number(value)).toFixed(4)}）`),
                  );
                }
                return Promise.resolve();
              },
            }),
          ]}
        >
          <InputNumber min={0} max={1} step={0.001} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item name="reason" label="原因（必填）" rules={[{ required: true, max: 200, message: "请填写原因（≤200）" }]}>
          <Input.TextArea rows={3} maxLength={200} showCount />
        </Form.Item>
      </Form>
      <Checkbox checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)}>
        我已确认该改动会影响开仓杠杆与维持保证金
      </Checkbox>
    </Modal>
  );
}
