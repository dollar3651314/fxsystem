import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, Input, Space, Table, Tabs, Typography, message } from "antd";
import type { TableProps } from "antd";
import { tradingApi } from "./tradingApi";
import type { ExposureItem } from "./types";
import { useAdminTradingSocket, type AdminExposureUpdate } from "./useAdminTradingSocket";
import { aggregateByQuoteCurrency, type ExposureByQuoteRow } from "./exposureAggregate";
import { getAdminAccessToken } from "../../lib/auth/adminTokenStorage";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { formatMoney, formatPercent, formatSignedMoney } from "../../lib/precision";

const { Title } = Typography;

export function TradingExposureBoardPage() {
  const [items, setItems] = useState<ExposureItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [symbolFilter, setSymbolFilter] = useState<string>("");

  const load = (symbol?: string) => {
    setLoading(true);
    tradingApi
      .listExposures(symbol)
      .then((data) => setItems(data.items))
      .catch((err) => message.error(`加载敞口失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, []);

  const handleSearch = () => {
    void load(symbolFilter.trim() || undefined);
  };

  // STAGE-2-REALTIME-DATA Phase 4：admin.exposure.update 实时 patch（200ms/symbol 节流）
  const user = useAdminAuthStore((s) => s.user);
  const token = useMemo(() => (user ? getAdminAccessToken() : null), [user]);
  const handleExposureUpdate = useCallback((update: AdminExposureUpdate) => {
    setItems((prev) => {
      const idx = prev.findIndex((it) => it.symbol === update.symbol);
      const patched: ExposureItem = {
        symbol: update.symbol,
        quoteCurrency: update.quoteCurrency ?? (idx >= 0 ? prev[idx].quoteCurrency : null),
        totalLongQty: update.totalLongQty ?? "0",
        totalShortQty: update.totalShortQty ?? "0",
        netExposure: update.netExposure ?? "0",
        netExposureUsd: update.netExposureUsd ?? "0",
        updatedAt: update.quoteTs ?? new Date().toISOString(),
      };
      if (idx < 0) {
        // 新 symbol：当前过滤为单一 symbol 时不混入
        if (symbolFilter && symbolFilter.trim() && symbolFilter.trim() !== update.symbol) {
          return prev;
        }
        return [...prev, patched];
      }
      const next = prev.slice();
      next[idx] = patched;
      return next;
    });
  }, [symbolFilter]);
  const socketState = useAdminTradingSocket(token, {
    onExposureUpdate: handleExposureUpdate,
  });

  const renderSigned = (v: string) => {
    const num = Number(v);
    const color = num < 0 ? "var(--fx-console-error)" : num > 0 ? "var(--fx-console-success)" : undefined;
    return <span style={{ color, fontVariantNumeric: "tabular-nums" }}>{v}</span>;
  };

  const renderSignedUsd = (v: number) => {
    const color = v < 0 ? "var(--fx-console-error)" : v > 0 ? "var(--fx-console-success)" : undefined;
    return <span style={{ color, fontVariantNumeric: "tabular-nums" }}>{formatSignedMoney(v, "USD")}</span>;
  };

  const columns: TableProps<ExposureItem>["columns"] = [
    { title: "Symbol", dataIndex: "symbol", key: "symbol", width: 140 },
    {
      title: "报价币",
      dataIndex: "quoteCurrency",
      key: "quoteCurrency",
      width: 100,
      render: (v: string | null) => v ?? <span style={{ color: "var(--fx-console-text-muted)" }}>—</span>,
    },
    { title: "多头总量", dataIndex: "totalLongQty", key: "totalLongQty", width: 140, align: "right" },
    { title: "空头总量", dataIndex: "totalShortQty", key: "totalShortQty", width: 140, align: "right" },
    { title: "净敞口（基础币）", dataIndex: "netExposure", key: "netExposure", width: 160, align: "right", render: renderSigned },
    { title: "净敞口（USD）", dataIndex: "netExposureUsd", key: "netExposureUsd", width: 160, align: "right", render: renderSigned },
    { title: "更新时间", dataIndex: "updatedAt", key: "updatedAt", width: 200 },
  ];

  const aggregatedRows = useMemo(() => aggregateByQuoteCurrency(items), [items]);

  const aggregatedColumns: TableProps<ExposureByQuoteRow>["columns"] = [
    { title: "报价币", dataIndex: "quoteCurrency", key: "quoteCurrency", width: 120 },
    { title: "Symbol 数", dataIndex: "symbolCount", key: "symbolCount", width: 110, align: "right" },
    {
      title: "净敞口（USD 等价）",
      dataIndex: "netExposureUsd",
      key: "netExposureUsd",
      width: 180,
      align: "right",
      render: (v: number) => renderSignedUsd(v),
    },
    {
      title: "多头敞口（USD 等价）",
      dataIndex: "longExposureUsd",
      key: "longExposureUsd",
      width: 190,
      align: "right",
      render: (v: number) => <span style={{ fontVariantNumeric: "tabular-nums" }}>{formatMoney(v, "USD")}</span>,
    },
    {
      title: "空头敞口（USD 等价）",
      dataIndex: "shortExposureUsd",
      key: "shortExposureUsd",
      width: 190,
      align: "right",
      render: (v: number) => <span style={{ fontVariantNumeric: "tabular-nums" }}>{formatMoney(v, "USD")}</span>,
    },
    {
      title: "占比",
      dataIndex: "share",
      key: "share",
      width: 100,
      align: "right",
      render: (v: number) => formatPercent(v * 100, 1),
    },
  ];

  return (
    <div>
      <Title level={3}>
        净敞口看板
        <Badge
          status={socketState === "open" ? "success" : socketState === "error" ? "error" : "default"}
          text={socketState === "open" ? "实时" : socketState}
          style={{ marginLeft: 12, fontSize: 14, fontWeight: 400 }}
        />
      </Title>
      <Card style={{ marginBottom: 16 }}>
        <Space>
          <Input
            placeholder="按 Symbol 过滤（精确）"
            value={symbolFilter}
            onChange={(e) => setSymbolFilter(e.target.value)}
            onPressEnter={handleSearch}
            allowClear
            style={{ width: 240 }}
          />
          <Button type="primary" onClick={handleSearch}>查询</Button>
          <Button onClick={() => { setSymbolFilter(""); load(); }}>重置</Button>
          <Button onClick={() => load(symbolFilter.trim() || undefined)}>刷新</Button>
        </Space>
      </Card>
      <Tabs
        defaultActiveKey="by-symbol"
        items={[
          {
            key: "by-symbol",
            label: "按 Symbol",
            children: (
              <Table
                rowKey="symbol"
                columns={columns}
                dataSource={items}
                loading={loading}
                pagination={false}
                scroll={{ x: "max-content" }}
              />
            ),
          },
          {
            key: "by-quote",
            label: "按报价币聚合",
            children: (
              <Table
                rowKey="quoteCurrency"
                columns={aggregatedColumns}
                dataSource={aggregatedRows}
                loading={loading}
                pagination={false}
                scroll={{ x: "max-content" }}
              />
            ),
          },
        ]}
      />
    </div>
  );
}
