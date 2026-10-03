import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Layout,
  Space,
  Typography,
  message as antdMessage,
} from "antd";
import { KeyOutlined, LockOutlined, SafetyOutlined } from "@ant-design/icons";
import { ApiError } from "../../lib/api/apiClient";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { useThemeStore } from "../../lib/theme/themeStore";
import { adminAuthApi } from "./adminAuthApi";
import { FalconMark } from "../../components/brand/FalconMark";
import { ThemeSwitch } from "../settings/ThemeSwitch";

const { Title, Text } = Typography;

interface FormValues {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

const PASSWORD_MIN_LENGTH = 12;
const SPECIAL_CHARS = "!@#$%^&*()_+-=[]{};:'\"\\|,.<>/?`~";

function checkPasswordPolicy(pwd: string): string | null {
  if (pwd.length < PASSWORD_MIN_LENGTH) return `密码长度至少 ${PASSWORD_MIN_LENGTH} 字符`;
  if (!/[A-Z]/.test(pwd)) return "密码必须包含大写字母";
  if (!/[a-z]/.test(pwd)) return "密码必须包含小写字母";
  if (!/\d/.test(pwd)) return "密码必须包含数字";
  if (![...pwd].some((c) => SPECIAL_CHARS.includes(c))) return "密码必须包含特殊字符";
  return null;
}

export function ChangePasswordPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const clear = useAdminAuthStore((s) => s.clear);
  const themeMode = useThemeStore((s) => s.theme);
  const pageBackground =
    themeMode === "dark"
      ? "radial-gradient(circle at 18% 20%, rgba(84, 230, 255, 0.08), transparent 38%)," +
        "radial-gradient(circle at 82% 80%, rgba(168, 255, 92, 0.05), transparent 45%)," +
        "linear-gradient(180deg, #050608 0%, #0b0e12 100%)"
      : "radial-gradient(circle at 18% 20%, rgba(8, 145, 178, 0.07), transparent 38%)," +
        "radial-gradient(circle at 82% 80%, rgba(124, 58, 237, 0.05), transparent 45%)," +
        "linear-gradient(180deg, #f4f7fb 0%, #eef3f8 100%)";
  const forced = (location.state as { forced?: boolean } | null)?.forced ?? false;
  const [submitting, setSubmitting] = useState(false);
  const [errorAlert, setErrorAlert] = useState<string | null>(null);

  const handleSubmit = async (values: FormValues) => {
    setSubmitting(true);
    setErrorAlert(null);
    try {
      await adminAuthApi.changePassword(values.oldPassword, values.newPassword);
      antdMessage.success("密码修改成功，请重新登录");
      clear();
      navigate("/admin/login", { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorAlert(`修改失败 (${err.code})：${err.message}`);
      } else {
        setErrorAlert("网络异常，请稍后重试");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Layout
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: pageBackground,
        padding: 16,
        position: "relative",
      }}
    >
      <div style={{ position: "absolute", top: 20, right: 20 }}>
        <ThemeSwitch />
      </div>
      <Card
        style={{
          width: 480,
          background: "var(--fx-surface-1)",
          border: "1px solid var(--fx-border)",
        }}
        variant="outlined"
      >
        <div style={{ textAlign: "center", marginBottom: 24 }}>
          <FalconMark style={{ width: 56, height: 34, color: "var(--fx-cyan)", marginBottom: 10 }} />
          <Title
            level={3}
            style={{
              marginBottom: 4,
              color: "var(--fx-text)",
              fontFamily: "var(--fx-mono-stack)",
              letterSpacing: "0.04em",
            }}
          >
            修改密码
          </Title>
          <Text
            type="secondary"
            style={{
              fontFamily: "var(--fx-mono-stack)",
              fontSize: 11,
              letterSpacing: "0.22em",
              textTransform: "uppercase",
            }}
          >
            Security · Credentials · Rotation
          </Text>
        </div>
        {forced && (
          <Alert
            type="warning"
            showIcon
            message="您是首次登录系统，请先修改默认密码后再继续。"
            style={{ marginBottom: 16 }}
          />
        )}
        {errorAlert && (
          <Alert
            type="error"
            message={errorAlert}
            showIcon
            closable
            onClose={() => setErrorAlert(null)}
            style={{ marginBottom: 16 }}
          />
        )}
        <Form<FormValues> layout="vertical" onFinish={handleSubmit} autoComplete="off">
          <Form.Item
            name="oldPassword"
            label="旧密码"
            rules={[{ required: true, message: "请输入旧密码" }]}
          >
            <Input.Password
              prefix={<KeyOutlined />}
              size="large"
              placeholder="请输入旧密码"
            />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: "请输入新密码" },
              {
                validator: async (_, value: string) => {
                  if (!value) return;
                  const issue = checkPasswordPolicy(value);
                  if (issue) throw new Error(issue);
                },
              },
            ]}
            extra="至少 12 字符，需包含大小写、数字、特殊字符"
          >
            <Input.Password
              prefix={<LockOutlined />}
              size="large"
              placeholder="请输入新密码"
            />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={["newPassword"]}
            rules={[
              { required: true, message: "请再次输入新密码" },
              ({ getFieldValue }) => ({
                async validator(_, value: string) {
                  if (!value || value === getFieldValue("newPassword")) return;
                  throw new Error("两次密码不一致");
                },
              }),
            ]}
          >
            <Input.Password
              prefix={<SafetyOutlined />}
              size="large"
              placeholder="请再次输入新密码"
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Space style={{ width: "100%" }} direction="vertical" size="middle">
              <Button type="primary" htmlType="submit" size="large" block loading={submitting}>
                提交修改
              </Button>
              {!forced && (
                <Button block onClick={() => navigate("/admin")} disabled={submitting}>
                  取消
                </Button>
              )}
            </Space>
          </Form.Item>
        </Form>
        <div style={{ textAlign: "center", marginTop: 20 }}>
          <Text
            type="secondary"
            style={{
              fontSize: 11,
              fontFamily: "var(--fx-mono-stack)",
              letterSpacing: "0.16em",
              textTransform: "uppercase",
            }}
          >
            v1.0.0 · 内部系统 · IP 限制
          </Text>
        </div>
      </Card>
    </Layout>
  );
}
