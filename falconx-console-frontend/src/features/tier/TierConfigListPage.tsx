import { useEffect, useMemo, useState } from "react";
import { Button, Card, Form, Input, Space, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import { RequiresPermission } from "../../components/RequiresPermission";
import { tierApi } from "./tierApi";
import { TierFormModal } from "./TierFormModal";
import { TierDeleteModal } from "./TierDeleteModal";
import type { TierItem, TierListQuery } from "./types";

const { Title } = Typography;

// STAGE-14C2 Task 9 R10：杠杆/MM 档位配置页。
//
// 列表按 symbol+groupCode 分组（父行），用 AntD Table expandable 展开行显示该组各档位
// （tierNo / notional 区间 / maxLeverage / mmRate / enabled）。
// 查询 Form（symbol/groupCode）+ 分页（分页针对后端返回的扁平档位条数，与 risk 页同口径）。
// 操作（编辑/删除）用 RequiresPermission("tier:edit") 包裹（无权限隐藏，不是 disabled）；
// 列表入口本身受路由菜单 tier:view 控制。

/** 分组父行：同 symbol+groupCode 的档位聚合。 */
interface TierGroup {
  groupKey: string;
  symbol: string;
  groupCode: string;
  tiers: TierItem[];
}

export function TierConfigListPage() {
  const [items, setItems] = useState<TierItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<TierListQuery>({ page: 1, size: 20 });
  const [formMode, setFormMode] = useState<"create" | "edit" | null>(null);
  const [formExisting, setFormExisting] = useState<TierItem | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<TierItem | null>(null);
  const [expandedKeys, setExpandedKeys] = useState<readonly string[]>([]);

  const load = (q: TierListQuery) => {
    setLoading(true);
    tierApi
      .listTiers(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载杠杆档位失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  const handleSearch = (values: { symbol?: string; groupCode?: string }) => {
    setQuery({ ...query, ...values, page: 1 });
  };

  // 扁平档位 → 按 symbol+groupCode 分组父行；组内按 tierNo 升序。
  const groups = useMemo<TierGroup[]>(() => {
    const map = new Map<string, TierGroup>();
    for (const item of items) {
      const groupKey = `${item.symbol}__${item.groupCode}`;
      let group = map.get(groupKey);
      if (!group) {
        group = { groupKey, symbol: item.symbol, groupCode: item.groupCode, tiers: [] };
        map.set(groupKey, group);
      }
      group.tiers.push(item);
    }
    for (const group of map.values()) {
      group.tiers.sort((a, b) => a.tierNo - b.tierNo);
    }
    return Array.from(map.values());
  }, [items]);

  // 数据加载后默认展开全部分组（AntD defaultExpandAllRows 对异步数据不生效，改受控展开）。
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setExpandedKeys(groups.map((g) => g.groupKey));
  }, [groups]);

  const groupColumns: TableProps<TierGroup>["columns"] = [
    { title: "Symbol", dataIndex: "symbol", key: "symbol", width: 200 },
    { title: "客户组", dataIndex: "groupCode", key: "groupCode", width: 160 },
    {
      title: "档位数",
      key: "tierCount",
      width: 120,
      align: "right",
      render: (_, group) => group.tiers.length,
    },
  ];

  const tierColumns: TableProps<TierItem>["columns"] = [
    { title: "档位", dataIndex: "tierNo", key: "tierNo", width: 80, align: "right" },
    {
      title: "名义区间 [下界, 上界)",
      key: "notional",
      width: 240,
      render: (_, t) => `${t.notionalLower} ~ ${t.notionalUpper ?? "∞"}`,
    },
    { title: "最大杠杆", dataIndex: "maxLeverage", key: "maxLeverage", width: 100, align: "right" },
    { title: "维持保证金率", dataIndex: "mmRate", key: "mmRate", width: 140, align: "right" },
    {
      title: "状态",
      dataIndex: "enabled",
      key: "enabled",
      width: 100,
      render: (enabled: boolean) =>
        enabled ? <Tag color="green">启用</Tag> : <Tag color="default">已停用</Tag>,
    },
    {
      title: "操作",
      key: "actions",
      width: 160,
      render: (_, t) => (
        <RequiresPermission code="tier:edit">
          <Space size="small">
            <Button size="small" onClick={() => { setFormExisting(t); setFormMode("edit"); }}>
              编辑
            </Button>
            <Button danger size="small" disabled={!t.enabled} onClick={() => setDeleteTarget(t)}>
              删除
            </Button>
          </Space>
        </RequiresPermission>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>杠杆档位配置</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={handleSearch}>
          <Form.Item name="symbol" label="Symbol">
            <Input placeholder="如 BTCUSDT" allowClear />
          </Form.Item>
          <Form.Item name="groupCode" label="客户组">
            <Input placeholder="如 default" allowClear />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => setQuery({ page: 1, size: 20 })}>重置</Button>
              <RequiresPermission code="tier:edit">
                <Button type="primary" danger onClick={() => { setFormExisting(null); setFormMode("create"); }}>
                  新建档位
                </Button>
              </RequiresPermission>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Table
        rowKey="groupKey"
        columns={groupColumns}
        dataSource={groups}
        loading={loading}
        expandable={{
          expandedRowKeys: expandedKeys as string[],
          onExpandedRowsChange: (keys) => setExpandedKeys(keys as string[]),
          expandedRowRender: (group) => (
            <Table
              rowKey="id"
              columns={tierColumns}
              dataSource={group.tiers}
              pagination={false}
              size="small"
            />
          ),
        }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [20, 50, 100],
          onChange: (p, s) => setQuery({ ...query, page: p, size: s }),
        }}
        scroll={{ x: "max-content" }}
      />
      <TierFormModal
        open={formMode != null}
        mode={formMode ?? "create"}
        existing={formExisting}
        onClose={() => { setFormMode(null); setFormExisting(null); }}
        onSuccess={() => load(query)}
      />
      <TierDeleteModal
        open={deleteTarget != null}
        tier={deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onSuccess={() => load(query)}
      />
    </div>
  );
}
