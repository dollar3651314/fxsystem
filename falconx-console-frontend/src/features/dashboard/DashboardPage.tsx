import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Col, Row, Tag, Typography } from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import { useQuery } from "@tanstack/react-query";
import { tradingApi } from "../trading/tradingApi";
import {
  useAdminTradingSocket,
  type AdminPositionSummaryUpdate,
} from "../trading/useAdminTradingSocket";
import { getAdminAccessToken } from "../../lib/auth/adminTokenStorage";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { formatPercent, formatSignedPnl } from "../../lib/precision";
import "./DashboardPage.css";

const { Title, Text } = Typography;

function fmtMoney(value: string | number | null | undefined, fixed = 2): string {
  if (value == null || value === "") return "—";
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return "—";
  return n.toLocaleString(undefined, {
    minimumFractionDigits: fixed,
    maximumFractionDigits: fixed,
  });
}

function fmtCount(value: number | null | undefined): string {
  if (value == null) return "—";
  return value.toLocaleString();
}

function fmtSigned(value: string | number | null | undefined, fixed = 2): string {
  if (value == null || value === "") return "—";
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return "—";
  return (n >= 0 ? "+" : "") + fmtMoney(n, fixed);
}

function shortenUserId(id: string | null | undefined): string {
  if (!id) return "—";
  const s = String(id);
  if (s.length <= 12) return s;
  return `${s.slice(0, 6)}…${s.slice(-4)}`;
}

interface StatCardProps {
  label: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  tone?: "default" | "long" | "short" | "cyan" | "warning";
  eyebrow?: string;
}

function StatCard({ label, value, sub, tone = "default", eyebrow }: StatCardProps) {
  return (
    <div className={`metrics-stat metrics-stat--${tone}`}>
      {eyebrow && <span className="metrics-stat__eyebrow">{eyebrow}</span>}
      <span className="metrics-stat__label">{label}</span>
      <span className="metrics-stat__value">{value}</span>
      {sub && <span className="metrics-stat__sub">{sub}</span>}
    </div>
  );
}

interface DonutProps {
  size?: number;
  /** 每段：{label, value, color}。所有 value 加起来=total（不强制 100%） */
  segments: Array<{ label: string; value: number; color: string }>;
  centerTitle?: string;
  centerValue?: React.ReactNode;
}

function Donut({ size = 140, segments, centerTitle, centerValue }: DonutProps) {
  const total = segments.reduce((a, s) => a + s.value, 0);
  if (total === 0) {
    return (
      <div className="metrics-donut metrics-donut--empty" style={{ width: size, height: size }}>
        <div className="metrics-donut__center">
          <span className="metrics-donut__center-title">{centerTitle ?? "暂无数据"}</span>
        </div>
      </div>
    );
  }
  let acc = 0;
  const stops = segments
    .map((s) => {
      const start = (acc / total) * 360;
      acc += s.value;
      const end = (acc / total) * 360;
      return `${s.color} ${start}deg ${end}deg`;
    })
    .join(", ");
  return (
    <div
      className="metrics-donut"
      style={{
        width: size,
        height: size,
        background: `conic-gradient(${stops})`,
      }}
    >
      <div className="metrics-donut__center">
        {centerTitle && <span className="metrics-donut__center-title">{centerTitle}</span>}
        {centerValue && <span className="metrics-donut__center-value">{centerValue}</span>}
      </div>
    </div>
  );
}

interface SectionProps {
  num: string;
  title: string;
  hint?: string;
  children: React.ReactNode;
}

function Section({ num, title, hint, children }: SectionProps) {
  return (
    <section className="metrics-section">
      <header className="metrics-section__head">
        <span className="metrics-section__num">§{num}</span>
        <h2 className="metrics-section__title">{title}</h2>
        {hint && <span className="metrics-section__hint">{hint}</span>}
        <span className="metrics-section__rule" />
      </header>
      <div className="metrics-section__body">{children}</div>
    </section>
  );
}

export function DashboardPage() {
  // 后端拉一次全量指标（lifetime 累计，不需要轮询）；用户手动刷新或重新进路由时重拉
  const metricsQuery = useQuery({
    queryKey: ["admin", "platform", "metrics-overview"],
    queryFn: () => tradingApi.getPlatformMetricsOverview(),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });
  const m = metricsQuery.data;

  // WS：复用已有 admin.position.summary 推送实时刷新「平台总未实现盈亏」（每 500ms 节流）
  // WS 仅推 totalUnrealizedPnl + computedAt，openCount / marginUsed 仍从 REST 拉取
  const user = useAdminAuthStore((s) => s.user);
  const token = useMemo(() => (user ? getAdminAccessToken() : null), [user]);
  const [wsSummary, setWsSummary] = useState<AdminPositionSummaryUpdate | null>(null);
  const wsHandlerRef = useRef((s: AdminPositionSummaryUpdate) => setWsSummary(s));
  wsHandlerRef.current = (s) => setWsSummary(s);
  useAdminTradingSocket(token, {
    onPositionSummaryUpdate: (s) => wsHandlerRef.current(s),
  });

  useEffect(() => () => setWsSummary(null), []);

  // 实时持仓数据：unrealizedPnl 优先 WS（500ms 跳动），其他 fallback REST
  const liveUnrealizedPnl = wsSummary?.totalUnrealizedPnl ?? null;

  const isLoading = metricsQuery.isLoading;
  const computedAt = m?.computedAt ? new Date(m.computedAt).toLocaleString() : "—";

  return (
    <div className="metrics-dashboard">
      <header className="metrics-header">
        <div>
          <Text className="metrics-header__eyebrow">
            FALCONX · ADMIN · OPERATIONS METRICS
          </Text>
          <Title level={2} style={{ margin: "4px 0 0", color: "var(--fx-text)" }}>
            平台运营仪表盘
          </Title>
          <Text type="secondary" style={{ fontFamily: "var(--fx-mono-stack)" }}>
            数据时间 {computedAt}
            {wsSummary && (
              <Tag color="cyan" style={{ marginLeft: 8 }}>
                持仓 WS 实时
              </Tag>
            )}
          </Text>
        </div>
        <Button
          icon={<ReloadOutlined />}
          loading={metricsQuery.isFetching}
          onClick={() => metricsQuery.refetch()}
        >
          刷新
        </Button>
      </header>

      {metricsQuery.isError && (
        <Alert
          type="error"
          message="加载平台指标失败"
          description={(metricsQuery.error as Error)?.message ?? "网络错误"}
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {isLoading && (
        <Alert
          type="info"
          message="加载中…"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {/* ─── §01 实时持仓监控 ─── */}
      <Section num="01" title="实时持仓监控" hint="WS 推送 · 500ms 节流">
        <Row gutter={[12, 12]}>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="ACTIVE"
              label="OPEN 持仓数"
              value={fmtCount(m?.positions.openCount)}
              sub={`多 ${fmtCount(m?.positions.longCount)} · 空 ${fmtCount(m?.positions.shortCount)}`}
              tone="cyan"
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="FILLED"
              label="累计成交订单"
              value={fmtCount(m?.orders.totalFilledOrders)}
              sub={`今日 +${fmtCount(m?.orders.filledOrdersToday)}`}
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="UNREALIZED"
              label="平台总未实现盈亏"
              value={
                liveUnrealizedPnl != null ? (
                  <span
                    className={Number(liveUnrealizedPnl) >= 0 ? "metrics-pnl-pos" : "metrics-pnl-neg"}
                  >
                    {formatSignedPnl(liveUnrealizedPnl)}
                  </span>
                ) : (
                  "—"
                )
              }
              sub="用户口径 · WS 实时跳动"
            />
          </Col>
          <Col xs={24} md={6}>
            <div className="metrics-card-with-donut">
              <Donut
                size={110}
                segments={[
                  {
                    label: "多",
                    value: m?.positions.longCount ?? 0,
                    color: "var(--fx-pnl-pos)",
                  },
                  {
                    label: "空",
                    value: m?.positions.shortCount ?? 0,
                    color: "var(--fx-pnl-neg)",
                  },
                ]}
                centerTitle="多 / 空"
                centerValue={
                  <span style={{ fontSize: 13 }}>
                    {fmtCount(m?.positions.longCount)} / {fmtCount(m?.positions.shortCount)}
                  </span>
                }
              />
              <div className="metrics-card-with-donut__legend">
                <span><i style={{ background: "var(--fx-pnl-pos)" }} /> 多 {fmtCount(m?.positions.longCount)}</span>
                <span><i style={{ background: "var(--fx-pnl-neg)" }} /> 空 {fmtCount(m?.positions.shortCount)}</span>
              </div>
            </div>
          </Col>
        </Row>
      </Section>

      {/* ─── §02 平台收入 ─── */}
      <Section num="02" title="平台收入" hint="lifetime + 近 30 天">
        <Row gutter={[12, 12]}>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="FEE"
              label="总手续费收入"
              value={<>${fmtMoney(m?.revenue.feeIncomeAllTime)}</>}
              sub={`近 30 天 +$${fmtMoney(m?.revenue.feeIncome30d)}`}
              tone="long"
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="SWAP"
              label="Swap 净收入"
              value={
                <span className={Number(m?.revenue.swapNetForPlatformAllTime ?? 0) >= 0 ? "metrics-pnl-pos" : "metrics-pnl-neg"}>
                  {fmtSigned(m?.revenue.swapNetForPlatformAllTime)}
                </span>
              }
              sub={`charge ${fmtMoney(m?.revenue.swapChargeAllTime)} · income ${fmtMoney(m?.revenue.swapIncomeAllTime)}`}
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="NET"
              label="平台费用净收入"
              value={<>${fmtMoney(m?.revenue.platformFeeRevenueAllTime)}</>}
              sub={`近 30 天 $${fmtMoney(m?.revenue.platformFeeRevenue30d)}`}
              tone="cyan"
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="ORDERS"
              label="已成交订单"
              value={fmtCount(m?.orders.totalFilledOrders)}
              sub={`今日 +${fmtCount(m?.orders.filledOrdersToday)}`}
            />
          </Col>
        </Row>
        <div className="metrics-revenue-bar" style={{ marginTop: 12 }}>
          <div className="metrics-revenue-bar__head">
            <span>收入构成（lifetime）</span>
            <span>
              ${fmtMoney(
                Number(m?.revenue.feeIncomeAllTime ?? 0) +
                  Math.max(0, Number(m?.revenue.swapNetForPlatformAllTime ?? 0))
              )}
            </span>
          </div>
          <div className="metrics-revenue-bar__track">
            {(() => {
              const fee = Number(m?.revenue.feeIncomeAllTime ?? 0);
              const swapNet = Math.max(0, Number(m?.revenue.swapNetForPlatformAllTime ?? 0));
              const total = fee + swapNet || 1;
              return (
                <>
                  <span
                    className="metrics-revenue-bar__seg metrics-revenue-bar__seg--fee"
                    style={{ width: `${(fee / total) * 100}%` }}
                    title={`手续费 ${fmtMoney(fee)} (${formatPercent((fee / total) * 100, 1)})`}
                  />
                  <span
                    className="metrics-revenue-bar__seg metrics-revenue-bar__seg--swap"
                    style={{ width: `${(swapNet / total) * 100}%` }}
                    title={`Swap 净 ${fmtMoney(swapNet)} (${formatPercent((swapNet / total) * 100, 1)})`}
                  />
                </>
              );
            })()}
          </div>
          <div className="metrics-revenue-bar__legend">
            <span><i style={{ background: "var(--fx-pnl-pos)" }} /> 手续费</span>
            <span><i style={{ background: "var(--fx-cyan)" }} /> Swap 净</span>
          </div>
        </div>
      </Section>

      {/* ─── §03 用户盈亏分布 ─── */}
      <Section num="03" title="用户盈亏分布" hint="基于已平仓持仓累计">
        <Row gutter={[12, 12]}>
          <Col xs={24} md={8}>
            <div className="metrics-card-with-donut metrics-card-with-donut--tall">
              <Donut
                size={160}
                segments={[
                  { label: "盈利", value: m?.userPnl.winningUsers ?? 0, color: "var(--fx-pnl-pos)" },
                  { label: "亏损", value: m?.userPnl.losingUsers ?? 0, color: "var(--fx-pnl-neg)" },
                  { label: "持平", value: m?.userPnl.evenUsers ?? 0, color: "var(--fx-subtle)" },
                ]}
                centerTitle="盈亏分布"
                centerValue={
                  <span style={{ fontSize: 13 }}>
                    {fmtCount((m?.userPnl.winningUsers ?? 0) + (m?.userPnl.losingUsers ?? 0) + (m?.userPnl.evenUsers ?? 0))} 用户
                  </span>
                }
              />
              <div className="metrics-card-with-donut__legend">
                <span><i style={{ background: "var(--fx-pnl-pos)" }} /> 盈利 {fmtCount(m?.userPnl.winningUsers)}</span>
                <span><i style={{ background: "var(--fx-pnl-neg)" }} /> 亏损 {fmtCount(m?.userPnl.losingUsers)}</span>
                <span><i style={{ background: "var(--fx-subtle)" }} /> 持平 {fmtCount(m?.userPnl.evenUsers)}</span>
              </div>
            </div>
          </Col>
          <Col xs={24} md={8}>
            <div className="metrics-extreme metrics-extreme--win">
              <span className="metrics-extreme__eyebrow">BIGGEST WINNER</span>
              <span className="metrics-extreme__label">单笔最大盈利</span>
              <span className="metrics-extreme__amount metrics-pnl-pos">
                {m?.userPnl.biggestWinner.amount == null ? "—" : formatSignedPnl(m.userPnl.biggestWinner.amount)}
              </span>
              <dl className="metrics-extreme__dl">
                <div>
                  <dt>用户</dt>
                  <dd className="metrics-mono" title={String(m?.userPnl.biggestWinner.userId ?? "")}>
                    {shortenUserId(m?.userPnl.biggestWinner.userId)}
                  </dd>
                </div>
                <div>
                  <dt>品种</dt>
                  <dd className="metrics-mono">{m?.userPnl.biggestWinner.symbol ?? "—"}</dd>
                </div>
              </dl>
            </div>
          </Col>
          <Col xs={24} md={8}>
            <div className="metrics-extreme metrics-extreme--lose">
              <span className="metrics-extreme__eyebrow">BIGGEST LOSER</span>
              <span className="metrics-extreme__label">单笔最大亏损</span>
              <span className="metrics-extreme__amount metrics-pnl-neg">
                {m?.userPnl.biggestLoser.amount == null ? "—" : formatSignedPnl(m.userPnl.biggestLoser.amount)}
              </span>
              <dl className="metrics-extreme__dl">
                <div>
                  <dt>用户</dt>
                  <dd className="metrics-mono" title={String(m?.userPnl.biggestLoser.userId ?? "")}>
                    {shortenUserId(m?.userPnl.biggestLoser.userId)}
                  </dd>
                </div>
                <div>
                  <dt>品种</dt>
                  <dd className="metrics-mono">{m?.userPnl.biggestLoser.symbol ?? "—"}</dd>
                </div>
              </dl>
            </div>
          </Col>
        </Row>
        <div className="metrics-revenue-bar" style={{ marginTop: 12 }}>
          <div className="metrics-revenue-bar__head">
            <span>用户累计已实现盈亏（用户口径）</span>
            <span className={Number(m?.positions.totalUserRealizedPnl ?? 0) >= 0 ? "metrics-pnl-pos" : "metrics-pnl-neg"}>
              {m?.positions.totalUserRealizedPnl == null ? "—" : formatSignedPnl(m.positions.totalUserRealizedPnl)}
            </span>
          </div>
          <Text type="secondary" style={{ fontSize: 11, fontFamily: "var(--fx-mono-stack)" }}>
            正 = 用户净盈利、平台净支付 · 负 = 用户净亏损、平台净收入
          </Text>
        </div>
      </Section>

      {/* ─── §04 资金流入流出 ─── */}
      <Section num="04" title="资金流入流出" hint="基于 ledger DEPOSIT_CREDIT / WITHDRAW_SETTLE">
        <Row gutter={[12, 12]}>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="DEPOSIT"
              label="总入金 (lifetime)"
              value={<>${fmtMoney(m?.flows.totalDepositAllTime)}</>}
              sub={`近 30 天 +$${fmtMoney(m?.flows.totalDeposit30d)}`}
              tone="long"
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="WITHDRAW"
              label="总出金 (lifetime)"
              value={<>${fmtMoney(m?.flows.totalWithdrawAllTime)}</>}
              sub={`近 30 天 -$${fmtMoney(m?.flows.totalWithdraw30d)}`}
              tone="warning"
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="NET FLOW"
              label="净流入 (lifetime)"
              value={
                <span className={Number(m?.flows.netFlowAllTime ?? 0) >= 0 ? "metrics-pnl-pos" : "metrics-pnl-neg"}>
                  {fmtSigned(m?.flows.netFlowAllTime)}
                </span>
              }
              sub="入金 − 出金"
            />
          </Col>
          <Col xs={24} md={6}>
            <StatCard
              eyebrow="NET 30D"
              label="净流入 (近 30 天)"
              value={
                <span className={Number(m?.flows.netFlow30d ?? 0) >= 0 ? "metrics-pnl-pos" : "metrics-pnl-neg"}>
                  {fmtSigned(m?.flows.netFlow30d)}
                </span>
              }
              sub="滚动 30 天窗口"
            />
          </Col>
        </Row>
        <div className="metrics-revenue-bar" style={{ marginTop: 12 }}>
          <div className="metrics-revenue-bar__head">
            <span>入 / 出 比例（lifetime）</span>
            <span>
              ${fmtMoney(Number(m?.flows.totalDepositAllTime ?? 0) + Number(m?.flows.totalWithdrawAllTime ?? 0))} 总流转
            </span>
          </div>
          <div className="metrics-revenue-bar__track">
            {(() => {
              const dep = Number(m?.flows.totalDepositAllTime ?? 0);
              const wit = Number(m?.flows.totalWithdrawAllTime ?? 0);
              const total = dep + wit || 1;
              return (
                <>
                  <span
                    className="metrics-revenue-bar__seg"
                    style={{ width: `${(dep / total) * 100}%`, background: "var(--fx-pnl-pos)" }}
                    title={`入金 ${fmtMoney(dep)} (${formatPercent((dep / total) * 100, 1)})`}
                  />
                  <span
                    className="metrics-revenue-bar__seg"
                    style={{ width: `${(wit / total) * 100}%`, background: "var(--fx-console-warning)" }}
                    title={`出金 ${fmtMoney(wit)} (${formatPercent((wit / total) * 100, 1)})`}
                  />
                </>
              );
            })()}
          </div>
          <div className="metrics-revenue-bar__legend">
            <span><i style={{ background: "var(--fx-pnl-pos)" }} /> 入金</span>
            <span><i style={{ background: "var(--fx-console-warning)" }} /> 出金</span>
          </div>
        </div>
      </Section>

      {/* ─── §05 持仓生命周期 ─── */}
      <Section num="05" title="持仓生命周期" hint="历史累计统计">
        <Row gutter={[12, 12]}>
          <Col xs={24} md={8}>
            <StatCard
              eyebrow="OPEN"
              label="当前持仓中"
              value={fmtCount(m?.positions.openCount)}
              sub="status = 1"
              tone="cyan"
            />
          </Col>
          <Col xs={24} md={8}>
            <StatCard
              eyebrow="CLOSED"
              label="累计已平仓"
              value={fmtCount(m?.positions.closedCount)}
              sub="用户主动 / TP/SL"
            />
          </Col>
          <Col xs={24} md={8}>
            <StatCard
              eyebrow="LIQUIDATED"
              label="累计已强平"
              value={fmtCount(m?.positions.liquidatedCount)}
              sub="风控强平 / 维持率不足"
              tone="short"
            />
          </Col>
        </Row>
      </Section>
    </div>
  );
}
