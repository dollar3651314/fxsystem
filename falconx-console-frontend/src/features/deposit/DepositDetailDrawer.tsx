import { useEffect, useState } from "react";
import { Button, Descriptions, Drawer, Space, Spin, Tag, Typography, message } from "antd";
import { useNavigate } from "react-router-dom";
import { depositApi } from "./depositApi";
import type { DepositCreditStatus, DepositItem, SnowflakeId } from "./types";

const CREDIT_BADGE: Record<DepositCreditStatus, { color: string; text: string }> = {
  CREDITED: { color: "success", text: "已入账" },
  REJECTED: { color: "error", text: "已拒收" },
  REVERSED: { color: "warning", text: "已回滚" },
  PENDING: { color: "default", text: "等待入账" },
  UNKNOWN: { color: "default", text: "未知" },
};
import { EXPLORER_NAME, networkOf, txExplorerUrl } from "../../lib/chain";

const { Text } = Typography;

interface Props {
  open: boolean;
  depositId: SnowflakeId | null;
  onClose: () => void;
}

export function DepositDetailDrawer({ open, depositId, onClose }: Props) {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [item, setItem] = useState<DepositItem | null>(null);

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!open || !depositId) {
      return;
    }
    setItem(null);
    setLoading(true);
    depositApi
      .detail(depositId)
      .then(setItem)
      .catch((err) => message.error(`加载详情失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  }, [open, depositId]);
  /* eslint-enable react-hooks/set-state-in-effect */

  const openExplorer = () => {
    if (!item) return;
    try {
      const url = txExplorerUrl(item.chain, item.txHash);
      window.open(url, "_blank", "noopener");
    } catch {
      void message.warning("未配置该链的 explorer 链接");
    }
  };
  const explorerName = item ? EXPLORER_NAME[item.chain] : "";

  return (
    <Drawer
      title="入金记录详情"
      open={open}
      onClose={onClose}
      width={600}
      destroyOnHidden
      footer={
        item ? (
          <Space>
            <Button onClick={openExplorer}>
              在 {explorerName} 查看 txHash
            </Button>
            {item.userId && (
              <Button type="primary" onClick={() => navigate(`/admin/customers/${item.userId}`)}>
                跳转客户详情
              </Button>
            )}
          </Space>
        ) : undefined
      }
    >
      {loading ? (
        <div style={{ textAlign: "center", padding: 48 }}>
          <Spin size="large" />
        </div>
      ) : item ? (
        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="ID">{item.id}</Descriptions.Item>
          <Descriptions.Item label="用户 ID">{item.userId ?? <em>orphan（未归属）</em>}</Descriptions.Item>
          <Descriptions.Item label="用户姓名">{item.userFullName ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="用户邮箱">{item.userEmail ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="用户 UID">{item.userUid ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="链">
            {item.chain}
            {networkOf(item.chain) && (
              <Tag color="geekblue" style={{ marginLeft: 8 }}>{networkOf(item.chain)}</Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="Token">{item.token}</Descriptions.Item>
          <Descriptions.Item label="Token 合约地址">{item.tokenContractAddress ?? "—（原生币）"}</Descriptions.Item>
          <Descriptions.Item label="txHash"><Text copyable code style={{ wordBreak: "break-all" }}>{item.txHash}</Text></Descriptions.Item>
          <Descriptions.Item label="logIndex">{item.logIndex}</Descriptions.Item>
          <Descriptions.Item label="from"><Text copyable code style={{ wordBreak: "break-all" }}>{item.fromAddress}</Text></Descriptions.Item>
          <Descriptions.Item label="to"><Text copyable code style={{ wordBreak: "break-all" }}>{item.toAddress}</Text></Descriptions.Item>
          <Descriptions.Item label="金额">{item.amount}</Descriptions.Item>
          <Descriptions.Item label="区块高度">{item.blockNumber ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="确认数">{item.confirmations} / {item.requiredConfirms}</Descriptions.Item>
          <Descriptions.Item label="链上状态">{item.status}</Descriptions.Item>
          <Descriptions.Item label="入账状态">
            {(() => {
              const cs = item.creditStatus ?? "PENDING";
              const badge = CREDIT_BADGE[cs] ?? CREDIT_BADGE.UNKNOWN;
              return (
                <span>
                  <Tag color={badge.color}>{badge.text}</Tag>
                  {cs === "REJECTED" && item.rejectionReason && (
                    <span style={{ marginLeft: 8, color: "var(--fx-console-text-muted, #888)" }}>
                      原因：<code>{item.rejectionReason}</code>
                    </span>
                  )}
                  {cs === "PENDING" && (
                    <span style={{ marginLeft: 8, color: "var(--fx-console-text-muted, #888)" }}>
                      trading-core 尚未消费 / 写入 t_deposit
                    </span>
                  )}
                </span>
              );
            })()}
          </Descriptions.Item>
          <Descriptions.Item label="检测时间">{item.detectedAt}</Descriptions.Item>
          <Descriptions.Item label="确认时间">{item.confirmedAt ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{item.updatedAt}</Descriptions.Item>
        </Descriptions>
      ) : null}
    </Drawer>
  );
}
