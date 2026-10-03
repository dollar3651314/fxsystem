import { useEffect, useState } from "react";
import { Button, Card, Form, Input, InputNumber, Select, Space, Switch, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { tradingApi } from "./tradingApi";
import type { AdminPendingOrderItem, AdminPendingOrderListQuery } from "./types";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { AdminOrderDetailDrawer, type DetailGroup } from "./AdminOrderDetailDrawer";
import { formatPrice } from "../../lib/precision";

const { Title } = Typography;

const ORDER_TYPE_COLOR: Record<string, string> = {
  LIMIT: "blue",
  STOP: "purple",
  STOP_LIMIT: "orange",
  SL_TP: "default",
};

const STATUS_COLOR: Record<string, string> = {
  PENDING: "processing",
  TRIGGERED: "success",
  CANCELLED: "default",
  EXPIRED: "default",
  REJECTED: "error",
};

export function TradingPendingOrderListPage() {
  const [items, setItems] = useState<AdminPendingOrderItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminPendingOrderListQuery>({ page: 1, size: 20, includeSlTp: false });
  const [cancelTarget, setCancelTarget] = useState<AdminPendingOrderItem | null>(null);
  const [detailTarget, setDetailTarget] = useState<AdminPendingOrderItem | null>(null);

  const load = (q: AdminPendingOrderListQuery) => {
    setLoading(true);
    tradingApi
      .listPendingOrders(q)
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

  const handleSearch = (values: AdminPendingOrderListQuery) => {
    setQuery({ ...query, ...values, page: 1 });
  };

  const columns: TableProps<AdminPendingOrderItem>["columns"] = [
    { title: "订单号", dataIndex: "orderNo", key: "orderNo", width: 160, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "用户", dataIndex: "id", key: "userId", width: 180,
      render: (_, record) => <span style={{ fontFamily: "monospace" }}>{record.parentPositionId ? `(SL_TP@${record.parentPositionId})` : "—"}</span> },
    { title: "Symbol", dataIndex: "symbol", key: "symbol", width: 120 },
    { title: "类型", dataIndex: "orderType", key: "orderType", width: 110,
      render: (v: string) => <Tag color={ORDER_TYPE_COLOR[v]}>{v}</Tag> },
    { title: "方向", dataIndex: "side", key: "side", width: 80,
      render: (v: string) => <Tag color={v === "BUY" ? "green" : "red"}>{v === "BUY" ? "多" : "空"}</Tag> },
    { title: "数量", dataIndex: "quantity", key: "quantity", width: 120, align: "right" },
    { title: "触发价", dataIndex: "triggerPrice", key: "triggerPrice", width: 120, align: "right", render: (v, r) => formatPrice(v, r.pricePrecision) },
    { title: "限价", dataIndex: "limitPrice", key: "limitPrice", width: 120, align: "right", render: (v, r) => v == null ? "—" : formatPrice(v, r.pricePrecision) },
    { title: "杠杆", dataIndex: "leverage", key: "leverage", width: 80, align: "right" },
    { title: "冻结保证金", dataIndex: "frozenMargin", key: "frozenMargin", width: 120, align: "right" },
    {
      title: "冻结手续费",
      dataIndex: "frozenFee",
      key: "frozenFee",
      width: 120,
      align: "right",
      render: (v: string) => (
        <span style={{ color: "var(--fx-console-warning, #ffb84d)", fontWeight: 600, fontVariantNumeric: "tabular-nums" }}>
          {v}
        </span>
      ),
    },
    { title: "状态", dataIndex: "status", key: "status", width: 110,
      render: (v: string) => <Tag color={STATUS_COLOR[v]}>{v}</Tag> },
    { title: "创建时间", dataIndex: "createdAt", key: "createdAt", width: 180 },
    { title: "操作", key: "actions", width: 180, fixed: "right",
      render: (_, record) => (
        <Space size={4}>
          <Button size="small" onClick={() => setDetailTarget(record)}>详情</Button>
          {record.status === "PENDING" && (
            <Button danger size="small" onClick={() => setCancelTarget(record)}>强制撤单</Button>
          )}
        </Space>
      ),
    },
  ];

  const pendingDetailGroups = (o: AdminPendingOrderItem): DetailGroup[] => [
    {
      num: "01",
      title: "挂单标识",
      fields: [
        { label: "订单号", value: o.orderNo, span: 2 },
        { label: "ID", value: o.id, span: 2 },
        { label: "客户端 ID", value: o.clientOrderId, span: 2 },
        { label: "父持仓 ID", value: o.parentPositionId, span: 2 },
      ],
    },
    {
      num: "02",
      title: "交易要素",
      fields: [
        { label: "品种", value: o.symbol },
        {
          label: "方向",
          value: <Tag color={o.side === "BUY" ? "green" : "red"}>{o.side === "BUY" ? "多 (BUY)" : "空 (SELL)"}</Tag>,
        },
        { label: "类型", value: <Tag color={ORDER_TYPE_COLOR[o.orderType]}>{o.orderType}</Tag> },
        { label: "数量", value: o.quantity },
        { label: "触发类型", value: o.triggerKind === "TAKE_PROFIT" ? "止盈" : o.triggerKind === "STOP_LOSS" ? "止损" : null },
        { label: "保证金模式", value: o.marginMode },
      ],
    },
    {
      num: "03",
      title: "触发与价格",
      fields: [
        { label: "触发价", value: formatPrice(o.triggerPrice, o.pricePrecision) },
        { label: "限价", value: formatPrice(o.limitPrice, o.pricePrecision) },
        { label: "杠杆", value: `${o.leverage}x` },
      ],
    },
    {
      num: "04",
      title: "冻结资金",
      fields: [
        { label: "冻结保证金", value: o.frozenMargin },
        {
          label: "冻结手续费",
          value: <span style={{ color: "var(--fx-console-warning, #ffb84d)", fontWeight: 700 }}>{o.frozenFee}</span>,
        },
      ],
    },
    {
      num: "05",
      title: "状态与时间",
      fields: [
        { label: "状态", value: <Tag color={STATUS_COLOR[o.status]}>{o.status}</Tag> },
        { label: "创建时间", value: o.createdAt },
        { label: "更新时间", value: o.updatedAt },
        o.triggeredAt ? { label: "触发时间", value: o.triggeredAt } : null,
        o.triggeredOrderId ? { label: "触发订单 ID", value: o.triggeredOrderId, span: 2 } : null,
        o.cancelledAt ? { label: "撤销时间", value: o.cancelledAt } : null,
        o.cancelReason ? { label: "撤销原因", value: <span style={{ color: "var(--fx-console-error)" }}>{o.cancelReason}</span>, span: 2 } : null,
      ].filter(Boolean) as DetailGroup["fields"],
    },
  ];

  return (
    <div>
      <Title level={3}>挂单监控</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="userId" label="User ID">
            <InputNumber placeholder="可选" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="symbol" label="Symbol">
            <Input placeholder="如 BTCUSDT" allowClear />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear style={{ width: 150 }} placeholder="全部"
              options={[
                { value: 1, label: "PENDING" },
                { value: 2, label: "TRIGGERED" },
                { value: 3, label: "CANCELLED" },
                { value: 4, label: "EXPIRED" },
                { value: 5, label: "REJECTED" },
              ]} />
          </Form.Item>
          <Form.Item name="includeSlTp" label="含 SL/TP" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => setQuery({ page: 1, size: 20, includeSlTp: false })}>重置</Button>
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
      <ForceCancelModal
        target={cancelTarget}
        onClose={() => setCancelTarget(null)}
        onSuccess={() => load(query)}
      />
      <AdminOrderDetailDrawer
        open={detailTarget != null}
        title={detailTarget ? `挂单 ${detailTarget.orderNo}` : ""}
        subtitle={detailTarget ? `${detailTarget.symbol} · ${detailTarget.side === "BUY" ? "多" : "空"} · ${detailTarget.orderType}` : undefined}
        tag={detailTarget ? { text: detailTarget.status, color: STATUS_COLOR[detailTarget.status] } : undefined}
        groups={detailTarget ? pendingDetailGroups(detailTarget) : []}
        onClose={() => setDetailTarget(null)}
      />
    </div>
  );
}

interface CancelProps {
  target: AdminPendingOrderItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 强制撤单走 HighRiskConfirmModal：reason ≥10 字符 + admin 用户名挑战。
 * 与 admin 余额调整 / 出金紧急取消同款风控档位，防止 social engineering 误操作他人挂单。
 */
function ForceCancelModal({ target, onClose, onSuccess }: CancelProps) {
  const adminUser = useAdminAuthStore((s) => s.user);
  if (!target) return null;
  return (
    <HighRiskConfirmModal<unknown>
      open={true}
      title={`强制撤单 ${target.orderNo}`}
      okText="确认撤单"
      description={`你即将强制撤销挂单 ${target.orderNo}。撤单后冻结资金立即解冻，操作不可逆。`}
      details={[
        ["订单号", target.orderNo],
        ["品种", target.symbol],
        ["类型", `${target.orderType} ${target.side}`],
        ["数量", String(target.quantity)],
        ["触发价 / 限价", `${formatPrice(target.triggerPrice, target.pricePrecision)}${target.limitPrice ? ` / ${formatPrice(target.limitPrice, target.pricePrecision)}` : ""}`],
      ]}
      usernameChallenge={
        adminUser
          ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
          : undefined
      }
      onSubmit={(reason) => tradingApi.cancelPendingOrder(target.id, reason)}
      onSuccess={() => {
        message.success("已撤单");
        onSuccess();
        onClose();
      }}
      onCancel={onClose}
    />
  );
}
