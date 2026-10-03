import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Badge,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import { withdrawApi } from "./withdrawApi";
import type {
  AdminWithdrawItem,
  AdminWithdrawListQuery,
  WithdrawNetwork,
  WithdrawStatus,
} from "./types";
import { WITHDRAW_STATUS_META } from "./types";
import { UserCell } from "../../components/UserCell";
import { useAdminTradingSocket, type AdminWithdrawStatusChanged } from "../trading/useAdminTradingSocket";
import { getAdminAccessToken } from "../../lib/auth/adminTokenStorage";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { formatMoney } from "../../lib/precision";

const { Title, Text } = Typography;

/** R3 §2.2 状态徽章。 */
function StatusTag({ status }: { status: WithdrawStatus }) {
  const meta = WITHDRAW_STATUS_META[status];
  return (
    <Tag color={meta.color}>
      {meta.icon ? `${meta.icon} ` : ""}
      {meta.label}
    </Tag>
  );
}

/**
 * STAGE-7-WITHDRAW Phase 4 出金审核列表页（路径 /admin/withdraws，权限 withdraw:view）。
 *
 * R3 设计 §2 落地：过滤区 + 待办分组卡片（PENDING/APPROVED_DELAYED 计数+合计） + 分页表格 + 状态徽章。
 * 排序：PENDING 优先 → APPROVED_DELAYED 次之 → 其它按 created_at DESC（trading-core 已实现，前端透传）。
 */
export function WithdrawListPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<AdminWithdrawItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminWithdrawListQuery>({ page: 1, pageSize: 20 });

  /** 待办分组聚合：从当前页 items 计算（轻量；准确数需 trading-core 提供 aggregate 接口）。 */
  const summary = useMemo(() => {
    const sum = (status: WithdrawStatus) =>
      items
        .filter((it) => it.status === status)
        .reduce(
          (acc, it) => ({ count: acc.count + 1, totalUsd: acc.totalUsd + Number(it.amount) }),
          { count: 0, totalUsd: 0 },
        );
    return { pending: sum("PENDING"), delayed: sum("APPROVED_DELAYED") };
  }, [items]);

  const load = (q: AdminWithdrawListQuery) => {
    setLoading(true);
    withdrawApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setPageSize(data.pageSize);
      })
      .catch((err) => message.error(`加载失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load(query);
  }, [query]);

  // STAGE-7-WITHDRAW §4 commit D：admin.withdraw.status-changed 触发 refetch + 行内闪烁
  // 列表分页 + 过滤条件复杂，直接 refetch 当前 query 比客户端 patch 更稳；
  // flashedRows 记录最近 5s 内被推过的 withdrawId，行渲染加高亮提示 admin 哪条变了。
  const user = useAdminAuthStore((s) => s.user);
  const token = useMemo(() => (user ? getAdminAccessToken() : null), [user]);
  const [flashedRows, setFlashedRows] = useState<Set<string>>(new Set());
  const queryRef = useRef(query);
  useEffect(() => {
    queryRef.current = query;
  }, [query]);
  // STAGE-12 perf：setTimeout 需在组件卸载时 clear，否则会 setState after unmount
  // 触发 React warning + 短时间内存悬挂（5s 内重渲组件卸载 → 旧 timer 仍跑回调）。
  const flashTimersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  useEffect(() => {
    const timers = flashTimersRef.current;
    return () => {
      timers.forEach((t) => clearTimeout(t));
      timers.clear();
    };
  }, []);
  const handleStatusChanged = useCallback((event: AdminWithdrawStatusChanged) => {
    setFlashedRows((prev) => {
      const next = new Set(prev);
      next.add(event.withdrawId);
      return next;
    });
    // 5s 后移除高亮（避免一直亮着）
    const prevTimer = flashTimersRef.current.get(event.withdrawId);
    if (prevTimer) clearTimeout(prevTimer);
    const timer = setTimeout(() => {
      setFlashedRows((prev) => {
        if (!prev.has(event.withdrawId)) return prev;
        const next = new Set(prev);
        next.delete(event.withdrawId);
        return next;
      });
      flashTimersRef.current.delete(event.withdrawId);
    }, 5_000);
    flashTimersRef.current.set(event.withdrawId, timer);
    load(queryRef.current);
  }, []);
  const socketState = useAdminTradingSocket(token, {
    onWithdrawStatusChanged: handleStatusChanged,
  });

  const columns: TableProps<AdminWithdrawItem>["columns"] = [
    {
      title: "出金 ID",
      dataIndex: "withdrawId",
      key: "withdrawId",
      width: 180,
      render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span>,
    },
    {
      title: "用户",
      dataIndex: "userId",
      key: "userId",
      width: 200,
      render: (_, r) => (
        <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} />
      ),
    },
    {
      // §4 commit C：admin 风控视角，看到该用户今天已累计多少 + 距离 $30K 上限多远
      title: "当日累计 (USDT)",
      dataIndex: "dailyAccumulatedUsd",
      key: "dailyAccumulatedUsd",
      width: 140,
      align: "right",
      render: (v: string | null | undefined) => {
        if (v == null) return <Text type="secondary">—</Text>;
        const num = Number(v);
        // 接近上限时给个橙色提示（$24K 起，80% of $30K）
        const warn = num >= 24000;
        return (
          <span style={warn ? { color: "#fa8c16", fontWeight: 500 } : undefined}>
            {formatMoney(num, "USD")}
          </span>
        );
      },
    },
    {
      title: "金额",
      dataIndex: "amount",
      key: "amount",
      width: 140,
      align: "right",
      render: (v, record) => `${formatMoney(v, record.currency)} ${record.currency}`,
    },
    { title: "网络", dataIndex: "network", key: "network", width: 90 },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 130,
      render: (v: WithdrawStatus) => <StatusTag status={v} />,
    },
    {
      title: "目标地址",
      dataIndex: "targetAddress",
      key: "targetAddress",
      ellipsis: true,
      render: (v: string) => <span style={{ fontFamily: "monospace" }}>{v}</span>,
    },
    { title: "提交时间", dataIndex: "createdAt", key: "createdAt", width: 180 },
    {
      title: "操作",
      key: "actions",
      width: 100,
      fixed: "right",
      render: (_, record) => (
        <Button size="small" type="primary" onClick={() => navigate(`/admin/withdraws/${record.withdrawId}`)}>
          查看
        </Button>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>
        出金审核
        <Badge
          status={socketState === "open" ? "success" : socketState === "error" ? "error" : "default"}
          text={socketState === "open" ? "实时" : socketState}
          style={{ marginLeft: 12, fontSize: 14, fontWeight: 400 }}
        />
      </Title>

      <Card style={{ marginBottom: 16 }}>
        <Form
          layout="inline"
          onFinish={(v: AdminWithdrawListQuery) => setQuery({ ...query, ...v, page: 1 })}
        >
          <Form.Item name="userId" label="User ID">
            <Input placeholder="可选" style={{ width: 200 }} allowClear />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select placeholder="全部" style={{ width: 160 }} allowClear>
              {(Object.keys(WITHDRAW_STATUS_META) as WithdrawStatus[]).map((s) => (
                <Select.Option key={s} value={s}>
                  {WITHDRAW_STATUS_META[s].icon} {WITHDRAW_STATUS_META[s].label}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="network" label="网络">
            <Select<WithdrawNetwork> placeholder="全部" style={{ width: 100 }} allowClear>
              <Select.Option value="ERC20">ERC20</Select.Option>
              <Select.Option value="TRC20">TRC20</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item name="minAmount" label="金额下限">
            <InputNumber placeholder="USD" style={{ width: 120 }} min={0} />
          </Form.Item>
          <Form.Item name="maxAmount" label="金额上限">
            <InputNumber placeholder="USD" style={{ width: 120 }} min={0} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                查询
              </Button>
              <Button onClick={() => setQuery({ page: 1, pageSize: 20 })}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card>
            <Statistic
              title={
                <>
                  <Text strong>⏰ PENDING 待审核</Text>
                </>
              }
              value={summary.pending.count}
              suffix={`笔 / 合计 $${formatMoney(summary.pending.totalUsd, "USD")}`}
              valueStyle={{ color: "#fa8c16" }}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              当前页统计（如需全局聚合，由 trading-core 后续提供 aggregate 接口）
            </Text>
          </Card>
        </Col>
        <Col span={12}>
          <Card>
            <Statistic
              title={
                <>
                  <Text strong>⏱ APPROVED_DELAYED 延迟期</Text>
                </>
              }
              value={summary.delayed.count}
              suffix={`笔 / 合计 $${formatMoney(summary.delayed.totalUsd, "USD")}`}
              valueStyle={{ color: "#722ed1" }}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              ≥$3K 自动进入 6h 延迟期，可紧急取消
            </Text>
          </Card>
        </Col>
      </Row>

      <Card>
        <Table<AdminWithdrawItem>
          rowKey="withdrawId"
          loading={loading}
          columns={columns}
          dataSource={items}
          rowClassName={(record) => (flashedRows.has(record.withdrawId) ? "fx-row-flash" : "")}
          scroll={{ x: 1200 }}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            pageSizeOptions: ["20", "50", "100"],
            onChange: (p, s) => setQuery({ ...query, page: p, pageSize: s }),
          }}
        />
      </Card>
    </div>
  );
}
