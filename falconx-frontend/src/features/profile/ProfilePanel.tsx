import { type FormEvent, useEffect, useMemo, useState } from "react";
import { FalconApiError } from "../../lib/api";
import { ISO_COUNTRIES, countryDisplay } from "../../lib/isoCountries";
import { decodeJwtPayload } from "../../lib/jwt";
import { useAuthStore } from "../auth/authStore";
import { getProfile, updateProfile } from "./profileApi";
import type { Gender, UserProfile } from "./types";

type ProfilePanelProps = {
  open: boolean;
  onClose: () => void;
  /**
   * 当前 t_user.kyc_level（来自 GET /api/v1/me/kyc）。
   * 0 = 未认证 / ≥1 = 已认证。优先于 profile.profileVerified 判定 KYC 显示状态：
   * admin 可能直接 patch kyc_level=1 而 profile.profileVerified 仍是 0。
   */
  currentKycLevel?: number;
};

interface AccessTokenPayload extends Record<string, unknown> {
  sub?: string;
  uid?: string;
  email?: string;
  status?: string;
  groupCode?: string;
}

export function ProfilePanel({ open, onClose, currentKycLevel = 0 }: ProfilePanelProps) {
  const session = useAuthStore((state) => state.session);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const [firstName, setFirstName] = useState("");
  const [middleName, setMiddleName] = useState("");
  const [lastName, setLastName] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [nationality, setNationality] = useState("CHN");
  const [gender, setGender] = useState<Gender | "">("");
  const [residenceCountry, setResidenceCountry] = useState("");
  const [residenceState, setResidenceState] = useState("");
  const [residenceCity, setResidenceCity] = useState("");
  const [residenceAddress, setResidenceAddress] = useState("");
  const [residencePostalCode, setResidencePostalCode] = useState("");
  const [phoneCountryCode, setPhoneCountryCode] = useState("");
  const [phoneNumber, setPhoneNumber] = useState("");

  const accountClaims = useMemo<AccessTokenPayload | null>(
    () => decodeJwtPayload<AccessTokenPayload>(session?.accessToken),
    [session?.accessToken]
  );

  useEffect(() => {
    if (!open || !session) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    setMessage(null);
    getProfile(session.accessToken)
      .then((p) => {
        setProfile(p);
        setFirstName(p.firstName);
        setMiddleName(p.middleName ?? "");
        setLastName(p.lastName);
        setBirthDate(p.birthDate);
        setNationality(p.nationality);
        setGender(p.gender ?? "");
        setResidenceCountry(p.residenceCountry ?? "");
        setResidenceState(p.residenceState ?? "");
        setResidenceCity(p.residenceCity ?? "");
        setResidenceAddress(p.residenceAddress ?? "");
        setResidencePostalCode(p.residencePostalCode ?? "");
        setPhoneCountryCode(p.phoneCountryCode ?? "");
        setPhoneNumber(p.phoneNumber ?? "");
      })
      .catch((cause) => {
        if (cause instanceof FalconApiError) {
          setError(`${cause.message} (${cause.code})`);
        } else {
          setError("加载资料失败");
        }
      })
      .finally(() => setLoading(false));
  }, [open, session]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session || !profile) return;
    setSubmitting(true);
    setError(null);
    setMessage(null);

    // 锁定 = profile_verified=1（正常 KYC 审批流程）或 currentKycLevel ≥ 1（admin 直接 patch）
    const verified = profile.profileVerified || currentKycLevel >= 1;
    try {
      const updated = await updateProfile(session.accessToken, {
        firstName: !verified && firstName !== profile.firstName ? firstName : undefined,
        middleName:
          !verified && (middleName || null) !== profile.middleName ? (middleName || null) : undefined,
        lastName: !verified && lastName !== profile.lastName ? lastName : undefined,
        birthDate: !verified && birthDate !== profile.birthDate ? birthDate : undefined,
        nationality: !verified && nationality !== profile.nationality ? nationality : undefined,
        gender: gender !== "" && gender !== profile.gender ? (gender as Gender) : undefined,
        residenceCountry:
          residenceCountry && residenceCountry !== profile.residenceCountry ? residenceCountry : undefined,
        residenceState: residenceState !== (profile.residenceState ?? "") ? residenceState : undefined,
        residenceCity: residenceCity !== (profile.residenceCity ?? "") ? residenceCity : undefined,
        residenceAddress:
          residenceAddress !== (profile.residenceAddress ?? "") ? residenceAddress : undefined,
        residencePostalCode:
          residencePostalCode !== (profile.residencePostalCode ?? "") ? residencePostalCode : undefined,
        phoneCountryCode:
          phoneCountryCode !== (profile.phoneCountryCode ?? "") ? phoneCountryCode : undefined,
        phoneNumber: phoneNumber !== (profile.phoneNumber ?? "") ? phoneNumber : undefined,
      });
      setProfile(updated);
      setMessage("已保存。");
      window.dispatchEvent(
        new CustomEvent("falconx:toast", { detail: { kind: "ok", text: "个人资料已保存" } })
      );
    } catch (cause) {
      if (cause instanceof FalconApiError) {
        setError(`${cause.message} (${cause.code})`);
      } else {
        setError("保存失败");
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  const fullName = profile ? buildFullName(profile) : "—";
  const initial = profile?.firstName?.slice(0, 1) || profile?.lastName?.slice(0, 1) || "U";

  return (
    <div className="fx-modal-backdrop" onClick={onClose}>
      <div className="fx-modal fx-modal--wide" onClick={(e) => e.stopPropagation()}>
        <div className="fx-modal-header">
          <h3>个人资料</h3>
          <button type="button" className="fx-modal-close" onClick={onClose} aria-label="关闭">×</button>
        </div>

        <div className="fx-modal-body profile-modal-body">
          {accountClaims && (
            <div className="profile-summary">
              <div className="profile-summary__avatar" aria-hidden="true">{initial.toUpperCase()}</div>
              <div className="profile-summary__main">
                <div className="profile-summary__name">{fullName}</div>
                <div className="profile-summary__email">
                  {accountClaims.email ?? "—"}
                  {session?.emailVerified ? (
                    <span className="profile-summary__badge profile-summary__badge--ok">已验证</span>
                  ) : (
                    <span className="profile-summary__badge profile-summary__badge--warn">未验证</span>
                  )}
                </div>
              </div>
              <dl className="profile-summary__meta">
                <div><dt>UID</dt><dd>{accountClaims.uid ?? "—"}</dd></div>
                <div><dt>User ID</dt><dd className="fx-mono">{accountClaims.sub ?? "—"}</dd></div>
                <div><dt>账户状态</dt><dd>{accountClaims.status ?? session?.userStatus ?? "—"}</dd></div>
                <div>
                  <dt>KYC</dt>
                  <dd>{currentKycLevel >= 1 || profile?.profileVerified ? "已通过" : "未验证"}</dd>
                </div>
              </dl>
            </div>
          )}

          {loading ? (
            <p className="fx-modal-empty">加载中...</p>
          ) : profile ? (
            <form id="profile-edit-form" className="profile-form" onSubmit={handleSubmit}>
              {(profile.profileVerified || currentKycLevel >= 1) && (
                <div className="profile-form__verified-banner">
                  ✓ 已通过 KYC：姓名 / 出生日期 / 国籍 已锁定，需重新 KYC 才能修改
                </div>
              )}

              <fieldset disabled={profile.profileVerified || currentKycLevel >= 1}>
                <legend>身份信息（KYC 锁定字段）</legend>
                <div className="profile-form__row">
                  <label>
                    姓
                    <input value={lastName} onChange={(e) => setLastName(e.target.value)} maxLength={64} />
                  </label>
                  <label>
                    名
                    <input value={firstName} onChange={(e) => setFirstName(e.target.value)} maxLength={64} />
                  </label>
                </div>
                <label>
                  中间名
                  <input value={middleName} onChange={(e) => setMiddleName(e.target.value)} maxLength={64} />
                </label>
                <div className="profile-form__row">
                  <label>
                    出生日期
                    <input
                      type="date"
                      value={birthDate}
                      onChange={(e) => setBirthDate(e.target.value)}
                      max={new Date().toISOString().slice(0, 10)}
                    />
                  </label>
                  <label>
                    国籍
                    <select value={nationality} onChange={(e) => setNationality(e.target.value)}>
                      {ISO_COUNTRIES.map((c) => (
                        <option key={c.code} value={c.code}>
                          {c.nameZh}（{c.code}）
                        </option>
                      ))}
                    </select>
                  </label>
                </div>
              </fieldset>

              <fieldset>
                <legend>联系方式</legend>
                <div className="profile-form__row">
                  <label>
                    性别
                    <select
                      value={gender === "" ? "" : String(gender)}
                      onChange={(e) => setGender(e.target.value === "" ? "" : (Number(e.target.value) as Gender))}
                    >
                      <option value="">未选择</option>
                      <option value="1">男</option>
                      <option value="2">女</option>
                      <option value="9">其他 / 不愿透露</option>
                    </select>
                  </label>
                  <label>
                    居住国
                    <select value={residenceCountry} onChange={(e) => setResidenceCountry(e.target.value)}>
                      <option value="">未选择</option>
                      {ISO_COUNTRIES.map((c) => (
                        <option key={c.code} value={c.code}>
                          {c.nameZh}（{c.code}）
                        </option>
                      ))}
                    </select>
                  </label>
                </div>
                <div className="profile-form__row">
                  <label>
                    省 / 州
                    <input
                      value={residenceState}
                      onChange={(e) => setResidenceState(e.target.value)}
                      maxLength={64}
                    />
                  </label>
                  <label>
                    城市
                    <input value={residenceCity} onChange={(e) => setResidenceCity(e.target.value)} maxLength={64} />
                  </label>
                </div>
                <label>
                  详细地址
                  <input
                    value={residenceAddress}
                    onChange={(e) => setResidenceAddress(e.target.value)}
                    maxLength={255}
                  />
                </label>
                <label>
                  邮编
                  <input
                    value={residencePostalCode}
                    onChange={(e) => setResidencePostalCode(e.target.value)}
                    maxLength={32}
                  />
                </label>
                <div className="profile-form__row">
                  <label>
                    手机国家码
                    <input
                      value={phoneCountryCode}
                      onChange={(e) => setPhoneCountryCode(e.target.value.replace(/[^0-9]/g, ""))}
                      placeholder="如 86"
                      maxLength={4}
                    />
                  </label>
                  <label>
                    手机号
                    <input
                      value={phoneNumber}
                      onChange={(e) => setPhoneNumber(e.target.value.replace(/[^0-9]/g, ""))}
                      maxLength={32}
                    />
                  </label>
                </div>
              </fieldset>

              <fieldset>
                <legend>偏好（只读，运营默认 zh-CN / Asia/Shanghai）</legend>
                <div className="profile-form__row">
                  <label>
                    语言
                    <input value={profile.languagePreference} disabled />
                  </label>
                  <label>
                    时区
                    <input value={profile.timezone} disabled />
                  </label>
                </div>
              </fieldset>

              {error && <div className="fx-modal-error">{error}</div>}
              {message && <div className="profile-form__success">{message}</div>}

              <div className="profile-form__hint">
                当前国籍：{countryDisplay(profile.nationality)} · 居住：
                {profile.residenceCountry ? countryDisplay(profile.residenceCountry) : "未填"}
              </div>
            </form>
          ) : (
            <p className="fx-modal-empty">{error ?? "无法加载资料"}</p>
          )}
        </div>

        <div className="fx-modal-footer">
          <button type="button" className="fx-btn-secondary" onClick={onClose}>取消</button>
          <button
            type="submit"
            form="profile-edit-form"
            className="fx-btn-primary"
            disabled={submitting || loading || !profile}
          >
            {submitting ? "保存中..." : "保存资料"}
          </button>
        </div>
      </div>
    </div>
  );
}

function buildFullName(profile: UserProfile): string {
  const parts = [profile.firstName, profile.middleName, profile.lastName].filter((p) => p && p.trim().length > 0);
  return parts.length > 0 ? parts.join(" ") : "—";
}
