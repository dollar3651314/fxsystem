import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { useThemeStore } from "./themeStore";

describe("themeStore（管理端深浅主题）", () => {
  beforeEach(() => {
    // 每个用例从浅色基线起步，并清掉上一个用例落在 <html> 的属性。
    useThemeStore.setState({ theme: "light" });
    document.documentElement.removeAttribute("data-theme");
  });

  afterEach(() => {
    localStorage.clear();
  });

  it("默认主题为浅色", () => {
    expect(useThemeStore.getState().theme).toBe("light");
  });

  it("setTheme(dark) 同步写 <html data-theme> 并广播 falconx:theme:changed", () => {
    let received: string | null = null;
    const handler = (e: Event) => {
      received = (e as CustomEvent<{ mode: string }>).detail.mode;
    };
    window.addEventListener("falconx:theme:changed", handler);

    useThemeStore.getState().setTheme("dark");

    expect(useThemeStore.getState().theme).toBe("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    expect(received).toBe("dark");

    window.removeEventListener("falconx:theme:changed", handler);
  });

  it("toggle 在深浅之间来回切换", () => {
    expect(useThemeStore.getState().theme).toBe("light");
    useThemeStore.getState().toggle();
    expect(useThemeStore.getState().theme).toBe("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    useThemeStore.getState().toggle();
    expect(useThemeStore.getState().theme).toBe("light");
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("setTheme 持久化到 localStorage（key falconx-console-theme）", () => {
    useThemeStore.getState().setTheme("dark");
    const raw = localStorage.getItem("falconx-console-theme");
    expect(raw).toBeTruthy();
    expect(JSON.parse(raw as string).state.theme).toBe("dark");
  });
});
