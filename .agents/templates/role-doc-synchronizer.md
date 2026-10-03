# R8 Doc Synchronizer 文档同步者任务模板

> 本模板用于 R8 角色的任务派发。R8 在 R7 验证通过后，同步所有真源文档与摘要文档。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R8 Doc Synchronizer 文档同步者
- 上游：R2（契约 spec）+ R7（验证报告）
- 下游：R1（最终回收）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R8 Doc Synchronizer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R8 的"✅ 可以"列表内：编辑统一接口文档/Kafka 规范/状态机/数据库设计/当前开发计划/统一问题清单/各 README/docs/README
[ ] 不在 R8 的"❌ 不可以"列表内：修改正式契约真源（已由 R2 主导）/ 修改业务代码 / 自行决定阶段结论
[ ] R7 验证报告已通过
[ ] 已读取必读文档（见下）
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2.8 R8 角色定义
- [`AI协作与提示规范`](../../docs/process/AI协作与提示规范.md) §11 文档同步规则
- [`完成定义`](../../docs/process/完成定义.md)
- R2 输出的契约 spec
- R4 / R5 输出的实现说明
- R7 验证报告

---

## 允许修改

- [`FalconX 统一接口文档`](../../docs/api/FalconX统一接口文档.md)（R2 改正式 REST/WS 规范后，R8 在统一接口文档汇总）
- [`Kafka 事件规范`](../../docs/event/Kafka事件规范.md)（R2 改 Kafka payload 后，R8 补示例）
- [`状态机规范`](../../docs/domain/状态机规范.md)（R2 改状态机后，R8 同步状态图）
- [`数据库设计`](../../docs/database/falconx一期数据库设计.md)（R2 改 DDL 后，R8 同步表说明）
- [`当前开发计划`](../../docs/setup/当前开发计划.md)（如阶段状态变化）
- [`统一问题清单`](../../docs/process/统一问题清单.md)（如修复了清单中的问题）
- 各服务 `README.md`（仅在模块事实变化时同步）
- `docs/README.md`（如新增正式文档）
- `docs/process/BBook一期完成执行路径.md`（如阶段任务状态变化）

---

## 禁止修改

- 业务代码
- R2 主导的正式契约文档（除汇总性质的更新；新增字段必须由 R2 主导）
- 已归档文档（`archive/` 下的内容只追加，不改写）

---

## 实施步骤

### 1. 读取上下文

- [ ] R7 验证报告：哪些功能已验证通过
- [ ] R2 契约 spec：哪些契约已变化
- [ ] R4 / R5 实施说明：实际实现与契约是否一致
- [ ] [`AI协作与提示规范`](../../docs/process/AI协作与提示规范.md) §11 同步顺序

### 2. 真源文档同步

按以下顺序（先真源后摘要）：

#### 2.1 接口任务

如有 REST 变化：

- [ ] 确认 [`REST 接口规范`](../../docs/api/REST接口规范.md) 已由 R2 同步
- [ ] 在 [`FalconX 统一接口文档`](../../docs/api/FalconX统一接口文档.md) 添加 / 更新接口条目
- [ ] 接口条目必须包含：所属服务、路径、方法、认证、请求头、请求体、响应、错误码、关键日志、测试结论

如有 WebSocket 变化：

- [ ] 确认 [`WebSocket 接口规范`](../../docs/api/WebSocket接口规范.md) 已由 R2 同步
- [ ] 在 [`FalconX 统一接口文档`](../../docs/api/FalconX统一接口文档.md) 添加 / 更新 WebSocket 条目

#### 2.2 Kafka 任务

如有 Kafka topic / payload 变化：

- [ ] 确认 [`Kafka 事件规范`](../../docs/event/Kafka事件规范.md) 已由 R2 同步
- [ ] 补充示例 payload、消费 owner、幂等键说明

#### 2.3 数据库任务

如有 DB schema 变化：

- [ ] 确认 [`数据库设计`](../../docs/database/falconx一期数据库设计.md) 已由 R2 同步
- [ ] 确认 Flyway migration 文件已落位

#### 2.4 状态机任务

如有状态机变化：

- [ ] 确认 [`状态机规范`](../../docs/domain/状态机规范.md) 已由 R2 同步
- [ ] 同步状态迁移图（如有图）

### 3. 状态文档同步

按 [`AI协作与提示规范`](../../docs/process/AI协作与提示规范.md) §11.2：

如本轮影响：

- 当前正式阶段 / 已完成 / 未完成 / 阻塞项 / 下一步顺序

必须同步：

- [ ] [`当前开发计划`](../../docs/setup/当前开发计划.md)：更新对应任务的状态、附验证证据
- [ ] [`BBook一期完成执行路径`](../../docs/process/BBook一期完成执行路径.md)：如阶段任务完成，更新阶段状态
- [ ] [`统一问题清单`](../../docs/process/统一问题清单.md)：如修复了问题，更新状态
  - 已修复 / 不成立 → 移到 [`已归档问题清单`](../../docs/process/archive/统一问题清单-已归档.md)

### 4. 摘要文档同步

按需更新（仅在事实变化时）：

- [ ] 各服务 `README.md`（模块事实变化）
- [ ] `docs/README.md`（新增正式文档时）
- [ ] 根 `README.md`（项目能力摘要变化时）

### 5. 链接完整性检查

```bash
# 用 Python 扫描修改文档的所有相对链接
python3 << 'EOF'
import re, os
# 列出所有改过的 .md 文件
docs = [
    "docs/process/...修改的文档...",
]
for doc in docs:
    with open(doc) as f:
        content = f.read()
    links = re.findall(r'\[[^\]]+\]\(([^)]+)\)', content)
    errors = []
    for link in links:
        if link.startswith('http'):
            continue
        path = link.split('#')[0]
        if not path:
            continue
        base = os.path.dirname(doc)
        full = os.path.normpath(os.path.join(base, path))
        if not os.path.exists(full):
            errors.append(f"{link} -> {full}")
    print(f"{doc}: {len(links)} 链接, {len(errors)} 失效")
    for e in errors:
        print(f"  失效: {e}")
EOF
```

### 6. 与代码事实交叉核对

- [ ] 接口路径：实际 Controller 中的路径与文档一致
- [ ] 字段名：实际实体 / payload 中的字段与文档一致
- [ ] 状态枚举：实际枚举值与状态机规范一致
- [ ] 错误码：实际抛出的错误码与文档一致

任意不一致 → 必须返回 R1 让 R2 重新决策（不在 R8 自行修改契约）。

---

## 必须返回的交付清单

1. **修改文件清单**
2. **真源 vs 摘要的同步顺序说明**
3. **链接完整性检查结果**：所有相对链接可达
4. **代码事实交叉核对结果**：文档与代码一致
5. **未同步项 / 待 R1 确认项**

---

## 强制约束

- **不得**修改业务代码
- **不得**修改 R2 主导的正式契约（除汇总性质的更新）
- **不得**自行决定阶段结论（如"已完成""已修复"）
- **不得**让真源文档和摘要文档保留冲突描述
- **不得**跳过链接完整性检查
- **不得**忽略文档与代码的不一致

R8 完成是 R1 形成 Git 回滚点的前置条件。R8 同步未完成 → 不得 commit。
