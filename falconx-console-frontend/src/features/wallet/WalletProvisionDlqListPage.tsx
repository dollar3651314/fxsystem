import { useEffect, useState } from "react";
import { Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { walletProvisionApi, type AdminWalletProvisionDlqItem, type AdminWalletProvisionDlqListQuery } from "./walletProvisionApi";

const { Title } = Typography;

const STATUS_COLOR: Record<string, string> = {
  PENDING: "warning",
  RESOLVED: "success",
};

export function WalletProvisionDlqListPage() {
  const [items, setItems] = useState<AdminWalletProvisionDlqItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminWalletProvisionDlqListQuery>({ page: 1, size: 20 });
  const [retryTarget, setRetryTarget] = useState<AdminWalletProvisionDlqItem | null>(null);

  const load = (q: AdminWalletProvisionDlqListQuery) => {
    setLoading(true);
    walletProvisionApi.list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.pageSize);
      })
      .catch((err) => message.error(`加载失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load(query);
  }, [query]);

  const columns: TableProps<AdminWalletProvisionDlqItem>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id", width: 180, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "Event ID", dataIndex: "eventId", key: "eventId", width: 200, ellipsis: true },
    { title: "User ID", dataIndex: "userId", key: "userId", width: 180, render: (v) => <span style={{ fontFamily: "monospace" }}>{v ?? "—"}</span> },
    { title: "UID", dataIndex: "uid", key: "uid", width: 120, render: (v) => v ?? "—" },
    { title: "邮箱", dataIndex: "email", key: "email", ellipsis: true, render: (v) => v ?? "—" },
    { title: "重试次数", dataIndex: "attemptCount", key: "attemptCount", width: 100, align: "right" },
    { title: "状态", dataIndex: "status", key: "status", width: 110,
      render: (v: string) => <Tag color={STATUS_COLOR[v]}>{v}</Tag> },
    { title: "错误码", dataIndex: "lastErrorCode", key: "lastErrorCode", width: 110, render: (v) => v ?? "—" },
    { title: "错误信息", dataIndex: "lastErrorMessage", key: "lastErrorMessage", ellipsis: true, render: (v) => v ?? "—" },
    { title: "最近尝试", dataIndex: "lastAttemptAt", key: "lastAttemptAt", width: 180 },
    { title: "解决于", dataIndex: "resolvedAt", key: "resolvedAt", width: 180, render: (v) => v ?? "—" },
    { title: "操作", key: "actions", width: 100, fixed: "right",
      render: (_, record) =>
        record.status === "PENDING" ? (
          <Button type="primary" size="small" onClick={() => setRetryTarget(record)}>重试</Button>
        ) : <span style={{ color: "var(--fx-console-text-muted)" }}>—</span>,
    },
  ];

  return (
    <div>
      <Title level={3}>地址预分配死信队列</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={(v: AdminWalletProvisionDlqListQuery) => setQuery({ ...query, ...v, page: 1 })}>
          <Form.Item name="userId" label="User ID">
            <InputNumber placeholder="可选" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear style={{ width: 160 }} placeholder="全部"
              options={[
                { value: 0, label: "PENDING" },
                { value: 1, label: "RESOLVED" },
              ]} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => setQuery({ page: 1, size: 20 })}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={{
          current: page, pageSize: size, total,
          showSizeChanger: true, pageSizeOptions: [20, 50, 100],
          onChange: (p, s) => setQuery({ ...query, page: p, size: s }),
        }}
        scroll={{ x: "max-content" }}
      />
      <RetryModal target={retryTarget} onClose={() => setRetryTarget(null)} onSuccess={() => load(query)} />
    </div>
  );
}

interface RetryProps {
  target: AdminWalletProvisionDlqItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

function RetryModal({ target, onClose, onSuccess }: RetryProps) {
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const handleOk = () => {
    if (!target || !reason.trim()) {
      message.warning("必须填写重试原因");
      return;
    }
    setSubmitting(true);
    walletProvisionApi.retry(target.id, reason.trim())
      .then(() => {
        message.success("重试成功，已分配地址");
        setReason("");
        onSuccess();
        onClose();
      })
      .catch((err) => message.error(`重试失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setSubmitting(false));
  };

  return (
    <Modal
      open={target != null}
      title={target ? `重试预分配 #${target.id}` : ""}
      okText="确认重试"
      cancelText="取消"
      onOk={handleOk}
      onCancel={() => { setReason(""); onClose(); }}
      confirmLoading={submitting}
      destroyOnClose
    >
      {target && (
        <div>
          <div style={{ marginBottom: 12 }}>
            User <b>{target.userId}</b>{target.email ? `（${target.email}）` : ""}
            <br />
            最近错误：{target.lastErrorCode ?? ""} {target.lastErrorMessage ?? ""}
            <br />
            已重试 {target.attemptCount} 次
          </div>
          <Input.TextArea
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="重试原因（必填，审计字段，例：xpub 已补齐）"
          />
        </div>
      )}
    </Modal>
  );
}
