import { useEffect, useState } from "react";
import { Button, Card, Form, Input, Space, Table, Typography, message } from "antd";
import type { TableProps } from "antd";
import { riskApi } from "./riskApi";
import { RiskConfigFormModal } from "./RiskConfigFormModal";
import { RiskConfigDeleteModal } from "./RiskConfigDeleteModal";
import type { RiskConfigItem, RiskConfigListQuery } from "./types";

const { Title } = Typography;

export function RiskConfigListPage() {
  const [items, setItems] = useState<RiskConfigItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<RiskConfigListQuery>({ page: 1, size: 20 });
  const [formMode, setFormMode] = useState<"create" | "edit" | null>(null);
  const [formExisting, setFormExisting] = useState<RiskConfigItem | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<RiskConfigItem | null>(null);

  const load = (q: RiskConfigListQuery) => {
    setLoading(true);
    riskApi
      .listConfigs(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载 risk_config 失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  const handleSearch = (values: { symbol?: string; marketCode?: string }) => {
    setQuery({ ...query, ...values, page: 1 });
  };

  const columns: TableProps<RiskConfigItem>["columns"] = [
    { title: "Symbol", dataIndex: "symbol", key: "symbol", width: 140 },
    { title: "Market", dataIndex: "marketCode", key: "marketCode", width: 120, render: (v) => v ?? "—" },
    { title: "用户最大持仓", dataIndex: "maxPositionPerUser", key: "maxPositionPerUser", width: 140, align: "right" },
    { title: "平台最大持仓", dataIndex: "maxPositionTotal", key: "maxPositionTotal", width: 140, align: "right" },
    { title: "维持保证金率", dataIndex: "maintenanceMarginRate", key: "maintenanceMarginRate", width: 140, align: "right" },
    { title: "最大杠杆", dataIndex: "maxLeverage", key: "maxLeverage", width: 100, align: "right" },
    { title: "对冲阈值 USD", dataIndex: "hedgeThresholdUsd", key: "hedgeThresholdUsd", width: 140, align: "right" },
    { title: "更新时间", dataIndex: "updatedAt", key: "updatedAt", width: 180 },
    {
      title: "操作",
      key: "actions",
      width: 160,
      fixed: "right",
      render: (_, record) => (
        <Space size="small">
          <Button size="small" onClick={() => { setFormExisting(record); setFormMode("edit"); }}>
            编辑
          </Button>
          <Button danger size="small" onClick={() => setDeleteTarget(record)}>
            删除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>risk_config 管理</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="symbol" label="Symbol">
            <Input placeholder="如 BTCUSDT" allowClear />
          </Form.Item>
          <Form.Item name="marketCode" label="Market Code">
            <Input placeholder="如 CRYPTO" allowClear />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => setQuery({ page: 1, size: 20 })}>重置</Button>
              <Button type="primary" danger onClick={() => { setFormExisting(null); setFormMode("create"); }}>
                新建配置
              </Button>
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
      <RiskConfigFormModal
        open={formMode != null}
        mode={formMode ?? "create"}
        existing={formExisting}
        onClose={() => { setFormMode(null); setFormExisting(null); }}
        onSuccess={() => load(query)}
      />
      <RiskConfigDeleteModal
        open={deleteTarget != null}
        config={deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onSuccess={() => load(query)}
      />
    </div>
  );
}
