import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Badge, Button, Card, Col, Form, Input, InputNumber, Row, Select, Space, Statistic, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { useQuery } from "@tanstack/react-query";
import { tradingApi } from "./tradingApi";
import { ManualLiquidateModal } from "./ManualLiquidateModal";
import { AdminOrderDetailDrawer, type DetailGroup } from "./AdminOrderDetailDrawer";
import {
  useAdminTradingSocket,
  type AdminPositionPnlUpdate,
  type AdminPositionSummaryUpdate,
} from "./useAdminTradingSocket";
import { getAdminAccessToken } from "../../lib/auth/adminTokenStorage";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import type { PositionListItem, PositionListQuery, PositionSummary } from "./types";
import { formatMoney, formatPercent, formatPnl, formatPrice, formatSignedPnl } from "../../lib/precision";
import { UserCell } from "../../components/UserCell";

const { Title, Text } = Typography;

/**
 * WS 推送的 patch：positionId → markPrice / unrealizedPnlInAccount。
 *
 * STAGE-14E2 Task5：admin.position.update 硬切双币后，列表「未实现盈亏」列展示
 * 账户币 AC 口径（unrealizedPnlInAccount，主），与 REST 列表 unrealizedPnl（同为 AC）一致。
 */
interface PnlPatch {
  markPrice: string | null;
  unrealizedPnlInAccount: string | null;
}

const SIDE_LABEL: Record<number, { text: string; color: string }> = {
  1: { text: "买", color: "green" },
  2: { text: "卖", color: "red" },
};

// 与 trading-core TradingMybatisSupport.toPositionStatusCode 对齐：1=OPEN / 2=CLOSED / 3=LIQUIDATED
const STATUS_LABEL: Record<number, { text: string; color: string }> = {
  1: { text: "持仓中", color: "success" },
  2: { text: "已平仓", color: "default" },
  3: { text: "已强平", color: "error" },
};

// 与 trading-core TradingMybatisSupport 一致：1=CROSS（全仓），2=ISOLATED（逐仓）
const MARGIN_MODE_LABEL: Record<number, string> = {
  1: "全仓",
  2: "逐仓",
};

export function TradingPositionListPage() {
  const [items, setItems] = useState<PositionListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<PositionListQuery>({ page: 1, size: 20 });
  const [liquidateTarget, setLiquidateTarget] = useState<PositionListItem | null>(null);
  const [detailTarget, setDetailTarget] = useState<PositionListItem | null>(null);
  const swapSummaryQuery = useQuery({
    queryKey: ["admin", "trading", "swap-summary", detailTarget?.id],
    queryFn: () => tradingApi.getPositionSwapSummary(detailTarget!.id),
    enabled: detailTarget != null,
    staleTime: 30_000,
  });
  const [summary, setSummary] = useState<PositionSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);

  // WS push 的 PnL patch 缓存：positionId → {markPrice, unrealizedPnlInAccount}
  // 用 ref 累积避免每条 push 触发 React render；setState 由 200ms 节流批量刷新
  const pnlBufferRef = useRef<Map<string, PnlPatch>>(new Map());
  const [pnlMap, setPnlMap] = useState<Map<string, PnlPatch>>(new Map());

  const load = (q: PositionListQuery) => {
    setLoading(true);
    tradingApi
      .listPositions(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
        // 拉新一页后丢弃旧 patch buffer（避免上一页 patch 漏到新页同 positionId 干扰）
        pnlBufferRef.current.clear();
        setPnlMap(new Map());
      })
      .catch((err) => message.error(`加载持仓列表失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  const loadSummary = () => {
    setSummaryLoading(true);
    tradingApi
      .getPositionSummary()
      .then((data) => setSummary(data))
      .catch((err) => message.warning(`持仓汇总加载失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setSummaryLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
    // query 变化时同步刷新顶部汇总
    loadSummary();
  }, [query]);

  // STAGE-2-REALTIME-DATA：admin.position.update WS 推送（按 symbol 200ms 节流，复用 QuoteDrivenEngine 算力）
  const user = useAdminAuthStore((s) => s.user);
  const token = useMemo(() => (user ? getAdminAccessToken() : null), [user]);
  const handlePositionPnl = useCallback((update: AdminPositionPnlUpdate) => {
    for (const it of update.items) {
      pnlBufferRef.current.set(it.positionId, {
        markPrice: it.markPrice,
        unrealizedPnlInAccount: it.unrealizedPnlInAccount,
      });
    }
  }, []);
  // 平台 totalUnrealizedPnl patch（500ms 节流由后端控制），直接 merge 进 summary state
  // openPositionCount / totalMarginUsed 不在 tick 路径上推（lifecycle 事件触发 REST 刷）
  const handleSummaryPatch = useCallback((update: AdminPositionSummaryUpdate) => {
    setSummary((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        totalUnrealizedPnl: update.totalUnrealizedPnl ?? prev.totalUnrealizedPnl,
        computedAt: update.computedAt ?? prev.computedAt,
      };
    });
  }, []);
  const socketState = useAdminTradingSocket(token, {
    onPositionPnlUpdate: handlePositionPnl,
    onPositionSummaryUpdate: handleSummaryPatch,
  });

  // 把 buffer 节流提到 state，200ms 一次 batch 刷新（与后端 admin push 节奏一致，避免 React 抖动）
  useEffect(() => {
    const flush = setInterval(() => {
      if (pnlBufferRef.current.size === 0) return;
      const snapshot = new Map(pnlBufferRef.current);
      setPnlMap((prev) => {
        const next = new Map(prev);
        snapshot.forEach((value, key) => next.set(key, value));
        return next;
      });
    }, 200);
    return () => clearInterval(flush);
  }, []);

  const handleSearch = (values: { userId?: number; symbol?: string; status?: number }) => {
    setQuery({ ...query, ...values, page: 1 });
  };

  // 把 WS patch 合入 REST 取到的 items，markPrice / 账户币浮盈亏（patch.unrealizedPnlInAccount → 列 unrealizedPnl）优先取 patch，没收到 patch 时回退 REST 初值
  const mergedItems = useMemo<PositionListItem[]>(() => {
    if (pnlMap.size === 0) return items;
    return items.map((it) => {
      const patch = pnlMap.get(String(it.id));
      if (!patch) return it;
      return {
        ...it,
        markPrice: patch.markPrice ?? it.markPrice,
        unrealizedPnl: patch.unrealizedPnlInAccount ?? it.unrealizedPnl,
      };
    });
  }, [items, pnlMap]);

  const columns: TableProps<PositionListItem>["columns"] = [
    { title: "持仓 ID", dataIndex: "id", key: "id", width: 160, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
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
    { title: "数量", dataIndex: "quantity", key: "quantity", width: 120, align: "right" },
    { title: "开仓价", dataIndex: "entryPrice", key: "entryPrice", width: 120, align: "right", render: (v, r) => formatPrice(v, r.pricePrecision) },
    {
      title: "标记价",
      dataIndex: "markPrice",
      key: "markPrice",
      width: 120,
      align: "right",
      render: (v: string | null, r) =>
        v == null ? <span style={{ color: "var(--fx-console-text-muted)" }}>—</span> : formatPrice(v, r.pricePrecision),
    },
    {
      title: "未实现盈亏",
      dataIndex: "unrealizedPnl",
      key: "unrealizedPnl",
      width: 130,
      align: "right",
      render: (v: string | null) => {
        if (v == null) return <span style={{ color: "var(--fx-console-text-muted)" }}>—</span>;
        const num = Number(v);
        const color = num < 0 ? "var(--fx-console-error)" : num > 0 ? "var(--fx-console-success)" : undefined;
        return <span style={{ color, fontVariantNumeric: "tabular-nums" }}>{formatSignedPnl(v)}</span>;
      },
    },
    { title: "杠杆", dataIndex: "leverage", key: "leverage", width: 80, align: "right" },
    { title: "保证金", dataIndex: "margin", key: "margin", width: 120, align: "right" },
    {
      title: "开仓手续费",
      key: "openFee",
      width: 130,
      align: "right",
      render: (_, r) => {
        if (!r.openFeeRate) return <span style={{ color: "var(--fx-console-text-muted)" }}>—</span>;
        const fee = Number(r.openFeeRate) * Number(r.entryPrice) * Number(r.quantity);
        return (
          <span
            style={{ color: "var(--fx-console-warning, #ffb84d)", fontVariantNumeric: "tabular-nums", fontWeight: 600 }}
            title={`费率 ${formatPercent(Number(r.openFeeRate) * 100, 3)}`}
          >
            {formatMoney(fee, undefined, 4)}
          </span>
        );
      },
    },
    { title: "模式", dataIndex: "marginMode", key: "marginMode", width: 80, render: (v: number) => MARGIN_MODE_LABEL[v] ?? v },
    {
      title: "强平价",
      dataIndex: "liquidationPrice",
      key: "liquidationPrice",
      width: 120,
      align: "right",
      render: (v, r) => v == null ? "—" : <span style={{ color: "var(--fx-console-error)" }}>{formatPrice(v, r.pricePrecision)}</span>,
    },
    { title: "止盈", dataIndex: "takeProfitPrice", key: "takeProfitPrice", width: 120, align: "right", render: (v, r) => v == null ? "—" : formatPrice(v, r.pricePrecision) },
    { title: "止损", dataIndex: "stopLossPrice", key: "stopLossPrice", width: 120, align: "right", render: (v, r) => v == null ? "—" : formatPrice(v, r.pricePrecision) },
    {
      title: "已实现盈亏",
      dataIndex: "realizedPnl",
      key: "realizedPnl",
      width: 130,
      align: "right",
      render: (v: string | null) => {
        if (v == null) return "—";
        const num = Number(v);
        const color = num < 0 ? "var(--fx-console-error)" : "var(--fx-console-success)";
        return <span style={{ color, fontVariantNumeric: "tabular-nums" }}>{formatPnl(v)}</span>;
      },
    },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 100,
      render: (v: number) => <Tag color={STATUS_LABEL[v]?.color}>{STATUS_LABEL[v]?.text ?? v}</Tag>,
    },
    { title: "开仓时间", dataIndex: "openedAt", key: "openedAt", width: 180 },
    {
      title: "操作",
      key: "actions",
      width: 180,
      fixed: "right",
      render: (_, record) => (
        <Space size={4}>
          <Button size="small" onClick={() => setDetailTarget(record)}>
            详情
          </Button>
          {record.status === 1 && (
            <Button danger size="small" onClick={() => setLiquidateTarget(record)}>
              手动强平
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const positionDetailGroups = (p: PositionListItem): DetailGroup[] => {
    const openFee =
      p.openFeeRate && p.entryPrice && p.quantity
        ? formatMoney(Number(p.openFeeRate) * Number(p.entryPrice) * Number(p.quantity), undefined, 4)
        : null;
    const pnl = p.realizedPnl != null ? Number(p.realizedPnl) : null;
    const pnlColor = pnl == null ? undefined : pnl >= 0 ? "var(--fx-console-success)" : "var(--fx-console-error)";
    return [
      {
        num: "01",
        title: "持仓标识",
        fields: [
          { label: "持仓 ID", value: p.id, span: 2 },
          { label: "开仓订单 ID", value: p.openingOrderId, span: 2 },
          { label: "用户 ID", value: p.userId, span: 2 },
          { label: "用户姓名", value: p.userFullName ?? "—" },
          { label: "用户邮箱", value: p.userEmail ?? "—" },
          { label: "用户 UID", value: p.userUid ?? "—", span: 2 },
        ],
      },
      {
        num: "02",
        title: "交易要素",
        fields: [
          { label: "品种", value: p.symbol },
          {
            label: "方向",
            value: <Tag color={SIDE_LABEL[p.side]?.color}>{SIDE_LABEL[p.side]?.text ?? p.side}</Tag>,
          },
          { label: "数量", value: p.quantity },
          { label: "杠杆", value: `${p.leverage}x` },
          { label: "保证金", value: p.margin },
          { label: "保证金模式", value: MARGIN_MODE_LABEL[p.marginMode] ?? p.marginMode },
        ],
      },
      {
        num: "03",
        title: "价格",
        fields: [
          { label: "开仓价", value: formatPrice(p.entryPrice, p.pricePrecision) },
          { label: "标记价", value: formatPrice(p.markPrice, p.pricePrecision) },
          { label: "平仓价", value: formatPrice(p.closePrice, p.pricePrecision) },
          {
            label: "强平价",
            value: p.liquidationPrice ? <span style={{ color: "var(--fx-console-error)" }}>{formatPrice(p.liquidationPrice, p.pricePrecision)}</span> : null,
          },
          { label: "止盈", value: formatPrice(p.takeProfitPrice, p.pricePrecision) },
          { label: "止损", value: formatPrice(p.stopLossPrice, p.pricePrecision) },
        ],
      },
      {
        num: "04",
        title: "费用与盈亏",
        fields: [
          {
            label: "开仓手续费",
            value: openFee ? <span style={{ color: "var(--fx-console-warning, #ffb84d)", fontWeight: 700 }}>{openFee}</span> : null,
          },
          { label: "费率", value: p.openFeeRate ? formatPercent(Number(p.openFeeRate) * 100, 3) : null },
          {
            label: "未实现盈亏",
            value: p.unrealizedPnl ? <span style={{ color: Number(p.unrealizedPnl) >= 0 ? "var(--fx-console-success)" : "var(--fx-console-error)" }}>{formatPnl(p.unrealizedPnl)}</span> : null,
          },
          {
            label: "已实现盈亏",
            value: p.realizedPnl ? <span style={{ color: pnlColor }}>{formatPnl(p.realizedPnl)}</span> : null,
          },
        ],
      },
      {
        num: "05",
        title: "状态与时间",
        fields: [
          {
            label: "状态",
            value: <Tag color={STATUS_LABEL[p.status]?.color}>{STATUS_LABEL[p.status]?.text ?? p.status}</Tag>,
          },
          { label: "平仓原因", value: p.closeReason },
          { label: "开仓时间", value: p.openedAt },
          { label: "平仓时间", value: p.closedAt },
          { label: "更新时间", value: p.updatedAt, span: 2 },
        ],
      },
      buildAdminSwapGroup(swapSummaryQuery.data, swapSummaryQuery.isLoading),
    ];
  };

  function buildAdminSwapGroup(s: import("./types").AdminSwapSummary | undefined, loading: boolean): DetailGroup {
    if (loading) {
      return {
        num: "06",
        title: "Swap 资金费率（加载中…）",
        fields: [{ label: "状态", value: "加载中…", span: 2 }],
      };
    }
    if (!s || (s.chargeCount === 0 && s.incomeCount === 0)) {
      return {
        num: "06",
        title: "Swap 资金费率",
        fields: [
          { label: "累计支付", value: <span style={{ color: "var(--fx-console-text-muted)" }}>0.0000</span> },
          { label: "累计收入", value: <span style={{ color: "var(--fx-console-text-muted)" }}>0.0000</span> },
          { label: "净影响", value: <span style={{ color: "var(--fx-console-text-muted)" }}>0.0000</span> },
          { label: "结算次数", value: "0 次" },
        ],
      };
    }
    const charge = Number(s.totalCharge);
    const income = Number(s.totalIncome);
    const net = Number(s.net);
    return {
      num: "06",
      title: "Swap 资金费率",
      fields: [
        {
          label: "累计支付",
          value: <span style={{ color: charge > 0 ? "var(--fx-console-error)" : undefined, fontWeight: 600 }}>{`-${formatMoney(charge, undefined, 4)}`}</span>,
        },
        {
          label: "累计收入",
          value: <span style={{ color: income > 0 ? "var(--fx-console-success)" : undefined, fontWeight: 600 }}>{`+${formatMoney(income, undefined, 4)}`}</span>,
        },
        {
          label: "净影响",
          value: <span style={{ color: net >= 0 ? "var(--fx-console-success)" : "var(--fx-console-error)", fontWeight: 700 }}>{(net >= 0 ? "+" : "") + formatMoney(net, undefined, 4)}</span>,
        },
        { label: "结算次数", value: `${s.chargeCount + s.incomeCount} 次` },
        s.firstAt ? { label: "首次结算", value: s.firstAt } : null,
        s.lastAt ? { label: "最近结算", value: s.lastAt } : null,
      ].filter(Boolean) as DetailGroup["fields"],
    };
  }

  const summaryPnl = summary?.totalUnrealizedPnl ?? null;
  const summaryPnlNum = summaryPnl == null ? null : Number(summaryPnl);
  const summaryPnlColor = summaryPnlNum == null
    ? undefined
    : summaryPnlNum < 0
    ? "var(--fx-console-error)"
    : summaryPnlNum > 0
    ? "var(--fx-console-success)"
    : undefined;

  return (
    <div>
      <Title level={3}>持仓监控</Title>
      <Card style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="平台 OPEN 持仓数"
              value={summary?.openPositionCount ?? 0}
              loading={summaryLoading}
              suffix="笔"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="占用保证金合计"
              value={summary?.totalMarginUsed ?? "—"}
              loading={summaryLoading}
              suffix="USDT"
              precision={summary?.totalMarginUsed ? 2 : undefined}
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="未实现盈亏合计"
              value={summaryPnl == null ? "—" : formatSignedPnl(summaryPnl)}
              loading={summaryLoading}
              suffix="USDT"
              valueStyle={summaryPnlColor ? { color: summaryPnlColor } : undefined}
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Space direction="vertical" size={0} style={{ width: "100%" }}>
              <Space size={4} align="center">
                <Text type="secondary" style={{ fontSize: 12 }}>计算时间</Text>
                <Badge
                  status={socketState === "open" ? "success" : socketState === "connecting" || socketState === "reconnecting" ? "processing" : "default"}
                  text={
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      实时 {socketState === "open" ? "已连接" : socketState === "connecting" ? "连接中" : socketState === "reconnecting" ? "重连" : "断开"}
                    </Text>
                  }
                />
              </Space>
              <Text style={{ fontFamily: "monospace", fontSize: 13 }}>
                {summary?.computedAt ? summary.computedAt.replace("T", " ").slice(0, 19) : "—"}
              </Text>
              {summary?.positionsWithoutQuote != null && summary.positionsWithoutQuote > 0 && (
                <Text type="warning" style={{ fontSize: 12 }}>
                  {summary.positionsWithoutQuote} 笔无报价（未计入 PnL）
                </Text>
              )}
              <Button size="small" type="link" loading={summaryLoading} onClick={() => loadSummary()} style={{ padding: 0 }}>
                刷新汇总
              </Button>
            </Space>
          </Col>
        </Row>
      </Card>
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
        dataSource={mergedItems}
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
      <ManualLiquidateModal
        open={liquidateTarget != null}
        position={liquidateTarget}
        onClose={() => setLiquidateTarget(null)}
        onSuccess={() => {
          load(query);
          loadSummary();
        }}
      />
      <AdminOrderDetailDrawer
        open={detailTarget != null}
        title={detailTarget ? `持仓 ${detailTarget.symbol}` : ""}
        subtitle={detailTarget ? `${SIDE_LABEL[detailTarget.side]?.text} · ${detailTarget.quantity}` : undefined}
        tag={detailTarget ? { text: STATUS_LABEL[detailTarget.status]?.text ?? "—", color: STATUS_LABEL[detailTarget.status]?.color } : undefined}
        groups={detailTarget ? positionDetailGroups(detailTarget) : []}
        onClose={() => setDetailTarget(null)}
      />
    </div>
  );
}
