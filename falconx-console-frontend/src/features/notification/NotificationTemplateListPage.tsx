import { useEffect, useState } from "react";
import {
  Button,
  Card,
  Checkbox,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import {
  CHANNEL_META,
  LEVEL_META,
  extractPlaceholders,
  type AdminNotificationTemplateItem,
  type AdminNotificationTemplateListQuery,
  type AdminNotificationTemplateUpsertRequest,
  type NotificationChannel,
  type NotificationLevel,
} from "./types";
import { notificationApi } from "./notificationApi";

const { Title, Text } = Typography;

const BUILT_IN_PREFIXES = ["PRICE_ALERT_", "POSITION_", "KYC_", "WITHDRAW_", "DEPOSIT_", "RISK_"];

function isBuiltIn(code: string): boolean {
  return BUILT_IN_PREFIXES.some((p) => code.startsWith(p));
}

const ALL_CHANNELS: NotificationChannel[] = ["IN_APP", "EMAIL", "TELEGRAM"];

export function NotificationTemplateListPage() {
  const [items, setItems] = useState<AdminNotificationTemplateItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminNotificationTemplateListQuery>({ page: 1, size: 20 });
  const [editTarget, setEditTarget] = useState<AdminNotificationTemplateItem | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const load = (q: AdminNotificationTemplateListQuery) => {
    setLoading(true);
    notificationApi
      .listTemplates(q)
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

  const columns: TableProps<AdminNotificationTemplateItem>["columns"] = [
    { title: "Code", dataIndex: "code", key: "code", width: 220,
      render: (v: string) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "标题模板", dataIndex: "titleTemplate", key: "titleTemplate", width: 240, ellipsis: true },
    { title: "等级", dataIndex: "level", key: "level", width: 100,
      render: (v: NotificationLevel) => <Tag color={LEVEL_META[v].color}>{LEVEL_META[v].label}</Tag> },
    { title: "Channels", dataIndex: "channels", key: "channels", width: 200,
      render: (v: NotificationChannel[]) => (
        <Space wrap size={[4, 4]}>
          {v.map((c) => <Tag key={c} color={CHANNEL_META[c].color}>{CHANNEL_META[c].label}</Tag>)}
        </Space>
      ) },
    { title: "启用", dataIndex: "enabled", key: "enabled", width: 80,
      render: (v: boolean) => v
        ? <Tag color="success">✅ 启用</Tag>
        : <Tag color="default">❌ 禁用</Tag> },
    { title: "描述", dataIndex: "description", key: "description", ellipsis: true,
      render: (v: string | null) => v ?? "—" },
    { title: "操作", key: "actions", width: 160, fixed: "right",
      render: (_, record) => {
        const builtin = isBuiltIn(record.code);
        return (
          <Space>
            <Button size="small" onClick={() => setEditTarget(record)}>编辑</Button>
            {builtin ? (
              <Tooltip title="内置模板不可删除">
                <Button size="small" danger disabled>删除</Button>
              </Tooltip>
            ) : (
              <Button size="small" danger onClick={() => handleDelete(record.code)}>删除</Button>
            )}
          </Space>
        );
      } },
  ];

  function handleDelete(code: string) {
    Modal.confirm({
      title: `确认软删除模板 ${code}？`,
      content: "删除后 producer 调用 send() 时该模板将抛 90880。模板表行保留，仅 enabled=0。",
      okText: "确认删除",
      okType: "danger",
      cancelText: "取消",
      onOk: () =>
        notificationApi
          .deleteTemplate(code)
          .then(() => {
            message.success("已软删除");
            load(query);
          })
          .catch((err) => message.error(`删除失败 ${err.code ?? ""}：${err.message ?? ""}`)),
    });
  }

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>通知模板管理</Title>
        <Button type="primary" onClick={() => setCreateOpen(true)}>+ 新建模板</Button>
      </div>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={(v: AdminNotificationTemplateListQuery) => setQuery({ ...query, ...v, page: 1 })}>
          <Form.Item name="enabled" label="启用">
            <Select allowClear style={{ width: 120 }} placeholder="全部"
              options={[{ value: 1, label: "已启用" }, { value: 0, label: "已禁用" }]} />
          </Form.Item>
          <Form.Item name="level" label="等级">
            <Select allowClear style={{ width: 140 }} placeholder="全部"
              options={[
                { value: 1, label: "INFO" },
                { value: 2, label: "WARN" },
                { value: 3, label: "CRITICAL" },
              ]} />
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
        rowKey="code"
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
      <TemplateUpsertDrawer
        mode="edit"
        open={editTarget != null}
        initial={editTarget}
        onClose={() => setEditTarget(null)}
        onSuccess={() => {
          setEditTarget(null);
          load(query);
        }}
      />
      <TemplateUpsertDrawer
        mode="create"
        open={createOpen}
        initial={null}
        onClose={() => setCreateOpen(false)}
        onSuccess={() => {
          setCreateOpen(false);
          load(query);
        }}
      />
    </div>
  );
}

interface UpsertDrawerProps {
  mode: "create" | "edit";
  open: boolean;
  initial: AdminNotificationTemplateItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

function TemplateUpsertDrawer({ mode, open, initial, onClose, onSuccess }: UpsertDrawerProps) {
  const [code, setCode] = useState("");
  const [titleTemplate, setTitleTemplate] = useState("");
  const [bodyTemplate, setBodyTemplate] = useState("");
  const [level, setLevel] = useState<NotificationLevel>("INFO");
  const [channels, setChannels] = useState<NotificationChannel[]>(["IN_APP"]);
  const [description, setDescription] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open && initial) {
      setCode(initial.code);
      setTitleTemplate(initial.titleTemplate);
      setBodyTemplate(initial.bodyTemplate);
      setLevel(initial.level);
      setChannels(initial.channels);
      setDescription(initial.description ?? "");
      setEnabled(initial.enabled);
    } else if (open && !initial) {
      setCode("");
      setTitleTemplate("");
      setBodyTemplate("");
      setLevel("INFO");
      setChannels(["IN_APP"]);
      setDescription("");
      setEnabled(true);
    }
  }, [open, initial]);

  const placeholders = extractPlaceholders(titleTemplate, bodyTemplate);
  const codeValid = mode === "edit" || /^[A-Z][A-Z0-9_]{2,63}$/.test(code);

  function handleSubmit() {
    if (mode === "create" && !codeValid) {
      message.warning("Code 不符合 ^[A-Z][A-Z0-9_]{2,63}$ 格式");
      return;
    }
    if (!titleTemplate.trim() || !bodyTemplate.trim()) {
      message.warning("标题模板和 Body 模板必填");
      return;
    }
    if (channels.length === 0) {
      message.warning("至少选择 1 个 Channel");
      return;
    }
    const req: AdminNotificationTemplateUpsertRequest = {
      code, titleTemplate, bodyTemplate, level, channels,
      description: description.trim() || undefined,
      enabled,
    };
    setSubmitting(true);
    const promise = mode === "create"
      ? notificationApi.createTemplate(req)
      : notificationApi.updateTemplate(code, req);
    promise
      .then(() => {
        message.success(mode === "create" ? "模板已新建" : "模板已更新");
        onSuccess();
      })
      .catch((err) => message.error(`保存失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setSubmitting(false));
  }

  return (
    <Drawer
      open={open}
      title={mode === "create" ? "新建通知模板" : `编辑模板 ${initial?.code ?? ""}`}
      width={560}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: "right" }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={submitting} onClick={handleSubmit}>保存</Button>
          </Space>
        </div>
      }
    >
      <Form layout="vertical">
        <Form.Item label="Code" required validateStatus={mode === "create" && code && !codeValid ? "error" : ""}
          help={mode === "create" ? "正则 ^[A-Z][A-Z0-9_]{2,63}$，全大写 + 下划线 + 数字" : "PK 不可修改"}>
          <Input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())}
            disabled={mode === "edit"} placeholder="CUSTOM_PROMO_001" />
        </Form.Item>
        <Form.Item label="标题模板" required>
          <Input value={titleTemplate} onChange={(e) => setTitleTemplate(e.target.value)}
            placeholder="活动通知: ${title}" />
        </Form.Item>
        <Form.Item label="Body 模板" required>
          <Input.TextArea rows={5} value={bodyTemplate} onChange={(e) => setBodyTemplate(e.target.value)}
            placeholder="${body}" />
        </Form.Item>
        <Form.Item label="等级" required>
          <Select value={level} onChange={(v) => setLevel(v as NotificationLevel)}
            options={[
              { value: "INFO", label: "INFO" },
              { value: "WARN", label: "WARN" },
              { value: "CRITICAL", label: "CRITICAL" },
            ]} />
        </Form.Item>
        <Form.Item label="Channels" required>
          <Checkbox.Group
            value={channels}
            onChange={(v) => setChannels(v as NotificationChannel[])}
            options={ALL_CHANNELS.map((c) => ({ value: c, label: c }))} />
        </Form.Item>
        <Form.Item label="描述">
          <Input value={description} onChange={(e) => setDescription(e.target.value)} />
        </Form.Item>
        <Form.Item>
          <Checkbox checked={enabled} onChange={(e) => setEnabled(e.target.checked)}>启用</Checkbox>
        </Form.Item>
        {placeholders.length > 0 && (
          <Card size="small" style={{ background: "var(--fx-console-bg-subtle, #fafafa)" }}>
            <Text type="secondary">占位符检测：</Text>
            <Space wrap size={[4, 4]} style={{ marginLeft: 8 }}>
              {placeholders.map((p) => <Tag key={p}>${`{${p}}`}</Tag>)}
            </Space>
            <div style={{ marginTop: 8, color: "var(--fx-console-text-muted, #888)", fontSize: 12 }}>
              ⚠️ producer 调用时未提供的占位符将保留原样
            </div>
          </Card>
        )}
      </Form>
    </Drawer>
  );
}
