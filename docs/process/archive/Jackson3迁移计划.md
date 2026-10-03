# Jackson 3 迁移计划

## 1. 目的

本文件用于保留 FalconX 从历史 `Jackson 2.21.2` 兼容模式迁移到当前 `Jackson 3.1.0` 基线的影响范围、实施步骤和验收标准。

该迁移是一个独立的技术专项，不与普通业务需求混合实施。

## 2. 当前状态

当前仓库的 JSON 技术栈状态如下：

- 目标技术基线：`Jackson 3.x`
- 当前专项状态：`STAGE6C-JACKSON-MIGRATE-02 已完成并收口`
- 当前仓库状态：5 个服务的运行时代码已切到 `tools.jackson.*` 主路径，`jackson-annotations` 仍保留 `com.fasterxml.jackson.annotation.*`
- 当前运行时依赖已统一到 `tools.jackson.core:jackson-databind / jackson-core:3.1.0`

当前已确认受影响的服务模块：

- `falconx-gateway`
- `falconx-identity-service`
- `falconx-market-service`
- `falconx-trading-core-service`
- `falconx-wallet-service`

### 2.1 `STAGE6C-JACKSON-MIGRATE-02` 当前实施结果

- 已完成：
  - `gateway / identity / market / trading / wallet` 的运行时代码主路径切换到 `tools.jackson.*`
  - 5 个服务移除显式 `jackson-databind / jackson-datatype-jsr310` 子模块声明，统一回到父工程 + Spring Boot 4 BOM 管理
  - `market-contract` 的 `MarketPriceTickEventPayload / MarketKlineUpdateEventPayload` 补齐 `@JsonIgnoreProperties(ignoreUnknown = true)`
  - `MarketPriceTickEventPayload.ts` 固定为数值时间戳输出，保持既有 Kafka / Redis / ClickHouse 主链路兼容
  - `gateway` 的 `ObjectMapper` 构造方式统一到 `JsonMapper.builder().build()`
- 已通过回归：
  - `gateway`：JWT / HTTP 错误 JSON / WebSocket 入口
  - `identity-service`：JWT、登录注册、Kafka 入站
  - `market-service`：Tiingo 协议解析、Kafka publisher、Redis / ClickHouse / WebSocket、本地与外部门禁用例
  - `trading-core-service`：Kafka 入站、Outbox publisher、`Swap` 结算、用户视角查询、强平主链路
  - `wallet-service`：Kafka、MyBatis、Web3 单测与持久化链路、外部真节点成功 / 失败路径
- 当前阶段结论：
  - `Kafka / Redis / MyBatis / JWT / Tiingo / Web3` 回归已全部闭合
  - `Stage 6C` 已完成并收口
  - 当前系统仍不能表述为“生产可用”或“可安全对外公测”

## 3. 迁移原则

迁移过程必须遵循以下原则：

1. 作为独立专项实施，不夹带普通业务需求  
2. 统一由父工程和 Spring Boot 4 BOM 管理版本，不允许各子模块私自覆盖  
3. 先迁移共享配置和基础设施，再迁移服务代码  
4. 每完成一个服务，都要完成该服务的 JSON、Kafka、Redis、MyBatis 回归验证  
5. 专项完成后，所有入口文档必须统一表述为当前已使用 Spring Boot 4 BOM 管理的 `Jackson 3.1.0`，不得保留 Jackson 2 作为当前基线

## 4. 影响清单

### 4.1 依赖与版本管理

需要调整的范围：

- 父工程依赖管理
- 各服务显式声明的 Jackson 依赖
- `jackson-datatype-jsr310` 等 Java 8 模块依赖

迁移重点：

- 统一 JSON 依赖策略
- 去掉无必要的 Jackson 2 显式依赖
- 明确 `Spring Boot 4 + Jackson 3` 的最终版本组合

### 4.2 import 与 API

当前仓库存在大量以下类型用法：

- `ObjectMapper`
- `JsonMapper`
- `JsonNode`
- `JsonProcessingException`

迁移时需要逐步核对：

- `com.fasterxml.jackson.databind.*`
- `com.fasterxml.jackson.core.*`
- `com.fasterxml.jackson.annotation.*`

注意事项：

- `jackson-annotations` 仍保留 `com.fasterxml.jackson.annotation.*`
- 其他核心 API 迁移时要以 Jackson 3 的官方包结构为准

### 4.3 Spring 配置与序列化入口

需要统一检查：

- 各服务 `ObjectMapper` / `JsonMapper` Bean
- Spring MVC / WebFlux JSON 序列化
- 安全链路里的 JSON 解析
- WebSocket / HTTP 客户端 JSON 处理

### 4.4 Kafka 事件链路

以下链路受影响最大：

- 事件发布
- 事件消费
- Outbox / Inbox JSON 落库
- 事件 payload 序列化与反序列化

迁移风险：

- payload 结构兼容性
- 时间字段格式变化
- 未知字段兼容策略变化

### 4.5 Redis 快照链路

重点核对：

- 交易时间快照
- 行情快照
- 其他跨服务 JSON 缓存

迁移风险：

- 旧缓存反序列化失败
- 未知字段兼容性回归
- 时间类型格式不一致

### 4.6 MyBatis JSON 持久化

需要检查各服务的 JSON 持久化支持类和 Mapper 读写路径。

迁移风险：

- 数据库存量 JSON 无法继续读取
- JSON 字段格式变化导致老数据解析失败

### 4.7 安全与认证链路

重点影响：

- JWT 解析
- 网关鉴权
- 登录 / 刷新 token JSON 读写

该部分属于高风险区域，必须单独验证。

### 4.8 外部协议解析

重点影响：

- Tiingo WebSocket 报文解析
- Web3 / 钱包链路中的 JSON 解析

迁移后必须验证：

- 数组型报文
- 对象型报文
- 心跳 / 错误帧

### 4.9 测试代码

测试代码中凡是直接使用 `ObjectMapper`、`JsonNode`、`JsonProcessingException` 的地方，也要同步迁移。

不允许主代码完成迁移后，测试代码仍留在旧 API 上。

## 5. 风险分级

### P0

- Kafka 事件序列化兼容性
- Redis 快照兼容性
- MyBatis JSON 字段兼容性
- JWT / 网关鉴权回归

### P1

- Tiingo 协议解析回归
- 各服务 Spring JSON 配置差异
- 时间字段格式变化

### P2

- 纯测试辅助类改造
- 低频 JSON 工具类改造

## 6. 实施步骤

### Step 1：冻结迁移基线

实施前先完成：

- 记录当前 Jackson 真实版本
- 记录所有服务的 Jackson 使用点
- 冻结本专项不夹带业务需求

### Step 2：调整父工程依赖策略

实施内容：

- 父工程统一声明 Jackson 3 策略
- 去除子模块不必要的显式 Jackson 2 依赖
- 评估并移除 `jackson-datatype-jsr310`

### Step 3：先迁移共享配置与基础设施

实施内容：

- 统一 `ObjectMapper / JsonMapper` 配置
- 统一时间格式、未知字段兼容和模块注册方式
- 优先处理 `falconx-infrastructure` 和各服务 `config`

### Step 4：按服务逐个迁移

建议顺序：

1. `falconx-gateway`
2. `falconx-identity-service`
3. `falconx-market-service`
4. `falconx-trading-core-service`
5. `falconx-wallet-service`

该顺序的原因：

- 先迁移边缘层和认证层
- 再迁移事件和缓存密集层
- 最后迁移链监听与钱包输入层

### Step 5：专项回归验证

每个服务完成后，至少回归：

- HTTP JSON 序列化
- Kafka 发布/消费
- Redis JSON 读写
- MyBatis JSON 字段读写
- 安全链路
- Tiingo / Web3 协议解析

### Step 6：全仓收口

全服务迁移完成后，进行全仓验证：

- `mvn test`
- `mvn clean compile`
- 关键链路联调

## 7. 验收标准

迁移完成后，至少满足以下标准：

1. 运行时代码不再依赖旧的 Jackson 2 核心 API 路径  
2. Jackson 3 目标版本由父工程统一管理  
3. Kafka / Redis / MyBatis JSON 兼容测试通过  
4. 网关鉴权和身份认证链路测试通过  
5. Tiingo 协议解析测试通过  
6. Web3 / 钱包链路 JSON 解析测试通过  
7. 外部真源 / 真节点门禁用例形成可归档运行证据  
8. 统一文档同步到“已迁移完成”状态  

## 8. 不在本专项范围内

以下事项不属于本专项范围：

- 新增业务接口
- 调整服务边界
- 修改事件语义
- 引入新的 JSON 协议设计
- 调整数据库 owner 边界

## 9. 与当前总计划的关系

本专项继续作为后续计划中的独立阶段推进：

- 名称：`Stage 6C：Jackson 3 专项迁移`
- 排期归属：以 [开发启动手册](../../setup/开发启动手册.md) 和 [当前开发计划](../../setup/当前开发计划.md) 的最新口径为准

当前固定口径：

- 它属于技术基线与序列化层治理，不应混入普通业务需求
- 它默认不作为 `Stage 7 / 7A` 交易闭环的阻塞门槛
- 业务闭环、安全基线与技术专项可以并行准备，但真正实施顺序应服从总计划文档

建议：

- 在交易风险闭环和上线前安全基线稳定后，再安排本专项的集中切换和回归
- 若总计划后续再次调整排序，应只更新总计划文档，不在本文件重复维护另一套阶段先后关系
