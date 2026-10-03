import { useEffect, useState } from "react";
import { Button, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { riskApi } from "./riskApi";
import { RiskMarketConfigModal } from "./RiskMarketConfigModal";
import type { RiskMarketConfigItem } from "./types";

const { Title } = Typography;

export function RiskMarketConfigListPage() {
  const [items, setItems] = useState<RiskMarketConfigItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [editTarget, setEditTarget] = useState<RiskMarketConfigItem | null>(null);

  const load = () => {
    setLoading(true);
    riskApi
      .listMarketConfigs()
      .then((data) => setItems(data.items))
      .catch((err) => message.error(`加载 risk_market_config 失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, []);

  const columns: TableProps<RiskMarketConfigItem>["columns"] = [
    { title: "Market Code", dataIndex: "marketCode", key: "marketCode", width: 200 },
    { title: "集中度阈值 USD", dataIndex: "concentrationThresholdUsd", key: "concentrationThresholdUsd", width: 200, align: "right" },
    {
      title: "启用",
      dataIndex: "enabled",
      key: "enabled",
      width: 100,
      render: (v: boolean) => <Tag color={v ? "success" : "default"}>{v ? "Y" : "N"}</Tag>,
    },
    { title: "更新时间", dataIndex: "updatedAt", key: "updatedAt", width: 220 },
    {
      title: "操作",
      key: "actions",
      width: 100,
      render: (_, record) => (
        <Button size="small" onClick={() => setEditTarget(record)}>编辑</Button>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>risk_market_config（跨品种集中度）</Title>
      <Table
        rowKey="marketCode"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={false}
      />
      <RiskMarketConfigModal
        open={editTarget != null}
        config={editTarget}
        onClose={() => setEditTarget(null)}
        onSuccess={load}
      />
    </div>
  );
}
