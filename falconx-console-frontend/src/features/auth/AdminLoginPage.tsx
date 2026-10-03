import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, Button, Card, Form, Input, Layout, Result, Typography } from "antd";
import { LockOutlined, UserOutlined } from "@ant-design/icons";
import { ApiError } from "../../lib/api/apiClient";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { useThemeStore } from "../../lib/theme/themeStore";
import { adminAuthApi } from "./adminAuthApi";
import { FalconMark } from "../../components/brand/FalconMark";
import { ThemeSwitch } from "../settings/ThemeSwitch";

const { Title, Text } = Typography;

interface FormValues {
  username: string;
  password: string;
}

export function AdminLoginPage() {
  const navigate = useNavigate();
  const loginSuccess = useAdminAuthStore((s) => s.loginSuccess);
  const themeMode = useThemeStore((s) => s.theme);
  const loginBackground =
    themeMode === "dark"
      ? "radial-gradient(circle at 18% 20%, rgba(84, 230, 255, 0.08), transparent 38%)," +
        "radial-gradient(circle at 82% 80%, rgba(168, 255, 92, 0.05), transparent 45%)," +
        "linear-gradient(180deg, #050608 0%, #0b0e12 100%)"
      : "radial-gradient(circle at 18% 20%, rgba(8, 145, 178, 0.07), transparent 38%)," +
        "radial-gradient(circle at 82% 80%, rgba(124, 58, 237, 0.05), transparent 45%)," +
        "linear-gradient(180deg, #f4f7fb 0%, #eef3f8 100%)";
  const [submitting, setSubmitting] = useState(false);
  const [errorAlert, setErrorAlert] = useState<string | null>(null);
  const [terminalError, setTerminalError] = useState<{ title: string; subTitle: string } | null>(
    null,
  );

  if (terminalError) {
    return (
      <Layout style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center" }}>
        <Result status="error" title={terminalError.title} subTitle={terminalError.subTitle} />
      </Layout>
    );
  }

  const handleSubmit = async (values: FormValues) => {
    setSubmitting(true);
    setErrorAlert(null);
    try {
      const response = await adminAuthApi.login(values.username, values.password);
      loginSuccess(response.accessToken, response.refreshToken, response.accessTokenExpiresIn, {
        adminUserId: response.adminUserId,
        username: response.username,
        realName: response.realName,
        roles: response.roles,
        mustChangePassword: response.mustChangePassword,
      });
      if (response.mustChangePassword) {
        navigate("/admin/change-password", { replace: true, state: { forced: true } });
      } else {
        navigate("/admin", { replace: true });
      }
    } catch (err) {
      if (err instanceof ApiError) {
        switch (err.code) {
          case "90001":
            setErrorAlert("用户名或密码错误");
            break;
          case "90006":
            setTerminalError({
              title: "账号已锁定",
              subTitle: "登录失败次数过多，请 30 分钟后重试",
            });
            break;
          case "90008":
            setTerminalError({
              title: "账号已禁用",
              subTitle: "请联系超级管理员",
            });
            break;
          case "90005":
            setTerminalError({
              title: "访问被拒",
              subTitle: "您的 IP 不在管理后台白名单内",
            });
            break;
          default:
            setErrorAlert(`登录失败 (${err.code})：${err.message}`);
        }
      } else {
        setErrorAlert("网络异常，请检查 VPN/IP 白名单");
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
        background: loginBackground,
        padding: 16,
        position: "relative",
      }}
    >
      <div style={{ position: "absolute", top: 20, right: 20 }}>
        <ThemeSwitch />
      </div>
      <Card
        style={{
          width: 420,
          background: "var(--fx-surface-1)",
          border: "1px solid var(--fx-border)",
        }}
        variant="outlined"
      >
        <div style={{ textAlign: "center", marginBottom: 24 }}>
          <FalconMark
            style={{ width: 64, height: 38, color: "var(--fx-cyan)", marginBottom: 12 }}
          />
          <Title level={3} style={{ marginBottom: 4, color: "var(--fx-text)", fontFamily: "var(--fx-mono-stack)", letterSpacing: "0.04em" }}>
            FalconX
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
            Admin Console
          </Text>
        </div>
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
        <Form<FormValues>
          layout="vertical"
          onFinish={handleSubmit}
          autoComplete="off"
          requiredMark={false}
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: "请输入用户名" },
              { min: 3, max: 32, message: "3-32 字符" },
            ]}
          >
            <Input prefix={<UserOutlined />} size="large" placeholder="请输入用户名" autoFocus />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: "请输入密码" }]}
          >
            <Input.Password prefix={<LockOutlined />} size="large" placeholder="请输入密码" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button type="primary" htmlType="submit" size="large" block loading={submitting}>
              登录
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: "center", marginTop: 16 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            忘记密码请联系超级管理员
          </Text>
        </div>
        <div style={{ textAlign: "center", marginTop: 24 }}>
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
