import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined,
  UnlockOutlined,
} from "@ant-design/icons";
import { adminUsersApi } from "./adminUsersApi";
import { adminRolesApi } from "./adminRolesApi";
import type {
  AdminUserListItem,
  AdminUserListQuery,
  AdminUserStatus,
  AdminRoleListItem,
  SnowflakeId,
} from "./types";

const { Title, Text } = Typography;

const STATUS_COLOR: Record<AdminUserStatus, string> = { ACTIVE: "green", DISABLED: "red" };
const SUPER_ADMIN_ROLE_ID: SnowflakeId = "1";

interface UserFormValues {
  username: string;
  realName?: string;
  roleIds: SnowflakeId[];
  password?: string;
  mustChangePassword: boolean;
}

export function AdminUsersPage() {
  const [items, setItems] = useState<AdminUserListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminUserListQuery>({ page: 0, size: 20 });
  const [allRoles, setAllRoles] = useState<AdminRoleListItem[]>([]);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<AdminUserListItem | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<UserFormValues>();

  const [resetTarget, setResetTarget] = useState<AdminUserListItem | null>(null);
  const [resetMode, setResetMode] = useState<"auto" | "manual">("auto");
  const [resetReason, setResetReason] = useState("");
  const [resetPassword, setResetPassword] = useState("");
  const [resetForce, setResetForce] = useState(true);
  const [resetSubmitting, setResetSubmitting] = useState(false);
  const [resetResult, setResetResult] = useState<string | null>(null);

  const [deleteTarget, setDeleteTarget] = useState<AdminUserListItem | null>(null);
  const [deleteReason, setDeleteReason] = useState("");
  const [deleteSubmitting, setDeleteSubmitting] = useState(false);

  const [generatedPassword, setGeneratedPassword] = useState<{
    username: string;
    password: string;
  } | null>(null);

  const load = (q: AdminUserListQuery) => {
    setLoading(true);
    adminUsersApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载管理员失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  useEffect(() => {
    void adminRolesApi.list({ size: 100 }).then((d) => setAllRoles(d.items));
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      username: "",
      realName: "",
      roleIds: [],
      password: "",
      mustChangePassword: true,
    });
    setDrawerOpen(true);
  };

  const openEdit = (record: AdminUserListItem) => {
    setEditing(record);
    form.setFieldsValue({
      username: record.username,
      realName: record.realName ?? "",
      roleIds: record.roles.map((r) => r.id),
      password: "",
      mustChangePassword: record.mustChangePassword,
    });
    setDrawerOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await adminUsersApi.update(editing.id, {
          realName: values.realName,
          roleIds: values.roleIds,
        });
        message.success("管理员已更新");
      } else {
        const resp = await adminUsersApi.create({
          username: values.username,
          realName: values.realName,
          roleIds: values.roleIds,
          password: values.password ? values.password : null,
          mustChangePassword: values.mustChangePassword,
        });
        message.success("管理员已新建");
        if (resp.generatedPassword) {
          setGeneratedPassword({ username: resp.username, password: resp.generatedPassword });
        }
      }
      setDrawerOpen(false);
      load(query);
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`提交失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSubmitting(false);
    }
  };

  const handleToggleStatus = async (record: AdminUserListItem) => {
    try {
      if (record.status === "ACTIVE") {
        await adminUsersApi.disable(record.id, `运营禁用 ${record.username} (R7 浏览器 QA 验证)`);
      } else {
        await adminUsersApi.enable(record.id);
      }
      message.success(record.status === "ACTIVE" ? "已禁用" : "已启用");
      load(query);
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`操作失败 ${e.code ?? ""}：${e.message ?? ""}`);
    }
  };

  const submitResetPassword = async () => {
    if (!resetTarget) return;
    setResetSubmitting(true);
    try {
      const resp = await adminUsersApi.resetPassword(resetTarget.id, {
        password: resetMode === "manual" ? resetPassword : null,
        forceChangePassword: resetForce,
        reason: resetReason,
      });
      message.success("密码已重置");
      if (resp.generatedPassword) {
        setResetResult(resp.generatedPassword);
      } else {
        setResetTarget(null);
        setResetReason("");
        setResetPassword("");
      }
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`重置失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setResetSubmitting(false);
    }
  };

  const submitDelete = async () => {
    if (!deleteTarget) return;
    setDeleteSubmitting(true);
    try {
      await adminUsersApi.remove(deleteTarget.id);
      message.success("管理员已删除");
      setDeleteTarget(null);
      setDeleteReason("");
      load(query);
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`删除失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setDeleteSubmitting(false);
    }
  };

  const handleSearch = (values: { username?: string; realName?: string; status?: AdminUserStatus }) => {
    setQuery({ ...query, ...values, page: 0 });
  };

  const columns: TableProps<AdminUserListItem>["columns"] = [
    {
      title: "用户名",
      dataIndex: "username",
      key: "username",
      width: 140,
      render: (u: string) => <code>{u}</code>,
    },
    {
      title: "真实姓名",
      dataIndex: "realName",
      key: "realName",
      width: 120,
      render: (n: string | null) => n ?? <span style={{ color: "#bbb" }}>-</span>,
    },
    {
      title: "角色",
      dataIndex: "roles",
      key: "roles",
      width: 220,
      render: (roles: AdminUserListItem["roles"]) =>
        roles.length === 0 ? null : (
          <Space size={4} wrap>
            {roles.slice(0, 2).map((r) => (
              <Tag key={r.id} color="blue">
                {r.code}
              </Tag>
            ))}
            {roles.length > 2 && (
              <Tooltip title={roles.slice(2).map((r) => r.code).join(", ")}>
                <Tag>+{roles.length - 2}</Tag>
              </Tooltip>
            )}
          </Space>
        ),
    },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 100,
      render: (s: AdminUserStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    {
      title: "最近登录",
      dataIndex: "lastLoginAt",
      key: "lastLoginAt",
      width: 200,
      render: (t: string | null, record) =>
        t ? (
          <div style={{ lineHeight: 1.3 }}>
            <div>{t}</div>
            <div style={{ color: "#888", fontSize: 12 }}>{record.lastLoginIp ?? "-"}</div>
          </div>
        ) : (
          <span style={{ color: "#bbb" }}>从未登录</span>
        ),
    },
    {
      title: "操作",
      key: "actions",
      width: 280,
      render: (_, record) => (
        <Space size={4}>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Popconfirm
            title={record.status === "ACTIVE" ? "禁用此管理员？" : "启用此管理员？"}
            description={
              record.status === "ACTIVE"
                ? "禁用后该账号 refresh token 立即撤销"
                : undefined
            }
            onConfirm={() => handleToggleStatus(record)}
          >
            <Button
              size="small"
              icon={record.status === "ACTIVE" ? <StopOutlined /> : <UnlockOutlined />}
              danger={record.status === "ACTIVE"}
            >
              {record.status === "ACTIVE" ? "禁用" : "启用"}
            </Button>
          </Popconfirm>
          <Button
            size="small"
            icon={<KeyOutlined />}
            onClick={() => {
              setResetTarget(record);
              setResetMode("auto");
              setResetReason("");
              setResetPassword("");
              setResetForce(true);
              setResetResult(null);
            }}
          >
            改密
          </Button>
          <Button
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => {
              setDeleteTarget(record);
              setDeleteReason("");
            }}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>管理员管理</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="username" label="用户名">
            <Input allowClear placeholder="模糊匹配" />
          </Form.Item>
          <Form.Item name="realName" label="真实姓名">
            <Input allowClear placeholder="模糊匹配" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              style={{ minWidth: 140 }}
              options={[
                { value: "ACTIVE", label: "ACTIVE" },
                { value: "DISABLED", label: "DISABLED" },
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                搜索
              </Button>
              <Button icon={<ReloadOutlined />} onClick={() => setQuery({ page: 0, size: 20 })}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建管理员
        </Button>
      </Space>
      <Table<AdminUserListItem>
        columns={columns}
        dataSource={items}
        rowKey="id"
        loading={loading}
        scroll={{ x: true }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (t) => `共 ${t} 条`,
          onChange: (newPage, newSize) => setQuery({ ...query, page: newPage - 1, size: newSize }),
        }}
      />

      <Drawer
        title={editing ? `编辑管理员 - ${editing.username}` : "新建管理员"}
        open={drawerOpen}
        width={520}
        onClose={() => setDrawerOpen(false)}
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" loading={submitting} onClick={handleSubmit}>
              提交
            </Button>
          </Space>
        }
      >
        <Form<UserFormValues> layout="vertical" form={form}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: "必填" },
              { pattern: /^[a-zA-Z0-9_]{3,32}$/, message: "字母/数字/下划线 3-32 字符" },
            ]}
          >
            <Input disabled={!!editing} placeholder="如 alice" />
          </Form.Item>
          <Form.Item name="realName" label="真实姓名">
            <Input placeholder="如 张三" />
          </Form.Item>
          <Form.Item name="roleIds" label="角色" rules={[{ required: true, message: "至少选一个角色" }]}>
            <Select
              mode="multiple"
              placeholder="不可选 SUPER_ADMIN（系统内置）"
              options={allRoles
                .filter((r) => r.id !== SUPER_ADMIN_ROLE_ID)
                .map((r) => ({ value: r.id, label: `${r.code} - ${r.name}` }))}
            />
          </Form.Item>
          {!editing && (
            <>
              <Form.Item
                name="password"
                label="初始密码"
                extra="留空 = 自动生成 16 位强密码（仅本次响应可见）"
              >
                <Input.Password placeholder="留空自动生成" />
              </Form.Item>
              <Form.Item name="mustChangePassword" label="首次登录强制改密" valuePropName="checked">
                <Switch />
              </Form.Item>
            </>
          )}
        </Form>
      </Drawer>

      {/* 自动生成密码展示 Modal */}
      <Modal
        open={!!generatedPassword}
        title="管理员已创建 - 请立即记录初始密码"
        onCancel={() => setGeneratedPassword(null)}
        onOk={() => setGeneratedPassword(null)}
        okText="我已记录"
        cancelButtonProps={{ style: { display: "none" } }}
        maskClosable={false}
      >
        <Alert
          type="warning"
          showIcon
          message="此密码仅展示一次，请立即记录并通过安全渠道告知该管理员；本次后无法再查询。"
          style={{ marginBottom: 16 }}
        />
        <p>
          <Text strong>用户名：</Text>
          {generatedPassword?.username}
        </p>
        <p>
          <Text strong>初始密码：</Text>
          <Text code copyable={{ text: generatedPassword?.password }}>
            {generatedPassword?.password}
          </Text>
        </p>
      </Modal>

      {/* 重置密码 Modal */}
      <Modal
        open={!!resetTarget}
        title={`高风险操作 - 重置 ${resetTarget?.username} 密码`}
        onCancel={() => setResetTarget(null)}
        onOk={submitResetPassword}
        okText="确认重置"
        okType="danger"
        confirmLoading={resetSubmitting}
        okButtonProps={{
          disabled: resetReason.length < 10 || (resetMode === "manual" && resetPassword.length === 0) || !!resetResult,
        }}
        maskClosable={false}
      >
        {resetResult ? (
          <div>
            <Alert type="success" message="密码已重置成功，请立即记录新密码。" style={{ marginBottom: 12 }} />
            <p>
              <Text strong>新密码：</Text>
              <Text code copyable={{ text: resetResult, icon: <CopyOutlined /> }}>
                {resetResult}
              </Text>
            </p>
          </div>
        ) : (
          <Form layout="vertical">
            <Form.Item label="密码方式">
              <Radio.Group value={resetMode} onChange={(e) => setResetMode(e.target.value)}>
                <Radio value="auto">自动生成 16 位强密码</Radio>
                <Radio value="manual">手动设置</Radio>
              </Radio.Group>
            </Form.Item>
            {resetMode === "manual" && (
              <Form.Item label="新密码">
                <Input.Password value={resetPassword} onChange={(e) => setResetPassword(e.target.value)} />
              </Form.Item>
            )}
            <Form.Item label="强制下次登录修改" valuePropName="checked">
              <Switch checked={resetForce} onChange={setResetForce} />
            </Form.Item>
            <Form.Item label="操作原因（必填，≥10 字符）" extra={`已输入 ${resetReason.length} 字符`}>
              <Input.TextArea rows={3} value={resetReason} onChange={(e) => setResetReason(e.target.value)} />
            </Form.Item>
          </Form>
        )}
      </Modal>

      {/* 删除高风险 Modal */}
      <Modal
        open={!!deleteTarget}
        title={`高风险操作 - 删除管理员 ${deleteTarget?.username}`}
        onCancel={() => setDeleteTarget(null)}
        onOk={submitDelete}
        okText="确认删除"
        okType="danger"
        confirmLoading={deleteSubmitting}
        okButtonProps={{ disabled: deleteReason.length < 10 }}
        maskClosable={false}
      >
        <Alert
          type="warning"
          showIcon
          message="删除后操作日志保留 7 年，但该账号无法恢复。如仅需暂停使用，请使用「禁用」。"
          style={{ marginBottom: 16 }}
        />
        <p>
          <Text strong>用户 ID：</Text>
          {deleteTarget?.id}
          <br />
          <Text strong>用户名：</Text>
          {deleteTarget?.username}
          <br />
          <Text strong>真实姓名：</Text>
          {deleteTarget?.realName ?? "-"}
          <br />
          <Text strong>当前角色：</Text>
          {deleteTarget?.roles.map((r) => r.code).join(", ")}
        </p>
        <Form layout="vertical">
          <Form.Item label="操作原因（必填，≥10 字符）" extra={`已输入 ${deleteReason.length} 字符`}>
            <Input.TextArea rows={3} value={deleteReason} onChange={(e) => setDeleteReason(e.target.value)} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
