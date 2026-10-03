import { describe, expect, it } from "vitest";
import {
  currencyScale,
  formatMoney,
  formatPercent,
  formatPnl,
  formatQty,
  formatSignedMoney,
  formatSignedPnl,
  truncToScale,
} from "./precision";

describe("truncToScale", () => {
  it("向零截断正数多余小数（不四舍五入）", () => {
    expect(truncToScale(1.236, 2)).toBe(1.23);
    expect(truncToScale(1.239, 2)).toBe(1.23);
  });

  it("向零截断负数（保留符号、不夸大）", () => {
    expect(truncToScale(-1.236, 2)).toBe(-1.23);
    expect(truncToScale(-1.239, 2)).toBe(-1.23);
  });

  it("零返回零", () => {
    expect(truncToScale(0, 2)).toBe(0);
  });

  it("不足精度位的值原样保留", () => {
    expect(truncToScale(1.2, 4)).toBe(1.2);
  });

  it("scale<=0 截断到整数", () => {
    expect(truncToScale(1.99, 0)).toBe(1);
    expect(truncToScale(-1.99, 0)).toBe(-1);
  });

  it("浮点表示误差用 epsilon 抵消", () => {
    // 1.005 * 100 = 100.49999… 不加 eps 会截成 1.00
    expect(truncToScale(1.005, 2)).toBe(1.0);
    expect(truncToScale(2.675, 2)).toBe(2.67);
  });

  it("非有限值原样返回", () => {
    expect(truncToScale(Number.NaN, 2)).toBeNaN();
    expect(truncToScale(Number.POSITIVE_INFINITY, 2)).toBe(Number.POSITIVE_INFINITY);
  });
});

describe("currencyScale", () => {
  it("USD 系稳定币 2 位", () => {
    expect(currencyScale("USD")).toBe(2);
    expect(currencyScale("USDT")).toBe(2);
    expect(currencyScale("USDC")).toBe(2);
  });

  it("零小数法币 0 位", () => {
    expect(currencyScale("JPY")).toBe(0);
    expect(currencyScale("KRW")).toBe(0);
  });

  it("大小写不敏感", () => {
    expect(currencyScale("jpy")).toBe(0);
    expect(currencyScale("usdt")).toBe(2);
  });

  it("未知币种默认 2", () => {
    expect(currencyScale("XYZ")).toBe(2);
  });

  it("空 / null 默认 2", () => {
    expect(currencyScale()).toBe(2);
    expect(currencyScale(null)).toBe(2);
    expect(currencyScale("")).toBe(2);
  });
});

describe("formatMoney", () => {
  it("默认 2 位截断（向零）", () => {
    expect(formatMoney(1234.567)).toBe("1,234.56");
    expect(formatMoney("1234.567")).toBe("1,234.56");
  });

  it("JPY 0 位（向零截断到整数，非四舍五入）", () => {
    expect(formatMoney(1234.99, "JPY")).toBe("1,234");
  });

  it("USD 显式 2 位", () => {
    expect(formatMoney(10, "USD")).toBe("10.00");
  });

  it("负数保留符号、不加正号", () => {
    expect(formatMoney(-5.5, "USD")).toBe("-5.50");
  });

  it("null / undefined / 空 → --", () => {
    expect(formatMoney(null)).toBe("--");
    expect(formatMoney(undefined)).toBe("--");
    expect(formatMoney("")).toBe("--");
    expect(formatMoney("--")).toBe("--");
  });

  it("非法数值 → --", () => {
    expect(formatMoney("abc")).toBe("--");
  });

  it("scaleOverride 强制位数（手续费/swap 4 位，向零截断）", () => {
    expect(formatMoney(1.23456, "USDT", 4)).toBe("1.2345");
    expect(formatMoney(1.23456, undefined, 4)).toBe("1.2345");
    // override 优先于币种精度（JPY 本为 0 位，被 4 覆盖）
    expect(formatMoney(1.2, "JPY", 4)).toBe("1.2000");
  });
});

describe("formatSignedMoney", () => {
  it("正数加 +", () => {
    expect(formatSignedMoney(12.3, "USD")).toBe("+12.30");
  });

  it("负数自带 -（不重复加号）", () => {
    expect(formatSignedMoney(-12.3, "USD")).toBe("-12.30");
  });

  it("0 不加号", () => {
    expect(formatSignedMoney(0, "USD")).toBe("0.00");
  });

  it("币种感知（JPY 0 位 + 符号）", () => {
    expect(formatSignedMoney(100.9, "JPY")).toBe("+100");
  });

  it("null → --", () => {
    expect(formatSignedMoney(null)).toBe("--");
  });

  it("scaleOverride 强制位数（4 位 + 符号）", () => {
    expect(formatSignedMoney(1.23456, "USDT", 4)).toBe("+1.2345");
    expect(formatSignedMoney(-1.23456, "USDT", 4)).toBe("-1.2345");
  });
});

describe("formatPnl", () => {
  it("|v|>=1 保留 2 位（四舍五入）", () => {
    expect(formatPnl(12.3456)).toBe("12.35");
    expect(formatPnl(1.5)).toBe("1.50");
    expect(formatPnl(-12.3456)).toBe("-12.35");
  });

  it("0<|v|<1 保留 2 位有效数字（跳过前导零，四舍五入）", () => {
    expect(formatPnl(-0.00216674)).toBe("-0.0022");
    expect(formatPnl(0.0000216)).toBe("0.000022");
    expect(formatPnl(-0.0015012)).toBe("-0.0015");
  });

  it("0 → 0.00", () => {
    expect(formatPnl(0)).toBe("0.00");
  });

  it("null / undefined / 空 → --", () => {
    expect(formatPnl(null)).toBe("--");
    expect(formatPnl(undefined)).toBe("--");
    expect(formatPnl("")).toBe("--");
    expect(formatPnl("abc")).toBe("--");
  });

  it("接受字符串入参", () => {
    expect(formatPnl("-0.00216674")).toBe("-0.0022");
  });
});

describe("formatSignedPnl", () => {
  it("正数加 +", () => {
    expect(formatSignedPnl(1.5)).toBe("+1.50");
    expect(formatSignedPnl(0.0000216)).toBe("+0.000022");
  });

  it("负数自带 -（不重复加号）", () => {
    expect(formatSignedPnl(-0.00216674)).toBe("-0.0022");
    expect(formatSignedPnl(-12.3456)).toBe("-12.35");
  });

  it("0 不加号", () => {
    expect(formatSignedPnl(0)).toBe("0.00");
  });

  it("null → --", () => {
    expect(formatSignedPnl(null)).toBe("--");
  });
});

describe("formatQty", () => {
  it("按给定精度向零截断", () => {
    expect(formatQty(1.23456, 3)).toBe("1.234");
    expect(formatQty(1.23456, 0)).toBe("1");
  });

  it("缺省精度回退 2 位", () => {
    expect(formatQty(1.239)).toBe("1.23");
    expect(formatQty(1.239, null)).toBe("1.23");
  });

  it("负精度回退 2 位", () => {
    expect(formatQty(1.239, -1)).toBe("1.23");
  });

  it("null → --", () => {
    expect(formatQty(null, 2)).toBe("--");
  });
});

describe("formatPercent", () => {
  it("入参已是百分数，按位数 + %（向零截断）", () => {
    expect(formatPercent(1.236, 2)).toBe("1.23%");
    expect(formatPercent(1.236, 3)).toBe("1.236%");
  });

  it("默认 2 位", () => {
    expect(formatPercent(5)).toBe("5.00%");
  });

  it("0 位", () => {
    expect(formatPercent(5.9, 0)).toBe("5%");
  });

  it("负数保留符号", () => {
    expect(formatPercent(-1.239, 2)).toBe("-1.23%");
  });

  it("null → --", () => {
    expect(formatPercent(null)).toBe("--");
  });
});
