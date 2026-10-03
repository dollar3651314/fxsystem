import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Empty,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from "@ant-design/icons";
import { RequiresPermission } from "../../components/RequiresPermission";
import { symbolApi } from "../symbol/symbolApi";
import { featuredApi } from "./featuredApi";
import type { FeaturedItem } from "./types";

const PERMISSION_UPDATE = "symbol:featured:update";

/**
 * 跑马灯热门产品配置页（FEATURED-TICKER）。
 *
 * 单一全局有序列表：选 symbol → 排序（上移/下移）→ 启停 → 保存（全量替换）。
 * 客户端顶栏跑马灯按此顺序展示 enabled 项；空列表时客户端回退默认偏好。
 */
export function FeaturedTickerPage() {
  const [items, setItems] = useState<FeaturedItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [options, setOptions] = useState<{ value: string; label: string }[]>([]);
  const [searching, setSearching] = useState(false);
  const searchSeq = useRef(0);

  const load = () => {
    setLoading(true);
    featuredApi
      .list()
      .then((res) => {
        setItems(res.items ?? []);
        setDirty(false);
      })
      .catch((err) => message.error(`加载失败 ${err?.code ?? ""}：${err?.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const searchSymbols = (keyword: string) => {
    const seq = ++searchSeq.current;
    setSearching(true);
    symbolApi
      .list({ symbolLike: keyword || undefined, page: 0, size: 30 })
      .then((res) => {
        if (seq !== searchSeq.current) return; // 丢弃过期响应
        setOptions(res.items.map((s) => ({ value: s.symbol, label: s.symbol })));
      })
      .catch((err) => message.warning(`品种检索失败：${err?.message ?? ""}`))
      .finally(() => {
        if (seq === searchSeq.current) setSearching(false);
      });
  };

  const addSymbol = (symbol: string) => {
    if (!symbol) return;
    if (items.some((it) => it.symbol === symbol)) {
      message.warning(`${symbol} 已在列表中`);
      return;
    }
    setItems((prev) => [...prev, { symbol, sortOrder: prev.length, enabled: true }]);
    setDirty(true);
  };

  const move = (index: number, delta: number) => {
    setItems((prev) => {
      const next = [...prev];
      const target = index + delta;
      if (target < 0 || target >= next.length) return prev;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    setDirty(true);
  };

  const toggleEnabled = (symbol: string, enabled: boolean) => {
    setItems((prev) => prev.map((it) => (it.symbol === symbol ? { ...it, enabled } : it)));
    setDirty(true);
  };

  const remove = (symbol: string) => {
    setItems((prev) => prev.filter((it) => it.symbol !== symbol));
    setDirty(true);
  };

  const save = () => {
    setSaving(true);
    featuredApi
      .replace({ items: items.map((it) => ({ symbol: it.symbol, enabled: it.enabled })) })
      .then((res) => {
        setItems(res.items ?? []);
        setDirty(false);
        message.success("已保存（客户端跑马灯随刷新生效）");
      })
      .catch((err) => message.error(`保存失败 ${err?.code ?? ""}：${err?.message ?? ""}`))
      .finally(() => setSaving(false));
  };

  const columns: TableProps<FeaturedItem>["columns"] = [
    {
      title: "顺序",
      width: 72,
      render: (_v, _r, index) => <Tag>{index + 1}</Tag>,
    },
    {
      title: "品种",
      dataIndex: "symbol",
      render: (symbol: string) => <Typography.Text strong>{symbol}</Typography.Text>,
    },
    {
      title: "展示",
      width: 110,
      render: (_v, record) => (
        <Switch
          checked={record.enabled}
          checkedChildren="展示"
          unCheckedChildren="停用"
          onChange={(checked) => toggleEnabled(record.symbol, checked)}
        />
      ),
    },
    {
      title: "操作",
      width: 200,
      render: (_v, record, index) => (
        <Space>
          <Button
            size="small"
            icon={<ArrowUpOutlined />}
            disabled={index === 0}
            onClick={() => move(index, -1)}
          />
          <Button
            size="small"
            icon={<ArrowDownOutlined />}
            disabled={index === items.length - 1}
            onClick={() => move(index, 1)}
          />
          <Button
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => remove(record.symbol)}
          />
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="跑马灯热门产品"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            刷新
          </Button>
          <RequiresPermission code={PERMISSION_UPDATE}>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={save}
              loading={saving}
              disabled={!dirty}
            >
              保存
            </Button>
          </RequiresPermission>
        </Space>
      }
    >
      <Alert
        type="info"
        message="客户端顶栏跑马灯按此列表顺序展示「展示」状态的品种。空列表时客户端回退内置默认偏好。"
        style={{ marginBottom: 16 }}
      />
      <RequiresPermission code={PERMISSION_UPDATE}>
        <Space style={{ marginBottom: 16 }}>
          <Select
            showSearch
            allowClear
            placeholder="搜索品种加入跑马灯"
            style={{ width: 280 }}
            filterOption={false}
            loading={searching}
            options={options}
            onSearch={searchSymbols}
            onFocus={() => options.length === 0 && searchSymbols("")}
            onSelect={(value) => addSymbol(value ?? "")}
            value={null}
            suffixIcon={<PlusOutlined />}
          />
          <Typography.Text type="secondary">已选 {items.length} 个</Typography.Text>
        </Space>
      </RequiresPermission>
      {items.length === 0 ? (
        <Empty description="未配置（客户端使用默认偏好）" />
      ) : (
        <Table
          rowKey="symbol"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={items}
          pagination={false}
        />
      )}
    </Card>
  );
}
