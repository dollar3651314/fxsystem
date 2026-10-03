import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tree,
  Tooltip,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import type { DataNode } from "antd/es/tree";
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { adminRolesApi } from "./adminRolesApi";
import { adminPermissionsApi } from "./adminPermissionsApi";
import type {
  AdminPermissionListItem,
  AdminRoleListItem,
  AdminRoleListQuery,
  SnowflakeId,
} from "./types";

const { Title, Text } = Typography;
const SUPER_ADMIN_ROLE_ID: SnowflakeId = "1";

interface RoleFormValues {
  code: string;
  name: string;
  description?: string;
}

export function AdminRolesPage() {
  const [items, setItems] = useState<AdminRoleListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminRoleListQuery>({ page: 0, size: 20 });

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<AdminRoleListItem | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<RoleFormValues>();

  const [permsRole, setPermsRole] = useState<AdminRoleListItem | null>(null);
  const [allPermissions, setAllPermissions] = useState<AdminPermissionListItem[]>([]);
  const [originalChecked, setOriginalChecked] = useState<string[]>([]);
  const [checkedCodes, setCheckedCodes] = useState<string[]>([]);
  const [permsReason, setPermsReason] = useState("");
  const [permsSubmitting, setPermsSubmitting] = useState(false);

  const load = (q: AdminRoleListQuery) => {
    setLoading(true);
    adminRolesApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载角色失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  useEffect(() => {
    void adminPermissionsApi.list({ size: 200 }).then((d) => setAllPermissions(d.items));
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setDrawerOpen(true);
  };

  const openEdit = (record: AdminRoleListItem) => {
    setEditing(record);
    form.setFieldsValue({ code: record.code, name: record.name, description: record.description ?? "" });
    setDrawerOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await adminRolesApi.update(editing.id, { name: values.name, description: values.description });
        message.success("角色已更新");
      } else {
        await adminRolesApi.create({
          code: values.code,
          name: values.name,
          description: values.description,
        });
        message.success("角色已新建");
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

  const handleDelete = async (record: AdminRoleListItem) => {
    try {
      await adminRolesApi.remove(record.id);
      message.success("角色已删除");
      load(query);
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`删除失败 ${e.code ?? ""}：${e.message ?? ""}`);
    }
  };

  const openPerms = async (record: AdminRoleListItem) => {
    setPermsRole(record);
    setPermsReason("");
    try {
      const data = await adminRolesApi.getPermissions(record.id);
      setOriginalChecked(data.permissionCodes);
      setCheckedCodes(data.permissionCodes);
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`加载角色权限失败 ${e.code ?? ""}：${e.message ?? ""}`);
    }
  };

  const submitPerms = async () => {
    if (!permsRole) return;
    setPermsSubmitting(true);
    try {
      await adminRolesApi.assignPermissions(permsRole.id, {
        permissionCodes: checkedCodes,
        reason: permsReason,
      });
      message.success("权限已保存");
      setPermsRole(null);
      load(query);
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`保存失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setPermsSubmitting(false);
    }
  };

  const handleSearch = (values: AdminRoleListQuery) => {
    setQuery({ ...query, ...values, page: 0 });
  };

  // 按 module 分组的 Tree 数据
  const permissionTree: DataNode[] = useMemo(() => {
    const byModule = new Map<string, AdminPermissionListItem[]>();
    for (const p of allPermissions) {
      if (!byModule.has(p.module)) byModule.set(p.module, []);
      byModule.get(p.module)!.push(p);
    }
    return Array.from(byModule.entries()).map(([mod, perms]) => ({
      title: <span style={{ fontWeight: 600 }}>{mod}（{perms.length}）</span>,
      key: `module:${mod}`,
      selectable: false,
      children: perms.map((p) => ({
        title: (
          <Space size={4}>
            <code style={{ fontFamily: "monospace" }}>{p.code}</code>
            <span style={{ color: "#888" }}>{p.description ?? ""}</span>
            {p.isHighRisk && <Tag color="red" icon={<WarningOutlined />}>高风险</Tag>}
          </Space>
        ),
        key: p.code,
      })),
    }));
  }, [allPermissions]);

  const diff = useMemo(() => {
    const orig = new Set(originalChecked);
    const curr = new Set(checkedCodes);
    const added = checkedCodes.filter((c) => !orig.has(c));
    const removed = originalChecked.filter((c) => !curr.has(c));
    return { added, removed };
  }, [originalChecked, checkedCodes]);

  const columns: TableProps<AdminRoleListItem>["columns"] = [
    {
      title: "编码",
      dataIndex: "code",
      key: "code",
      width: 160,
      render: (c: string) => <code style={{ fontFamily: "monospace" }}>{c}</code>,
    },
    { title: "名称", dataIndex: "name", key: "name", width: 160 },
    { title: "描述", dataIndex: "description", key: "description", ellipsis: true },
    {
      title: "类型",
      dataIndex: "isSystem",
      key: "isSystem",
      width: 110,
      render: (s: boolean) => (s ? <Tag color="blue">系统内置</Tag> : <Tag>自定义</Tag>),
    },
    { title: "成员数", dataIndex: "memberCount", key: "memberCount", width: 90, align: "center" },
    { title: "权限数", dataIndex: "permissionCount", key: "permissionCount", width: 90, align: "center" },
    {
      title: "操作",
      key: "actions",
      width: 280,
      render: (_, record) => (
        <Space size={4}>
          <Tooltip title={record.id === SUPER_ADMIN_ROLE_ID ? "SUPER_ADMIN 权限不可通过界面修改" : ""}>
            <Button
              size="small"
              icon={<SafetyCertificateOutlined />}
              disabled={record.id === SUPER_ADMIN_ROLE_ID}
              onClick={() => openPerms(record)}
            >
              分配权限
            </Button>
          </Tooltip>
          <Tooltip title={record.isSystem ? "系统内置角色不可编辑" : ""}>
            <Button size="small" icon={<EditOutlined />} disabled={record.isSystem} onClick={() => openEdit(record)}>
              编辑
            </Button>
          </Tooltip>
          <Tooltip title={record.isSystem ? "系统内置角色不可删除" : ""}>
            <Popconfirm
              disabled={record.isSystem}
              title={`确认删除角色 ${record.code}？`}
              description="有成员的角色无法删除"
              onConfirm={() => handleDelete(record)}
            >
              <Button size="small" danger icon={<DeleteOutlined />} disabled={record.isSystem}>
                删除
              </Button>
            </Popconfirm>
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>角色管理</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="code" label="编码">
            <Input placeholder="精确" allowClear />
          </Form.Item>
          <Form.Item name="name" label="名称">
            <Input placeholder="模糊" allowClear />
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
          新建角色
        </Button>
      </Space>
      <Table<AdminRoleListItem>
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
        title={editing ? `编辑角色 - ${editing.code}` : "新建角色"}
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
        <Form<RoleFormValues> layout="vertical" form={form}>
          <Form.Item
            name="code"
            label="角色编码"
            rules={[
              { required: true, message: "必填" },
              { pattern: /^[A-Z0-9_]{2,32}$/, message: "大写字母/数字/下划线 2-32 字符" },
            ]}
          >
            <Input disabled={!!editing} placeholder="如 FINANCE / KYC_REVIEWER" />
          </Form.Item>
          <Form.Item name="name" label="角色名称" rules={[{ required: true, message: "必填" }]}>
            <Input placeholder="如 财务" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} maxLength={255} showCount placeholder="该角色的职责说明" />
          </Form.Item>
        </Form>
      </Drawer>

      {/* 权限分配 Drawer */}
      <Drawer
        title={`分配权限 - ${permsRole?.code ?? ""}`}
        open={!!permsRole}
        width={720}
        onClose={() => setPermsRole(null)}
        extra={
          <Space>
            <Button onClick={() => setPermsRole(null)}>取消</Button>
            <Button
              type="primary"
              danger
              loading={permsSubmitting}
              disabled={permsReason.length < 10 || (diff.added.length === 0 && diff.removed.length === 0)}
              onClick={submitPerms}
            >
              确认保存
            </Button>
          </Space>
        }
      >
        <Alert
          type="warning"
          showIcon
          message={`高风险操作：权限分配立即生效，影响该角色下 ${permsRole?.memberCount ?? 0} 名成员。`}
          style={{ marginBottom: 16 }}
        />
        <div style={{ marginBottom: 12 }}>
          <Text strong>已选权限：</Text>
          {checkedCodes.length} / 共 {allPermissions.length} 个
          <Space style={{ marginLeft: 16 }}>
            <Button size="small" onClick={() => setCheckedCodes(allPermissions.map((p) => p.code))}>
              全选
            </Button>
            <Button size="small" onClick={() => setCheckedCodes([])}>
              清空
            </Button>
            <Button size="small" onClick={() => setCheckedCodes(originalChecked)}>
              恢复
            </Button>
          </Space>
        </div>
        <Tree
          checkable
          checkedKeys={checkedCodes}
          onCheck={(keys) => {
            const arr = Array.isArray(keys) ? keys : keys.checked;
            setCheckedCodes(
              arr.map(String).filter((k) => !k.startsWith("module:")),
            );
          }}
          treeData={permissionTree}
          defaultExpandAll
          style={{ background: "var(--fx-surface-2)", padding: 12, borderRadius: 6, border: "1px solid var(--fx-hairline)" }}
        />
        {(diff.added.length > 0 || diff.removed.length > 0) && (
          <div style={{ marginTop: 16, padding: 12, background: "rgba(255, 184, 77, 0.08)", borderRadius: 6, border: "1px solid rgba(255, 184, 77, 0.32)" }}>
            <div>
              <Text strong>变更预览：</Text>
              {diff.added.length > 0 && <span> 新增 {diff.added.length} 项</span>}
              {diff.removed.length > 0 && <span style={{ marginLeft: 12 }}>移除 {diff.removed.length} 项</span>}
            </div>
            <Space wrap style={{ marginTop: 8 }}>
              {diff.added.map((c) => (
                <Tag color="green" key={`a-${c}`}>+{c}</Tag>
              ))}
              {diff.removed.map((c) => (
                <Tag color="red" key={`r-${c}`}>-{c}</Tag>
              ))}
            </Space>
          </div>
        )}
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item label="操作原因（必填，≥10 字符）" extra={`已输入 ${permsReason.length} 字符`}>
            <Input.TextArea rows={3} value={permsReason} onChange={(e) => setPermsReason(e.target.value)} />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
}
