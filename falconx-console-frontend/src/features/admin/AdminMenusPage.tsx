import { useEffect, useState } from "react";
import {
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  TreeSelect,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import { adminMenusApi } from "./adminMenusApi";
import { adminPermissionsApi } from "./adminPermissionsApi";
import type {
  AdminMenuItem,
  AdminMenuSortDirection,
  AdminPermissionListItem,
  SnowflakeId,
} from "./types";

const { Title } = Typography;

interface MenuFormValues {
  parentId: SnowflakeId | null;
  code: string;
  name: string;
  icon: string;
  path: string;
  permissionCode: string;
  sortOrder: number;
  isVisible: boolean;
}

function flattenMenu(items: AdminMenuItem[], acc: AdminMenuItem[] = []) {
  for (const it of items) {
    acc.push(it);
    if (it.children?.length) flattenMenu(it.children, acc);
  }
  return acc;
}

function toTreeSelectData(items: AdminMenuItem[]): unknown[] {
  return items.map((it) => ({
    value: it.id,
    title: `${it.name} (${it.code})`,
    children: it.children?.length ? toTreeSelectData(it.children) : undefined,
  }));
}

export function AdminMenusPage() {
  const [items, setItems] = useState<AdminMenuItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<AdminMenuItem | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<MenuFormValues>();
  const [permissionOptions, setPermissionOptions] = useState<AdminPermissionListItem[]>([]);

  const load = () => {
    setLoading(true);
    adminMenusApi
      .list(true)
      .then((data) => setItems(data.items))
      .catch((err) => message.error(`加载菜单失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
    void adminPermissionsApi.list({ size: 200 }).then((d) => setPermissionOptions(d.items));
  }, []);

  const openCreate = (parentId: SnowflakeId | null) => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      parentId,
      sortOrder: 1,
      isVisible: true,
      code: "",
      name: "",
      icon: "",
      path: "",
      permissionCode: "",
    });
    setDrawerOpen(true);
  };

  const openEdit = (record: AdminMenuItem) => {
    setEditing(record);
    form.setFieldsValue({
      parentId: record.parentId,
      code: record.code,
      name: record.name,
      icon: record.icon ?? "",
      path: record.path ?? "",
      permissionCode: record.permissionCode ?? "",
      sortOrder: record.sortOrder,
      isVisible: record.isVisible,
    });
    setDrawerOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const body = {
        parentId: values.parentId ?? null,
        code: values.code,
        name: values.name,
        icon: values.icon || null,
        path: values.path || null,
        permissionCode: values.permissionCode,
        sortOrder: values.sortOrder,
        isVisible: values.isVisible,
      };
      if (editing) {
        await adminMenusApi.update(editing.id, body);
        message.success("菜单已更新");
      } else {
        await adminMenusApi.create(body);
        message.success("菜单已新建");
      }
      setDrawerOpen(false);
      load();
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`提交失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSubmitting(false);
    }
  };

  const handleSort = async (id: SnowflakeId, direction: AdminMenuSortDirection) => {
    try {
      await adminMenusApi.sort(id, direction);
      message.success(`已${direction === "up" ? "上移" : "下移"}`);
      load();
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`排序失败 ${e.code ?? ""}：${e.message ?? ""}`);
    }
  };

  const handleDelete = async (id: SnowflakeId) => {
    try {
      await adminMenusApi.remove(id);
      message.success("菜单已删除");
      load();
    } catch (err: unknown) {
      const e = err as { code?: string; message?: string };
      message.error(`删除失败 ${e.code ?? ""}：${e.message ?? ""}`);
    }
  };

  const flatMenus = flattenMenu(items);
  const parentOptions = toTreeSelectData(items);

  const columns: TableProps<AdminMenuItem>["columns"] = [
    {
      title: "编码",
      dataIndex: "code",
      key: "code",
      width: 180,
      render: (code: string) => <code style={{ fontFamily: "monospace" }}>{code}</code>,
    },
    { title: "名称", dataIndex: "name", key: "name", width: 160 },
    {
      title: "图标",
      dataIndex: "icon",
      key: "icon",
      width: 120,
      render: (icon: string | null) => icon || <span style={{ color: "#bbb" }}>-</span>,
    },
    {
      title: "路径",
      dataIndex: "path",
      key: "path",
      width: 200,
      render: (path: string | null) =>
        path ? <code style={{ fontFamily: "monospace" }}>{path}</code> : <span style={{ color: "#bbb" }}>-</span>,
    },
    {
      title: "权限码",
      dataIndex: "permissionCode",
      key: "permissionCode",
      width: 200,
      render: (code: string | null) => (code ? <Tag color="blue">{code}</Tag> : null),
    },
    { title: "排序", dataIndex: "sortOrder", key: "sortOrder", width: 80, align: "center" },
    {
      title: "可见",
      dataIndex: "isVisible",
      key: "isVisible",
      width: 80,
      align: "center",
      render: (v: boolean) => (v ? <Tag color="green">显示</Tag> : <Tag>隐藏</Tag>),
    },
    {
      title: "操作",
      key: "actions",
      width: 280,
      render: (_, record) => (
        <Space size={4}>
          <Button size="small" icon={<PlusOutlined />} onClick={() => openCreate(record.id)}>
            新增子菜单
          </Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Button size="small" icon={<ArrowUpOutlined />} onClick={() => handleSort(record.id, "up")} />
          <Button size="small" icon={<ArrowDownOutlined />} onClick={() => handleSort(record.id, "down")} />
          <Popconfirm title="确认删除该菜单？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>菜单管理</Title>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate(null)}>
          新建顶级菜单
        </Button>
        <Button icon={<ReloadOutlined />} onClick={() => load()}>
          刷新
        </Button>
        <span style={{ color: "#888" }}>共 {flatMenus.length} 条</span>
      </Space>
      <Table<AdminMenuItem>
        columns={columns}
        dataSource={items}
        rowKey="id"
        loading={loading}
        pagination={false}
        expandable={{ defaultExpandAllRows: true }}
        scroll={{ x: true }}
      />
      <Drawer
        title={editing ? "编辑菜单" : "新建菜单"}
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
        <Form<MenuFormValues> layout="vertical" form={form}>
          <Form.Item name="parentId" label="父级菜单">
            <TreeSelect
              allowClear
              placeholder="留空 = 顶级菜单"
              treeData={parentOptions as never}
              treeDefaultExpandAll
            />
          </Form.Item>
          <Form.Item
            name="code"
            label="编码"
            rules={[
              { required: true, message: "必填" },
              { pattern: /^[A-Z0-9_]{2,64}$/, message: "大写字母/数字/下划线 2-64 字符" },
            ]}
          >
            <Input disabled={!!editing} placeholder="如 ADMIN_USER" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: "必填" }]}>
            <Input placeholder="如 用户管理" />
          </Form.Item>
          <Form.Item name="icon" label="图标 (Ant Design 图标 code)">
            <Input placeholder="如 dashboard / team / setting" />
          </Form.Item>
          <Form.Item name="path" label="路径">
            <Input placeholder="如 /admin/users（父级菜单可空）" />
          </Form.Item>
          <Form.Item name="permissionCode" label="关联权限码" rules={[{ required: true, message: "必填" }]}>
            <Select
              showSearch
              placeholder="从权限点字典选择"
              optionFilterProp="value"
              options={permissionOptions.map((p) => ({ value: p.code, label: `${p.code} - ${p.description ?? ""}` }))}
            />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序" rules={[{ required: true }]}>
            <InputNumber min={0} max={9999} style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item name="isVisible" label="可见性" valuePropName="checked">
            <Switch checkedChildren="显示" unCheckedChildren="隐藏" />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
}
