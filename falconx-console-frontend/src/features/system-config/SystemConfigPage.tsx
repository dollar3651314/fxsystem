import { useEffect, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import { systemConfigApi } from "./systemConfigApi";
import type { SystemConfigAuditItem, SystemConfigItem } from "./types";

const { Title, Text } = Typography;

const CATEGORY_LABELS: Record<string, { label: string; color: string; hint: string }> = {
  RATE_LIMIT: { label: "限流速率", color: "magenta", hint: "每分钟/每秒最大请求数" },
  SECURITY: { label: "网络安全", color: "red", hint: "可信代理 IP、IP 白名单、Security Headers" },
  TOKEN: { label: "Token TTL", color: "blue", hint: "access / refresh token 有效期" },
  AUTH: { label: "认证策略", color: "purple", hint: "bcrypt 强度、登录失败锁定" },
  HEADER: { label: "HTTP Header", color: "geekblue", hint: "CSP / HSTS 等响应头" },
};

export function SystemConfigPage() {
  const [items, setItems] = useState<SystemConfigItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [activeCategory, setActiveCategory] = useState<string>("RATE_LIMIT");
  const [editTarget, setEditTarget] = useState<SystemConfigItem | null>(null);
  const [historyTarget, setHistoryTarget] = useState<SystemConfigItem | null>(null);
  const [history, setHistory] = useState<SystemConfigAuditItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  const load = () => {
    setLoading(true);
    systemConfigApi
      .list()
      .then((data) => setItems(data.items))
      .catch((err) =>
        message.error(`加载系统配置失败 ${err.code ?? ""}：${err.message ?? ""}`)
      )
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const openHistory = (item: SystemConfigItem) => {
    setHistoryTarget(item);
    setHistoryLoading(true);
    systemConfigApi
      .history(item.configKey, 50)
      .then((data) => setHistory(data.items))
      .catch((err) =>
        message.error(`加载历史失败 ${err.code ?? ""}：${err.message ?? ""}`)
      )
      .finally(() => setHistoryLoading(false));
  };

  const handleReset = (item: SystemConfigItem) => {
    if (item.defaultValue == null) {
      message.warning("该项无默认值，无法重置");
      return;
    }
    systemConfigApi
      .reset(item.configKey)
      .then(() => {
        message.success(`已重置 ${item.configKey} 为默认值`);
        load();
      })
      .catch((err) =>
        message.error(`重置失败 ${err.code ?? ""}：${err.message ?? ""}`)
      );
  };

  const filteredItems = items.filter((i) => i.category === activeCategory);

  const columns: TableProps<SystemConfigItem>["columns"] = [
    {
      title: "Key",
      dataIndex: "configKey",
      key: "configKey",
      width: 320,
      render: (v: string, record) => (
        <Space direction="vertical" size={0}>
          <Text code style={{ fontSize: 12 }}>
            {v}
          </Text>
          {record.description && (
            <Text type="secondary" style={{ fontSize: 11 }}>
              {record.description}
            </Text>
          )}
        </Space>
      ),
    },
    {
      title: "类型",
      dataIndex: "valueType",
      key: "valueType",
      width: 90,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: "当前值",
      dataIndex: "configValue",
      key: "configValue",
      render: (v: string, record) => {
        const isDefault = v === record.defaultValue;
        return (
          <Space direction="vertical" size={0}>
            <Text code style={{ wordBreak: "break-all" }}>{v}</Text>
            {!isDefault && record.defaultValue != null && (
              <Text type="secondary" style={{ fontSize: 10 }}>
                默认: <Text code>{record.defaultValue}</Text>
              </Text>
            )}
            {isDefault && <Badge status="default" text={<Text type="secondary" style={{ fontSize: 10 }}>当前 = 默认</Text>} />}
          </Space>
        );
      },
    },
    {
      title: "校验",
      dataIndex: "validationRegex",
      key: "validationRegex",
      width: 160,
      render: (v: string | null) =>
        v ? (
          <Tooltip title={`正则: ${v}`}>
            <Tag color="orange">{v.length > 12 ? v.slice(0, 12) + "…" : v}</Tag>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: "更新",
      dataIndex: "updatedAt",
      key: "updatedAt",
      width: 180,
      render: (v: string, record) => (
        <Space direction="vertical" size={0}>
          <Text style={{ fontSize: 11 }}>{v.replace("T", " ").slice(0, 19)}</Text>
          {record.updatedBy && (
            <Text type="secondary" style={{ fontSize: 10 }}>
              by admin#{record.updatedBy}
            </Text>
          )}
        </Space>
      ),
    },
    {
      title: "操作",
      key: "actions",
      width: 180,
      fixed: "right",
      render: (_, record) => (
        <Space size="small">
          <Button size="small" type="primary" onClick={() => setEditTarget(record)}>
            编辑
          </Button>
          <Button size="small" onClick={() => openHistory(record)}>
            历史
          </Button>
          <Popconfirm
            title="重置为默认值？"
            description={`将 ${record.configKey} 改回 ${record.defaultValue ?? "(无默认)"}`}
            onConfirm={() => handleReset(record)}
            okButtonProps={{ danger: true }}
          >
            <Button size="small" danger disabled={record.defaultValue == null}>
              重置
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <Title level={3} style={{ marginTop: 0 }}>
        系统配置中心
      </Title>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message="高危：所有改动通过 Redis pub/sub 立即推送到所有 service，秒级生效。每次变更自动写审计日志，不可撤销。"
      />
      <Tabs
        activeKey={activeCategory}
        onChange={setActiveCategory}
        items={Object.entries(CATEGORY_LABELS).map(([key, meta]) => ({
          key,
          label: (
            <Space>
              <Tag color={meta.color}>{meta.label}</Tag>
              <Text type="secondary" style={{ fontSize: 11 }}>
                {meta.hint}
              </Text>
            </Space>
          ),
        }))}
      />
      <Table
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={filteredItems}
        loading={loading}
        pagination={false}
        scroll={{ x: 1100 }}
      />

      {editTarget && (
        <EditModal
          item={editTarget}
          onClose={() => setEditTarget(null)}
          onSaved={() => {
            setEditTarget(null);
            load();
          }}
        />
      )}

      <Drawer
        title={historyTarget ? `${historyTarget.configKey} 变更历史` : "变更历史"}
        open={!!historyTarget}
        onClose={() => setHistoryTarget(null)}
        width={720}
      >
        {historyLoading ? (
          <Text>加载中…</Text>
        ) : history.length === 0 ? (
          <Text type="secondary">无历史记录</Text>
        ) : (
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={history}
            columns={[
              {
                title: "时间",
                dataIndex: "createdAt",
                key: "createdAt",
                width: 160,
                render: (v: string) => v.replace("T", " ").slice(0, 19),
              },
              {
                title: "动作",
                dataIndex: "action",
                key: "action",
                width: 80,
                render: (v: string) => (
                  <Tag color={v === "RESET" ? "orange" : v === "UPDATE" ? "blue" : "default"}>{v}</Tag>
                ),
              },
              {
                title: "旧值 → 新值",
                key: "diff",
                render: (_, record) => (
                  <Space direction="vertical" size={0}>
                    <Text delete code style={{ fontSize: 11 }}>
                      {record.oldValue ?? "—"}
                    </Text>
                    <Text code style={{ fontSize: 11, color: "#52c41a" }}>
                      → {record.newValue ?? "—"}
                    </Text>
                  </Space>
                ),
              },
              {
                title: "操作人",
                key: "operator",
                width: 160,
                render: (_, record) => (
                  <Space direction="vertical" size={0}>
                    <Text style={{ fontSize: 11 }}>
                      {record.operatorEmail ?? `admin#${record.operatorId}`}
                    </Text>
                    {record.clientIp && (
                      <Text type="secondary" style={{ fontSize: 10 }}>
                        {record.clientIp}
                      </Text>
                    )}
                  </Space>
                ),
              },
              {
                title: "原因",
                dataIndex: "reason",
                key: "reason",
                width: 180,
                render: (v: string | null) => v ?? <Text type="secondary">—</Text>,
              },
            ]}
          />
        )}
      </Drawer>
    </Card>
  );
}

function EditModal({
  item,
  onClose,
  onSaved,
}: {
  item: SystemConfigItem;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<{ newValue: string; reason: string }>();
  const [saving, setSaving] = useState(false);

  const handleSubmit = (values: { newValue: string; reason: string }) => {
    setSaving(true);
    systemConfigApi
      .update(item.configKey, { newValue: values.newValue, reason: values.reason })
      .then(() => {
        message.success(`${item.configKey} 已更新`);
        onSaved();
      })
      .catch((err) =>
        message.error(`更新失败 ${err.code ?? ""}：${err.message ?? ""}`)
      )
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      title={`编辑 ${item.configKey}`}
      open
      onCancel={onClose}
      footer={null}
      width={600}
    >
      <Alert
        type="info"
        showIcon
        message={item.description ?? "无描述"}
        style={{ marginBottom: 12 }}
      />
      <Form
        form={form}
        layout="vertical"
        initialValues={{ newValue: item.configValue, reason: "" }}
        onFinish={handleSubmit}
      >
        <Form.Item label="当前值" style={{ marginBottom: 6 }}>
          <Text code>{item.configValue}</Text>
        </Form.Item>
        {item.defaultValue != null && (
          <Form.Item label="默认值" style={{ marginBottom: 6 }}>
            <Text code type="secondary">
              {item.defaultValue}
            </Text>
          </Form.Item>
        )}
        {item.validationRegex && (
          <Form.Item label="校验正则" style={{ marginBottom: 6 }}>
            <Tag color="orange">{item.validationRegex}</Tag>
          </Form.Item>
        )}
        <Form.Item
          label="新值"
          name="newValue"
          rules={[
            { required: true, message: "请输入新值" },
            {
              pattern: item.validationRegex ? new RegExp(item.validationRegex) : undefined,
              message: `值必须匹配正则 ${item.validationRegex}`,
            },
          ]}
        >
          {item.valueType === "JSON" ? (
            <Input.TextArea autoSize={{ minRows: 3, maxRows: 8 }} />
          ) : (
            <Input autoFocus />
          )}
        </Form.Item>
        <Form.Item
          label="变更原因（可选，建议填写）"
          name="reason"
          rules={[{ max: 255, message: "原因不超过 255 字符" }]}
        >
          <Input placeholder="如：流量峰值临时调高到 100/min" />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0, textAlign: "right" }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" htmlType="submit" loading={saving} danger>
              保存（立即生效）
            </Button>
          </Space>
        </Form.Item>
      </Form>
    </Modal>
  );
}
