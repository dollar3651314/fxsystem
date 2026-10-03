import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { useBreakpoint } from "./useBreakpoint";

function mockWidth(width: number) {
  vi.stubGlobal("innerWidth", width);
  vi.stubGlobal("matchMedia", (query: string) => {
    const minMatch = query.match(/min-width:\s*(\d+)/);
    const maxMatch = query.match(/max-width:\s*(\d+)/);
    let matches = true;
    if (minMatch && width < parseInt(minMatch[1], 10)) matches = false;
    if (maxMatch && width > parseInt(maxMatch[1], 10)) matches = false;
    return {
      matches,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    };
  });
}

describe("useBreakpoint", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("width < 640 → current=xs, isMobile=true, isTablet=false, isDesktop=false", () => {
    mockWidth(375);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("xs");
    expect(result.current.isMobile).toBe(true);
    expect(result.current.isTablet).toBe(false);
    expect(result.current.isDesktop).toBe(false);
  });

  it("width 640-767 → current=sm, isMobile=true", () => {
    mockWidth(700);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("sm");
    expect(result.current.isMobile).toBe(true);
    expect(result.current.isTablet).toBe(false);
  });

  it("width 768-1023 → current=md, isTablet=true, isMobile=false", () => {
    mockWidth(900);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("md");
    expect(result.current.isMobile).toBe(false);
    expect(result.current.isTablet).toBe(true);
    expect(result.current.isDesktop).toBe(false);
  });

  it("width 1024-1279 → current=lg, isDesktop=true", () => {
    mockWidth(1200);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("lg");
    expect(result.current.isMobile).toBe(false);
    expect(result.current.isTablet).toBe(false);
    expect(result.current.isDesktop).toBe(true);
  });

  it("width >= 1280 → current=xl, isDesktop=true", () => {
    mockWidth(1920);
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current.current).toBe("xl");
    expect(result.current.isDesktop).toBe(true);
  });
});
