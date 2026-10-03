# STAGE-2-SYMBOL-THREE-TABLE-ADMIN R7 验证报告

> 阶段 2.3 行情品种管理「三表补充」R7 收口证据。覆盖范围：console-service `/admin/symbols/quote-mappings` 与 `/admin/symbols/group-visibility` 三表新增端点、market-service `/internal/v1/market/symbols/*` internal RPC 与运行时热刷新、审计 HIGH_RISK 落库、console-frontend 三 Tab 工作区与高风险 Drawer、客户端 `falconx-frontend` 按 group 过滤的列表回归与字段无 source 泄露。
>
> 任务卡：[`STAGE-2-SYMBOL-THREE-TABLE-ADMIN`](../../process/task-cards/STAGE-2-SYMBOL-THREE-TABLE-ADMIN.md)
>
> 上轮 R7（一轮、覆盖 `t_symbol` 基础配置/暂停/Swap/Trading Hours）报告：[`STAGE-2-SYMBOL-R7-verification-report.md`](./STAGE-2-SYMBOL-R7-verification-report.md)。本报告不推翻一轮结论，专门收口三表补充范围（`t_symbol_quote_mapping` 与 `t_symbol_group_visibility`）。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-11 |
| 验证人角色 | R7 |
| 测试用例集 | [`STAGE-2-SYMBOL-THREE-TABLE-ADMIN-test-cases.md`](../STAGE-2-SYMBOL-THREE-TABLE-ADMIN-test-cases.md) |
| 验证方式 | Maven 单元 + API live 脚本 + 浏览器 QA + 客户端 API 回归 |
| 整体结论 | **R7 通过**：后端单元 6/6、API live 17/17、console-frontend 三件套通过、桌面/移动浏览器 QA 12 张截图、客户端按 group 过滤 1571/0 + 无 source 字段泄露。DevTools `runtimeErrors=0`，业务 5xx=0。 |

---

## 1. 本轮新增/确认的范围

| 维度 | 一轮（`STAGE-2-SYMBOL`） | 二轮 / 本轮（三表补充） |
| --- | --- | --- |
| `t_symbol` | 列表 / 详情 / 编辑 / 暂停 / 恢复 / Swap / Trading Hours | 沿用，无变更 |
| `t_symbol_quote_mapping` | 不在范围 | **新增**：列表筛选、新建（含 `.p` 后缀允许）、编辑、`lpSubscribeEnabled` 即时刷新、错误码 `90613/90614/90615/90616` |
| `t_symbol_group_visibility` | 不在范围 | **新增**：列表筛选、单项 upsert、批量 upsert（≤500）、错误码 `90613/90618`、INNER JOIN visible=1 过滤语义 |

---

## 2. 前置环境处置

| 事项 | 说明 |
| --- | --- |
| 进程版本对齐 | 本轮 commit `8bce89f` 提交时间 17:54，但 market-service (PID 35766, 16:40) 与 console-service (PID 75225, 14:23) 都在旧 jar 上。R1 经用户授权后：`mvn -pl falconx-market-service,falconx-console-service -am -DskipTests package` → kill 旧 PID → 用 `java -jar` 启动新 jar；其他 4 个服务（identity/gateway/trading-core/wallet）本轮未修改代码，不重启。 |
| 测试数据隔离 | 经 `docker exec falconx-mysql` 清理本轮测试遗留的 `XAUUSD.p / XAUUSD.c / XAUUSD.f / TEST_BAD_SRC` 在 `t_symbol_quote_mapping` 与 `t_symbol_group_visibility` 中的行，重跑 API 脚本不依赖已有测试痕迹。 |

---

## 3. 命令验证证据

| 命令 | 结果 |
| --- | --- |
| `mvn -pl falconx-market-service -Dtest=MarketSymbolAdminApplicationServiceMappingTests test` | ✅ `Tests run: 3, Failures: 0` |
| `mvn -pl falconx-console-service -Dtest=HighRiskPermissionRegistryTests test` | ✅ `Tests run: 3, Failures: 0` |
| `mvn -pl falconx-market-service,falconx-console-service -am -DskipTests package` | ✅ BUILD SUCCESS（含 common / domain / infrastructure / market-contract 依赖模块） |
| R7 API live script（日志：`/tmp/falconx-symbol-3table-r7/r7-api-live.log`） | ✅ `SUMMARY pass=17 fail=0` |
| `npm run lint`（`falconx-console-frontend`） | ✅ exit 0；2 个历史 warning（`CustomerDetailPage.tsx`，与本任务无关） |
| `npm run test`（`falconx-console-frontend`） | ✅ `1 file / 4 tests passed`（`adminTokenStorage.test.ts`） |
| `npm run build`（`falconx-console-frontend`） | ✅ `3067 modules transformed`，gzip 431.80 KB；保留 Vite chunk-size 大 chunk warning |

---

## 4. API 覆盖（17/17）

脚本：`/tmp/falconx-symbol-3table-r7/r7-api-live.sh` ，日志：`/tmp/falconx-symbol-3table-r7/r7-api-live.log`。

| # | 用例 | 期望 | 实际 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | `GET /admin/symbols/quote-mappings`（baseline） | code=`0` | `0` total=`1571` | mapping 基线列表 |
| 2 | `POST quote-mappings XAUUSD.p → XAUUSD` | code=`0` | `0` | **后缀 `.p` 不被拒**，验证任务卡禁止"按后缀写特殊拦截"原则 |
| 3 | `POST quote-mappings XAUUSD.c → XAUUSD` | code=`0` | `0` | 两个后缀共享同一 source |
| 4 | `POST quote-mappings DOES_NOT_EXIST_XYZ source` | `90615` | `90615` | source not found |
| 5 | `POST quote-mappings XAUUSD.p (重复)` | `90614` | `90614` | platform 唯一性 |
| 6 | `PUT quote-mappings XAUUSD.p` (lpSubscribeEnabled=0) | `0` | `0` | 编辑成功 + after_commit 热刷新 |
| 7 | `PUT quote-mappings NOT_EXIST_PLAT` | `90613` | `90613` | mapping not found |
| 8 | `POST quote-mappings enabled=2` (toggle 非法) | `90616` | `90616` | 0/1 校验 |
| 9 | `GET quote-mappings?enabled=1&lpSubscribeEnabled=0` | `0` | `0` total=`7` | XAUUSD.p 在内（被本轮 case 6 切到 lp=0） |
| 10 | `GET group-visibility`（baseline） | `0` | `0` total=`3288` | visibility 基线 |
| 11 | `PUT visibility default/XAUUSD.p visible=1` | `0` | `0` | 单项 upsert |
| 12 | `PUT visibility vip/XAUUSD.p visible=0` | `0` | `0` | 隐藏 |
| 13 | `PUT visibility default/NOT_IN_MAPPING_XYZ` | `90613` | `90613` | 目标必须在 mapping 表内 |
| 14 | `PUT visibility default/bulk [XAUUSD.p, XAUUSD.c] visible=1` | `0` | `0` | 批量 |
| 15 | `PUT visibility default/bulk symbols=[]` | `99004` | `99004` | DTO `@NotEmpty` 在 `@Valid` 层先于 service 层 `90618` 拦截。详见 §8 备注 |
| 16 | `PUT visibility default/bulk [XAUUSD.p, NO_SUCH_MAPPING]` | `90613` | `90613` | 批量中含未注册 symbol → 整批拒绝 |
| 17 | `GET group-visibility?groupCode=default` | `0` | `0` | 末态：`XAUUSD.c`/`XAUUSD.p`/HKG 列表 visible=1 |

---

## 5. 热刷新证据（mapping create/update 后立即生效）

提取自 `/tmp/falconx-symbol-3table-r7/r7-market-refresh.log`，关键日志：

```
market.symbol.quote-mapping.created   platformSymbol=XAUUSD.p sourceSymbol=XAUUSD enabled=1 lpSubscribeEnabled=1
market.symbol.quote-mapping.snapshot.refreshed platformSymbol=XAUUSD.p timing=after_commit sourceSymbolCount=1563
market.symbol.quote-mapping.created   platformSymbol=XAUUSD.c sourceSymbol=XAUUSD enabled=1 lpSubscribeEnabled=1
market.symbol.quote-mapping.snapshot.refreshed platformSymbol=XAUUSD.c timing=after_commit sourceSymbolCount=1563
market.symbol.quote-mapping.updated   platformSymbol=XAUUSD.p sourceSymbol=null enabled=null lpSubscribeEnabled=0
market.symbol.quote-mapping.snapshot.refreshed platformSymbol=XAUUSD.p timing=after_commit sourceSymbolCount=1563
market.symbol.group-visibility.upserted groupCode=default symbol=XAUUSD.p visible=1
market.symbol.group-visibility.upserted groupCode=vip     symbol=XAUUSD.p visible=0
```

结论：每次 `t_symbol_quote_mapping` create/update 后通过 `TransactionSynchronization.afterCommit` 调用 `quoteMappingService.refreshMappings()` + `quoteProvider.refreshSymbols()`，重建 LP 订阅白名单（`sourceSymbolCount=1563`）；group-visibility 不缓存，下次查询直接命中 DB。

---

## 6. 审计 HIGH_RISK 落库证据

`/tmp/falconx-symbol-3table-r7/r7-audit.log`：

| id | permission_code | risk_level | target_type | target_id | ip | occurred_at |
| --- | --- | --- | --- | --- | --- | --- |
| 47269521819570176 | `symbol:group-visibility:update` | `HIGH_RISK` | symbol | default | 127.0.0.1 | 10:32:12.227 |
| 47269521001680896 | `symbol:group-visibility:update` | `HIGH_RISK` | symbol | vip | 127.0.0.1 | 10:32:12.033 |
| 47269520565473280 | `symbol:group-visibility:update` | `HIGH_RISK` | symbol | default | 127.0.0.1 | 10:32:11.928 |
| 47269518300549120 | `symbol:quote-mapping:update` | `HIGH_RISK` | symbol | XAUUSD.p | 127.0.0.1 | 10:32:11.389 |
| 47269517042257920 | `symbol:quote-mapping:update` | `HIGH_RISK` | symbol | NULL | 127.0.0.1 | 10:32:11.089 |
| 47269516593467392 | `symbol:quote-mapping:update` | `HIGH_RISK` | symbol | NULL | 127.0.0.1 | 10:32:10.981 |
| 47269111251734528 | `symbol:group-visibility:update` | `HIGH_RISK` | symbol | default | 127.0.0.1 | 10:30:34.340 |
| 47269110421262336 | `symbol:group-visibility:update` | `HIGH_RISK` | symbol | vip | 127.0.0.1 | 10:30:34.143 |
| 47269110006026240 | `symbol:group-visibility:update` | `HIGH_RISK` | symbol | default | 127.0.0.1 | 10:30:34.044 |
| 47269107703353344 | `symbol:quote-mapping:update` | `HIGH_RISK` | symbol | XAUUSD.p | 127.0.0.1 | 10:30:33.495 |

所有三表写操作均落 `risk_level=HIGH_RISK`，含 IP 与时间戳。**`target_id=NULL`** 来自 `POST quote-mappings`（创建场景下 URL path 无 `platformSymbol` 段，AOP 取不到），是轻微观测缺口，记入 §8 同步项。

---

## 7. console-frontend 浏览器 QA 证据

12 张截图归档 `/tmp/falconx-symbol-3table-r7/`：

| 截图 | 说明 |
| --- | --- |
| `console-01-after-login.png` | superadmin 登录后 Dashboard |
| `console-02-tab1-symbol-master.png` | 桌面 1440：「Symbol 主表」Tab |
| `console-03-tab2-mapping-list.png` | 桌面 1440：「报价映射」Tab，含 platform/source/enabled/lpSubscribeEnabled 筛选 + 新建映射按钮 |
| `console-04-mapping-create-drawer.png` | 「新建报价映射（高风险）」Drawer：风险提示文案 + 必填字段（platform/source/multiplier/bid+ask 加点）+ 启用/LP 订阅开关 + reason 0/500 计数 |
| `console-05-tab3-visibility-list.png` | 桌面 1440：「组可见性」Tab，含用户组/Symbol/可见筛选 + 单项 upsert + 批量设置按钮 |
| `console-06-visibility-bulk-drawer.png` | 「批量设置组可见性（高风险）」Drawer：换行/逗号/分号分隔 + 单次最多 500 提示 + 是否可见开关 + reason 0/500 计数 |
| `console-07-mobile-symbol-master.png` | 移动 375：Symbol 主表降级视图 |
| `console-08-mobile-mapping.png` | 移动 375：报价映射降级视图 |
| `console-09-mobile-visibility.png` | 移动 375：组可见性降级视图 |
| `client-01-landing.png` / `client-02-landing-desktop.png` / `client-03-landing-mobile.png` | falconx-frontend 入口（5201）桌面 + 移动，加载 200 |

**DevTools** (`/tmp/falconx-symbol-3table-r7/r7-console-errors.txt`)：仅 antd v5 / React 19 兼容性 warning（与本任务无关，FX-067 历史问题），无业务错误。`/admin/symbols`、`/admin/symbols/quote-mappings`、`/admin/symbols/group-visibility` 接口全部 200。

`FE-SYMBOL-3TABLE-001..005` 全部命中：三 Tab、按钮权限、reason 字数提示、字段展示、单项+批量入口。

---

## 8. 客户端 falconx-frontend 回归证据

任务卡 §"客户端回归"两条用例：

| 用例 | 验证手段 | 结果 |
| --- | --- | --- |
| `C-SYMBOL-3TABLE-001` C 端 `/market/symbols` 只返回当前用户组可见且 mapping 启用、源 symbol TRADING 的 platform symbol | 直接 `GET http://127.0.0.1:18082/api/v1/market/symbols`（不带/带 `X-User-Group-Code: vip`）；归档至 `/tmp/falconx-symbol-3table-r7/r7-c-end-symbols-{default,vip}.json` | `default → total=1571`（含 `XAUUSD.p` / `XAUUSD.c`）；`vip → total=0`（仅 1 条 `visible=0` 记录，符合 SQL `INNER JOIN v.visible=1` 语义）；返回字段不含 `sourceSymbol` / `source_symbol`，仅含 `quoteSource` 值类型（无 LP 源 symbol 泄露） |
| `C-SYMBOL-3TABLE-002` WebSocket 订阅 platform symbol 后，后端使用 mapping 解析源报价并按 platform symbol 推送 | 本轮以 mapping 写操作触发的 `quote-mapping.snapshot.refreshed + sourceSymbolCount=1563` 与 §5 后端日志覆盖；WebSocket 端到端订阅截图作为遗留项（见 §9） | 部分覆盖：mapping 热刷新 + LP 白名单重建已证；WebSocket 客户端推送整链截图遗留 |

`falconx-frontend` 入口加载证据：桌面 + 移动 200，控制台无业务错误。**未做完整登录 → 行情面板的 UI 截图**（避免污染测试用户、保持 R7 范围聚焦于三表补充）。

---

## 9. 完成判定

| 判定项 | 结果 |
| --- | --- |
| R4 market-service 三表 internal RPC 可运行 + 测试通过 | ✅ |
| R9 console-service 三表 REST + RBAC + 审计可运行 + 测试通过 | ✅ |
| R10 console-frontend 三 Tab + 双 Drawer 可运行 + 三件套通过 | ✅ |
| API live 全部错误码与后缀不被拒规则验证 | ✅ |
| mapping 写操作热刷新证据 | ✅ |
| 审计 `HIGH_RISK` 落库 + IP/时间戳 | ✅ |
| 桌面 + 移动浏览器 QA | ✅ |
| 客户端 group 过滤 + 无 source 泄露 | ✅ |
| R8 文档同步 | ✅（本报告 + 路径文档 §5.3 + 任务卡完成判定 + 当前开发计划） |

**结论**：阶段 2.3 行情品种管理三表补充 R7 验证收口通过，可作为后续阶段 2.4 / 2.5 推进的基线。

### 同步项（R8 已记录或推回 R2 三轮）

| 项 | 说明 | 收纳处 |
| --- | --- | --- |
| `99004 vs 90618` (§4 case 15) | DTO `@NotEmpty` 在 `@Valid` 层先于 service 层 `90618` 拦截。`AdminGlobalExceptionHandler` 把 `MethodArgumentNotValidException` 包装为 `99004`，service 层 `90618` 为 dead code。建议 R2 三轮在 `管理端接口规范.md §6.10` 错误码块加一条 `99004 invalid payload`（含 empty / null symbols / null visible / reason 长度），或者把 DTO 校验改成 service 层抛 `90618` 以收敛到业务错误码段 | 推回 R2 三轮（轻微，不阻断阶段 2.4 启动） |
| `target_id=NULL` (§6) | `POST /admin/symbols/quote-mappings` 创建时 URL path 无 `platformSymbol`，审计 AOP `@AfterReturning` 取不到 target_id。可让 R9 把 target_id 改成从 request body 取 `platformSymbol` 字段 | 推回 R9 三轮（轻微，不阻断阶段 2.4 启动） |
| WebSocket 端到端推送截图 (§8 `C-SYMBOL-3TABLE-002`) | 已用 mapping 热刷新日志 + LP 白名单重建覆盖核心链路；客户端 UI 端到端 WebSocket 推送截图作为遗留 | 推回 R7 后续补做（建议作为阶段 2.4 启动前的快速回归） |
| `vip` 组返回 0 symbols 的用户体验 | INNER JOIN visible=1 语义下，没有显式 visible=1 配置的 group 看不到任何 symbol。这是 R2 contract 的预期产品策略，但 UI 上若用户切换到全新 group 时显示空列表可能造成困惑。建议在 console 「组可见性」Tab 提供「从 default 复制」批量初始化按钮 | 推回 R3 / R10 三轮（产品体验项，非阻断） |

测试债务沿用一轮结论：`STAGE-2-SYMBOL-test-cases.md` 中建议的 81 个 TC 与本轮新增的 11 个 TC（`STAGE-2-SYMBOL-THREE-TABLE-ADMIN-test-cases.md`）仍待全部转为独立 CI `@Test` / 组件测试；本轮以 Maven 目标测试 + API live + 浏览器 QA 完成 R7 收口。
