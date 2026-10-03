import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Bell,
  Check,
  Copy,
  KeyRound,
  LogOut,
  Settings as SettingsIcon,
  ShieldAlert,
  ShieldCheck,
} from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { logout } from "../auth/authApi";
import { decodeJwtPayload } from "../../lib/jwt";
import { getLatestKyc } from "../kyc/kycApi";
import { getMarginMode } from "../trading/tradingApi";
import { MARGIN_MODE_QUERY_KEY } from "../trading/marginModeShared";
import {
  usePreferencesStore,
  type LanguagePreference,
  type MarginModePreference,
} from "./preferencesStore";

interface Props {
  onOpenProfile: () => void;
  onOpenKyc: () => void;
}

interface AccessTokenClaims extends Record<string, unknown> {
  sub?: string;
  uid?: string;
  email?: string;
  status?: string;
  groupCode?: string;
  exp?: number;
}

const LEVERAGE_OPTIONS = [1, 5, 10, 20, 50, 100, 200] as const;
const MARGIN_OPTIONS: { value: MarginModePreference; label: string; subtitle: string }[] = [
  { value: "ISOLATED", label: "Isolated", subtitle: "逐仓" },
  { value: "CROSS", label: "Cross", subtitle: "全仓" },
];
const LANGUAGE_OPTIONS: { value: LanguagePreference; label: string; disabled?: boolean }[] = [
  { value: "zh-CN", label: "简体中文" },
  { value: "en-US", label: "English · soon", disabled: true },
];

/**
 * 设置页（console 风格）：账户元数据 + 资料/KYC 入口 + 交易偏好 + 安全。
 * 偏好（leverage / marginMode / language）写入 zustand persist，OrderTicket 在 mount 时读取。
 */
export function SettingsPage({ onOpenProfile, onOpenKyc }: Props) {
  const session = useAuthStore((s) => s.session);
  const clearSession = useAuthStore((s) => s.clearSession);
  const token = session?.accessToken ?? null;

  const claims = useMemo<AccessTokenClaims | null>(
    () => decodeJwtPayload<AccessTokenClaims>(token),
    [token],
  );

  const kycQuery = useQuery({
    queryKey: ["identity", "kyc", "latest"],
    queryFn: () => (token ? getLatestKyc(token) : Promise.resolve(null)),
    enabled: Boolean(token),
    staleTime: 60_000,
  });
  const kycLevel = kycQuery.data?.currentKycLevel ?? 0;
  const kycStatus = kycQuery.data?.status ?? null;

  const prefs = usePreferencesStore();
  const [loggingOut, setLoggingOut] = useState(false);

  // 账户级 margin mode（复用 MarginModeToggle 的 queryKey 共享缓存），仅取 crossModeEnabled 用于门禁。
  const marginModeQuery = useQuery({
    queryKey: MARGIN_MODE_QUERY_KEY,
    queryFn: () => {
      if (!token) throw new Error("Not authenticated");
      return getMarginMode(token);
    },
    enabled: Boolean(token),
  });
  // loading / 拿不到时保守按 false（disable 全仓），避免误放行历史脏偏好。
  const crossModeEnabled = marginModeQuery.data?.crossModeEnabled ?? false;
  const crossDisabled = !crossModeEnabled;

  // 历史脏偏好纠正：本地默认全仓但平台未开放全仓时，自动回退为逐仓，
  // 避免 OrderTicket 继续按 CROSS 提交被后端 40010 拒。仅在 query 成功返回后执行。
  // 纠正后下方常驻 hint「全仓暂未开放，当前仅支持逐仓」即为用户提示。
  const setDefaultMarginMode = prefs.setDefaultMarginMode;
  const wasCross = prefs.defaultMarginMode === "CROSS";
  useEffect(() => {
    if (marginModeQuery.isSuccess && !crossModeEnabled && wasCross) {
      setDefaultMarginMode("ISOLATED");
    }
  }, [marginModeQuery.isSuccess, crossModeEnabled, wasCross, setDefaultMarginMode]);

  async function handleLogout() {
    setLoggingOut(true);
    try {
      if (token) await logout(token).catch(() => undefined);
    } finally {
      clearSession();
      setLoggingOut(false);
    }
  }

  const sessionExp = claims?.exp ? new Date(claims.exp * 1000) : null;
  const sessionExpStr = sessionExp
    // 统一 yyyy-MM-dd HH:mm:ss（与 Wallet / Activity 表格口径一致）
    ? `${sessionExp.getFullYear()}-${String(sessionExp.getMonth() + 1).padStart(2, "0")}-${String(sessionExp.getDate()).padStart(2, "0")} ${String(sessionExp.getHours()).padStart(2, "0")}:${String(sessionExp.getMinutes()).padStart(2, "0")}:${String(sessionExp.getSeconds()).padStart(2, "0")}`
    : "—";

  return (
    <main className="fx-console-page">
      <header className="fx-console-header">
        <div className="fx-console-header__row">
          <div className="fx-console-route">
            <span className="fx-console-route__icon">
              <SettingsIcon size={13} strokeWidth={2.2} aria-hidden="true" />
            </span>
            <span>FalconX</span>
            <span className="fx-console-route__sep">/</span>
            <span>设置</span>
            <span className="fx-console-route__sep">/</span>
            <span className="fx-console-route__current">会话</span>
          </div>
          <div className="fx-console-meta">
            <span className={`fx-dot ${claims?.status === "ACTIVE" ? "fx-dot--ok" : "fx-dot--warn"}`}>
              {claims?.status === "ACTIVE" ? "已激活" : claims?.status ?? "未知"}
            </span>
          </div>
        </div>
      </header>

      <div className="fx-console-page__body">
        {/* ----------- §01 账户信息 ----------- */}
        <section className="fx-console-section">
          <div className="fx-console-section__head">
            <span className="fx-console-section__num">§01</span>
            <h2 className="fx-console-section__title">账户信息</h2>
            <span className="fx-console-section__hint">来自 JWT · 只读</span>
          </div>
          <dl className="fx-info-list">
            <InfoRow label="邮箱" value={claims?.email ?? "—"} mono={false} />
            <InfoRow label="用户 ID" value={claims?.sub ?? "—"} copyable />
            <InfoRow label="UID" value={claims?.uid ?? "—"} copyable />
            <InfoRow
              label="账户状态"
              value={
                <span className={`fx-dot ${claims?.status === "ACTIVE" ? "fx-dot--ok" : "fx-dot--warn"}`}>
                  {claims?.status === "ACTIVE" ? "已激活" : claims?.status ?? "—"}
                </span>
              }
              raw
            />
            <InfoRow label="用户组" value={claims?.groupCode ?? "—"} mono={false} />
            <InfoRow label="会话过期" value={sessionExpStr} />
          </dl>
        </section>

        {/* ----------- §02 身份与认证 ----------- */}
        <section className="fx-console-section">
          <div className="fx-console-section__head">
            <span className="fx-console-section__num">§02</span>
            <h2 className="fx-console-section__title">身份与认证</h2>
            <span className="fx-console-section__hint">个人资料 · 实名认证</span>
          </div>
          <div className="settings-identity">
            <div className="settings-identity__block">
              <div className="settings-identity__head">
                <span className="settings-identity__title">个人资料</span>
                <button type="button" className="fx-ghost-btn" onClick={onOpenProfile}>
                  编辑
                </button>
              </div>
              <p className="settings-identity__body">
                姓名 / 国籍 / 出生日期已锁定（需重新 KYC 才能修改）；地址 / 手机 / 语言偏好可在编辑面板内自行更新。
              </p>
            </div>

            <div
              className={
                kycLevel >= 1
                  ? "settings-identity__block settings-identity__block--kyc-ok"
                  : kycStatus === "PENDING"
                    ? "settings-identity__block settings-identity__block--kyc-pending"
                    : "settings-identity__block"
              }
            >
              <div className="settings-identity__head">
                <span className="settings-identity__title">
                  {kycLevel >= 1 ? (
                    <span className="fx-dot fx-dot--ok">
                      <ShieldCheck size={13} aria-hidden="true" /> KYC · L{kycLevel} 已通过
                    </span>
                  ) : kycStatus === "PENDING" ? (
                    <span className="fx-dot fx-dot--pending">
                      <ShieldAlert size={13} aria-hidden="true" /> KYC · 审核中
                    </span>
                  ) : (
                    <span className="fx-dot fx-dot--warn">
                      <ShieldAlert size={13} aria-hidden="true" /> KYC · 未开始
                    </span>
                  )}
                </span>
                <button type="button" className="fx-ghost-btn fx-ghost-btn--primary" onClick={onOpenKyc}>
                  {kycLevel >= 1 ? "查看" : kycStatus === "PENDING" ? "进度" : "去认证"}
                </button>
              </div>
              <p className="settings-identity__body">
                {kycLevel >= 1
                  ? "您已通过 KYC L1 实名认证，可使用全部交易 / 出金功能。"
                  : kycStatus === "PENDING"
                    ? "您的 KYC 资料已提交，等待运营审核（通常 1 个工作日内）。"
                    : kycStatus === "REJECTED"
                      ? "上次 KYC 审核未通过，请按要求补正后重新提交。"
                      : "出金、解锁更高额度需要先完成实名认证。"}
              </p>
            </div>
          </div>
        </section>

        {/* ----------- §03 交易偏好 ----------- */}
        <section className="fx-console-section">
          <div className="fx-console-section__head">
            <span className="fx-console-section__num">§03</span>
            <h2 className="fx-console-section__title">交易偏好</h2>
            <span className="fx-console-section__hint">下次打开下单页时生效</span>
          </div>

          <div className="settings-pref">
            <div className="settings-pref-row">
              <div className="settings-pref-row__label">
                <span className="settings-pref-row__title">默认杠杆</span>
                <span className="settings-pref-row__hint">下单页默认杠杆，OrderTicket mount 时读取</span>
              </div>
              <div className="fx-segmented">
                {LEVERAGE_OPTIONS.map((n) => (
                  <button
                    key={n}
                    type="button"
                    className={prefs.defaultLeverage === n ? "fx-segmented__btn is-active" : "fx-segmented__btn"}
                    onClick={() => prefs.setDefaultLeverage(n)}
                  >
                    {n}x
                  </button>
                ))}
              </div>
            </div>

            <div className="settings-pref-row">
              <div className="settings-pref-row__label">
                <span className="settings-pref-row__title">保证金模式</span>
                <span className="settings-pref-row__hint">逐仓 = 单仓位独立风险；全仓 = 账户共担</span>
              </div>
              <div className="fx-segmented">
                {MARGIN_OPTIONS.map((opt) => {
                  const optDisabled = opt.value === "CROSS" && crossDisabled;
                  return (
                    <button
                      key={opt.value}
                      type="button"
                      disabled={optDisabled}
                      className={
                        prefs.defaultMarginMode === opt.value
                          ? "fx-segmented__btn is-active is-active--lime"
                          : "fx-segmented__btn"
                      }
                      onClick={() => !optDisabled && prefs.setDefaultMarginMode(opt.value)}
                    >
                      {opt.label} <span style={{ opacity: 0.55, marginLeft: 4 }}>{opt.subtitle}</span>
                    </button>
                  );
                })}
              </div>
              {crossDisabled && (
                <span className="settings-pref-row__hint" role="note">
                  全仓暂未开放，当前仅支持逐仓
                </span>
              )}
            </div>

            <div className="settings-pref-row">
              <div className="settings-pref-row__label">
                <span className="settings-pref-row__title">界面语言</span>
                <span className="settings-pref-row__hint">默认简体中文，其他语言后续接入</span>
              </div>
              <div className="fx-segmented">
                {LANGUAGE_OPTIONS.map((opt) => (
                  <button
                    key={opt.value}
                    type="button"
                    disabled={opt.disabled}
                    className={
                      prefs.language === opt.value ? "fx-segmented__btn is-active" : "fx-segmented__btn"
                    }
                    onClick={() => !opt.disabled && prefs.setLanguage(opt.value)}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            <p className="settings-disclaimer">
              · 仅保存在本设备 · localStorage · 不跨设备同步 ·
            </p>
          </div>
        </section>

        {/* ----------- §04 安全 ----------- */}
        <section className="fx-console-section">
          <div className="fx-console-section__head">
            <span className="fx-console-section__num">§04</span>
            <h2 className="fx-console-section__title">安全</h2>
            <span className="fx-console-section__hint">仅作用于当前设备</span>
          </div>
          <div className="settings-security">
            <div className="settings-security__row">
              <span className="settings-security__icon">
                <KeyRound size={14} strokeWidth={2} aria-hidden="true" />
              </span>
              <div>
                <span className="settings-security__label">修改密码</span>
                <span className="settings-security__hint">change password</span>
              </div>
              <button type="button" className="fx-ghost-btn" disabled>
                即将推出
              </button>
            </div>
            <div className="settings-security__row">
              <span className="settings-security__icon">
                <Bell size={14} strokeWidth={2} aria-hidden="true" />
              </span>
              <div>
                <span className="settings-security__label">通知偏好</span>
                <span className="settings-security__hint">notification preferences</span>
              </div>
              <button type="button" className="fx-ghost-btn" disabled>
                即将推出
              </button>
            </div>
            <div className="settings-security__row">
              <span className="settings-security__icon" style={{ color: "var(--fx-danger)" }}>
                <LogOut size={14} strokeWidth={2} aria-hidden="true" />
              </span>
              <div>
                <span className="settings-security__label">登出当前设备</span>
                <span className="settings-security__hint">sign out</span>
              </div>
              <button
                type="button"
                className="fx-ghost-btn fx-ghost-btn--danger"
                onClick={handleLogout}
                disabled={loggingOut}
              >
                {loggingOut ? "登出中…" : "登出"}
              </button>
            </div>
          </div>
        </section>
      </div>
    </main>
  );
}

interface InfoRowProps {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
  copyable?: boolean;
  raw?: boolean;
}

function InfoRow({ label, value, mono = true, copyable, raw }: InfoRowProps) {
  const [copied, setCopied] = useState(false);
  const text = typeof value === "string" ? value : "";
  const onCopy = async () => {
    if (!text || text === "—") return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1400);
    } catch {
      // ignore (non-https etc)
    }
  };
  return (
    <div>
      <dt>{label}</dt>
      <dd className={raw ? "fx-info-list__sans" : mono ? "" : "fx-info-list__sans"}>
        <span>{value}</span>
        {copyable && text && text !== "—" && (
          <button
            type="button"
            className={copied ? "fx-copy-chip fx-copy-chip--done" : "fx-copy-chip"}
            onClick={onCopy}
            style={{ marginLeft: 10 }}
            aria-label={`复制 ${label}`}
          >
            {copied ? <Check size={11} aria-hidden="true" /> : <Copy size={11} aria-hidden="true" />}
            {copied ? "已复制" : "复制"}
          </button>
        )}
      </dd>
    </div>
  );
}
