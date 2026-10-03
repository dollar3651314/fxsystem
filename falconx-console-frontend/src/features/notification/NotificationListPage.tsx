import { useEffect, useState } from "react";
import {
  Button,
  Card,
  Checkbox,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import {
  LEVEL_META,
  STATUS_META,
  extractPlaceholders,
  type AdminNotificationItem,
  type AdminNotificationListQuery,
  type AdminNotificationTemplateItem,
  type NotificationLevel,
  type NotificationStatus,
} from "./types";
import { notificationApi } from "./notificationApi";
import { UserCell } from "../../components/UserCell";

const { Title } = Typography;

export function NotificationListPage() {
  const [items, setItems] = useState<AdminNotificationItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminNotificationListQuery>({ page: 1, size: 20 });
  const [detailTarget, setDetailTarget] = useState<AdminNotificationItem | null>(null);
  const [sendOpen, setSendOpen] = useState(false);

  const permissions = useAdminAuthStore((s) => s.permissions);
  const isSuperAdmin = useAdminAuthStore((s) => s.isSuperAdmin);
  const canSend = isSuperAdmin || permissions.includes("notification:send");

  const load = (q: AdminNotificationListQuery) => {
    setLoading(true);
    notificationApi
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

  const columns: TableProps<AdminNotificationItem>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id", width: 160,
      render: (v: string) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "用户", dataIndex: "userId", key: "userId", width: 200,
      render: (_: unknown, r: AdminNotificationItem) => <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} /> },
    { title: "类型", dataIndex: "type", key: "type", width: 200 },
    { title: "模板码", dataIndex: "templateCode", key: "templateCode", width: 180,
      render: (v: string | null) => v ?? "—" },
    { title: "等级", dataIndex: "level", key: "level", width: 100,
      render: (v: NotificationLevel) =>
        <Tag color={LEVEL_META[v].color}>{LEVEL_META[v].label}</Tag> },
    { title: "标题", dataIndex: "title", key: "title", ellipsis: true },
    { title: "状态", dataIndex: "status", key: "status", width: 90,
      render: (v: NotificationStatus) =>
        <Tag color={STATUS_META[v].color}>{STATUS_META[v].label}</Tag> },
    { title: "创建于", dataIndex: "createdAt", key: "createdAt", width: 160 },
    { title: "操作", key: "actions", width: 80, fixed: "right",
      render: (_, record) => (
        <Button size="small" onClick={() => setDetailTarget(record)}>详情</Button>
      ) },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>用户通知列表</Title>
        <Button type="primary" disabled={!canSend}
          title={canSend ? "" : "缺 notification:send 权限"}
          onClick={() => setSendOpen(true)}>
          + 手动发送通知
        </Button>
      </div>
      <Card style={{ marginBottom: 16 }}>
        <Form
          layout="inline"
          onFinish={(v: AdminNotificationListQuery) => setQuery({ ...query, ...v, page: 1 })}
        >
          <Form.Item name="userId" label="User ID">
            <Input placeholder="可选" style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="type" label="类型">
            <Input placeholder="如 PRICE_ALERT_TRIGGERED" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="templateCode" label="模板码">
            <Input placeholder="可选" style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="level" label="等级">
            <Select allowClear style={{ width: 140 }} placeholder="全部"
              options={[
                { value: 1, label: "INFO" },
                { value: 2, label: "WARN" },
                { value: 3, label: "CRITICAL" },
              ]} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear style={{ width: 120 }} placeholder="全部"
              options={[
                { value: 0, label: "UNREAD" },
                { value: 1, label: "READ" },
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
      <NotificationDetailDrawer target={detailTarget} onClose={() => setDetailTarget(null)} />
      <SendNotificationModal
        open={sendOpen}
        onClose={() => setSendOpen(false)}
        onSuccess={() => {
          setSendOpen(false);
          load(query);
        }}
      />
    </div>
  );
}

interface DetailProps {
  target: AdminNotificationItem | null;
  onClose: () => void;
}

function NotificationDetailDrawer({ target, onClose }: DetailProps) {
  return (
    <Drawer open={target != null} width={600} onClose={onClose} title="通知详情">
      {target && (
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label="ID"><code>{target.id}</code></Descriptions.Item>
          <Descriptions.Item label="User ID"><code>{target.userId}</code></Descriptions.Item>
          <Descriptions.Item label="类型">{target.type}</Descriptions.Item>
          <Descriptions.Item label="模板码">{target.templateCode ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="等级">
            <Tag color={LEVEL_META[target.level].color}>{LEVEL_META[target.level].label}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={STATUS_META[target.status].color}>{STATUS_META[target.status].label}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="标题">{target.title}</Descriptions.Item>
          <Descriptions.Item label="正文">
            <div style={{ whiteSpace: "pre-wrap" }}>{target.body}</div>
          </Descriptions.Item>
          <Descriptions.Item label="关联">
            {target.relatedKey ?? "—"}{target.relatedId ? ` / ${target.relatedId}` : ""}
          </Descriptions.Item>
          <Descriptions.Item label="Payload">
            {target.payloadJson
              ? <pre style={{ fontSize: 12, margin: 0 }}>{target.payloadJson}</pre>
              : "—"}
          </Descriptions.Item>
          <Descriptions.Item label="创建于">{target.createdAt}</Descriptions.Item>
          <Descriptions.Item label="读取于">{target.readAt ?? "—"}</Descriptions.Item>
        </Descriptions>
      )}
    </Drawer>
  );
}

interface SendProps {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

function SendNotificationModal({ open, onClose, onSuccess }: SendProps) {
  const [userId, setUserId] = useState("");
  const [templates, setTemplates] = useState<AdminNotificationTemplateItem[]>([]);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [params, setParams] = useState<Record<string, string>>({});
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) {
      // 加载启用中的模板列表（最多 100 个）
      notificationApi
        .listTemplates({ enabled: 1, page: 1, size: 100 })
        .then((data) => setTemplates(data.items))
        .catch(() => setTemplates([]));
      // reset 表单
      setUserId("");
      setSelectedCode(null);
      setParams({});
      setReason("");
      setConfirmed(false);
    }
  }, [open]);

  const selectedTemplate = templates.find((t) => t.code === selectedCode) ?? null;
  const placeholders = selectedTemplate
    ? extractPlaceholders(selectedTemplate.titleTemplate, selectedTemplate.bodyTemplate)
    : [];

  function handleSubmit() {
    if (!userId.trim() || !/^\d+$/.test(userId.trim())) {
      message.warning("User ID 必须是雪花 ID 数字");
      return;
    }
    if (!selectedCode) {
      message.warning("请选择模板");
      return;
    }
    if (!reason.trim() || reason.trim().length < 10) {
      message.warning("Reason 必填且至少 10 字符");
      return;
    }
    if (!confirmed) {
      message.warning("请勾选确认 checkbox");
      return;
    }
    setSubmitting(true);
    notificationApi
      .send({
        userId: userId.trim(),
        templateCode: selectedCode,
        params,
        reason: reason.trim(),
      })
      .then((item) => {
        message.success(`已发送，通知 ID #${item.id}`);
        onSuccess();
      })
      .catch((err) => message.error(`发送失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setSubmitting(false));
  }

  return (
    <Modal
      open={open}
      title="手动发送通知"
      width={600}
      okText="确认发送"
      cancelText="取消"
      onOk={handleSubmit}
      onCancel={onClose}
      confirmLoading={submitting}
      destroyOnClose
    >
      <Form layout="vertical">
        <Form.Item label="User ID" required help="雪花 ID 数字字符串">
          <Input value={userId} onChange={(e) => setUserId(e.target.value)}
            placeholder="2000001" />
        </Form.Item>
        <Form.Item label="Template" required>
          <Select
            value={selectedCode}
            onChange={(v) => { setSelectedCode(v); setParams({}); }}
            placeholder="选择启用中的模板"
            options={templates.map((t) => ({
              value: t.code,
              label: `${t.code} — ${t.titleTemplate}`,
            }))}
            showSearch
            optionFilterProp="label"
          />
        </Form.Item>
        {selectedTemplate && (
          <Card size="small" style={{ marginBottom: 12, background: "var(--fx-surface-2)", border: "1px solid var(--fx-hairline)" }}>
            <div style={{ fontSize: 12, color: "var(--fx-muted)" }}>标题：{selectedTemplate.titleTemplate}</div>
            <div style={{ fontSize: 12, color: "var(--fx-muted)", marginTop: 4 }}>Body：{selectedTemplate.bodyTemplate}</div>
          </Card>
        )}
        {placeholders.length > 0 && (
          <Form.Item label="Params">
            <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              {placeholders.map((p) => (
                <div key={p} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <code style={{ minWidth: 120 }}>${`{${p}}`}</code>
                  <Input
                    value={params[p] ?? ""}
                    onChange={(e) => setParams({ ...params, [p]: e.target.value })}
                    placeholder={`${p} 的值`}
                  />
                </div>
              ))}
            </div>
          </Form.Item>
        )}
        <Form.Item label="Reason（审计字段）" required
          help="≥ 10 字符；将写入 t_admin_operation_log.reason">
          <Input.TextArea rows={2} value={reason} onChange={(e) => setReason(e.target.value)}
            placeholder="运营测试模板渲染 / 紧急通知用户..." />
        </Form.Item>
        <Form.Item>
          <Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)}>
            我确认此操作将向目标用户发送 IN_APP 通知（且不可撤回）
          </Checkbox>
        </Form.Item>
      </Form>
    </Modal>
  );
}
