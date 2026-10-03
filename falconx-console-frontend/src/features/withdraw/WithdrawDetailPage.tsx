import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  Modal,
  Result,
  Row,
  Space,
  Spin,
  Tooltip,
  Typography,
  message,
} from "antd";
import { withdrawApi } from "./withdrawApi";
import type { AdminWithdrawItem, WithdrawStatus } from "./types";
import { WITHDRAW_STATUS_META } from "./types";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { ApiError } from "../../lib/api/apiClient";
import { useAdminTradingSocket } from "../trading/useAdminTradingSocket";
import { getAdminAccessToken } from "../../lib/auth/adminTokenStorage";
import { txExplorerUrlByNetwork } from "../../lib/chain";
import { formatMoney } from "../../lib/precision";

const { Title, Text } = Typography;

// explorer URL 集中在 src/lib/chain.ts，跟入金 / 客户端钱包同口径

function statusInfo(status: WithdrawStatus): { color: string; label: string; icon: string } {
  return WITHDRAW_STATUS_META[status];
}

/**
 * STAGE-7-WITHDRAW Phase 4 出金详情工作台（路径 /admin/withdraws/:id，权限 withdraw:view）。
 *
 * R3 设计 §3 落地：基本信息 + 链上进度 + 3 个高危 action（approve / reject / emergency-cancel），
 * 后两个 reason 必填走 HighRiskConfirmModal，approve 用简化 Modal（reviewNote 可选）。
 *
 * RBAC（R3 §5）：
 * - 列表 / 详情：withdraw:view
 * - approve / reject 按钮：withdraw:review，缺权限 → disabled + Tooltip
 * - emergency-cancel：withdraw:emergency-cancel，缺权限 → disabled + Tooltip
 *
 * 状态依赖：
 * - approve / reject 仅 status=PENDING 启用
 * - emergency-cancel 仅 status=APPROVED_DELAYED 启用
 */
export function WithdrawDetailPage() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();

  const permissions = useAdminAuthStore((s) => s.permissions);
  const isSuperAdmin = useAdminAuthStore((s) => s.isSuperAdmin);
  const user = useAdminAuthStore((s) => s.user);
  const hasPerm = (code: string) => isSuperAdmin || permissions.includes(code);

  const canView = hasPerm("withdraw:view");
  const canReview = hasPerm("withdraw:review");
  const canEmergencyCancel = hasPerm("withdraw:emergency-cancel");

  const [item, setItem] = useState<AdminWithdrawItem | null>(null);
  const [loading, setLoading] = useState(false);
  const [errorCode, setErrorCode] = useState<string | null>(null);

  const [approveOpen, setApproveOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [emergencyCancelOpen, setEmergencyCancelOpen] = useState(false);

  const load = () => {
    if (!id) return;
    setLoading(true);
    withdrawApi
      .detail(id)
      .then((data) => {
        setItem(data);
        setErrorCode(null);
      })
      .catch((err) => setErrorCode((err as ApiError).code ?? "UNKNOWN"))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, [id]);

  // Phase 4 §4 commit C: 订阅 admin.withdraws 频道，实时刷新本单状态变化
  const wsToken = getAdminAccessToken();
  useAdminTradingSocket(wsToken, {
    onWithdrawStatusChanged: (event) => {
      if (id && event.withdrawId === id) {
        // 当前页正看的这单状态变了 → 重新拉详情显示最新链上数据
        load();
      }
    },
  });

  if (loading && !item) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 64 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (errorCode === "90500") {
    return (
      <Result
        status="404"
        title="出金单不存在"
        extra={<Button onClick={() => navigate("/admin/withdraws")}>返回列表</Button>}
      />
    );
  }

  if (errorCode === "90004" || !canView) {
    return (
      <Result
        status="403"
        title="无权限"
        subTitle="您当前账号无 withdraw:view 权限。"
        extra={<Button onClick={() => navigate("/admin/withdraws")}>返回列表</Button>}
      />
    );
  }

  if (!item) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={errorCode ? `错误码 ${errorCode}` : "未知错误"}
        extra={<Button onClick={load}>重试</Button>}
      />
    );
  }

  const meta = statusInfo(item.status);
  const canApprove = canReview && item.status === "PENDING";
  const canReject = canReview && item.status === "PENDING";
  const showEmergencyCancel = item.status === "APPROVED_DELAYED";

  const txUrl = item.txHash ? txExplorerUrlByNetwork(item.network, item.txHash) : null;

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate("/admin/withdraws")}>← 返回</Button>
        <Button onClick={load}>刷新</Button>
      </Space>

      <Title level={3}>
        出金单 <span style={{ fontFamily: "monospace" }}>#{item.withdrawId}</span>
      </Title>

      <Alert
        type={meta.color === "red" ? "error" : meta.color === "orange" ? "warning" : meta.color === "green" ? "success" : "info"}
        message={`状态：${meta.icon} ${meta.label}`}
        description={
          item.status === "APPROVED_DELAYED" && item.delayedUntil
            ? `延迟期至 ${item.delayedUntil}，期间可执行紧急取消`
            : item.status === "REJECTED" && item.rejectReason
            ? `拒绝原因：${item.rejectReason}`
            : item.status === "FAILED" && item.failureReason
            ? `失败原因：${item.failureReason}`
            : undefined
        }
        showIcon
        style={{ marginBottom: 16 }}
      />

      <Row gutter={16}>
        <Col span={14}>
          <Card title="基本信息" style={{ marginBottom: 16 }}>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="用户 ID">
                <span style={{ fontFamily: "monospace" }}>{item.userId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="用户姓名">{item.userFullName ?? "—"}</Descriptions.Item>
              <Descriptions.Item label="用户 UID">{item.userUid ?? "—"}</Descriptions.Item>
              <Descriptions.Item label="金额">
                {Number(item.amount).toFixed(8)} {item.currency}
              </Descriptions.Item>
              <Descriptions.Item label="网络">{item.network}</Descriptions.Item>
              <Descriptions.Item label="目标地址">
                <Space>
                  <Text copyable={{ text: item.targetAddress }} style={{ fontFamily: "monospace" }}>
                    {item.targetAddress}
                  </Text>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="提交时间">{item.createdAt}</Descriptions.Item>
              {item.coolingUntil && (
                <Descriptions.Item label="冷静期至">{item.coolingUntil}</Descriptions.Item>
              )}
            </Descriptions>
          </Card>

          {(item.txHash || item.confirmations != null) && (
            <Card title="链上进度">
              <Descriptions column={1} bordered size="small">
                {item.txHash && (
                  <Descriptions.Item label="链上 tx">
                    {txUrl ? (
                      <a href={txUrl} target="_blank" rel="noopener noreferrer">
                        <span style={{ fontFamily: "monospace" }}>{item.txHash}</span> 🔗
                      </a>
                    ) : (
                      <span style={{ fontFamily: "monospace" }}>{item.txHash}</span>
                    )}
                  </Descriptions.Item>
                )}
                {item.confirmations != null && (
                  <Descriptions.Item label="确认数">
                    {item.confirmations} / 12 {item.confirmations >= 12 ? "✅" : "⏳"}
                  </Descriptions.Item>
                )}
              </Descriptions>
            </Card>
          )}
        </Col>

        <Col span={10}>
          <Card title="审核操作（高危）" style={{ marginBottom: 16 }}>
            <Space direction="vertical" style={{ width: "100%" }}>
              <Tooltip
                title={
                  !canReview
                    ? "无 withdraw:review 权限"
                    : item.status !== "PENDING"
                    ? `当前状态 ${meta.label}，仅 PENDING 可操作`
                    : ""
                }
              >
                <Button
                  type="primary"
                  block
                  disabled={!canApprove}
                  onClick={() => setApproveOpen(true)}
                >
                  ✅ 通过出金
                </Button>
              </Tooltip>

              <Tooltip
                title={
                  !canReview
                    ? "无 withdraw:review 权限"
                    : item.status !== "PENDING"
                    ? `当前状态 ${meta.label}，仅 PENDING 可操作`
                    : ""
                }
              >
                <Button danger block disabled={!canReject} onClick={() => setRejectOpen(true)}>
                  ❌ 拒绝出金
                </Button>
              </Tooltip>

              {showEmergencyCancel && (
                <Tooltip title={!canEmergencyCancel ? "无 withdraw:emergency-cancel 权限" : ""}>
                  <Button
                    danger
                    type="dashed"
                    block
                    disabled={!canEmergencyCancel}
                    onClick={() => setEmergencyCancelOpen(true)}
                  >
                    ⚠️ 紧急取消（最高危）
                  </Button>
                </Tooltip>
              )}
            </Space>
          </Card>

          <Card title="用户信息" size="small">
            <Descriptions column={1} size="small">
              <Descriptions.Item label="User ID">
                <span style={{ fontFamily: "monospace" }}>{item.userId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="邮箱">
                {item.userEmail ? (
                  <span>{item.userEmail}</span>
                ) : (
                  <Text type="secondary">—（identity 不可达或用户不存在）</Text>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="KYC 等级">
                {item.kycLevel != null ? (
                  item.kycLevel >= 1 ? (
                    <span>✅ {item.kycLevel}（已认证）</span>
                  ) : (
                    <span style={{ color: "#fa8c16" }}>⚠️ {item.kycLevel}（未认证）</span>
                  )
                ) : (
                  <Text type="secondary">—</Text>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="当日累计 / 上限">
                {(() => {
                  if (item.dailyAccumulatedUsd == null) {
                    return <Text type="secondary">—</Text>;
                  }
                  const used = Number(item.dailyAccumulatedUsd);
                  const limit = 30000;
                  const remaining = Math.max(0, limit - used);
                  const warn = used >= 24000;
                  return (
                    <span style={warn ? { color: "#fa8c16", fontWeight: 500 } : undefined}>
                      {formatMoney(used, "USDT")} / {formatMoney(limit, "USDT")} USDT
                      <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                        （余额上限 {formatMoney(remaining, "USDT")}）
                      </Text>
                    </span>
                  );
                })()}
              </Descriptions.Item>
            </Descriptions>
            <Text type="secondary" style={{ fontSize: 12 }}>
              近 30 天出金画像需 trading-core 新内部 RPC（R2 设计专项），下轮补齐。
            </Text>
          </Card>
        </Col>
      </Row>

      {/* Approve modal（reviewNote 可选，不走 HighRiskConfirmModal 因 reason 强校验 10+ 字符不适用） */}
      <ApproveModal
        open={approveOpen}
        item={item}
        onCancel={() => setApproveOpen(false)}
        onSuccess={() => {
          setApproveOpen(false);
          message.success("已通过");
          load();
        }}
      />

      {/* Reject modal（reason 必填 10+ 字符） */}
      <HighRiskConfirmModal
        open={rejectOpen}
        title="高危操作确认：拒绝出金"
        okText="确认拒绝"
        description={`拒绝用户 ${item.userId} 的出金申请 ${item.amount} ${item.currency}，余额将立即解冻。`}
        details={[
          ["出金 ID", item.withdrawId],
          ["用户", item.userId],
          ["金额", `${item.amount} ${item.currency}`],
          ["目标地址", `${item.network} ${item.targetAddress}`],
        ]}
        onSubmit={(reason) => withdrawApi.reject(item.withdrawId, reason)}
        onSuccess={() => {
          setRejectOpen(false);
          message.success("已拒绝");
          load();
        }}
        onCancel={() => setRejectOpen(false)}
      />

      {/* Emergency-cancel modal（reason 必填 10+ 字符 + 需勾选确认） */}
      <HighRiskConfirmModal
        open={emergencyCancelOpen}
        title="最高危操作确认：紧急取消"
        okText="⚠️ 立即紧急取消"
        description={
          item.delayedUntil
            ? `此出金已进入延迟期（至 ${item.delayedUntil}），紧急取消后余额立即解冻。`
            : "紧急取消后余额立即解冻。"
        }
        details={[
          ["出金 ID", item.withdrawId],
          ["用户", item.userId],
          ["金额", `${item.amount} ${item.currency}`],
          ["目标地址", `${item.network} ${item.targetAddress}`],
        ]}
        requireConfirmCheckbox
        usernameChallenge={
          user
            ? { expected: user.username, label: `请输入您的用户名 "${user.username}" 确认` }
            : undefined
        }
        onSubmit={(reason) => withdrawApi.emergencyCancel(item.withdrawId, reason)}
        onSuccess={() => {
          setEmergencyCancelOpen(false);
          message.success("已紧急取消");
          load();
        }}
        onCancel={() => setEmergencyCancelOpen(false)}
      />
    </div>
  );
}

/** approve 专用简化 Modal：reviewNote 可选（HighRiskConfirmModal 不适用，因强校验 reason 10+）。 */
function ApproveModal({
  open,
  item,
  onCancel,
  onSuccess,
}: {
  open: boolean;
  item: AdminWithdrawItem;
  onCancel: () => void;
  onSuccess: () => void;
}) {
  const [reviewNote, setReviewNote] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const handleOk = async () => {
    setSubmitting(true);
    setErrorMsg(null);
    try {
      await withdrawApi.approve(item.withdrawId, reviewNote);
      onSuccess();
      setReviewNote("");
    } catch (err) {
      const apiErr = err as ApiError;
      setErrorMsg(`${apiErr.code ?? ""} ${apiErr.message ?? "提交失败"}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="通过出金"
      okText="确认通过"
      okButtonProps={{ loading: submitting }}
      onOk={handleOk}
      onCancel={() => {
        setReviewNote("");
        setErrorMsg(null);
        onCancel();
      }}
    >
      <Descriptions column={1} bordered size="small" style={{ marginBottom: 12 }}>
        <Descriptions.Item label="出金 ID">{item.withdrawId}</Descriptions.Item>
        <Descriptions.Item label="用户">{item.userId}</Descriptions.Item>
        <Descriptions.Item label="金额">
          {item.amount} {item.currency}
        </Descriptions.Item>
        <Descriptions.Item label="目标地址">
          {item.network} {item.targetAddress}
        </Descriptions.Item>
      </Descriptions>
      <Form layout="vertical">
        <Form.Item label="审核备注（可选，最多 512 字符）" name="reviewNote">
          <Input.TextArea
            value={reviewNote}
            onChange={(e) => setReviewNote(e.target.value)}
            maxLength={512}
            rows={3}
            placeholder="可选审核备注"
          />
        </Form.Item>
      </Form>
      {errorMsg && <Alert type="error" message={errorMsg} showIcon />}
    </Modal>
  );
}
