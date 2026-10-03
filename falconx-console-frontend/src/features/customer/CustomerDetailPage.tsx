import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  Radio,
  Result,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  message,
} from "antd";
import dayjs from "dayjs";
import { customerApi } from "./customerApi";
import { HighRiskConfirmModal } from "./HighRiskConfirmModal";
import type { CustomerDetail, CustomerProfile, CustomerStatus } from "./types";
import { RequiresPermission } from "../../components/RequiresPermission";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { formatMoney } from "../../lib/precision";

const { Title, Text } = Typography;

const STATUS_COLOR_MAP: Record<CustomerStatus, string> = {
  ACTIVE: "green",
  FROZEN: "red",
  BANNED: "default",
  PENDING_DEPOSIT: "orange",
};

const STATUS_OPTIONS: CustomerStatus[] = ["ACTIVE", "FROZEN", "BANNED", "PENDING_DEPOSIT"];

/**
 * 表单值结构 = 基本信息 5 字段（可编辑）+ profile 16 字段（可编辑）。
 * birthDate 用 dayjs，提交时 toISOString -> yyyy-MM-dd。
 */
interface EditFormValues {
  // identity
  email: string;
  status: CustomerStatus;
  kycLevel: number;
  groupCode: string;
  emailVerified: boolean;
  // profile (16 fields)
  firstName?: string;
  middleName?: string;
  lastName?: string;
  birthDate?: dayjs.Dayjs | null;
  nationality?: string;
  gender?: number | null;
  residenceCountry?: string;
  residenceState?: string;
  residenceCity?: string;
  residenceAddress?: string;
  residencePostalCode?: string;
  phoneCountryCode?: string;
  phoneNumber?: string;
  languagePreference?: string;
  timezone?: string;
}

export function CustomerDetailPage() {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const [customer, setCustomer] = useState<CustomerDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [form] = Form.useForm<EditFormValues>();
  const [saveOpen, setSaveOpen] = useState(false);

  const [adjustOpen, setAdjustOpen] = useState(false);
  const [adjustDirection, setAdjustDirection] = useState<"add" | "deduct">("add");
  const [adjustAmount, setAdjustAmount] = useState<number | null>(null);

  // §4 commit E：高危挑战项需要当前 admin 的 username。null 时挑战回落到不展示（避免 disabled 死锁）。
  const adminUser = useAdminAuthStore((s) => s.user);

  const load = useCallback(() => {
    if (!userId) return;
    setLoading(true);
    customerApi
      .detail(userId)
      .then((data) => {
        setCustomer(data);
        setErrorCode(null);
        form.setFieldsValue(buildInitialValues(data));
      })
      .catch((err) => setErrorCode(err.code ?? "UNKNOWN"))
      .finally(() => setLoading(false));
  }, [form, userId]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  // 取初始值用于 diff（保存时只发变化的字段）
  const initialValues = useMemo<EditFormValues | null>(
    () => (customer ? buildInitialValues(customer) : null),
    [customer],
  );

  if (loading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 64 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (errorCode === "90303") {
    return (
      <Result
        status="404"
        title="客户不存在"
        extra={<Button onClick={() => navigate("/admin/customers")}>返回列表</Button>}
      />
    );
  }

  if (!customer || !initialValues) {
    return <Result status="error" title="加载失败" subTitle={errorCode ?? ""} />;
  }

  const isTerminal = customer.status === "BANNED";
  const balanceBefore = Number(customer.balance.totalUSD);
  const adjustDelta = adjustDirection === "add" ? adjustAmount ?? 0 : -(adjustAmount ?? 0);
  const balanceAfter = balanceBefore + adjustDelta;

  return (
    <div>
      <Title level={3}>
        客户编辑 <Tag color={STATUS_COLOR_MAP[customer.status]}>{customer.status}</Tag>
      </Title>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button onClick={() => navigate("/admin/customers")}>← 返回列表</Button>
        <RequiresPermission code="customer:edit">
          <Button type="primary" onClick={() => setSaveOpen(true)} disabled={isTerminal}>
            保存所有改动
          </Button>
        </RequiresPermission>
        <Button onClick={() => form.setFieldsValue(initialValues)} disabled={isTerminal}>
          重置表单
        </Button>
        <RequiresPermission code="customer:balance:adjust">
          <Button type="primary" onClick={() => setAdjustOpen(true)} disabled={isTerminal}>
            调余额（独立 ledger 流程）
          </Button>
        </RequiresPermission>
        {isTerminal && <Text type="secondary">终态 BANNED 不可操作</Text>}
      </Space>

      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message="所有改动通过「保存所有改动」一次性提交，必填操作原因 ≥ 10 字符（写入审计日志）。余额调整保持独立 ledger 流程（增减 + 双写双扣）。"
      />

      <Form<EditFormValues> form={form} layout="vertical" initialValues={initialValues} disabled={isTerminal}>
        <Card title="只读元数据" style={{ marginBottom: 16 }}>
          <Descriptions column={2} size="small">
            <Descriptions.Item label="用户 ID">{customer.userId}</Descriptions.Item>
            <Descriptions.Item label="UID">{customer.uid}</Descriptions.Item>
            <Descriptions.Item label="激活时间">{customer.activatedAt ?? "-"}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{customer.createdAt}</Descriptions.Item>
            <Descriptions.Item label="最近登录">{customer.lastLoginAt ?? "从未登录"}</Descriptions.Item>
            <Descriptions.Item label="最近登录 IP">{customer.lastLoginIp ?? "-"}</Descriptions.Item>
          </Descriptions>
        </Card>

        <Card title="基本信息（可编辑）" style={{ marginBottom: 16 }}>
          <Row gutter={[16, 0]}>
            <Col xs={24} md={12}>
              <Form.Item name="email" label="邮箱" rules={[{ required: true, type: "email" }, { max: 128 }]}>
                <Input placeholder="user@example.com" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="emailVerified" label="邮箱已验证" valuePropName="checked">
                <Switch checkedChildren="已验证" unCheckedChildren="未验证" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="status"
                label="状态"
                rules={[{ required: true }]}
                extra="改为非 ACTIVE 时后端自动撤销该用户全部 refresh token（强制重新登录）"
              >
                <Select options={STATUS_OPTIONS.map((s) => ({ value: s, label: s }))} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="groupCode" label="用户组" rules={[{ max: 64 }]}>
                <Input placeholder="如 default / vip-asia" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="kycLevel"
                label="KYC 等级"
                rules={[{ required: true, type: "number", min: 0, max: 1 }]}
                extra="0=未认证 / 1=已通过简单 KYC"
              >
                <Select
                  options={[
                    { value: 0, label: "0 - 未认证" },
                    { value: 1, label: "1 - 已认证" },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card
          title={
            <Space>
              基础资料（可编辑）
              {customer.profile?.profileVerified && <Tag color="green">profile_verified=1</Tag>}
            </Space>
          }
          extra={
            <Text type="secondary" style={{ fontSize: 12 }}>
              管理员保存可绕过 verified 锁
            </Text>
          }
          style={{ marginBottom: 16 }}
        >
          {customer.profile ? (
            <Row gutter={[16, 0]}>
              <Col xs={24} md={8}>
                <Form.Item name="firstName" label="名（First Name）" rules={[{ max: 64 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="middleName" label="中间名（Middle Name）" rules={[{ max: 64 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="lastName" label="姓（Last Name）" rules={[{ max: 64 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="birthDate" label="出生日期">
                  <DatePicker style={{ width: "100%" }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item
                  name="nationality"
                  label="国籍（ISO 3166-1 alpha-3）"
                  rules={[{ pattern: /^[A-Z]{3}$/, message: "3 位大写字母（如 USA / CHN）" }]}
                >
                  <Input placeholder="USA" maxLength={3} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="gender" label="性别">
                  <Select
                    allowClear
                    options={[
                      { value: 1, label: "男" },
                      { value: 2, label: "女" },
                      { value: 9, label: "其他 / 不愿透露" },
                    ]}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item
                  name="residenceCountry"
                  label="居住国（ISO alpha-3）"
                  rules={[{ pattern: /^[A-Z]{3}$/, message: "3 位大写字母" }]}
                >
                  <Input placeholder="USA" maxLength={3} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="residenceState" label="州 / 省" rules={[{ max: 64 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="residenceCity" label="城市" rules={[{ max: 64 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={16}>
                <Form.Item name="residenceAddress" label="详细地址" rules={[{ max: 255 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="residencePostalCode" label="邮编" rules={[{ max: 32 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="phoneCountryCode" label="手机国家码" rules={[{ max: 8 }]}>
                  <Input placeholder="86" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="phoneNumber" label="手机号" rules={[{ max: 32 }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="languagePreference" label="语言偏好" rules={[{ max: 16 }]}>
                  <Input placeholder="zh-CN / en-US" />
                </Form.Item>
              </Col>
              <Col xs={24} md={16}>
                <Form.Item name="timezone" label="时区" rules={[{ max: 64 }]}>
                  <Input placeholder="Asia/Shanghai" />
                </Form.Item>
              </Col>
            </Row>
          ) : (
            <Text type="secondary">该客户尚无基础资料（历史注册数据，未走 STAGE-1B 流程）；保存时仍可填字段但需后端先创建 profile 行</Text>
          )}
        </Card>

        <Card title="资金概览（只读 / 走独立 ledger 流程）">
          <Descriptions column={3} size="small">
            <Descriptions.Item label="总余额 (USD)">${customer.balance.totalUSD}</Descriptions.Item>
            <Descriptions.Item label="可用 (USD)">${customer.balance.availableUSD}</Descriptions.Item>
            <Descriptions.Item label="占用保证金 (USD)">${customer.balance.marginUsedUSD}</Descriptions.Item>
          </Descriptions>
          <Divider />
          <Text type="secondary" style={{ fontSize: 12 }}>
            余额修改需通过「调余额（独立 ledger 流程）」按钮触发，按增减 delta 入账 t_ledger 表，
            不能在本编辑表单里直接覆盖。
          </Text>
        </Card>
      </Form>

      <HighRiskConfirmModal
        open={saveOpen}
        title="保存客户改动"
        okText="确认保存"
        description="所有改动一次性提交，identity 改动会立即影响登录态（status 改为非 ACTIVE 时强制重新登录）。"
        details={getChangeSummary(initialValues, form)}
        onSubmit={async (reason) => {
          const values = await form.validateFields();
          const payload = buildPatchPayload(initialValues, values, reason, customer.profile);
          if (!payload.identity && !payload.profile) {
            throw new Error("无字段变更，无需保存");
          }
          return customerApi.edit(customer.userId, payload);
        }}
        onSuccess={() => {
          message.success("客户信息已保存");
          setSaveOpen(false);
          load();
        }}
        onCancel={() => setSaveOpen(false)}
      />

      <HighRiskConfirmModal
        open={adjustOpen}
        title="调整客户余额"
        okText="确认调整"
        description="调余额限额以后端配置为准，提交时由 console-service 统一校验。"
        details={[
          ["用户", `${customer.uid} (${customer.email})`],
          ["当前余额", `$${customer.balance.totalUSD}`],
          ["调整后预览", `$${formatMoney(balanceAfter, "USD")}`],
        ]}
        requireConfirmCheckbox
        usernameChallenge={
          adminUser
            ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
            : undefined
        }
        validate={() => {
          if (adjustAmount === null || adjustAmount <= 0) return "请输入调整金额（> 0）";
          if (balanceAfter < 0) return "扣减后余额不能为负";
          return null;
        }}
        onSubmit={(reason) =>
          customerApi.adjustBalance(
            customer.userId,
            (adjustDirection === "add" ? adjustAmount! : -adjustAmount!).toFixed(2),
            reason,
          )
        }
        onSuccess={(result) => {
          message.success(`已调整 ${result.deltaUSD} USD（ledger ${result.ledgerEntryId}）`);
          setAdjustOpen(false);
          setAdjustAmount(null);
          load();
        }}
        onCancel={() => {
          setAdjustOpen(false);
          setAdjustAmount(null);
        }}
      >
        <Space style={{ marginBottom: 16, width: "100%" }} direction="vertical">
          <Radio.Group value={adjustDirection} onChange={(e) => setAdjustDirection(e.target.value)}>
            <Radio value="add">增加</Radio>
            <Radio value="deduct">扣减</Radio>
          </Radio.Group>
          <InputNumber<number>
            value={adjustAmount}
            onChange={(v) => setAdjustAmount(v)}
            placeholder="金额（USD）"
            min={0.01}
            step={0.01}
            precision={2}
            prefix="$"
            style={{ width: "100%" }}
          />
        </Space>
      </HighRiskConfirmModal>
    </div>
  );
}

/** 将后端 CustomerDetail 转换为 form 表单初始值（birthDate → dayjs）。 */
function buildInitialValues(c: CustomerDetail): EditFormValues {
  const p = c.profile;
  return {
    email: c.email,
    status: c.status,
    kycLevel: c.kycLevel ?? 0,
    groupCode: c.groupCode,
    emailVerified: c.emailVerified,
    firstName: p?.firstName ?? undefined,
    middleName: p?.middleName ?? undefined,
    lastName: p?.lastName ?? undefined,
    birthDate: p?.birthDate ? dayjs(p.birthDate) : null,
    nationality: p?.nationality ?? undefined,
    gender: p?.gender ?? null,
    residenceCountry: p?.residenceCountry ?? undefined,
    residenceState: p?.residenceState ?? undefined,
    residenceCity: p?.residenceCity ?? undefined,
    residenceAddress: p?.residenceAddress ?? undefined,
    residencePostalCode: p?.residencePostalCode ?? undefined,
    phoneCountryCode: p?.phoneCountryCode ?? undefined,
    phoneNumber: p?.phoneNumber ?? undefined,
    languagePreference: p?.languagePreference ?? undefined,
    timezone: p?.timezone ?? undefined,
  };
}

/** 把 form 当前值与 initial 对比，构造 PATCH 请求体（只发改了的字段）。 */
function buildPatchPayload(
  initial: EditFormValues,
  current: EditFormValues,
  reason: string,
  profilePresent: CustomerProfile | null,
): { identity?: Record<string, unknown>; profile?: Record<string, unknown>; reason: string } {
  const identity: Record<string, unknown> = {};
  if (current.email !== initial.email) identity.email = current.email;
  if (current.emailVerified !== initial.emailVerified) identity.emailVerified = current.emailVerified;
  if (current.groupCode !== initial.groupCode) identity.groupCode = current.groupCode;
  if (current.kycLevel !== initial.kycLevel) identity.kycLevel = current.kycLevel;
  if (current.status !== initial.status) identity.status = current.status;

  const profile: Record<string, unknown> = {};
  if (profilePresent) {
    const profileKeys: (keyof EditFormValues)[] = [
      "firstName", "middleName", "lastName", "nationality", "gender",
      "residenceCountry", "residenceState", "residenceCity", "residenceAddress",
      "residencePostalCode", "phoneCountryCode", "phoneNumber",
      "languagePreference", "timezone",
    ];
    for (const k of profileKeys) {
      const cv = current[k];
      const iv = initial[k];
      if (cv !== iv && cv !== undefined && cv !== "") {
        profile[k] = cv;
      }
    }
    // birthDate 单独处理（dayjs → yyyy-MM-dd）
    const initBd = initial.birthDate ? initial.birthDate.format("YYYY-MM-DD") : null;
    const currBd = current.birthDate ? current.birthDate.format("YYYY-MM-DD") : null;
    if (currBd && currBd !== initBd) profile.birthDate = currBd;
  }

  return {
    identity: Object.keys(identity).length > 0 ? identity : undefined,
    profile: Object.keys(profile).length > 0 ? profile : undefined,
    reason,
  };
}

/** 用于 HighRiskConfirmModal 的 details：列出本次变更字段（最多 8 行）。 */
function getChangeSummary(
  initial: EditFormValues,
  form: ReturnType<typeof Form.useForm<EditFormValues>>[0],
): Array<[string, string]> {
  const current = form.getFieldsValue();
  const rows: Array<[string, string]> = [];
  const tryAdd = (label: string, iv: unknown, cv: unknown) => {
    if (iv === cv) return;
    if (iv === undefined && (cv === "" || cv === undefined || cv === null)) return;
    rows.push([label, `${formatVal(iv)} → ${formatVal(cv)}`]);
  };
  tryAdd("邮箱", initial.email, current.email);
  tryAdd("邮箱验证", initial.emailVerified, current.emailVerified);
  tryAdd("状态", initial.status, current.status);
  tryAdd("用户组", initial.groupCode, current.groupCode);
  tryAdd("KYC 等级", initial.kycLevel, current.kycLevel);
  tryAdd("名", initial.firstName, current.firstName);
  tryAdd("中间名", initial.middleName, current.middleName);
  tryAdd("姓", initial.lastName, current.lastName);
  const initBd = initial.birthDate ? initial.birthDate.format("YYYY-MM-DD") : null;
  const currBd = current.birthDate ? current.birthDate.format("YYYY-MM-DD") : null;
  tryAdd("出生日期", initBd, currBd);
  tryAdd("国籍", initial.nationality, current.nationality);
  tryAdd("性别", initial.gender, current.gender);
  tryAdd("居住国", initial.residenceCountry, current.residenceCountry);
  tryAdd("州/省", initial.residenceState, current.residenceState);
  tryAdd("城市", initial.residenceCity, current.residenceCity);
  tryAdd("详细地址", initial.residenceAddress, current.residenceAddress);
  tryAdd("邮编", initial.residencePostalCode, current.residencePostalCode);
  tryAdd("手机国家码", initial.phoneCountryCode, current.phoneCountryCode);
  tryAdd("手机号", initial.phoneNumber, current.phoneNumber);
  tryAdd("语言偏好", initial.languagePreference, current.languagePreference);
  tryAdd("时区", initial.timezone, current.timezone);
  if (rows.length === 0) return [["（无变更）", ""]];
  return rows.slice(0, 12);
}

function formatVal(v: unknown): string {
  if (v === undefined || v === null || v === "") return "(空)";
  if (typeof v === "boolean") return v ? "是" : "否";
  return String(v);
}
