import { AnimatePresence, motion } from "framer-motion";
import { ArrowLeft } from "lucide-react";
import { type FormEvent, useState } from "react";
import { FalconApiError } from "../../lib/api";
import { ISO_COUNTRIES } from "../../lib/isoCountries";
import { login, register } from "./authApi";
import { toAuthSession, useAuthStore } from "./authStore";

type AuthFormProps = {
  mode: "login" | "register";
  onModeChange: (mode: "idle" | "login" | "register") => void;
};

export function AuthForm({ mode, onModeChange }: AuthFormProps) {
  const setSession = useAuthStore((state) => state.setSession);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  // STAGE-1B-USER-PROFILE：注册时强制 5 PII 字段
  const [firstName, setFirstName] = useState("");
  const [middleName, setMiddleName] = useState("");
  const [lastName, setLastName] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [nationality, setNationality] = useState("CHN");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setMessage(null);
    setIsSubmitting(true);

    try {
      if (mode === "register") {
        await register({
          email,
          password,
          firstName: firstName.trim(),
          middleName: middleName.trim() ? middleName.trim() : null,
          lastName: lastName.trim(),
          birthDate,
          nationality,
        });
        setMessage("账户已创建，请登录进入交易终端。");
        onModeChange("login");
        return;
      }

      const response = await login(email, password);
      setSession(toAuthSession(response));
    } catch (cause) {
      if (cause instanceof FalconApiError) {
        setError(`${cause.message} (${cause.code})`);
      } else {
        setError("请求失败，请检查网络或稍后重试。");
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AnimatePresence mode="wait">
      <motion.form
        key={mode}
        className="auth-form"
        onSubmit={handleSubmit}
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -12 }}
        transition={{ duration: 0.24, ease: [0.16, 1, 0.3, 1] }}
      >
        <button
          type="button"
          className="auth-form__back"
          onClick={() => onModeChange("idle")}
          aria-label="返回上一步"
        >
          <ArrowLeft size={12} strokeWidth={2.5} />
          返回
        </button>

        <div className="auth-form__header">
          <h2>{mode === "login" ? "登录 FalconX" : "创建 FalconX 账户"}</h2>
          <p>
            {mode === "login"
              ? "进入专业 CFD 交易终端。"
              : "完善基础资料后开通账户（≥18 岁监管要求）。"}
          </p>
        </div>

        {mode === "register" && (
          <div className="auth-form__section">
            <span className="auth-form__section-num">§01</span>
            账户凭证
            <span className="auth-form__section-rule" />
          </div>
        )}

        <label>
          邮箱
          <input
            autoComplete="email"
            inputMode="email"
            required
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="alice@example.com"
          />
        </label>

        <label>
          密码
          <input
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            minLength={8}
            required
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="至少 8 个字符"
          />
        </label>

        {mode === "register" && (
          <>
            <div className="auth-form__section">
              <span className="auth-form__section-num">§02</span>
              实名信息
              <span className="auth-form__section-rule" />
            </div>

            <div className="auth-form__name-row">
              <label>
                姓 (Last name)
                <input
                  required
                  maxLength={64}
                  type="text"
                  value={lastName}
                  onChange={(event) => setLastName(event.target.value)}
                  placeholder="Wang"
                />
              </label>
              <label>
                名 (First name)
                <input
                  required
                  maxLength={64}
                  type="text"
                  value={firstName}
                  onChange={(event) => setFirstName(event.target.value)}
                  placeholder="Yi"
                />
              </label>
            </div>

            <label>
              中间名（可选）
              <input
                maxLength={64}
                type="text"
                value={middleName}
                onChange={(event) => setMiddleName(event.target.value)}
                placeholder="留空即可"
              />
            </label>

            <label>
              出生日期
              <input
                required
                type="date"
                value={birthDate}
                onChange={(event) => setBirthDate(event.target.value)}
                max={new Date().toISOString().slice(0, 10)}
              />
            </label>

            <div className="auth-form__section">
              <span className="auth-form__section-num">§03</span>
              监管国籍
              <span className="auth-form__section-rule" />
            </div>

            <label>
              国籍
              <select
                required
                value={nationality}
                onChange={(event) => setNationality(event.target.value)}
              >
                {ISO_COUNTRIES.map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.nameZh}（{c.code}）
                  </option>
                ))}
              </select>
            </label>
          </>
        )}

        {error ? <p className="auth-form__error">{error}</p> : null}
        {message ? <p className="auth-form__message">{message}</p> : null}

        <button className="auth-form__submit" disabled={isSubmitting} type="submit">
          {isSubmitting ? "处理中..." : mode === "login" ? "登录" : "创建账户"}
        </button>
      </motion.form>
    </AnimatePresence>
  );
}
