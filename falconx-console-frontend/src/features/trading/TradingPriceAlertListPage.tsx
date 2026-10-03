import { useEffect, useState } from "react";
import { Button, Card, Form, Input, InputNumber, Select, Space, Table, Tag, Typography, message } from "antd";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import type { TableProps } from "antd";
import { tradingApi } from "./tradingApi";
import type { AdminPriceAlertItem, AdminPriceAlertListQuery } from "./types";
import { formatPrice } from "../../lib/precision";
import { UserCell } from "../../components/UserCell";

const { Title } = Typography;

const DIRECTION_COLOR: Record<string, string> = { ABOVE: "green", BELOW: "red" };

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: "processing",
  EXHAUSTED: "warning",
  CANCELLED: "default",
  ADMIN_DELETED: "error",
};

export function TradingPriceAlertListPage() {
  const [items, setItems] = useState<AdminPriceAlertItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminPriceAlertListQuery>({ page: 1, size: 20 });
  const [deleteTarget, setDeleteTarget] = useState<AdminPriceAlertItem | null>(null);

  const load = (q: AdminPriceAlertListQuery) => {
    setLoading(true);
    tradingApi.listPriceAlerts(q)
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

  const columns: TableProps<AdminPriceAlertItem>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id", width: 180, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "用户", dataIndex: "userId", key: "userId", width: 200,
      render: (_, r) => <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} /> },
    { title: "Symbol", dataIndex: "symbol", key: "symbol", width: 120 },
    { title: "方向", dataIndex: "direction", key: "direction", width: 100,
      render: (v: string) => <Tag color={DIRECTION_COLOR[v]}>{v === "ABOVE" ? "向上" : "向下"}</Tag> },
    { title: "触发价", dataIndex: "targetPrice", key: "targetPrice", width: 120, align: "right", render: (v, r) => formatPrice(v, r.pricePrecision) },
    { title: "基准价", dataIndex: "basePrice", key: "basePrice", width: 120, align: "right", render: (v, r) => v == null ? "—" : formatPrice(v, r.pricePrecision) },
    { title: "触发次数", dataIndex: "triggerCount", key: "triggerCount", width: 110, align: "right",
      render: (v: number, r) => `${v} / 3 (剩 ${r.remainingTriggers})` },
    { title: "最近触发", dataIndex: "lastTriggeredAt", key: "lastTriggeredAt", width: 180, render: (v) => v ?? "—" },
    { title: "状态", dataIndex: "status", key: "status", width: 130,
      render: (v: string) => <Tag color={STATUS_COLOR[v]}>{v}</Tag> },
    { title: "备注", dataIndex: "note", key: "note", ellipsis: true, render: (v) => v ?? "—" },
    { title: "撤销/删除原因", dataIndex: "cancelSource", key: "cancelSource", width: 200, ellipsis: true, render: (v) => v ?? "—" },
    { title: "创建时间", dataIndex: "createdAt", key: "createdAt", width: 180 },
    { title: "操作", key: "actions", width: 110, fixed: "right",
      render: (_, record) =>
        record.status === "ACTIVE" ? (
          <Button danger size="small" onClick={() => setDeleteTarget(record)}>强制删除</Button>
        ) : <span style={{ color: "var(--fx-console-text-muted)" }}>—</span>,
    },
  ];

  return (
    <div>
      <Title level={3}>价格告警监控</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={(v: AdminPriceAlertListQuery) => setQuery({ ...query, ...v, page: 1 })}>
          <Form.Item name="userId" label="User ID">
            <InputNumber placeholder="可选" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="symbol" label="Symbol">
            <Input placeholder="如 BTCUSDT" allowClear />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear style={{ width: 160 }} placeholder="全部"
              options={[
                { value: 1, label: "ACTIVE" },
                { value: 2, label: "EXHAUSTED" },
                { value: 3, label: "CANCELLED" },
                { value: 4, label: "ADMIN_DELETED" },
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
      <ForceDeleteModal target={deleteTarget} onClose={() => setDeleteTarget(null)} onSuccess={() => load(query)} />
    </div>
  );
}

interface DeleteProps {
  target: AdminPriceAlertItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 强制删除告警走 HighRiskConfirmModal：reason ≥10 字符 + admin 用户名挑战。
 * 与 admin 余额调整 / 出金紧急取消同款风控档位（影响用户体验，需可追溯）。
 */
function ForceDeleteModal({ target, onClose, onSuccess }: DeleteProps) {
  const adminUser = useAdminAuthStore((s) => s.user);
  if (!target) return null;
  return (
    <HighRiskConfirmModal<unknown>
      open={true}
      title={`强制删除告警 ${target.id}`}
      okText="确认删除"
      description={`你即将强制删除用户 ${target.userId} 的价格告警。删除后用户无法再收到此告警通知，操作不可逆。`}
      details={[
        ["告警 ID", target.id],
        ["用户", target.userId],
        ["品种", target.symbol],
        ["方向 / 目标价", `${target.direction} ${formatPrice(target.targetPrice, target.pricePrecision)}`],
        ["已触发 / 剩余", `${target.triggerCount}/3，剩 ${target.remainingTriggers}`],
      ]}
      usernameChallenge={
        adminUser
          ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
          : undefined
      }
      onSubmit={(reason) => tradingApi.deletePriceAlert(target.id, reason)}
      onSuccess={() => {
        message.success("已删除");
        onSuccess();
        onClose();
      }}
      onCancel={onClose}
    />
  );
}
