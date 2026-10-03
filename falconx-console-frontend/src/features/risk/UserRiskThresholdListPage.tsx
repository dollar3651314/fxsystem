import { useEffect, useState } from "react";
import { Button, Card, Form, Input, InputNumber, Modal, Space, Switch, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { riskApi } from "./riskApi";
import type { UserRiskThresholdItem, UserRiskThresholdUpsertRequest } from "./types";
import { UserCell } from "../../components/UserCell";

const { Title } = Typography;

export function UserRiskThresholdListPage() {
  const [items, setItems] = useState<UserRiskThresholdItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [userIdFilter, setUserIdFilter] = useState<string>("");
  const [editTarget, setEditTarget] = useState<UserRiskThresholdItem | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const load = () => {
    setLoading(true);
    const filterId = userIdFilter.trim() ? Number(userIdFilter.trim()) : undefined;
    riskApi
      .listUserRiskThresholds(filterId, page, size)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
      })
      .catch((err) => message.error(`加载失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size]);

  const handleDelete = (userId: number) => {
    Modal.confirm({
      title: "删除该用户的风控阈值？",
      content: `userId=${userId} 删除后此用户不再受用户级敞口阈值限制`,
      okType: "danger",
      onOk: () => riskApi.deleteUserRiskThreshold(userId).then(() => {
        message.success("已删除");
        load();
      }).catch((err) => message.error(`删除失败 ${err.code ?? ""}：${err.message ?? ""}`)),
    });
  };

  const columns: TableProps<UserRiskThresholdItem>["columns"] = [
    { title: "用户", dataIndex: "userId", key: "userId", width: 200,
      render: (_, r) => <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} /> },
    { title: "普通阈值 (USD)", dataIndex: "netExposureThresholdUsd", key: "netExposureThresholdUsd", width: 160, align: "right",
      render: (v) => v ?? "—" },
    { title: "盈利用户阈值 (USD)", dataIndex: "profitableNetExposureThresholdUsd", key: "profitableNetExposureThresholdUsd", width: 180, align: "right",
      render: (v) => v ?? "—" },
    { title: "盈利用户", dataIndex: "profitableUser", key: "profitableUser", width: 100,
      render: (v: boolean) => <Tag color={v ? "red" : "default"}>{v ? "Y" : "N"}</Tag> },
    { title: "修改人", dataIndex: "updatedBy", key: "updatedBy", width: 120 },
    { title: "修改原因", dataIndex: "updatedReason", key: "updatedReason", ellipsis: true },
    { title: "更新时间", dataIndex: "updatedAt", key: "updatedAt", width: 200 },
    {
      title: "操作", key: "actions", width: 150, fixed: "right",
      render: (_, record) => (
        <Space>
          <Button size="small" onClick={() => setEditTarget(record)}>编辑</Button>
          <Button size="small" danger onClick={() => handleDelete(record.userId)}>删除</Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>用户级风控阈值</Title>
      <Card style={{ marginBottom: 16 }}>
        <Space>
          <Input
            placeholder="按 User ID 过滤"
            value={userIdFilter}
            onChange={(e) => setUserIdFilter(e.target.value)}
            onPressEnter={() => { setPage(1); load(); }}
            allowClear
            style={{ width: 240 }}
          />
          <Button type="primary" onClick={() => { setPage(1); load(); }}>查询</Button>
          <Button onClick={() => { setUserIdFilter(""); setPage(1); load(); }}>重置</Button>
          <Button type="primary" danger onClick={() => setCreateOpen(true)}>新增阈值</Button>
        </Space>
      </Card>
      <Table
        rowKey="userId"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={{
          current: page, pageSize: size, total,
          showSizeChanger: true, pageSizeOptions: [20, 50, 100],
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
        scroll={{ x: "max-content" }}
      />
      <UserRiskThresholdUpsertModal
        open={createOpen || editTarget != null}
        target={editTarget}
        onClose={() => { setCreateOpen(false); setEditTarget(null); }}
        onSuccess={load}
      />
    </div>
  );
}

interface ModalProps {
  open: boolean;
  target: UserRiskThresholdItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

function UserRiskThresholdUpsertModal({ open, target, onClose, onSuccess }: ModalProps) {
  const [form] = Form.useForm<UserRiskThresholdUpsertRequest>();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) {
      if (target) {
        form.setFieldsValue({
          userId: target.userId,
          netExposureThresholdUsd: target.netExposureThresholdUsd ?? "",
          profitableNetExposureThresholdUsd: target.profitableNetExposureThresholdUsd ?? "",
          profitableUser: target.profitableUser,
          reason: "",
        });
      } else {
        form.resetFields();
      }
    }
  }, [open, target, form]);

  const handleOk = () => {
    form.validateFields().then((values) => {
      setSubmitting(true);
      const payload: UserRiskThresholdUpsertRequest = {
        userId: Number(values.userId),
        netExposureThresholdUsd: values.netExposureThresholdUsd ? String(values.netExposureThresholdUsd) : null,
        profitableNetExposureThresholdUsd: values.profitableNetExposureThresholdUsd ? String(values.profitableNetExposureThresholdUsd) : null,
        profitableUser: Boolean(values.profitableUser),
        reason: values.reason,
      };
      riskApi.upsertUserRiskThreshold(payload).then(() => {
        message.success("已保存");
        onSuccess();
        onClose();
      }).catch((err) => message.error(`保存失败 ${err.code ?? ""}：${err.message ?? ""}`))
        .finally(() => setSubmitting(false));
    });
  };

  return (
    <Modal
      open={open}
      title={target ? `编辑用户阈值 userId=${target.userId}` : "新增用户阈值"}
      okText="保存"
      cancelText="取消"
      onOk={handleOk}
      onCancel={onClose}
      confirmLoading={submitting}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item label="User ID" name="userId" rules={[{ required: true, message: "必填" }]}>
          <InputNumber style={{ width: "100%" }} disabled={target != null} />
        </Form.Item>
        <Form.Item label="普通阈值 (USD)" name="netExposureThresholdUsd" extra="留空 = 不限">
          <InputNumber style={{ width: "100%" }} min={0} />
        </Form.Item>
        <Form.Item label="盈利用户阈值 (USD)" name="profitableNetExposureThresholdUsd" extra="留空 = 不限；仅在 profitableUser=true 时生效">
          <InputNumber style={{ width: "100%" }} min={0} />
        </Form.Item>
        <Form.Item label="盈利用户" name="profitableUser" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item label="变更原因" name="reason" rules={[{ required: true, message: "必填" }]}>
          <Input.TextArea rows={2} placeholder="审计字段，必填" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
