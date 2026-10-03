import { describe, expect, it } from "vitest";
import { extractPlaceholders } from "./types";

describe("extractPlaceholders (TC-NOTIF-FE-053)", () => {
  it("TC-NOTIF-FE-053a: 单模板提取所有 ${var} 变量", () => {
    expect(extractPlaceholders("Hello ${name}, balance is ${amount}"))
      .toEqual(["name", "amount"]);
  });

  it("TC-NOTIF-FE-053b: 多模板合并 + 去重 + 保持顺序", () => {
    expect(extractPlaceholders(
      "${symbol} ${direction}",
      "${symbol} ${targetPrice}",
    )).toEqual(["symbol", "direction", "targetPrice"]);
  });

  it("TC-NOTIF-FE-053c: 空 / null / 无占位符模板返回空数组", () => {
    expect(extractPlaceholders("")).toEqual([]);
    expect(extractPlaceholders("no placeholders here")).toEqual([]);
  });

  it("TC-NOTIF-FE-053d: 仅匹配合法变量名（字母 + 下划线开头）", () => {
    expect(extractPlaceholders("${valid_name} ${123invalid} ${_underscore}"))
      .toEqual(["valid_name", "_underscore"]);
  });
});
