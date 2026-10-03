import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Badge, Card, Descriptions, Spin, Switch, Typography, message } from "antd";
import { tradingApi } from "./tradingApi";
import { AutoLiquidateSwitchModal } from "./AutoLiquidateSwitchModal";
import type { RiskSwitchItem } from "./types";
import { useAdminTradingSocket } from "./useAdminTradingSocket";
import { getAdminAccessToken } from "../../lib/auth/adminTokenStorage";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";

const { Title } = Typography;

const KEY_AUTO_LIQUIDATE = "auto_liquidate.enabled";

export function TradingRiskSwitchPage() {
  const [items, setItems] = useState<RiskSwitchItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [pendingTarget, setPendingTarget] = useState<boolean>(false);

  const load = () => {
    setLoading(true);
    tradingApi
      .listRiskSwitches()
      .then((data) => setItems(data.items))
      .catch((err) => message.error(`加载风控开关失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, []);

  // STAGE-2-REALTIME-DATA Phase 4：admin.risk-switch.changed 触发 refetch
  // 开关项较少（V1 只 1 项）+ 不需要细颗粒增量，refetch 最简
  const user = useAdminAuthStore((s) => s.user);
  const token = useMemo(() => (user ? getAdminAccessToken() : null), [user]);
  const handleRiskSwitchChanged = useCallback(() => load(), []);
  const socketState = useAdminTradingSocket(token, {
    onRiskSwitchChanged: handleRiskSwitchChanged,
  });

  const autoLiquidate = items.find((i) => i.key === KEY_AUTO_LIQUIDATE);

  return (
    <div>
      <Title level={3}>
        风控开关
        <Badge
          status={socketState === "open" ? "success" : socketState === "error" ? "error" : "default"}
          text={socketState === "open" ? "实时" : socketState}
          style={{ marginLeft: 12, fontSize: 14, fontWeight: 400 }}
        />
      </Title>
      <Alert
        message="全局风控开关"
        description="关闭自动强平后，trading-core 强平 worker 不再触发强平。请仅在维护窗口使用。"
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
      />
      {loading ? (
        <div style={{ textAlign: "center", padding: 48 }}>
          <Spin size="large" />
        </div>
      ) : (
        <Card title="自动强平" style={{ maxWidth: 600 }}>
          {autoLiquidate ? (
            <>
              <div style={{ marginBottom: 16, display: "flex", alignItems: "center", gap: 12 }}>
                <Switch
                  checked={autoLiquidate.enabled}
                  onChange={(checked) => {
                    setPendingTarget(checked);
                    setModalOpen(true);
                  }}
                />
                <span>{autoLiquidate.enabled ? "已启用" : "已暂停"}</span>
              </div>
              <Descriptions size="small" column={1} bordered>
                <Descriptions.Item label="更新人">{autoLiquidate.updatedBy ?? "—"}</Descriptions.Item>
                <Descriptions.Item label="更新时间">{autoLiquidate.updatedAt}</Descriptions.Item>
                <Descriptions.Item label="备注">{autoLiquidate.reason ?? "—"}</Descriptions.Item>
              </Descriptions>
            </>
          ) : (
            <span style={{ color: "var(--fx-console-text-muted)" }}>未找到 {KEY_AUTO_LIQUIDATE} 配置项</span>
          )}
        </Card>
      )}
      <AutoLiquidateSwitchModal
        open={modalOpen}
        currentEnabled={autoLiquidate?.enabled ?? true}
        targetEnabled={pendingTarget}
        onClose={() => setModalOpen(false)}
        onSuccess={load}
      />
    </div>
  );
}
