import { motion, useReducedMotion } from "framer-motion";
import { useCallback, useEffect, useState } from "react";
import { FalconBackdrop } from "../../components/brand/FalconBackdrop";
import { FalconMark } from "../../components/brand/FalconMark";
import { FalconWordmark } from "../../components/brand/FalconWordmark";
import { LanguageSelect } from "../settings/LanguageSelect";
import { ThemeToggle } from "../settings/ThemeToggle";
import { AuthForm } from "./AuthForm";
import { useAuthStore } from "./authStore";

type AuthMode = "idle" | "login" | "register";

function hashToMode(hash: string): AuthMode {
  const v = hash.replace(/^#/, "");
  if (v === "login" || v === "register") return v;
  return "idle";
}

export function AuthGate() {
  const [mode, setMode] = useState<AuthMode>(() =>
    typeof window !== "undefined" ? hashToMode(window.location.hash) : "idle",
  );
  const reduceMotion = useReducedMotion();
  const notice = useAuthStore((state) => state.notice);

  // 模式切换通过 history.pushState 同步 hash，让浏览器后退键能回到上一模式而不是整站退出。
  const goMode = useCallback((next: AuthMode) => {
    setMode(next);
    const target = next === "idle" ? " " : `#${next}`;
    if (typeof window !== "undefined" && window.location.hash !== (next === "idle" ? "" : `#${next}`)) {
      window.history.pushState({ authMode: next }, "", target);
    }
  }, []);

  // 监听 popstate，浏览器前进/后退时同步内部 mode。
  useEffect(() => {
    if (typeof window === "undefined") return;
    const onPop = () => setMode(hashToMode(window.location.hash));
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  return (
    <main className="auth-gate">
      {/* 顶右控件群：语言切换 + 主题切换（登录前就能改） */}
      <div className="auth-gate__topright">
        <LanguageSelect />
        <ThemeToggle variant="compact" />
      </div>

      <motion.section
        className="auth-gate__brand"
        initial={reduceMotion ? false : { y: 16, opacity: 0 }}
        animate={reduceMotion ? undefined : { y: 0, opacity: 1 }}
        transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
      >
        <FalconMark className="auth-gate__mark" />
        <FalconWordmark />
        <p className="auth-tagline">
          PROFESSIONAL <span>·</span> CFD <span>·</span> TERMINAL
        </p>
        {notice ? <p className="auth-gate__notice">{notice}</p> : null}

        {mode === "idle" ? (
          <div className="auth-actions" aria-label="认证操作">
            <button className="auth-button primary" type="button" onClick={() => goMode("login")}>
              登录
            </button>
            <button className="auth-button secondary" type="button" onClick={() => goMode("register")}>
              创建账户
            </button>
          </div>
        ) : (
          <AuthForm mode={mode} onModeChange={goMode} />
        )}
      </motion.section>

      <FalconBackdrop />
    </main>
  );
}
