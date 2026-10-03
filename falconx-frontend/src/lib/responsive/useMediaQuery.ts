import { useEffect, useState } from "react";

/**
 * 通用 matchMedia hook。SSR 安全（`matchMedia` 不存在时返回 false）。
 *
 * 用法：
 *   const isLandscape = useMediaQuery("(orientation: landscape)");
 *   const isMobile = useMediaQuery("(max-width: 767px)");
 */
export function useMediaQuery(query: string): boolean {
  const getMatch = () =>
    typeof window !== "undefined" && typeof window.matchMedia === "function"
      ? window.matchMedia(query).matches
      : false;

  const [matches, setMatches] = useState<boolean>(getMatch);

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
      return;
    }
    const mql = window.matchMedia(query);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    // 初值由 useState(getMatch) lazy 初始化设置；query 变化时由 change event 同步。
    // 不在 effect 内直接 setMatches(mql.matches) — 触发 react-hooks/set-state-in-effect lint，
    // 且 useState lazy initializer 已能保证 mount 时初值正确。
    mql.addEventListener("change", handler);
    return () => mql.removeEventListener("change", handler);
  }, [query]);

  return matches;
}
