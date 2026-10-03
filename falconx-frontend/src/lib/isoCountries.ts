/**
 * ISO 3166-1 alpha-3 国家代码常用子集（STAGE-1B-USER-PROFILE 客户端）。
 *
 * 后端 IsoCountryCodes 字典共 249 国（JDK Locale 派生）；前端只展示常见交易地区，
 * 完整字典支持后端校验，前端用户输入未列出 code 也会被后端拦截（10023）。
 */
export type IsoCountry = { code: string; nameZh: string; nameEn: string };

export const ISO_COUNTRIES: IsoCountry[] = [
  { code: "CHN", nameZh: "中国大陆", nameEn: "China" },
  { code: "HKG", nameZh: "中国香港", nameEn: "Hong Kong" },
  { code: "MAC", nameZh: "中国澳门", nameEn: "Macao" },
  { code: "TWN", nameZh: "中国台湾", nameEn: "Taiwan" },
  { code: "USA", nameZh: "美国", nameEn: "United States" },
  { code: "CAN", nameZh: "加拿大", nameEn: "Canada" },
  { code: "GBR", nameZh: "英国", nameEn: "United Kingdom" },
  { code: "DEU", nameZh: "德国", nameEn: "Germany" },
  { code: "FRA", nameZh: "法国", nameEn: "France" },
  { code: "ITA", nameZh: "意大利", nameEn: "Italy" },
  { code: "ESP", nameZh: "西班牙", nameEn: "Spain" },
  { code: "NLD", nameZh: "荷兰", nameEn: "Netherlands" },
  { code: "CHE", nameZh: "瑞士", nameEn: "Switzerland" },
  { code: "SWE", nameZh: "瑞典", nameEn: "Sweden" },
  { code: "JPN", nameZh: "日本", nameEn: "Japan" },
  { code: "KOR", nameZh: "韩国", nameEn: "South Korea" },
  { code: "SGP", nameZh: "新加坡", nameEn: "Singapore" },
  { code: "MYS", nameZh: "马来西亚", nameEn: "Malaysia" },
  { code: "THA", nameZh: "泰国", nameEn: "Thailand" },
  { code: "VNM", nameZh: "越南", nameEn: "Vietnam" },
  { code: "IDN", nameZh: "印尼", nameEn: "Indonesia" },
  { code: "PHL", nameZh: "菲律宾", nameEn: "Philippines" },
  { code: "IND", nameZh: "印度", nameEn: "India" },
  { code: "AUS", nameZh: "澳大利亚", nameEn: "Australia" },
  { code: "NZL", nameZh: "新西兰", nameEn: "New Zealand" },
  { code: "ARE", nameZh: "阿联酋", nameEn: "UAE" },
  { code: "SAU", nameZh: "沙特阿拉伯", nameEn: "Saudi Arabia" },
  { code: "BRA", nameZh: "巴西", nameEn: "Brazil" },
  { code: "MEX", nameZh: "墨西哥", nameEn: "Mexico" },
  { code: "ZAF", nameZh: "南非", nameEn: "South Africa" },
];

export const ISO_COUNTRY_MAP: Record<string, IsoCountry> = Object.fromEntries(
  ISO_COUNTRIES.map((c) => [c.code, c])
);

export function countryDisplay(code: string | null | undefined): string {
  if (!code) return "—";
  const c = ISO_COUNTRY_MAP[code];
  return c ? `${c.nameZh} (${code})` : code;
}
