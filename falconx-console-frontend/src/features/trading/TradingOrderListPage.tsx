import { useEffect, useState } from "react";
import { Button, Card, Form, Input, InputNumber, Select, Space, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { tradingApi } from "./tradingApi";
import { AdminOrderDetailDrawer, type DetailGroup } from "./AdminOrderDetailDrawer";
import type { OrderListItem, OrderListQuery } from "./types";
import { formatPercent, formatPrice } from "../../lib/precision";
import { UserCell } from "../../components/UserCell";

const { Title } = Typography;

const STATUS_LABEL: Record<number, { text: string; color: string }> = {
  0: { text: "待处理", color: "processing" },
  1: { text: "部分成交", color: "processing" },
  2: { text: "已成交", color: "success" },
  3: { text: "已拒绝", color: "error" },
  4: { text: "已撤销", color: "default" },
};

const SIDE_LABEL: Record<number, { text: string; color: string }> = {
  1: { text: "买", color: "green" },
  2: { text: "卖", color: "red" },
};

const ORDER_TYPE_LABEL: Record<number, string> = {
  1: "市价",
  2: "限价",
};

export function TradingOrderListPage() {
  const [items, setItems] = useState<OrderListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<OrderListQuery>({ page: 1, size: 20 });
  const [detailTarget, setDetailTarget] = useState<OrderListItem | null>(null);

  const load = (q: OrderListQuery) => {
    setLoading(true);
    tradingApi
      .listOrders(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载订单列表失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  const handleSearch = (values: { userId?: number; symbol?: string; status?: number }) => {
    setQuery({ ...query, ...values, page: 1 });
  };

  const columns: TableProps<OrderListItem>["columns"] = [
    { title: "订单号", dataIndex: "orderNo", key: "orderNo", width: 180, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    {
      title: "用户",
      dataIndex: "userId",
      key: "userId",
      width: 200,
      render: (_, r) => (
        <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} />
      ),
    },
    { title: "Symbol", dataIndex: "symbol", key: "symbol", width: 120 },
    {
      title: "方向",
      dataIndex: "side",
      key: "side",
      width: 80,
      render: (v: number) => <Tag color={SIDE_LABEL[v]?.color}>{SIDE_LABEL[v]?.text ?? v}</Tag>,
    },
    { title: "类型", dataIndex: "orderType", key: "orderType", width: 80, render: (v: number) => ORDER_TYPE_LABEL[v] ?? v },
    { title: "数量", dataIndex: "quantity", key: "quantity", width: 120, align: "right" },
    { title: "成交价", dataIndex: "filledPrice", key: "filledPrice", width: 120, align: "right", render: (v, r) => formatPrice(v, r.pricePrecision) },
    { title: "杠杆", dataIndex: "leverage", key: "leverage", width: 80, align: "right" },
    { title: "保证金", dataIndex: "margin", key: "margin", width: 120, align: "right" },
    { title: "手续费", dataIndex: "fee", key: "fee", width: 100, align: "right" },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 100,
      render: (v: number) => <Tag color={STATUS_LABEL[v]?.color}>{STATUS_LABEL[v]?.text ?? v}</Tag>,
    },
    { title: "创建时间", dataIndex: "createdAt", key: "createdAt", width: 180 },
    {
      title: "操作",
      key: "actions",
      width: 90,
      fixed: "right",
      render: (_, record) => (
        <Button size="small" onClick={() => setDetailTarget(record)}>
          详情
        </Button>
      ),
    },
  ];

  const orderDetailGroups = (o: OrderListItem): DetailGroup[] => [
    {
      num: "01",
      title: "订单标识",
      fields: [
        { label: "订单号", value: o.orderNo, span: 2 },
        { label: "订单 ID", value: o.id, span: 2 },
        { label: "客户端 ID", value: o.clientOrderId, span: 2 },
        { label: "用户 ID", value: o.userId, span: 2 },
        { label: "用户姓名", value: o.userFullName ?? "—" },
        { label: "用户邮箱", value: o.userEmail ?? "—" },
        { label: "用户 UID", value: o.userUid ?? "—", span: 2 },
      ],
    },
    {
      num: "02",
      title: "交易要素",
      fields: [
        { label: "品种", value: o.symbol },
        {
          label: "方向",
          value: <Tag color={SIDE_LABEL[o.side]?.color}>{SIDE_LABEL[o.side]?.text ?? o.side}</Tag>,
        },
        { label: "订单类型", value: ORDER_TYPE_LABEL[o.orderType] ?? o.orderType },
        {
          label: "状态",
          value: <Tag color={STATUS_LABEL[o.status]?.color}>{STATUS_LABEL[o.status]?.text ?? o.status}</Tag>,
        },
      ],
    },
    {
      num: "03",
      title: "数量与价格",
      fields: [
        { label: "数量", value: o.quantity },
        { label: "杠杆", value: `${o.leverage}x` },
        { label: "委托价", value: formatPrice(o.requestedPrice, o.pricePrecision) },
        { label: "成交价", value: formatPrice(o.filledPrice, o.pricePrecision) },
      ],
    },
    {
      num: "04",
      title: "资金与费用",
      fields: [
        { label: "保证金", value: o.margin },
        {
          label: "手续费",
          value: <span style={{ color: "var(--fx-console-warning, #ffb84d)", fontWeight: 700 }}>{o.fee}</span>,
        },
        { label: "费率", value: o.openFeeRate ? formatPercent(Number(o.openFeeRate) * 100, 3) : null, span: 2 },
      ],
    },
    {
      num: "05",
      title: "时间线",
      fields: [
        { label: "创建时间", value: o.createdAt },
        { label: "更新时间", value: o.updatedAt },
        o.rejectReason ? { label: "拒绝原因", value: <span style={{ color: "var(--fx-console-error)" }}>{o.rejectReason}</span>, span: 2 } : null,
      ].filter(Boolean) as DetailGroup["fields"],
    },
  ];

  return (
    <div>
      <Title level={3}>订单监控</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="userId" label="用户 ID">
            <InputNumber min={1} placeholder="用户 ID" />
          </Form.Item>
          <Form.Item name="symbol" label="Symbol">
            <Input placeholder="如 BTCUSDT" allowClear />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              placeholder="全部"
              style={{ width: 140 }}
              options={Object.entries(STATUS_LABEL).map(([code, v]) => ({ value: Number(code), label: v.text }))}
            />
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
      <AdminOrderDetailDrawer
        open={detailTarget != null}
        title={detailTarget ? `订单 ${detailTarget.orderNo}` : ""}
        subtitle={detailTarget ? `${detailTarget.symbol} · ${SIDE_LABEL[detailTarget.side]?.text} · ${ORDER_TYPE_LABEL[detailTarget.orderType]}` : undefined}
        tag={detailTarget ? { text: STATUS_LABEL[detailTarget.status]?.text ?? "—", color: STATUS_LABEL[detailTarget.status]?.color } : undefined}
        groups={detailTarget ? orderDetailGroups(detailTarget) : []}
        onClose={() => setDetailTarget(null)}
      />
    </div>
  );
}
