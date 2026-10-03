import { useEffect, useState } from "react";
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Result,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import {
  DISCREPANCY_META,
  RESOLUTION_TYPE_OPTIONS,
  type AdminReconciliationItem,
  type AdminReconciliationListQuery,
  type DiscrepancyType,
  type ResolutionType,
} from "./types";
import { reconciliationApi } from "./reconciliationApi";
import { UserCell } from "../../components/UserCell";

const { Title } = Typography;

export function ReconciliationListPage() {
  const permissions = useAdminAuthStore((s) => s.permissions);
  const isSuperAdmin = useAdminAuthStore((s) => s.isSuperAdmin);
  const canView = isSuperAdmin || permissions.includes("reconciliation:view");
  const canResolve = isSuperAdmin || permissions.includes("reconciliation:resolve");

  const [items, setItems] = useState<AdminReconciliationItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminReconciliationListQuery>({ page: 1, size: 20 });
  const [detailTarget, setDetailTarget] = useState<AdminReconciliationItem | null>(null);
  const [resolveTarget, setResolveTarget] = useState<AdminReconciliationItem | null>(null);
  const [resolutionType, setResolutionType] = useState<ResolutionType>("MANUAL_CREDIT");

  const load = (q: AdminReconciliationListQuery) => {
    setLoading(true);
    reconciliationApi
      .listUnmatched(q)
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
    if (canView) {
      load(query);
    }
  }, [query, canView]);

  if (!canView) {
    return (
      <Result
        status="403"
        title="403"
        subTitle="缺 reconciliation:view 权限，无法查看入金对账"
      />
    );
  }

  const columns: TableProps<AdminReconciliationItem>["columns"] = [
    { title: "Wallet TX ID", dataIndex: "walletTxId", key: "walletTxId", width: 160,
      render: (v: string | null) => v ? <span style={{ fontFamily: "monospace" }}>{v}</span> : "—" },
    { title: "链", dataIndex: "chain", key: "chain", width: 80 },
    { title: "币种", dataIndex: "token", key: "token", width: 80 },
    { title: "用户", dataIndex: "userId", key: "userId", width: 200,
      render: (_, r) => <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} /> },
    { title: "Wallet 金额", dataIndex: "walletAmount", key: "walletAmount", width: 140,
      render: (v: string | null) => v ?? "—" },
    { title: "Trading 金额", dataIndex: "tradingAmount", key: "tradingAmount", width: 140,
      render: (v: string | null) => v ?? "—" },
    { title: "差异类型", dataIndex: "discrepancyType", key: "discrepancyType", width: 160,
      render: (v: DiscrepancyType) =>
        <Tag color={DISCREPANCY_META[v].color}>{DISCREPANCY_META[v].label}</Tag> },
    { title: "检测时间", dataIndex: "walletDetectedAt", key: "walletDetectedAt", width: 200,
      render: (v: string | null) => v ?? "—" },
    { title: "操作", key: "actions", width: 80, fixed: "right",
      render: (_, record) => (
        <Button size="small" onClick={() => setDetailTarget(record)}>详情</Button>
      ) },
  ];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>入金对账（unmatched）</Title>
      </div>
      <Card style={{ marginBottom: 16 }}>
        <Form
          layout="inline"
          onFinish={(v: AdminReconciliationListQuery) => setQuery({ ...query, ...v, page: 1 })}
        >
          <Form.Item name="chain" label="链">
            <Select allowClear style={{ width: 120 }} placeholder="全部"
              options={[
                { value: "ETH", label: "ETH" },
                { value: "TRON", label: "TRON" },
              ]} />
          </Form.Item>
          <Form.Item name="token" label="币种">
            <Select allowClear style={{ width: 120 }} placeholder="全部"
              options={[
                { value: "USDT", label: "USDT" },
                { value: "USDC", label: "USDC" },
              ]} />
          </Form.Item>
          <Form.Item name="discrepancyType" label="差异类型">
            <Select allowClear style={{ width: 200 }} placeholder="全部"
              options={[
                { value: "WALLET_ONLY", label: "WALLET_ONLY" },
                { value: "TRADING_ONLY", label: "TRADING_ONLY" },
                { value: "AMOUNT_MISMATCH", label: "AMOUNT_MISMATCH" },
                { value: "STATUS_DIVERGED", label: "STATUS_DIVERGED" },
              ]} />
          </Form.Item>
          <Form.Item name="fromDetectedAt" label="开始">
            <Input placeholder="ISO8601" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="toDetectedAt" label="结束">
            <Input placeholder="ISO8601" style={{ width: 200 }} />
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
        rowKey={(r) => r.walletTxId ?? r.tradingDepositId ?? r.txHash}
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
      <ReconciliationDetailDrawer
        target={detailTarget}
        canResolve={canResolve}
        onClose={() => setDetailTarget(null)}
        onResolve={() => {
          setResolveTarget(detailTarget);
          setDetailTarget(null);
        }}
      />
      {resolveTarget && (
        <HighRiskConfirmModal<{ walletTxId: string }>
          open={!!resolveTarget}
          title="标记入金对账项已 resolved"
          okText="确认标记"
          description="此操作仅写入审计日志，不会修改 wallet / trading-core 业务表。请确保已手工补单或已经做出运营决策。"
          details={[
            ["Wallet TX ID", resolveTarget.walletTxId ?? "—"],
            ["差异类型", resolveTarget.discrepancyType],
            ["链", resolveTarget.chain],
            ["币种", resolveTarget.token],
            ["txHash", resolveTarget.txHash],
          ]}
          requireConfirmCheckbox
          onSubmit={async (reason) => {
            const walletTxId = resolveTarget.walletTxId;
            if (!walletTxId) throw new Error("walletTxId 为空");
            await reconciliationApi.markResolved(walletTxId, { reason, resolutionType });
            return { walletTxId };
          }}
          onSuccess={() => {
            message.success("已标记 resolved");
            setResolveTarget(null);
            load(query);
          }}
          onCancel={() => setResolveTarget(null)}
        >
          <Form.Item label="处置方式（必选）" required>
            <Select
              value={resolutionType}
              onChange={(v) => setResolutionType(v)}
              options={RESOLUTION_TYPE_OPTIONS}
            />
          </Form.Item>
        </HighRiskConfirmModal>
      )}
    </div>
  );
}

interface DetailProps {
  target: AdminReconciliationItem | null;
  canResolve: boolean;
  onClose: () => void;
  onResolve: () => void;
}

function ReconciliationDetailDrawer({ target, canResolve, onClose, onResolve }: DetailProps) {
  return (
    <Drawer
      open={target != null}
      width={720}
      onClose={onClose}
      title="入金对账详情"
      extra={
        <Tooltip title={canResolve ? "" : "无 reconciliation:resolve 权限"}>
          <Button type="primary" danger disabled={!canResolve} onClick={onResolve}>
            标记已 resolved
          </Button>
        </Tooltip>
      }
    >
      {target && (
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label="Wallet TX ID">
            <code>{target.walletTxId ?? "—"}</code>
          </Descriptions.Item>
          <Descriptions.Item label="Trading Deposit ID">
            <code>{target.tradingDepositId ?? "—"}</code>
          </Descriptions.Item>
          <Descriptions.Item label="链">{target.chain}</Descriptions.Item>
          <Descriptions.Item label="币种">{target.token}</Descriptions.Item>
          <Descriptions.Item label="txHash"
            contentStyle={{ wordBreak: "break-all" }}>
            <code>{target.txHash}</code>
          </Descriptions.Item>
          <Descriptions.Item label="User ID">
            <code>{target.userId ?? "—"}</code>
          </Descriptions.Item>
          <Descriptions.Item label="To Address"
            contentStyle={{ wordBreak: "break-all" }}>
            {target.toAddress ?? "—"}
          </Descriptions.Item>
          <Descriptions.Item label="差异类型">
            <Tag color={DISCREPANCY_META[target.discrepancyType].color}>
              {DISCREPANCY_META[target.discrepancyType].label}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Wallet 金额">{target.walletAmount ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="Trading 金额">{target.tradingAmount ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="Wallet 状态">{target.walletStatus ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="Trading 状态">{target.tradingStatus ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="检测时间">{target.walletDetectedAt ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="确认时间">{target.walletConfirmedAt ?? "—"}</Descriptions.Item>
        </Descriptions>
      )}
    </Drawer>
  );
}
