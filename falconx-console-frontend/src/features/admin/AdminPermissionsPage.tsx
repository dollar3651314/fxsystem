import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Popover,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import { ReloadOutlined, SearchOutlined, WarningOutlined } from "@ant-design/icons";
import { adminPermissionsApi } from "./adminPermissionsApi";
import type {
  AdminPermissionListItem,
  AdminPermissionListQuery,
  AdminPermissionRoleRef,
} from "./types";

const { Title } = Typography;

function RolePopover({ code }: { code: string }) {
  const [roles, setRoles] = useState<AdminPermissionRoleRef[] | null>(null);
  const [loading, setLoading] = useState(false);

  const handleOpen = (open: boolean) => {
    if (open && roles === null) {
      setLoading(true);
      adminPermissionsApi
        .roles(code)
        .then(setRoles)
        .catch(() => setRoles([]))
        .finally(() => setLoading(false));
    }
  };

  const content = loading ? (
    <span>加载中…</span>
  ) : roles && roles.length > 0 ? (
    <Space direction="vertical" size={4}>
      {roles.map((r) => (
        <Tag key={r.roleId} color="blue">
          {r.roleCode} ({r.roleName})
        </Tag>
      ))}
    </Space>
  ) : (
    <span style={{ color: "#999" }}>未关联任何角色</span>
  );

  return (
    <Popover content={content} title="关联角色" onOpenChange={handleOpen}>
      <Button type="link" size="small">
        查看
      </Button>
    </Popover>
  );
}

export function AdminPermissionsPage() {
  const [items, setItems] = useState<AdminPermissionListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(100);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminPermissionListQuery>({ page: 0, size: 100 });

  const load = (q: AdminPermissionListQuery) => {
    setLoading(true);
    adminPermissionsApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载权限点失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  const handleSearch = (values: { module?: string; action?: string; highRiskOnly?: boolean }) => {
    setQuery({ ...query, ...values, page: 0 });
  };

  const columns: TableProps<AdminPermissionListItem>["columns"] = [
    {
      title: "权限码",
      dataIndex: "code",
      key: "code",
      width: 240,
      render: (code: string) => <code style={{ fontFamily: "monospace" }}>{code}</code>,
    },
    {
      title: "模块",
      dataIndex: "module",
      key: "module",
      width: 140,
      render: (m: string) => <Tag>{m}</Tag>,
    },
    { title: "动作", dataIndex: "action", key: "action", width: 120 },
    { title: "描述", dataIndex: "description", key: "description", ellipsis: true },
    {
      title: "标记",
      dataIndex: "isHighRisk",
      key: "isHighRisk",
      width: 110,
      render: (high: boolean) =>
        high ? (
          <Tag color="red" icon={<WarningOutlined />}>
            高风险
          </Tag>
        ) : null,
    },
    {
      title: "关联角色",
      dataIndex: "roleCount",
      key: "roleCount",
      width: 160,
      render: (count: number, record) => (
        <Space>
          <span>{count}</span>
          {count > 0 && <RolePopover code={record.code} />}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>权限点字典</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="权限点由后端代码扫描 @RequiresPermission 注解生成，本页仅供查阅，不可编辑。如需新增权限点，请在后端代码中添加注解后重启 console-service。"
      />
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="module" label="模块">
            <Select
              style={{ minWidth: 160 }}
              allowClear
              placeholder="选择模块"
              options={[
                { value: "customer", label: "customer" },
                { value: "admin-user", label: "admin-user" },
                { value: "admin-role", label: "admin-role" },
                { value: "admin-menu", label: "admin-menu" },
                { value: "admin-permission", label: "admin-permission" },
              ]}
            />
          </Form.Item>
          <Form.Item name="action" label="动作">
            <Input placeholder="精确匹配" allowClear />
          </Form.Item>
          <Form.Item name="highRiskOnly" label="仅高风险" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                搜索
              </Button>
              <Button icon={<ReloadOutlined />} onClick={() => setQuery({ page: 0, size: 100 })}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Table<AdminPermissionListItem>
        columns={columns}
        dataSource={items}
        rowKey="code"
        loading={loading}
        scroll={{ x: true }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [20, 50, 100, 200],
          showTotal: (t) => `共 ${t} 条`,
          onChange: (newPage, newSize) => setQuery({ ...query, page: newPage - 1, size: newSize }),
        }}
      />
    </div>
  );
}
