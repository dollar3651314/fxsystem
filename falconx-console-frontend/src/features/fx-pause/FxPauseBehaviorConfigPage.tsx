import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  Modal,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import { RequiresPermission } from "../../components/RequiresPermission";
import { fxPauseApi } from "./fxPauseApi";
import { FX_CATEGORY_LABELS } from "./types";
import type { FxPauseBehavior } from "./types";

const { Title } = Typography;

// STAGE-14D3b Task7：FX_PAUSED 8 类目行为配置页。
//
// 固定 8 行 Table（无分页），每行展示某标的类目在 FX 报价暂停期间的开仓/平仓/强平闸门；
// 对接 console-service D3b Task3 GET/PUT /admin/trading/fx-pause-behavior(/{category})。
// · 读：进入页面拉 8 行，Table 各列用只读 Tag 展示三开关当前值。
// · 写：高危——「编辑」按钮按 fx:pause-behavior:edit 权限渲染（无权限隐藏，非 disabled，
//   对齐 RequiresPermission / DESIGN §11）；点编辑弹 Modal（3 个 Switch + reason 必填 +
//   acknowledge 勾选后方可提交，参考 Task5），确认后 PUT /{category} 成功 message + refetch。
// · categoryName 优先用后端返回值；缺失/纯数字时回退 FX_CATEGORY_LABELS 展示映射。

/** 后端 categoryName 缺失或为纯数字时回退展示映射。 */
function categoryLabel(row: FxPauseBehavior): string {
  const name = row.categoryName;
  if (name && name.trim() !== "" && !/^\d+$/.test(name.trim())) return name;
  return FX_CATEGORY_LABELS[row.category] ?? String(row.category);
}

function boolTag(value: boolean): ReactNode {
  return value ? <Tag color="green">允许</Tag> : <Tag color="red">禁止</Tag>;
}

export function FxPauseBehaviorConfigPage() {
  const [rows, setRows] = useState<FxPauseBehavior[]>([]);
  const [loading, setLoading] = useState(false);
  const [confirmForm] = Form.useForm<{ reason: string }>();
  const [editing, setEditing] = useState<FxPauseBehavior | null>(null);
  const [allowOpen, setAllowOpen] = useState(false);
  const [allowClose, setAllowClose] = useState(false);
  const [allowLiquidation, setAllowLiquidation] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const fetchList = () => {
    setLoading(true);
    fxPauseApi
      .list()
      .then((data) => setRows(data))
      .catch((err) => {
        const e = err as { code?: string; message?: string };
        void message.error(`加载失败 ${e.code ?? ""}：${e.message ?? ""}`);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchList();
  }, []);

  const openEdit = (row: FxPauseBehavior) => {
    setEditing(row);
    setAllowOpen(row.allowOpen);
    setAllowClose(row.allowClose);
    setAllowLiquidation(row.allowLiquidation);
    setAcknowledged(false);
    confirmForm.resetFields();
  };

  const closeEdit = () => {
    setEditing(null);
    setAcknowledged(false);
    confirmForm.resetFields();
  };

  const handleSubmit = async () => {
    try {
      const { reason } = await confirmForm.validateFields();
      if (!editing) return;
      setSubmitting(true);
      await fxPauseApi.update(editing.category, { allowOpen, allowClose, allowLiquidation, reason });
      void message.success("类目行为已更新");
      closeEdit();
      fetchList();
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

  const columns: TableProps<FxPauseBehavior>["columns"] = [
    {
      title: "类目",
      key: "category",
      width: 200,
      render: (_, row) => `${row.category} · ${categoryLabel(row)}`,
    },
    {
      title: "允许开仓",
      dataIndex: "allowOpen",
      key: "allowOpen",
      width: 120,
      align: "center",
      render: (v: boolean) => boolTag(v),
    },
    {
      title: "允许平仓",
      dataIndex: "allowClose",
      key: "allowClose",
      width: 120,
      align: "center",
      render: (v: boolean) => boolTag(v),
    },
    {
      title: "允许强平",
      dataIndex: "allowLiquidation",
      key: "allowLiquidation",
      width: 120,
      align: "center",
      render: (v: boolean) => boolTag(v),
    },
    {
      title: "操作",
      key: "actions",
      width: 120,
      render: (_, row) => (
        <RequiresPermission code="fx:pause-behavior:edit">
          <Button size="small" onClick={() => openEdit(row)}>
            编辑
          </Button>
        </RequiresPermission>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>FX 暂停类目行为配置</Title>
      <Alert
        message="FX 报价暂停期间各标的类目行为"
        description={
          <span>
            当某标的类目 FX 报价暂停（FX_PAUSED）时，按本表闸门控制该类目的开仓 /
            平仓 / 强平是否放行。建议保持平仓开启（手动平仓不限制），避免用户被锁仓。修改即时生效，无需重启服务。
          </span>
        }
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Card>
        <Table
          rowKey="category"
          columns={columns}
          dataSource={rows}
          loading={loading}
          pagination={false}
          scroll={{ x: "max-content" }}
        />
      </Card>

      <Modal
        title={
          editing
            ? `编辑类目行为 — ${editing.category} · ${categoryLabel(editing)}`
            : "编辑类目行为"
        }
        open={editing != null}
        onCancel={closeEdit}
        onOk={handleSubmit}
        okText="确认更新"
        okType="danger"
        okButtonProps={{ disabled: !acknowledged, loading: submitting }}
        cancelText="取消"
        destroyOnHidden
        width={520}
      >
        <Alert
          message="高危操作"
          description="改动会影响该类目在 FX 报价暂停期间全部用户的开仓/平仓/强平闸门。"
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
        <Form layout="horizontal" labelCol={{ span: 8 }} wrapperCol={{ span: 16 }}>
          <Form.Item label="允许开仓">
            <Switch checked={allowOpen} onChange={setAllowOpen} />
          </Form.Item>
          <Form.Item label="允许平仓" extra="手动平仓不限制，建议保持开启">
            <Switch checked={allowClose} onChange={setAllowClose} />
          </Form.Item>
          <Form.Item label="允许强平">
            <Switch checked={allowLiquidation} onChange={setAllowLiquidation} />
          </Form.Item>
        </Form>
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
          我已确认该改动会影响该类目在 FX 报价暂停期间全部用户的交易闸门
        </Checkbox>
      </Modal>
    </div>
  );
}
