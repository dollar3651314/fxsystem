import { useCallback, useEffect, useState } from "react";
import { Alert, Checkbox, Form, Input, InputNumber, Modal, Select, message } from "antd";
import { riskApi } from "./riskApi";
import { symbolApi } from "../symbol/symbolApi";
import type { RiskConfigItem } from "./types";
import type { SymbolQuoteMappingItem } from "../symbol/types";

interface Props {
  open: boolean;
  mode: "create" | "edit";
  existing: RiskConfigItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

interface FormValues {
  symbol?: string;
  marketCode?: string;
  maxPositionPerUser: number;
  maxPositionTotal: number;
  maintenanceMarginRate?: number;
  maxLeverage: number;
  hedgeThresholdUsd: number;
  reason: string;
}

export function RiskConfigFormModal({ open, mode, existing, onClose, onSuccess }: Props) {
  const [form] = Form.useForm<FormValues>();
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [symbolOptions, setSymbolOptions] = useState<SymbolQuoteMappingItem[]>([]);
  const [symbolOptionsLoading, setSymbolOptionsLoading] = useState(false);

  const loadSymbolOptions = useCallback(async (platformSymbolLike?: string) => {
    setSymbolOptionsLoading(true);
    try {
      const resp = await symbolApi.listQuoteMappings({
        platformSymbolLike: platformSymbolLike?.trim() || undefined,
        page: 0,
        size: 100,
      });
      setSymbolOptions(resp.items);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      void message.error(`加载 platform symbol 失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSymbolOptionsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open && mode === "edit" && existing) {
      form.setFieldsValue({
        symbol: existing.symbol,
        marketCode: existing.marketCode ?? "",
        maxPositionPerUser: Number(existing.maxPositionPerUser),
        maxPositionTotal: Number(existing.maxPositionTotal),
        maintenanceMarginRate: Number(existing.maintenanceMarginRate),
        maxLeverage: existing.maxLeverage,
        hedgeThresholdUsd: Number(existing.hedgeThresholdUsd),
        reason: "",
      });
    } else if (open && mode === "create") {
      form.resetFields();
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void loadSymbolOptions();
    }
  }, [open, mode, existing, form, loadSymbolOptions]);

  const handleClose = () => {
    form.resetFields();
    setAcknowledged(false);
    onClose();
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (mode === "create") {
        await riskApi.createConfig({
          symbol: values.symbol!.trim(),
          marketCode: values.marketCode || undefined,
          maxPositionPerUser: String(values.maxPositionPerUser),
          maxPositionTotal: String(values.maxPositionTotal),
          maintenanceMarginRate: String(values.maintenanceMarginRate ?? 0),
          maxLeverage: values.maxLeverage,
          hedgeThresholdUsd: String(values.hedgeThresholdUsd),
          reason: values.reason,
        });
        void message.success("risk_config 已创建");
      } else if (existing) {
        await riskApi.updateConfig(existing.symbol, {
          maxPositionPerUser: String(values.maxPositionPerUser),
          maxPositionTotal: String(values.maxPositionTotal),
          maxLeverage: values.maxLeverage,
          hedgeThresholdUsd: String(values.hedgeThresholdUsd),
          reason: values.reason,
        });
        void message.success(`${existing.symbol} risk_config 已更新`);
      }
      handleClose();
      onSuccess();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      if (e.code) {
        const codeMap: Record<string, string> = {
          "90804": "symbol 已存在",
          "90805": "maxLeverage 越界 (1-500)",
          "90806": "持仓限额非法（不能为负且 perUser ≤ total）",
          "90807": "对冲阈值不能为负",
        };
        void message.error(codeMap[e.code] ?? `操作失败 ${e.code}：${e.message ?? ""}`);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={mode === "create" ? "新建 risk_config" : `编辑 ${existing?.symbol ?? ""}`}
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
        description={mode === "create" ? "新建后立即影响开仓校验" : "保存后下一次开仓即生效"}
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Form form={form} layout="vertical">
        <Form.Item name="symbol" label="Symbol" rules={[{ required: mode === "create" }]}>
          <Select
            disabled={mode === "edit"}
            showSearch
            filterOption={false}
            loading={symbolOptionsLoading}
            placeholder="从 t_symbol_quote_mapping 选择"
            onSearch={(value) => void loadSymbolOptions(value)}
            onFocus={() => {
              if (symbolOptions.length === 0) void loadSymbolOptions();
            }}
            onSelect={(value) => {
              const selected = symbolOptions.find((item) => item.platformSymbol === value);
              if (selected) {
                form.setFieldsValue({ marketCode: selected.marketCode });
              }
            }}
            options={symbolOptions.map((item) => ({
              value: item.platformSymbol,
              label: `${item.platformSymbol} · ${item.marketCode}`,
            }))}
          />
        </Form.Item>
        <Form.Item name="marketCode" label="Market Code">
          <Input disabled placeholder="由所选 platform symbol 自动带入" />
        </Form.Item>
        <Form.Item name="maxLeverage" label="最大杠杆 (1-500)" rules={[{ required: true }]}>
          <InputNumber min={1} max={500} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item name="maxPositionPerUser" label="用户最大持仓" rules={[{ required: true }]}>
          <InputNumber min={0} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item name="maxPositionTotal" label="平台最大持仓" rules={[{ required: true }]}>
          <InputNumber min={0} style={{ width: "100%" }} />
        </Form.Item>
        {mode === "create" && (
          <Form.Item name="maintenanceMarginRate" label="维持保证金率（创建后不可改）" rules={[{ required: true }]}>
            <InputNumber min={0} max={1} step={0.001} style={{ width: "100%" }} />
          </Form.Item>
        )}
        {mode === "edit" && (
          <Form.Item label="维持保证金率（只读）">
            <Input value={existing?.maintenanceMarginRate} disabled />
          </Form.Item>
        )}
        <Form.Item name="hedgeThresholdUsd" label="对冲阈值（USD）" rules={[{ required: true }]}>
          <InputNumber min={0} style={{ width: "100%" }} />
        </Form.Item>
        <Form.Item name="reason" label="原因（必填）" rules={[{ required: true, max: 200 }]}>
          <Input.TextArea rows={3} maxLength={200} showCount />
        </Form.Item>
      </Form>
      <Checkbox checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)}>
        我已确认该改动会立即影响开仓校验
      </Checkbox>
    </Modal>
  );
}
