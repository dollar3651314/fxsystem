#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
STAGE-14C1 Task 1 —— t_symbol_leverage_tier build-time seed 生成脚本。

作用：
  连读 owner 服务 market 的 falconx_market.t_symbol（status=1），按 master 设计
  §5.2 的 CASE 映射规则把每个 symbol 归入 §5.1 的 10 个 tier 模板（T1-T10），
  展开各档位后输出 INSERT INTO t_symbol_leverage_tier(...) VALUES ... 的静态 SQL，
  内联进 V30 migration。

约束（见 AGENTS.md §3.3 owner 数据 / §3.4 DB 变更，及计划实施风险预警）：
  - 这是 build-time 一次性脚本，产物是 owner 数据的静态快照 INSERT；
    trading-core 运行时不跨 schema 直读 falconx_market。
  - id 用确定性序列（从 ID_BASE 递增），保证可重复生成同样结果、无随机。
  - group_code 固定 'default'。
  - master §5.2 CASE 只覆盖 category 1-5；category 6(stock)/7(etf) 经控制者确认
    复用 T10（股指+能源）模板（保证可交易 + 保守杠杆）。其余严格按 §5.2 CASE。

用法：
  python3 tools/tier-seed/generate_tier_seed.py > /tmp/tier_inserts.sql
  数据源默认通过 `docker exec falconx-mysql mysql -uroot -proot` 读取。
"""

import subprocess
import sys
from decimal import Decimal

# ----------------------------------------------------------------------------
# id 确定性序列起点（从 30000001 递增，与 V30 对应，可重复生成）
# ----------------------------------------------------------------------------
ID_BASE = 30000001

# ----------------------------------------------------------------------------
# §5.1 —— 10 个 tier 模板。
# 每档 = (tier_no, notional_lower, notional_upper(None=无上限), max_leverage, mm_rate)
# notional 单位：账户币 notional（master 以 USDT/USD 计），DECIMAL(24,8)。
# mm_rate DECIMAL(8,6)。注意 CHECK: max_leverage * mm_rate <= 1.0（DB 硬约束）。
# 设计准则（2026-06-03 起）：max_leverage × mm_rate ≤ 0.5（满杠杆时 MM ≤ IM 的
#   50%，保留 ≥50% IM 的强平缓冲）。本文件值已按该准则修正；V30 已凝固不可改，
#   存量库经 V38 UPDATE 修正——若重跑本脚本生成 seed，须生成新版本号 migration。
# ----------------------------------------------------------------------------
TEMPLATES = {
    # T1 顶级 crypto（BTC/ETH 系列）
    "T1": [
        (1, "0",        "50000",    125, "0.004000"),
        (2, "50000",    "250000",   100, "0.005000"),
        (3, "250000",   "1000000",   50, "0.010000"),
        (4, "1000000",  "5000000",   20, "0.025000"),
        (5, "5000000",  "20000000",  10, "0.050000"),
        (6, "20000000", None,         5, "0.100000"),
    ],
    # T2 主流 crypto（top 30）
    "T2": [
        (1, "0",       "50000",   75, "0.005000"),
        (2, "50000",   "200000",  50, "0.010000"),
        (3, "200000",  "1000000", 25, "0.020000"),
        (4, "1000000", "5000000", 10, "0.050000"),
        (5, "5000000", None,       5, "0.100000"),
    ],
    # T3 长尾 crypto
    "T3": [
        (1, "0",     "10000", 25, "0.020000"),
        (2, "10000", "50000", 10, "0.050000"),
        (3, "50000", None,     5, "0.100000"),
    ],
    # T4 稳定币对
    "T4": [
        (1, "0",       "100000",  50, "0.010000"),
        (2, "100000",  "1000000", 20, "0.025000"),
        (3, "1000000", None,      10, "0.050000"),
    ],
    # ------------------------------------------------------------------
    # T5-T10（FX/贵金属/股指能源）2026-06-03 修正：原稿 mm_rate = 1/maxLev
    #   （满杠杆 lev×mm=1.0 → MM 占满 IM、强平缓冲为零，按对手价开仓即低于
    #   强平价 → 瞬时强平；demo AUDCAD 300x 实证开仓 199ms 被强平）。
    #   统一对齐 T1-T4 既有准则 mm_rate = 0.5/maxLev（满杠杆 MM=IM 的 50%），
    #   全部 mm 减半。已回写 master §5.1，存量库经 V38 UPDATE 修正。
    # ------------------------------------------------------------------
    # T5 主流 FX（G10 直接含 USD）—— tier1 500×0.001=0.5
    "T5": [
        (1, "0",        "100000",   500, "0.001000"),
        (2, "100000",   "500000",   200, "0.002500"),
        (3, "500000",   "2000000",  100, "0.005000"),
        (4, "2000000",  "10000000",  50, "0.010000"),
        (5, "10000000", None,        20, "0.025000"),
    ],
    # T6 G10 FX 交叉盘 —— tier1 300×0.00165=0.495
    "T6": [
        (1, "0",       "50000",   300, "0.001650"),
        (2, "50000",   "250000",  100, "0.005000"),
        (3, "250000",  "1000000",  50, "0.010000"),
        (4, "1000000", None,       20, "0.025000"),
    ],
    # T7 次级 FX
    "T7": [
        (1, "0",       "50000",   200, "0.002500"),
        (2, "50000",   "250000",  100, "0.005000"),
        (3, "250000",  "1000000",  50, "0.010000"),
        (4, "1000000", None,       20, "0.025000"),
    ],
    # T8 新兴市场 FX
    "T8": [
        (1, "0",      "25000",  100, "0.005000"),
        (2, "25000",  "100000",  50, "0.010000"),
        (3, "100000", "500000",  20, "0.025000"),
        (4, "500000", None,      10, "0.050000"),
    ],
    # T9 贵金属
    # 历史注：master §5.1 T9 tier3 原写 50x×2.5%=1.25 违反 CHECK，STAGE-14C1
    #   修正 2.5%→2.0%（与 T6/T7 同档一致）；2026-06-03 随 T5-T10 减半成 1.0%。
    "T9": [
        (1, "0",       "50000",   200, "0.002500"),
        (2, "50000",   "200000",  100, "0.005000"),
        (3, "200000",  "1000000",  50, "0.010000"),
        (4, "1000000", "5000000",  20, "0.025000"),
        (5, "5000000", None,       10, "0.050000"),
    ],
    # T10 股指 + 能源（category 6/7 复用）
    "T10": [
        (1, "0",       "100000",  100, "0.005000"),
        (2, "100000",  "500000",   50, "0.010000"),
        (3, "500000",  "2000000",  20, "0.025000"),
        (4, "2000000", None,       10, "0.050000"),
    ],
}

# §5.2 显式 symbol 集（T1）
T1_SYMBOLS = {
    "BTCUSDT", "BTCUSD", "BTCUSDC", "BTCFDUSD", "BTCEUR",
    "ETHUSDT", "ETHUSD", "ETHUSDC",
}
# §5.2 T2 base_currency 集
T2_BASES = {
    "SOL", "BNB", "XRP", "ADA", "DOGE", "AVAX", "LINK", "DOT",
    "TRX", "POL", "LTC", "BCH", "ATOM", "NEAR", "FIL",
    "ARB", "OP", "APT", "SUI", "TON", "ETC", "HBAR", "ICP",
    "AAVE", "UNI", "RENDER", "TIA", "SEI",
    "PEPE", "SHIB", "PAXG", "XAUT",
}
# §5.2 T4 稳定币 base 集（且 quote ∈ {USDT,USD}）
T4_STABLE_BASES = {"USDC", "USDD", "TUSD", "PYUSD", "FDUSD"}
# §5.2 T5 G10 货币（quote=USD 时 base 落此集，外加 MXN）
T5_G10 = {"EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD"}
T5_QUOTE_USD_BASES = T5_G10 | {"MXN"}
# §5.2 T7 次级 FX 货币
T7_CCY = {"SEK", "NOK", "DKK", "HKD", "SGD", "CZK"}
# §5.2 T8 新兴市场 FX 货币
T8_CCY = {"CNH", "MXN", "TRY", "ZAR", "PLN", "HUF", "BYN"}
# §5.2 T9 贵金属 base
T9_METAL_BASES = {"XAU", "XAG", "XPT", "XPD"}


def classify(symbol, category, base, quote):
    """按 master §5.2 CASE 返回模板名 T1-T10。
    CASE 自上而下，先命中先返回。category 6/7 由控制者确认归 T10。"""
    # 1. T1 显式 symbol
    if symbol in T1_SYMBOLS:
        return "T1"
    # 2. T2 主流 crypto base
    if base in T2_BASES:
        return "T2"
    # 3. T4 稳定币对（base ∈ 稳定币 且 quote ∈ {USDT,USD}）
    if base in T4_STABLE_BASES and quote in {"USDT", "USD"}:
        return "T4"
    # 4. category=1 长尾 crypto → T3
    if category == 1:
        return "T3"
    # 5. T5 主流 FX（G10 直接含 USD）
    #    语义：(base=USD 且 quote∈G10) 或 (quote=USD 且 base∈G10+MXN)
    if category == 2 and (
        (base == "USD" and quote in T5_G10)
        or (quote == "USD" and base in T5_QUOTE_USD_BASES)
    ):
        return "T5"
    # 6. T7 次级 FX
    if category == 2 and (base in T7_CCY or quote in T7_CCY):
        return "T7"
    # 7. T8 新兴市场 FX
    if category == 2 and (base in T8_CCY or quote in T8_CCY):
        return "T8"
    # 8. category=2 其余 → T6（G10 交叉盘）
    if category == 2:
        return "T6"
    # 9. T9 贵金属
    if category == 3 or base in T9_METAL_BASES:
        return "T9"
    # 10. category 4(index)/5(energy) → T10
    if category in (4, 5):
        return "T10"
    # 控制者确认：category 6(stock)/7(etf) 复用 T10
    if category in (6, 7):
        return "T10"
    return None  # 未落任何模板（应为 0）


def fetch_symbols():
    """通过 docker exec 连读 owner market schema 的 t_symbol（status=1）。"""
    sql = (
        "SELECT symbol, category, base_currency, quote_currency "
        "FROM falconx_market.t_symbol WHERE status=1 ORDER BY symbol;"
    )
    out = subprocess.check_output(
        ["docker", "exec", "falconx-mysql", "mysql", "-uroot", "-proot", "-N", "-e", sql],
        stderr=subprocess.DEVNULL,
    ).decode("utf-8")
    rows = []
    for line in out.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        rows.append((parts[0], int(parts[1]), parts[2], parts[3]))
    return rows


def emit_mapping_align():
    """B 切片（2026-06-03）：生成 market 侧 mapping.max_leverage ↔ tier1 上限对齐 SQL。

    背景：t_symbol_quote_mapping.max_leverage（客户端静态上限/SymbolSpec 来源）与
    t_symbol_leverage_tier tier1 上限（开仓真源）两套 seed 独立维护——demo 实测 105 个
    symbol mapping > tier1 致 UI 可选但下单 30070。运维已对齐 demo；本产物作为 market
    migration（V19）对齐所有存量/新环境，防重灌 seed 复现错配。

    规则：mapping.max_leverage 只降不升（> tier1 上限才 UPDATE，admin 有意配更低的保留）。
    用法：python3 tools/tier-seed/generate_tier_seed.py mapping-align > V19__....sql
    """
    rows = fetch_symbols()
    by_cap = {}  # tier1 cap -> [symbol...]
    for symbol, category, base, quote in rows:
        tpl = classify(symbol, category, base, quote)
        if tpl is None:
            continue
        cap = TEMPLATES[tpl][0][3]  # tier1 max_leverage
        by_cap.setdefault(cap, []).append(symbol)

    print("-- 自动生成（tools/tier-seed/generate_tier_seed.py mapping-align）。请勿手工编辑。")
    print("-- B 切片（2026-06-03）：t_symbol_quote_mapping.max_leverage 对齐 t_symbol_leverage_tier")
    print("-- tier1 上限（trading 侧开仓真源，模板见 master §5.1）。只降不升（admin 配更低的保留），")
    print("-- 幂等（重跑匹配 0 行）。demo 已先行运维对齐（备份 docs/operations/leverage-align-backup-demo-20260603.tsv）。")
    for cap in sorted(by_cap):
        symbols = sorted(by_cap[cap])
        print(f"\n-- tier1 上限 {cap}x（{len(symbols)} symbols）")
        batch = 200
        for i in range(0, len(symbols), batch):
            chunk = ",".join(f"'{s}'" for s in symbols[i:i + batch])
            print("UPDATE t_symbol_quote_mapping SET max_leverage = "
                  f"{cap} WHERE max_leverage > {cap} AND platform_symbol IN ({chunk});")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "mapping-align":
        emit_mapping_align()
        return
    rows = fetch_symbols()
    next_id = ID_BASE
    values = []
    unmapped = []
    template_count = {}  # 模板 -> 命中 symbol 数

    for symbol, category, base, quote in rows:
        tpl = classify(symbol, category, base, quote)
        if tpl is None:
            unmapped.append((symbol, category, base, quote))
            continue
        template_count[tpl] = template_count.get(tpl, 0) + 1
        for (tier_no, lower, upper, max_lev, mm_rate) in TEMPLATES[tpl]:
            # CHECK 自校验：max_leverage * mm_rate <= 1.0
            assert Decimal(max_lev) * Decimal(mm_rate) <= Decimal("1.0"), \
                f"CHECK violated: {symbol} {tpl} tier{tier_no} {max_lev}x{mm_rate}"
            upper_sql = "NULL" if upper is None else upper
            values.append(
                f"({next_id},'{symbol}','default',{tier_no},"
                f"{lower},{upper_sql},{max_lev},{mm_rate})"
            )
            next_id += 1

    if unmapped:
        sys.stderr.write(f"WARN: {len(unmapped)} symbol 未落任何模板:\n")
        for u in unmapped[:50]:
            sys.stderr.write(f"  {u}\n")

    # 统计输出到 stderr（不污染 SQL 产物）
    sys.stderr.write("=== 模板覆盖 symbol 数 ===\n")
    total_sym = 0
    for tpl in sorted(template_count, key=lambda x: int(x[1:])):
        sys.stderr.write(f"  {tpl}: {template_count[tpl]}\n")
        total_sym += template_count[tpl]
    sys.stderr.write(f"  总 symbol: {total_sym}\n")
    sys.stderr.write(f"  总 INSERT 行: {len(values)}\n")

    # SQL 产物到 stdout：分批 INSERT，每批 200 行 VALUES，避免单语句过长
    print("-- 自动生成（tools/tier-seed/generate_tier_seed.py）。请勿手工编辑。")
    print("-- 数据源快照：falconx_market.t_symbol WHERE status=1（owner=market）。")
    print(f"-- 总 symbol={total_sym}，总 tier 行={len(values)}，id 自 {ID_BASE} 递增。")
    cols = ("INSERT INTO t_symbol_leverage_tier "
            "(id,symbol,group_code,tier_no,notional_lower,notional_upper,max_leverage,mm_rate) VALUES")
    batch = 200
    for i in range(0, len(values), batch):
        chunk = values[i:i + batch]
        print(cols)
        print(",\n".join(chunk) + ";")


if __name__ == "__main__":
    main()
