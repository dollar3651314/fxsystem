import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Button, Card, Form, Input, Select, Space, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { ReloadOutlined, SearchOutlined } from "@ant-design/icons";
import { customerApi } from "./customerApi";
import type { CustomerListItem, CustomerListQuery, CustomerStatus } from "./types";

const { Title } = Typography;

const STATUS_COLOR_MAP: Record<CustomerStatus, string> = {
  ACTIVE: "green",
  FROZEN: "red",
  BANNED: "default",
  PENDING_DEPOSIT: "orange",
};

const STATUS_OPTIONS: CustomerStatus[] = ["ACTIVE", "FROZEN", "BANNED", "PENDING_DEPOSIT"];

export function CustomerListPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<CustomerListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<CustomerListQuery>({ page: 0, size: 20 });

  const load = (q: CustomerListQuery) => {
    setLoading(true);
    customerApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载客户列表失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  const handleSearch = (values: { email?: string; status?: CustomerStatus[] }) => {
    setQuery({ ...query, ...values, page: 0 });
  };

  const columns: TableProps<CustomerListItem>["columns"] = [
    {
      title: "UID",
      dataIndex: "uid",
      key: "uid",
      width: 120,
      render: (uid, record) => <Link to={`/admin/customers/${record.userId}`}>{uid}</Link>,
    },
    { title: "邮箱", dataIndex: "email", key: "email", ellipsis: true },
    {
      title: "客户姓名",
      dataIndex: "fullName",
      key: "fullName",
      width: 160,
      render: (name: string | null) => name ?? <span style={{ color: "var(--fx-console-text-muted)" }}>—</span>,
    },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 130,
      render: (status: CustomerStatus) => (
        <Tag color={STATUS_COLOR_MAP[status]}>{status}</Tag>
      ),
    },
    {
      title: "KYC",
      dataIndex: "kycLevel",
      key: "kycLevel",
      width: 110,
      render: (level: number | null) => {
        if (level == null) return <Tag>未知</Tag>;
        if (level >= 1) return <Tag color="green">已认证 L{level}</Tag>;
        return <Tag color="default">未认证</Tag>;
      },
    },
    { title: "用户组", dataIndex: "groupCode", key: "groupCode", width: 100 },
    {
      title: "余额 (USD)",
      dataIndex: "balanceUSD",
      key: "balanceUSD",
      width: 140,
      align: "right",
      render: (balance: string) => {
        const num = Number(balance);
        const color = num < 0 ? "var(--fx-console-error)" : undefined;
        return <span style={{ color, fontVariantNumeric: "tabular-nums" }}>${balance}</span>;
      },
    },
    { title: "最近登录", dataIndex: "lastLoginAt", key: "lastLoginAt", width: 180 },
    { title: "创建时间", dataIndex: "createdAt", key: "createdAt", width: 180 },
    {
      title: "操作",
      key: "actions",
      width: 100,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => navigate(`/admin/customers/${record.userId}`)}>
          查看
        </Button>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>客户列表</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="email" label="邮箱">
            <Input placeholder="支持模糊匹配" allowClear />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select mode="multiple" style={{ minWidth: 200 }} placeholder="多选" allowClear>
              {STATUS_OPTIONS.map((s) => (
                <Select.Option key={s} value={s}>
                  {s}
                </Select.Option>
              ))}
            </Select>
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
      <Table<CustomerListItem>
        columns={columns}
        dataSource={items}
        rowKey="userId"
        loading={loading}
        scroll={{ x: true }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (t) => `共 ${t} 条`,
          onChange: (newPage, newSize) =>
            setQuery({ ...query, page: newPage - 1, size: newSize }),
        }}
      />
    </div>
  );
}
