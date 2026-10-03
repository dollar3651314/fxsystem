# tier-seed —— t_symbol_leverage_tier build-time seed 生成

STAGE-14C1 Task 1 产物。`generate_tier_seed.py` 连读 owner 服务 market 的
`falconx_market.t_symbol`（`status=1`），按 master 设计
`docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md` §5.2 的
CASE 映射规则把每个 symbol 归入 §5.1 的 10 个 tier 模板（T1-T10），展开各档位后
输出静态 `INSERT INTO t_symbol_leverage_tier(...) VALUES ...`，内联进
`V30__symbol_leverage_tier.sql`。

## 为什么是 build-time 而不是运行时
trading-core 不可运行时跨 schema 直读 `falconx_market`（owner 边界，AGENTS §3.2/§3.3）。
本脚本在构建期一次性读 owner 数据生成静态快照 SQL，产物（V30 INSERT）不含任何
运行时跨库访问；脚本与产物均入库留痕。

## 用法
```bash
# 默认通过 docker exec falconx-mysql mysql -uroot -proot 读本地 owner 数据
python3 tools/tier-seed/generate_tier_seed.py > /tmp/tier_inserts.sql   # SQL 到 stdout
# 模板覆盖统计、未落模板告警输出到 stderr
```
生成结果是确定性的：id 从 `30000001` 递增，无随机，相同输入重复运行得到相同 SQL。

## 映射要点（§5.2）
- category 1（crypto）：显式 BTC/ETH 系列 → T1；§5.2 base 集 → T2；其余 → T3。
  稳定币对（base∈USDC/USDD/TUSD/PYUSD/FDUSD 且 quote∈USDT/USD）→ T4。
- category 2（forex）：G10 含 USD → T5；次级 FX → T7；新兴市场 FX → T8；其余交叉盘 → T6。
- category 3（metal）或 base∈XAU/XAG/XPT/XPD → T9。
- category 4（index）/5（energy）→ T10。
- **category 6（stock）/7（etf）：master §5.2 CASE 未覆盖，经控制者确认复用 T10**
  （股指+能源模板，保证可交易 + 保守杠杆）。

## master §5.1 笔误修正（已记入 V30 注释 + 回写 master 勘误）
T9 tier3 master 原写 `50x × 2.5% = 1.25`，违反表级
`CHECK (max_leverage * mm_rate <= 1.0)`（master 其余 24 个边界行乘积均为 1.0）。
修正：master §5.1 T9 tier3 笔误 2.5%→2.0%，与 T6/T7 同档一致，50×0.02=1.0。
即 T6（G10 交叉）/T7（次级 FX）tier3 同档均为 `50x/2.0%`，T9 贵金属 tier3
同档应与之一致；最可能的笔误是 mmRate 2.0 被误写成 2.5（而非杠杆应降）。
50×0.02=1.0 满足 CHECK 且维持 mmRate 单调 0.5%→1%→2%→5%→10%。脚本
`TEMPLATES["T9"]` 中已标注，master §5.1 已加勘误注释。

## 本地校验结果快照（2026-05-29，1572 symbol）
| 模板 | 覆盖 symbol 数 |
|---|---|
| T1 | 3 |
| T2 | 24 |
| T3 | 20 |
| T4 | 0（数据中无稳定币对） |
| T5 | 7 |
| T6 | 25 |
| T7 | 22 |
| T8 | 14 |
| T9 | 6 |
| T10 | 1451（= category 4+5+6+7） |
| 合计 | 1572，未落模板 0 |

总 tier 行 6311。一致性核对：category1=47=T1+T2+T3，category2=68=T5+T6+T7+T8，
category3=6=T9，category4+5+6+7=1451=T10。
