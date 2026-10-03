import { useEffect, useState } from "react";
import { Alert, Badge, Card, Empty, Table, Tag, Typography } from "antd";
import type { TableProps } from "antd";
import { useQuery } from "@tanstack/react-query";
import { useHasPermission } from "../../lib/auth/usePermission";
import { fxRateApi } from "./fxRateApi";
import type { FxRate } from "./types";

const { Title } = Typography;

// STAGE-14E2 Task4：console FX rate 监控页。
//
// 对接 console-service E2 Task3 GET /admin/market/fx/rates（透传 market FX RPC，需 fx:view）。
// · 读：REST 5s 轮询（refetchInterval）拉 FX rate 列表，固定若干行无分页展示。
// · stale：后端无 stale 字段——前端依 eventTimeMillis 与当前时间差判定，
//   差值 > STALE_THRESHOLD_MS 红色「过期」badge，否则绿色「实时」badge。
// · RBAC：fx:view 守卫——无权限不发请求、展示空态提示（对齐 RequiresPermission / DESIGN §11）。
// · admin.fx.rate.update WS 推送为后续 refinement，本期用 REST 轮询。

/** stale 判定阈值：eventTimeMillis 落后当前时间超过该值视为过期（ms）。 */
export const STALE_THRESHOLD_MS = 60_000;

/** REST 轮询间隔（ms）。 */
const REFETCH_INTERVAL_MS = 5_000;

function isStale(eventTimeMillis: number, now: number): boolean {
  return now - eventTimeMillis > STALE_THRESHOLD_MS;
}

export function FxRateMonitorPage() {
  const hasPermission = useHasPermission();
  const canView = hasPermission("fx:view");

  // stale 判定基准时间走 state，每秒滴答一次：即便 5s 轮询未带来新数据，
  // 过期 badge 也能随时间推进自动翻红（避免在 render 中直接调 Date.now 这一不纯副作用）。
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(timer);
  }, []);

  const query = useQuery({
    queryKey: ["admin", "market", "fx-rates"],
    queryFn: () => fxRateApi.list(),
    enabled: canView,
    refetchInterval: REFETCH_INTERVAL_MS,
  });

  if (!canView) {
    return (
      <div>
        <Title level={3}>FX 汇率监控</Title>
        <Card>
          <Empty description="无 fx:view 权限，无法查看 FX 汇率监控" />
        </Card>
      </div>
    );
  }

  const rows = query.data ?? [];

  const columns: TableProps<FxRate>["columns"] = [
    {
      title: "货币对",
      key: "pair",
      width: 140,
      render: (_, row) => `${row.baseCurrency}/${row.quoteCurrency}`,
    },
    {
      title: "汇率",
      dataIndex: "rate",
      key: "rate",
      width: 160,
      align: "right",
    },
    {
      title: "来源",
      key: "source",
      width: 180,
      render: (_, row) => (
        <Tag>
          {row.sourceLpCode} · {row.sourceSymbol}
        </Tag>
      ),
    },
    {
      title: "更新时间",
      dataIndex: "eventTimeMillis",
      key: "eventTimeMillis",
      width: 200,
      render: (v: number) => new Date(v).toLocaleString(),
    },
    {
      title: "状态",
      key: "stale",
      width: 100,
      align: "center",
      render: (_, row) =>
        isStale(row.eventTimeMillis, now) ? (
          <Badge status="error" text="过期" />
        ) : (
          <Badge status="success" text="实时" />
        ),
    },
  ];

  return (
    <div>
      <Title level={3}>FX 汇率监控</Title>
      <Alert
        message="FX 汇率实时监控"
        description="展示各货币对的最新 FX 报价（来源 LP / 更新时间）。汇率每 5 秒自动刷新；某货币对超过 60 秒未更新则标记为「过期」，请关注对应 LP 报价链路。"
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />
      {query.isError && (
        <Alert
          type="error"
          message="FX 汇率加载失败"
          description="无法获取 FX 汇率数据，请检查网络或联系管理员。"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}
      <Card>
        <Table
          rowKey={(row) => `${row.baseCurrency}/${row.quoteCurrency}`}
          columns={columns}
          dataSource={rows}
          loading={query.isLoading}
          pagination={false}
          scroll={{ x: "max-content" }}
        />
      </Card>
    </div>
  );
}
