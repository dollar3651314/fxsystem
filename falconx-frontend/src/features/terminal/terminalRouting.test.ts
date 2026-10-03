import { describe, expect, it } from "vitest";
import {
  resolveTerminalViewFromHash,
  terminalViewToHash,
} from "./terminalRouting";

describe("terminalRouting", () => {
  it("resolves supported terminal hashes", () => {
    expect(resolveTerminalViewFromHash("#terminal/market")).toBe("market");
    expect(resolveTerminalViewFromHash("#terminal/wallet")).toBe("wallet");
  });

  it("ignores auth hashes and unsupported terminal views", () => {
    expect(resolveTerminalViewFromHash("#login")).toBeNull();
    expect(resolveTerminalViewFromHash("#register")).toBeNull();
    expect(resolveTerminalViewFromHash("#terminal/orders")).toBeNull();
  });

  it("serializes terminal views into shareable hashes", () => {
    expect(terminalViewToHash("dashboard")).toBe("#terminal/dashboard");
    expect(terminalViewToHash("market")).toBe("#terminal/market");
  });
});
