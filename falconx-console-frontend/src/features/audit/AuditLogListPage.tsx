import { useEffect, useState } from "react";
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import {
  RISK_LEVEL_META,
  type AdminAuditLogItem,
  type AdminAuditLogListQuery,
  type AuditRiskLevel,
} from "./types";
import { auditLogApi } from "./auditLogApi";

const { Title } = Typography;

export function AuditLogListPage() {
  const [items, setItems] = useState<AdminAuditLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminAuditLogListQuery>({ page: 1, size: 20 });
  const [detailTarget, setDetailTarget] = useState<AdminAuditLogItem | null>(null);

  const load = (q: AdminAuditLogListQuery) => {
    setLoading(true);
    auditLogApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
      })
      .catch((err) =>
        message.error(`加载失败 ${err.code ?? ""}：${err.message ?? ""}`),
      )
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load(query);
  }, [query]);

  const columns: TableProps<AdminAuditLogItem>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id", width: 160,
      render: (v: string) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "Admin", dataIndex: "adminUserId", key: "adminUserId", width: 140,
      render: (v: string) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "权限码", dataIndex: "permissionCode", key: "permissionCode", width: 180 },
    { title: "目标", key: "target", width: 200,
      render: (_, r) =>
        r.targetType ? `${r.targetType}${r.targetId ? ` / ${r.targetId}` : ""}` : "—" },
    { title: "风险", dataIndex: "riskLevel", key: "riskLevel", width: 110,
      render: (v: AuditRiskLevel) =>
        <Tag color={RISK_LEVEL_META[v].color}>{RISK_LEVEL_META[v].label}</Tag> },
    { title: "IP", dataIndex: "ip", key: "ip", width: 140,
      render: (v: string | null) => v ?? "—" },
    { title: "发生于", dataIndex: "occurredAt", key: "occurredAt", width: 200 },
    { title: "操作", key: "actions", width: 80, fixed: "right",
      render: (_, record) => (
        <Button size="small" onClick={() => setDetailTarget(record)}>详情</Button>
      ) },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>审计日志</Title>
      </div>
      <Card style={{ marginBottom: 16 }}>
        <Form
          layout="inline"
          onFinish={(v: AdminAuditLogListQuery) => setQuery({ ...query, ...v, page: 1 })}
        >
          <Form.Item name="adminUserId" label="Admin ID">
            <Input placeholder="可选 雪花 ID" style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="permissionCode" label="权限码">
            <Input placeholder="如 withdraw:review" style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="targetType" label="目标类型">
            <Input placeholder="如 withdraw" style={{ width: 140 }} />
          </Form.Item>
          <Form.Item name="targetId" label="目标 ID">
            <Input placeholder="可选" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="riskLevel" label="风险">
            <Select allowClear style={{ width: 140 }} placeholder="全部"
              options={[
                { value: "LOW", label: "LOW" },
                { value: "MEDIUM", label: "MEDIUM" },
                { value: "HIGH_RISK", label: "HIGH_RISK" },
              ]} />
          </Form.Item>
          <Form.Item name="fromOccurredAt" label="开始">
            <Input placeholder="ISO8601" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="toOccurredAt" label="结束">
            <Input placeholder="ISO8601" style={{ width: 200 }} />
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
          current: query.page ?? 1, pageSize: query.size ?? 20, total,
          showSizeChanger: true, pageSizeOptions: [20, 50, 100],
          onChange: (p, s) => setQuery({ ...query, page: p, size: s }),
        }}
        scroll={{ x: "max-content" }}
      />
      <AuditLogDetailDrawer target={detailTarget} onClose={() => setDetailTarget(null)} />
    </div>
  );
}

interface DetailProps {
  target: AdminAuditLogItem | null;
  onClose: () => void;
}

function AuditLogDetailDrawer({ target, onClose }: DetailProps) {
  return (
    <Drawer open={target != null} width={720} onClose={onClose} title="审计日志详情">
      {target && (
        <>
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="ID"><code>{target.id}</code></Descriptions.Item>
            <Descriptions.Item label="Admin ID"><code>{target.adminUserId}</code></Descriptions.Item>
            <Descriptions.Item label="权限码">{target.permissionCode}</Descriptions.Item>
            <Descriptions.Item label="目标">
              {target.targetType ?? "—"}{target.targetId ? ` / ${target.targetId}` : ""}
            </Descriptions.Item>
            <Descriptions.Item label="风险">
              <Tag color={RISK_LEVEL_META[target.riskLevel].color}>
                {RISK_LEVEL_META[target.riskLevel].label}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="IP">{target.ip ?? "—"}</Descriptions.Item>
            <Descriptions.Item label="UA"
              contentStyle={{ wordBreak: "break-all", maxWidth: 480 }}>
              {target.userAgent ?? "—"}
            </Descriptions.Item>
            <Descriptions.Item label="发生于">{target.occurredAt}</Descriptions.Item>
          </Descriptions>
          <div style={{ marginTop: 16 }}>
            <Title level={5}>前后值快照</Title>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
              <Card size="small" title="Before">
                <pre data-testid="audit-before" style={{ fontSize: 12, margin: 0, whiteSpace: "pre-wrap" }}>
                  {formatJson(target.beforeValue)}
                </pre>
              </Card>
              <Card size="small" title="After">
                <pre data-testid="audit-after" style={{ fontSize: 12, margin: 0, whiteSpace: "pre-wrap" }}>
                  {formatJson(target.afterValue)}
                </pre>
              </Card>
            </div>
          </div>
        </>
      )}
    </Drawer>
  );
}

function formatJson(raw: string | null): string {
  if (raw == null || raw === "") return "—";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
