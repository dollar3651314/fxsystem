import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, Form, Input, Select, Space, Switch, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { riskApi } from "./riskApi";
import { RiskActionActivateModal } from "./RiskActionActivateModal";
import { RiskActionDeactivateModal } from "./RiskActionDeactivateModal";
import type {
  RiskActionItem,
  RiskActionListQuery,
  RiskActionType,
  RiskTriggerSource,
} from "./types";
import { useAdminTradingSocket } from "../trading/useAdminTradingSocket";
import { getAdminAccessToken } from "../../lib/auth/adminTokenStorage";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";

const { Title } = Typography;

const ACTION_TYPES: RiskActionType[] = ["REJECT_OPEN", "REDUCE_ONLY", "SUSPEND_SYMBOL", "GLOBAL_PAUSE"];
const TRIGGER_SOURCES: RiskTriggerSource[] = ["AUTO", "AUTO_CONCENTRATION", "MANUAL", "MANUAL_ADMIN"];

const SOURCE_COLOR: Record<RiskTriggerSource, string> = {
  AUTO: "blue",
  AUTO_CONCENTRATION: "purple",
  MANUAL: "orange",
  MANUAL_ADMIN: "red",
};

export function RiskActionListPage() {
  const [items, setItems] = useState<RiskActionItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<RiskActionListQuery>({ page: 1, size: 20 });
  const [activateOpen, setActivateOpen] = useState(false);
  const [deactivateTarget, setDeactivateTarget] = useState<RiskActionItem | null>(null);

  const load = (q: RiskActionListQuery) => {
    setLoading(true);
    riskApi
      .listActions(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载风控动作失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  // STAGE-2-REALTIME-DATA Phase 4：admin.risk-action.changed 触发当前 query refetch
  const user = useAdminAuthStore((s) => s.user);
  const token = useMemo(() => (user ? getAdminAccessToken() : null), [user]);
  const handleRiskActionChanged = useCallback(() => load(query), [query]);
  const socketState = useAdminTradingSocket(token, {
    onRiskActionChanged: handleRiskActionChanged,
  });

  const handleSearch = (values: {
    symbol?: string;
    actionType?: RiskActionType;
    triggerSource?: RiskTriggerSource;
    isActive?: boolean;
  }) => {
    setQuery({ ...query, ...values, page: 1 });
  };

  const columns: TableProps<RiskActionItem>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id", width: 140, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "Symbol", dataIndex: "symbol", key: "symbol", width: 120, render: (v) => v ?? <em>*global*</em> },
    { title: "类型", dataIndex: "actionType", key: "actionType", width: 160 },
    {
      title: "触发源",
      dataIndex: "triggerSource",
      key: "triggerSource",
      width: 160,
      render: (v: RiskTriggerSource) => <Tag color={SOURCE_COLOR[v]}>{v}</Tag>,
    },
    { title: "原因", dataIndex: "triggerReason", key: "triggerReason", ellipsis: true },
    {
      title: "激活",
      dataIndex: "isActive",
      key: "isActive",
      width: 80,
      render: (v: boolean) => <Tag color={v ? "success" : "default"}>{v ? "Y" : "N"}</Tag>,
    },
    { title: "创建时间", dataIndex: "createdAt", key: "createdAt", width: 180 },
    {
      title: "操作",
      key: "actions",
      width: 100,
      fixed: "right",
      render: (_, record) =>
        record.isActive && record.triggerSource === "MANUAL_ADMIN" ? (
          <Button danger size="small" onClick={() => setDeactivateTarget(record)}>
            停用
          </Button>
        ) : (
          <span style={{ color: "var(--fx-console-text-muted)" }}>—</span>
        ),
    },
  ];

  return (
    <div>
      <Title level={3}>
        风控动作
        <Badge
          status={socketState === "open" ? "success" : socketState === "error" ? "error" : "default"}
          text={socketState === "open" ? "实时" : socketState}
          style={{ marginLeft: 12, fontSize: 14, fontWeight: 400 }}
        />
      </Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="symbol" label="Symbol">
            <Input placeholder="如 BTCUSDT" allowClear />
          </Form.Item>
          <Form.Item name="actionType" label="类型">
            <Select allowClear style={{ width: 180 }} placeholder="全部"
              options={ACTION_TYPES.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item name="triggerSource" label="触发源">
            <Select allowClear style={{ width: 200 }} placeholder="全部"
              options={TRIGGER_SOURCES.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item name="isActive" label="激活中" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => setQuery({ page: 1, size: 20 })}>重置</Button>
              <Button type="primary" danger onClick={() => setActivateOpen(true)}>激活风控动作</Button>
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
      <RiskActionActivateModal
        open={activateOpen}
        onClose={() => setActivateOpen(false)}
        onSuccess={() => load(query)}
      />
      <RiskActionDeactivateModal
        open={deactivateTarget != null}
        action={deactivateTarget}
        onClose={() => setDeactivateTarget(null)}
        onSuccess={() => load(query)}
      />
    </div>
  );
}
