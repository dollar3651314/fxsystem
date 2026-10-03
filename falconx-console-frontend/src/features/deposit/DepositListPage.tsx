import { useEffect, useState } from "react";
import { Button, Card, Form, Input, InputNumber, Select, Space, Switch, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { depositApi } from "./depositApi";
import { DepositDetailDrawer } from "./DepositDetailDrawer";
import type { ChainType, DepositCreditStatus, DepositItem, DepositListQuery, DepositStatus, SnowflakeId } from "./types";
import { UserCell } from "../../components/UserCell";
import { networkOf } from "../../lib/chain";

const CREDIT_BADGE: Record<DepositCreditStatus, { color: string; text: string }> = {
  CREDITED: { color: "success", text: "已入账" },
  REJECTED: { color: "error", text: "已拒收" },
  REVERSED: { color: "warning", text: "已回滚" },
  PENDING: { color: "default", text: "等待入账" },
  UNKNOWN: { color: "default", text: "未知" },
};

const { Title, Text } = Typography;

const STATUS_OPTIONS: DepositStatus[] = ["DETECTED", "CONFIRMING", "CONFIRMED", "REVERSED", "IGNORED"];
const CHAIN_OPTIONS: ChainType[] = ["ETH", "BSC", "TRON", "SOL"];

const STATUS_BADGE: Record<DepositStatus, { color: string; text: string }> = {
  DETECTED: { color: "processing", text: "已检测" },
  CONFIRMING: { color: "processing", text: "确认中" },
  CONFIRMED: { color: "success", text: "已确认" },
  REVERSED: { color: "error", text: "已回滚" },
  IGNORED: { color: "default", text: "已忽略" },
};

export function DepositListPage() {
  const [items, setItems] = useState<DepositItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<DepositListQuery>({ page: 1, size: 20 });
  const [detailId, setDetailId] = useState<SnowflakeId | null>(null);

  const load = (q: DepositListQuery) => {
    setLoading(true);
    depositApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载入金记录失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  const handleSearch = (values: {
    userId?: number;
    chain?: ChainType;
    token?: string;
    status?: DepositStatus[];
    onlyOrphan?: boolean;
  }) => {
    setQuery({ ...query, ...values, page: 1 });
  };

  const columns: TableProps<DepositItem>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id", width: 140, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    {
      title: "用户",
      dataIndex: "userId",
      key: "userId",
      width: 200,
      render: (v, r) => v
        ? <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} />
        : <em style={{ color: "var(--fx-muted)" }}>orphan（未归属）</em>,
    },
    { title: "链", dataIndex: "chain", key: "chain", width: 80, render: (v: ChainType) => <Tag color="blue">{v}</Tag> },
    {
      title: "网络",
      key: "network",
      width: 90,
      render: (_, r) => {
        const net = networkOf(r.chain);
        return net ? <Tag color="geekblue">{net}</Tag> : "—";
      },
    },
    { title: "Token", dataIndex: "token", key: "token", width: 80 },
    { title: "金额", dataIndex: "amount", key: "amount", width: 140, align: "right" },
    {
      title: "确认数",
      key: "confirmations",
      width: 100,
      align: "right",
      render: (_, r) => `${r.confirmations}/${r.requiredConfirms}`,
    },
    {
      title: "链上状态",
      dataIndex: "status",
      key: "status",
      width: 100,
      render: (v: DepositStatus) => <Tag color={STATUS_BADGE[v]?.color}>{STATUS_BADGE[v]?.text ?? v}</Tag>,
    },
    {
      title: "入账状态",
      key: "creditStatus",
      width: 110,
      render: (_, r) => {
        const cs = r.creditStatus ?? "PENDING";
        const badge = CREDIT_BADGE[cs] ?? CREDIT_BADGE.UNKNOWN;
        const tooltip = cs === "REJECTED" && r.rejectionReason ? r.rejectionReason : undefined;
        return <Tag color={badge.color} title={tooltip}>{badge.text}</Tag>;
      },
    },
    {
      title: "txHash",
      dataIndex: "txHash",
      key: "txHash",
      width: 200,
      ellipsis: true,
      render: (v: string) => <Text copyable={{ text: v }} style={{ fontFamily: "monospace" }}>{v.substring(0, 12)}…</Text>,
    },
    { title: "检测时间", dataIndex: "detectedAt", key: "detectedAt", width: 180 },
    { title: "确认时间", dataIndex: "confirmedAt", key: "confirmedAt", width: 180, render: (v) => v ?? "—" },
    {
      title: "操作",
      key: "actions",
      width: 90,
      fixed: "right",
      render: (_, record) => (
        <Button size="small" onClick={() => setDetailId(record.id)}>详情</Button>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>入金记录</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="userId" label="用户 ID">
            <InputNumber min={1} placeholder="用户 ID" />
          </Form.Item>
          <Form.Item name="chain" label="链">
            <Select allowClear style={{ width: 120 }} placeholder="全部"
              options={CHAIN_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item name="token" label="Token">
            <Input placeholder="如 USDT" allowClear />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select mode="multiple" allowClear style={{ width: 280 }} placeholder="全部"
              options={STATUS_OPTIONS.map((v) => ({ value: v, label: STATUS_BADGE[v].text }))} />
          </Form.Item>
          <Form.Item name="onlyOrphan" label="仅脱钩" valuePropName="checked">
            <Switch />
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
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [20, 50, 100],
          onChange: (p, s) => setQuery({ ...query, page: p, size: s }),
        }}
        scroll={{ x: "max-content" }}
      />
      <DepositDetailDrawer
        open={detailId != null}
        depositId={detailId}
        onClose={() => setDetailId(null)}
      />
    </div>
  );
}
