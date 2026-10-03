import { useMediaQuery } from "./useMediaQuery";

export type Breakpoint = "xs" | "sm" | "md" | "lg" | "xl";

export interface BreakpointState {
  current: Breakpoint;
  isMobile: boolean; // < md (< 768px)
  isTablet: boolean; // [md, lg)
  isDesktop: boolean; // >= lg
  width: number;
}

/**
 * 4 档断点 hook。
 *   xs: < 640px       手机小屏（iPhone SE）
 *   sm: 640 - 767     手机大屏 / 小 phablet
 *   md: 768 - 1023    平板竖屏
 *   lg: 1024 - 1279   平板横屏 / 小桌面
 *   xl: >= 1280       桌面
 *
 * isMobile = (current === xs || current === sm)
 * isTablet = (current === md)
 * isDesktop = (current === lg || current === xl)
 */
export function useBreakpoint(): BreakpointState {
  const isSmUp = useMediaQuery("(min-width: 640px)");
  const isMdUp = useMediaQuery("(min-width: 768px)");
  const isLgUp = useMediaQuery("(min-width: 1024px)");
  const isXlUp = useMediaQuery("(min-width: 1280px)");

  let current: Breakpoint = "xs";
  if (isXlUp) current = "xl";
  else if (isLgUp) current = "lg";
  else if (isMdUp) current = "md";
  else if (isSmUp) current = "sm";

  return {
    current,
    isMobile: !isMdUp,
    isTablet: isMdUp && !isLgUp,
    isDesktop: isLgUp,
    width: typeof window !== "undefined" ? window.innerWidth : 1280,
  };
}
