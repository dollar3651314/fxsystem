import { useState } from "react";
import { Alert, Descriptions, Form, Input, Modal } from "antd";
import { WarningOutlined } from "@ant-design/icons";
import { ApiError } from "../../lib/api/apiClient";

interface HighRiskConfirmModalProps<TResult> {
  open: boolean;
  title: string;
  okText: string;
  okDanger?: boolean;
  description: string;
  /** 显示在 Descriptions 区块的字段对（label/value）。 */
  details: Array<[string, string]>;
  onSubmit: (reason: string) => Promise<TResult>;
  onSuccess: (result: TResult) => void;
  onCancel: () => void;
  /** Children 用于注入额外字段（如调余额的金额输入）。reason TextArea 必然存在。 */
  children?: React.ReactNode;
  /** 是否需要勾选确认（C5 调余额最高风险用）。 */
  requireConfirmCheckbox?: boolean;
  /** 自定义 reason 之外的提交校验。返回 null 表示通过；返回错误消息字符串拒绝提交。 */
  validate?: () => string | null;
  /**
   * Phase 4 §4 commit C：用户名挑战项。
   * 当设置时，渲染一个文本输入框，用户必须输入匹配 {@code expected} 才能提交。
   * 用于最高危操作（emergency-cancel 等）防误操作。{@code label} 自定义提示文案
   * （如"请输入您的用户名 / 用户邮箱后 5 位"），不传则默认"请输入挑战值"。
   */
  usernameChallenge?: { expected: string; label?: string };
}

export function HighRiskConfirmModal<TResult>({
  open,
  title,
  okText,
  okDanger = true,
  description,
  details,
  onSubmit,
  onSuccess,
  onCancel,
  children,
  requireConfirmCheckbox = false,
  validate,
  usernameChallenge,
}: HighRiskConfirmModalProps<TResult>) {
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [challenge, setChallenge] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const reasonOk = reason.length >= 10;
  const checkboxOk = !requireConfirmCheckbox || confirmed;
  const challengeOk = !usernameChallenge || challenge === usernameChallenge.expected;
  const canSubmit = reasonOk && checkboxOk && challengeOk && !submitting;

  const handleOk = async () => {
    if (validate) {
      const validateMsg = validate();
      if (validateMsg) {
        setErrorMsg(validateMsg);
        return;
      }
    }
    setSubmitting(true);
    setErrorMsg(null);
    try {
      const result = await onSubmit(reason);
      onSuccess(result);
      setReason("");
      setConfirmed(false);
      setChallenge("");
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMsg(`${err.code} ${err.message}`);
      } else {
        setErrorMsg("提交失败，请重试");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    setReason("");
    setConfirmed(false);
    setChallenge("");
    setErrorMsg(null);
    onCancel();
  };

  return (
    <Modal
      open={open}
      title={
        <span>
          <WarningOutlined style={{ color: "#d48806", marginRight: 8 }} />
          高风险操作 - {title}
        </span>
      }
      okText={okText}
      okType={okDanger ? "danger" : "primary"}
      okButtonProps={{ disabled: !canSubmit, loading: submitting }}
      cancelText="取消"
      onOk={handleOk}
      onCancel={handleCancel}
      maskClosable={false}
      width={560}
    >
      <Alert type="warning" message={description} style={{ marginBottom: 16 }} />
      <Descriptions size="small" column={1} bordered style={{ marginBottom: 16 }}>
        {details.map(([label, value]) => (
          <Descriptions.Item key={label} label={label}>
            {value}
          </Descriptions.Item>
        ))}
      </Descriptions>
      {children}
      <Form layout="vertical">
        <Form.Item
          label="操作原因（必填，≥ 10 字符）"
          extra={`已输入 ${reason.length} 字符`}
          required
        >
          <Input.TextArea
            rows={4}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="请详细说明操作原因（用于审计追溯）"
          />
        </Form.Item>
        {usernameChallenge && (
          <Form.Item
            label={usernameChallenge.label ?? "请输入挑战值"}
            extra={challenge && !challengeOk ? "挑战值不匹配" : "用于最高危操作防误操作"}
            validateStatus={challenge && !challengeOk ? "error" : undefined}
            required
          >
            <Input
              value={challenge}
              onChange={(e) => setChallenge(e.target.value)}
              placeholder="请输入"
              autoComplete="off"
            />
          </Form.Item>
        )}
        {requireConfirmCheckbox && (
          <Form.Item>
            <label style={{ cursor: "pointer" }}>
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(e) => setConfirmed(e.target.checked)}
                style={{ marginRight: 8 }}
              />
              我已确认调整金额、客户身份和操作原因正确
            </label>
          </Form.Item>
        )}
        {errorMsg && <Alert type="error" message={errorMsg} closable onClose={() => setErrorMsg(null)} />}
      </Form>
    </Modal>
  );
}
