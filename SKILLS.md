# FalconX 开发技能手册（SKILLS.md）

本文件为 FalconX 项目常见开发任务提供锚定真实代码的操作模板。
每个 Skill 的路径、类名、代码模式均来自当前仓库实现，可直接驱动 AI 代理执行。

---

## Skill 1：新增 REST 接口

### 适用场景
在某个业务服务中增加一个新的 HTTP API 端点。

### 真实模式参考

**Controller 签名模式**（参考 `AuthController`、`TradingOrderController`）：
```java
@RestController
@RequestMapping("/api/v1/{resource}")
public class XxxController {

    @PostMapping
    public ApiResponse<XxxResponse> doSomething(
        @RequestHeader("X-User-Id") Long userId,   // 由 gateway 注入，不要让客户端传
        @Valid @RequestBody XxxRequest request
    ) {
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        XxxResponse response = applicationService.execute(new XxxCommand(userId, request));
        return new ApiResponse<>("0", "success", response, OffsetDateTime.now(), traceId);
    }
}
```

**ApiResponse 结构**（参考 `falconx-common` 的 `ApiResponse` record）：
```java
// 成功
new ApiResponse<>("0", "success", data, OffsetDateTime.now(), traceId)
// 失败（由 GlobalExceptionHandler 统一处理）
new ApiResponse<>(errorCode.code(), errorCode.message(), null, OffsetDateTime.now(), traceId)
```

### 操作步骤

1. **读取规范**：`docs/api/REST接口规范.md`

2. **新增 Command 对象**
   ```
   路径：com.falconx.{service}.command.XxxCommand.java
   规则：用 Java record，只含字段，无逻辑
   ```

3. **新增 / 修改 ApplicationService**
   ```
   路径：com.falconx.{service}.application.XxxApplicationService.java
   规则：
   - 类注解 @Service，写操作加 @Transactional
   - 方法开头打 INFO 日志，格式参考现有服务：
     log.info("{service}.{action}.request userId={} symbol={}", ...)
     log.info("{service}.{action}.completed userId={} orderId={}", ...)
   - 数据库操作必须通过 Repository 接口，不得直接用 Mapper
   ```

4. **新增错误码**（如有新错误场景）
   ```
   路径：com.falconx.{service}.error.XxxErrorCode.java
   模式：enum implements ErrorCode，code 前缀按服务分配
         identity=1xxxx  market=2xxxx  trading=3xxxx/4xxxx  wallet=5xxxx
   ```

5. **确认 GlobalExceptionHandler 已覆盖**
   ```
   路径：com.falconx.{service}.config.XxxGlobalExceptionHandler.java
   规则：
   - @ExceptionHandler(XxxBusinessException.class) → log.warn + ApiResponse(errorCode)
   - @ExceptionHandler(MethodArgumentNotValidException.class) → 400 + CommonErrorCode.INVALID_REQUEST_PAYLOAD
   - @ExceptionHandler(Exception.class) → log.error + CommonErrorCode.INTERNAL_ERROR
   ```

6. **写集成测试**
   ```
   路径：src/test/java/com/falconx/{service}/controller/XxxControllerTests.java
   ```

7. **同步接口文档**
   ```
   文件：docs/api/FalconX统一接口文档.md
   必填：所属服务、路径、方法、认证要求、X-User-Id 是否必须、
         请求体、成功响应示例、错误码表、关键日志点、测试结论
   ```

### 禁止事项
- ❌ 禁止在 Controller 层写任何业务逻辑，只做参数绑定和委托
- ❌ 禁止让客户端直接传 `userId`，必须由 gateway 通过 `X-User-Id` 注入
- ❌ 禁止 Controller 直接调用 Repository 或 Mapper
- ❌ 禁止 `traceId` 手工生成，必须从 `MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)` 取

---

## Skill 2：新增低频关键 Kafka 事件（Outbox/Inbox 路径）

### 适用场景
服务间解耦的业务事件，如存款确认、订单状态变更等。**不适用于行情 price.tick（见 Skill 3）。**

### 真实模式参考

**Outbox Entity 模式**（参考 `TradingOutboxMessage`）：
```java
public record XxxOutboxMessage(
    String outboxId,           // DB 自动生成
    String eventId,            // 业务幂等键，如 "deposit-confirmed:" + txHash
    String eventType,          // topic 对应的事件类型字符串
    String partitionKey,       // Kafka 分区键，通常为 userId 或 symbol
    Object payload,            // Map<String,Object> 或明确的 payload 类
    XxxOutboxStatus status,    // PENDING / SENT / FAILED / DEAD_LETTER
    OffsetDateTime createdAt,
    OffsetDateTime sentAt,
    int retryCount,
    OffsetDateTime nextRetryAt,
    String lastError
) {}
```

**Outbox 写入模式**（在 ApplicationService 事务内）：
```java
@Transactional
public void processBusinessEvent(...) {
    // 1. 执行业务操作
    // 2. 在同一事务内写 outbox（原子性保证）
    outboxRepository.save(new XxxOutboxMessage(
        null,
        "event-type:" + businessKey,     // eventId
        "falconx.xxx.yyy",               // eventType = topic name
        String.valueOf(userId),           // partitionKey
        Map.of("key", value, ...),        // payload
        XxxOutboxStatus.PENDING,
        OffsetDateTime.now(), null, 0, OffsetDateTime.now(), null
    ));
}
```

**Kafka 发布模式**（参考 `KafkaTradingOutboxEventPublisher`）：
```java
kafkaTemplate.send(
    KafkaEventMessageSupport.buildJsonMessage(
        topic, partitionKey, payloadJson,
        eventId, eventType, "falconx-{service}-service"
    )
).get(5, TimeUnit.SECONDS);   // 同步等待确认，超时抛异常
```

**消费者入口模式**（参考 `IdentityKafkaEventListener`）：
```java
@KafkaListener(
    topics = "${falconx.{service}.kafka.xxx-topic}",
    groupId = "${falconx.{service}.kafka.consumer-group-id}"
)
public void onXxxEvent(
    String payloadJson,
    @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
    @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false) String traceId
) {
    KafkaEventMessageSupport.bindTraceId(traceId);
    try {
        log.info("{service}.kafka.consume.received eventId={}", eventId);
        xxxEventConsumer.handle(eventId, objectMapper.readValue(payloadJson, XxxPayload.class));
    } catch (Exception e) {
        throw new IllegalStateException("Unable to process xxx Kafka event", e);
    } finally {
        KafkaEventMessageSupport.clearTraceId();
    }
}
```

**Inbox 幂等处理模式**（在 consumer handle 方法中）：
```java
public void handle(String eventId, XxxPayload payload) {
    if (inboxRepository.existsByEventId(eventId)) {
        log.info("{service}.kafka.consume.duplicate eventId={}", eventId);
        return;
    }
    // 执行业务 + 写 inbox 记录，在同一个事务内
}
```

### 操作步骤

1. **在 contract 模块定义 Payload**
   ```
   路径：falconx-{service}-contract/src/main/java/com/falconx/{service}/contract/event/XxxEventPayload.java
   规则：
   - Java record，只含字段
   - 类级别加 @JsonIgnoreProperties(ignoreUnknown = true)
   - 新增字段必须有默认值（null 或合理默认）
   ```

2. **生产者：在事务内写 Outbox**（不在此处发 Kafka）

3. **消费者 Listener**：只做消息接收 + traceId 绑定 + 委托给 Consumer

4. **Consumer 实现**：检查 inbox 幂等 → 执行业务 → 写 inbox（同一事务）

5. **注册 Topic**
   - 在生产者服务 `application.yml` 中增加 topic 配置项
   - 在消费者服务 `application.yml` 中增加 topic 配置项
   - 更新 `AGENTS.md` §0 Kafka Topics 表格；`CLAUDE.md` 只作为 `AGENTS.md` 跳转入口，不维护重复表格
   - 更新 `docs/event/Kafka事件规范.md`

### 禁止事项
- ❌ 禁止在 `@KafkaListener` 回调线程直接执行完整业务链路（DB / Redis / ClickHouse）
- ❌ 禁止 Outbox 在事务外写入（必须与业务操作原子）
- ❌ 禁止跳过 Inbox 幂等检查
- ❌ 禁止 `kafkaTemplate.send()` 不等待结果（必须 `.get()` 同步确认）
- ❌ 禁止消费后不写 `KafkaEventMessageSupport.clearTraceId()`（MDC 泄漏）

---

## Skill 3：新增高频行情事件（直接 Kafka，不走 Outbox）

### 适用场景
仅限 `falconx.market.price.tick` 等高频事件。低频业务事件必须用 Skill 2。

### 真实模式参考

**直接发送模式**（参考 `KafkaMarketEventPublisher.publishPriceTick`）：
```java
// 不写 Outbox，不写 Inbox，直接发 Kafka
private void send(String topic, String partitionKey, Object payload, String eventType) {
    String eventId = "evt-" + idGenerator.nextId();
    String payloadJson = objectMapper.writeValueAsString(payload);
    kafkaTemplate.send(
        KafkaEventMessageSupport.buildJsonMessage(
            topic, partitionKey, payloadJson,
            eventId, eventType, "falconx-market-service"
        )
    ).get(5, TimeUnit.SECONDS);
}
```

**消费端必须切换线程**（参考 `MarketPriceTickEventConsumer`）：
```java
public void consume(String eventId, MarketPriceTickEventPayload payload) {
    String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
    ClassLoader callerCl = Thread.currentThread().getContextClassLoader();
    tradingPriceTickExecutor.submit(() -> {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        KafkaEventMessageSupport.bindTraceId(traceId);
        Thread.currentThread().setContextClassLoader(callerCl);
        try {
            quoteDrivenEngine.processTick(payload);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            KafkaEventMessageSupport.clearTraceId();
        }
    }).get();
}
```

### 操作步骤

1. Payload 定义同 Skill 2（contract 模块 + `@JsonIgnoreProperties`）
2. 生产者：**直接调用 `kafkaTemplate.send()`**，不写任何 outbox 记录
3. 消费端 Listener：同 Skill 2 的 `bindTraceId / clearTraceId` 模式
4. 消费端 Consumer：**必须提交到专用 `ExecutorService`**，显式传递 traceId 和 ClassLoader

### 禁止事项
- ❌ 禁止为高频事件写 Outbox（性能崩溃）
- ❌ 禁止在 SDK / WebSocket 回调线程直接执行 DB / Redis 操作
- ❌ 禁止切换线程后忘记传递 `traceId` 和 `contextClassLoader`
- ❌ 禁止把低频业务事件也走这条路径（缺乏可靠性保证）

---

## Skill 4：新增数据库表

### 适用场景
在某个服务的 MySQL schema 中增加新表或字段。

### 当前各服务最大 Flyway 版本号

| 服务 | 当前最大版本 | 下一个版本号 |
|------|------------|------------|
| identity-service | V1 | V2 |
| market-service | V4 | V5 |
| trading-core-service | V8 | V9 |
| wallet-service | V2 | V3 |

### 真实模式参考

**XML Mapper resultMap 模式**（参考 `IdentityUserMapper.xml`）：
```xml
<resultMap id="xxxxxRecordMap" type="com.falconx.{service}.repository.mapper.record.XxxRecord">
    <constructor>
        <idArg column="id" javaType="java.lang.Long"/>
        <arg column="uid" javaType="java.lang.String"/>
        <!-- 其他字段 -->
        <arg column="created_at" javaType="java.time.LocalDateTime"/>
        <arg column="updated_at" javaType="java.time.LocalDateTime"/>
    </constructor>
</resultMap>

<sql id="baseColumns">
    id, uid, status, created_at, updated_at
</sql>

<select id="selectById" resultMap="xxxxxRecordMap">
    SELECT <include refid="baseColumns"/>
    FROM t_xxxx
    WHERE id = #{id}
</select>
```

### 操作步骤

1. **读取规范**：`docs/database/falconx一期数据库设计.md`

2. **新建 Flyway migration**
   ```
   路径：{service}/src/main/resources/db/migration/V{N+1}__{简短描述}.sql
   规则：
   - N 必须是上表中"下一个版本号"（先 ls 确认当前最大版本）
   - 禁止修改已有 V{N}__ 文件（checksum 校验会失败）
   - 所有列加 COMMENT
   - 时间列：created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
   ```

3. **新增 Entity Record**
   ```
   路径：com.falconx.{service}.repository.mapper.record.XxxRecord.java
   规则：用 Java record，字段与 DB 列一一对应，枚举字段存 int/string 原始值
   ```

4. **新增 Mapper Interface**
   ```
   路径：com.falconx.{service}.repository.mapper.XxxMapper.java
   规则：继承 BaseMapper<XxxRecord>（MyBatis Plus），自定义查询方法只声明签名
   ```

5. **新增 XML Mapper**
   ```
   路径：{service}/src/main/resources/mapper/XxxMapper.xml
   规则：
   - namespace 与 Mapper interface 全路径一致
   - 公共列用 <sql id="baseColumns"> 定义，通过 <include refid="baseColumns"/> 引用
   - 禁止跨 XML 文件 <include>
   ```

6. **新增 Repository 接口 + MyBatis 实现**
   ```
   接口：com.falconx.{service}.repository.XxxRepository.java（业务语义方法）
   实现：com.falconx.{service}.repository.MybatisXxxRepository.java
   规则：
   - 实现类注解 @Repository
   - save() 方法：id 为 null 则 INSERT（insertXxx），否则 UPDATE（updateXxx）
   - 领域模型转换：toDomain() / toRecord() 在实现类内完成
   - ApplicationService 只依赖 Repository 接口，不直接用 Mapper
   ```

7. **同步数据库文档**：`docs/database/falconx一期数据库设计.md`

### 禁止事项
- ❌ 禁止修改已提交的 Flyway migration 文件（checksum 不可逆）
- ❌ 禁止 ApplicationService 直接注入 Mapper（必须通过 Repository 接口）
- ❌ 禁止在 Service 层拼 SQL 字符串
- ❌ 禁止跨服务共用 Mapper 或 Repository（owner 边界）
- ❌ 禁止用 In-Memory Repository 代替真实 MyBatis 实现

---

## Skill 5：新增 Redis 缓存

### 适用场景
为查询热点数据或跨服务共享状态引入 Redis 缓存。

### 三件必明确（缺一不算完成）

| 项目 | 要求 |
|------|------|
| **TTL** | 必须在 `application.yml` 中配置，禁止 hardcode，禁止 no-expiry |
| **刷新策略** | 定时刷新 / 事件驱动更新 / 启动预热，三选一或组合，代码中显式实现 |
| **cache miss 降级** | 回源 DB / 返回默认值 / 拒绝服务，必须在代码中有明确 fallback 分支 |

### 真实工具参考

```java
// 使用 RedissonClient（项目统一，不用 StringRedisTemplate）
@Autowired private RedissonClient redissonClient;

// 写缓存
RBucket<String> bucket = redissonClient.getBucket("falconx:market:quote:" + symbol);
bucket.set(json, ttlSeconds, TimeUnit.SECONDS);

// 读缓存（含 cache miss 回源）
String cached = bucket.get();
if (cached == null) {
    // cache miss：回源 DB 或降级处理
    Quote fresh = quoteRepository.findLatestBySymbol(symbol)
        .orElseThrow(() -> new MarketBusinessException(MarketErrorCode.QUOTE_NOT_AVAILABLE));
    bucket.set(toJson(fresh), ttlSeconds, TimeUnit.SECONDS);
    return fresh;
}
return fromJson(cached, Quote.class);
```

### Key 命名规范
```
格式：falconx:{service}:{entity}:{id_or_key}
示例：
  falconx:market:quote:BTCUSD
  falconx:identity:token-blacklist:{jti}
  falconx:trading:risk-exposure:{userId}
```

### 操作步骤

1. 在 `XxxServiceProperties` 中增加 TTL 配置项（`@ConfigurationProperties`）
2. 在 `application.yml` 中设置具体 TTL 值
3. 实现缓存读写，**必须有 cache miss 分支**
4. 实现刷新机制（`@Scheduled` / Kafka 消费触发 / `@PostConstruct` 预热）
5. 若是跨服务共享快照，owner 服务负责写，其他服务只读

### 禁止事项
- ❌ 禁止使用 `StringRedisTemplate`（项目统一用 Redisson）
- ❌ 禁止不设 TTL（内存泄漏风险）
- ❌ 禁止非 owner 服务向共享 Redis key 写数据
- ❌ 禁止 cache miss 时无任何降级处理

---

## Skill 6：新增 ClickHouse 分析查询

### 适用场景
在 `falconx-market-service` 中对 `falconx_market_analytics` 做写入或查询。

### 真实模式参考

**ClickHouse Mapper XML**（参考 `MarketQuoteTickMapper.xml`、`MarketKlineMapper.xml`）：
```xml
<!-- namespace 指向 analytics 包下的 Mapper interface -->
<mapper namespace="com.falconx.market.analytics.mapper.XxxAnalyticsMapper">

    <!-- 批量写入（ClickHouse 推荐批量插入） -->
    <insert id="insertBatch">
        INSERT INTO falconx_market_analytics.xxx_table (
            symbol, event_time, value
        ) VALUES
        <foreach collection="list" item="r" separator=",">
            (#{r.symbol}, #{r.eventTime}, #{r.value})
        </foreach>
    </insert>

    <!-- 查询（ClickHouse SQL 语法，注意不支持所有 MySQL 特性） -->
    <select id="selectLatest" resultType="...XxxRecord">
        SELECT symbol, max(event_time) AS event_time, argMax(value, event_time) AS value
        FROM falconx_market_analytics.xxx_table
        WHERE symbol = #{symbol}
        GROUP BY symbol
    </select>
</mapper>
```

**DataSource 配置**（analytics 用独立 DataSource，参考 `MarketServiceConfiguration`）：
```
ClickHouse DataSource bean 名称：clickHouseDataSource（或 analyticsDataSource）
JDBC URL：jdbc:clickhouse://localhost:8123/falconx_market_analytics
对应 MyBatis SqlSessionFactory 必须指定此 DataSource
```

### 操作步骤

1. Mapper interface 放在 `com.falconx.market.analytics.mapper.` 包下
2. XML 放在 `resources/mapper/analytics/` 目录（注意与 MySQL mapper 隔离）
3. 确认 Mapper 注册到 ClickHouse 的 `SqlSessionFactory`，不能混用 MySQL 的
4. **优先批量写入**（`insertBatch`），避免逐条 INSERT
5. ClickHouse 不支持 UPDATE/DELETE，状态变更用新行 + 查询时取最新值

### 禁止事项
- ❌ 禁止把 ClickHouse Mapper 注册到 MySQL 的 `SqlSessionFactory`
- ❌ 禁止逐条 INSERT 高频数据（性能崩溃）
- ❌ 禁止在 ClickHouse 表上执行 UPDATE / DELETE
- ❌ 禁止其他服务直接访问 ClickHouse（owner 边界属于 market-service）

---

## Skill 7：新增 Wallet 链支持

### 适用场景
为 `falconx-wallet-service` 接入新的区块链。

### 当前已支持链（供参考实现模式）
`ETH`、`BSC`（Web3J）、`SOL`（Solanaj）、`TRX`（Trident）

### 操作步骤

1. 在 `falconx-domain` 的 `ChainType` 枚举新增链类型
2. 在 `wallet-service` 的 `client/` 下新增链 RPC 客户端封装
3. 在 `listener/` 下新增链监听器，**回调线程必须立即切换到应用线程**：
   ```java
   // SDK 回调线程只做：接收 → 提交到线程池
   sdkCallback.onBlock(block -> {
       String traceId = TraceIdSupport.newTraceId();
       walletBlockExecutor.submit(() -> {
           KafkaEventMessageSupport.bindTraceId(traceId);
           try {
               walletDepositTrackingService.processBlock(block);
           } finally {
               KafkaEventMessageSupport.clearTraceId();
           }
       });
   });
   ```
4. 在 `WalletServiceProperties` 新增链配置（rpcUrl、requiredConfirmations、cursorType）
5. 新建 Flyway migration（版本号续接，当前最大 V2，新建 V3）处理地址/游标表变更
6. 更新 `application.yml` 和 `application-local.yml`

### 禁止事项
- ❌ 禁止在区块链 SDK 回调线程直接操作 DB / Redis / Kafka
- ❌ 禁止回调线程切换后忘记传递 traceId
- ❌ 禁止 wallet-service 向 trading-service 直接查询账户数据（事件驱动，不跨服务调用）

---

## Skill 8：新增风险控制规则

### 适用场景
在 `falconx-trading-core-service` 中新增或修改风险控制逻辑。

### 状态机约束（改动前必须读）

**订单状态**（`TradingOrderStatus`）：
```
PENDING → FILLED / REJECTED / CANCELLED
TRIGGERED → FILLED / REJECTED / CANCELLED
```

**持仓状态**（`TradingPositionStatus`）：
```
OPEN → CLOSED（主动平仓）
OPEN → LIQUIDATED（强平）
CLOSED / LIQUIDATED 为终态，不可逆
```

**必须读取**：`docs/domain/状态机规范.md` + `docs/architecture/事务与幂等规范.md`

### 操作步骤

1. 风险规则在 `TradingRiskService` 接口 + `DefaultTradingRiskService` 实现中添加
2. 风险参数从 `t_risk_config` 读取（通过 `TradingRiskConfigMapper`），**禁止硬编码**
3. 强平逻辑必须写 `TradingLiquidationLog`，保留审计记录
4. 实时风险指标写 Redis（`TradingRiskExposure`），遵循 Skill 5 三件必明确
5. Outbox 通知下游走 Skill 2 路径

### 测试必须覆盖
- 正常风控通过路径
- 触发强平的边界保证金场景
- 并发下单与风控交互（考虑加锁或乐观锁）

### 禁止事项
- ❌ 禁止在代码中硬编码保证金率、最大杠杆等风险参数
- ❌ 禁止跳过状态机约束直接修改持仓状态
- ❌ 禁止强平后不写 `TradingLiquidationLog`

---

## Skill 9：新增状态机迁移

### 适用场景
修改 `trading-core-service` 中订单、持仓、存款的状态流转规则。

### 操作步骤

1. **必读**：`docs/domain/状态机规范.md`，核对当前允许的迁移边
2. 修改 `TradingOrderStatus` / `TradingPositionStatus` / `TradingDepositStatus` 枚举（如新增状态）
3. 如有新状态值，新建 Flyway migration 更新 `CHECK CONSTRAINT` 或 ENUM 列定义
4. 在业务逻辑中添加迁移前置校验（当前状态不符合则抛 `TradingBusinessException`）：
   ```java
   if (position.status() != TradingPositionStatus.OPEN) {
       throw new TradingBusinessException(TradingErrorCode.POSITION_ALREADY_CLOSED);
   }
   ```
5. 更新 `docs/domain/状态机规范.md` 中的状态迁移图

### 禁止事项
- ❌ 禁止从终态（`CLOSED`、`LIQUIDATED`、`REJECTED`）做任何迁移
- ❌ 禁止直接 UPDATE 状态字段而不做前置校验
- ❌ 禁止修改状态枚举后不同步 Flyway migration
- ❌ 这是高风险改动，**必须先向用户确认再实施**

---

## Skill 10：编写集成测试 / E2E 测试

### 适用场景
为跨服务流程或复杂业务场景编写集成测试或端到端测试。

### 真实基础设施参考

**数据库自动清理**（参考 `E2EDatabaseCleanupExtension`）：
```java
@ExtendWith(E2EDatabaseCleanupExtension.class)  // 测试结束后自动 DROP 测试 DB
class XxxIntegrationTests {
    // 定义以 _DB_NAME 结尾的常量，扩展会自动识别并清理
    static final String IDENTITY_DB_NAME = "falconx_identity_test_xxx";
    static final String TRADING_DB_NAME  = "falconx_trading_test_xxx";
}
```

**E2E 测试工具方法**（参考 `GatewayTradingRiskE2ETestSupport`）：
```java
// 完整注册→激活→登录流程
String accessToken = registerDepositActivateAndLogin(email, password);

// 通过 market-service owner 路径写入行情
ingestMarketQuote("BTCUSD", BigDecimal.valueOf(50000));

// 触发 Outbox 调度（不等定时器）
dispatchTradingOutboxNow();

// 带超时的循环断言（15s / 250ms 间隔）
waitForAssertion(() -> {
    BigDecimal balance = decimalValue("SELECT balance FROM t_account WHERE user_id=?", userId);
    assertThat(balance).isGreaterThan(BigDecimal.ZERO);
});

// SQL 断言工具
assertThat(countRows("SELECT 1 FROM t_order WHERE user_id=?", userId)).isEqualTo(1);
```

### 操作步骤

1. 单服务集成测试（持久化验证）：
   ```
   路径：{service}/src/test/java/com/falconx/{service}/XxxPersistenceIntegrationTests.java
   配置：使用独立测试 DB（application-test.yml 中配置隔离 schema）
   ```

2. 跨服务 E2E 测试：
   ```
   路径：falconx-gateway/src/test/java/com/falconx/gateway/XxxE2ETests.java
   基类：继承 GatewayTradingRiskE2ETestSupport（或参考其模式）
   注解：@ExtendWith(E2EDatabaseCleanupExtension.class)
   ```

3. Kafka 事件验证：
   ```java
   // 创建消费者观察事件
   KafkaConsumer<String, String> consumer = createTopicConsumer("falconx.xxx.topic");
   // 执行操作...
   // 断言事件发出
   waitForAssertion(() -> assertThat(consumer.poll(Duration.ZERO)).isNotEmpty());
   ```

### 禁止事项
- ❌ 禁止 E2E 测试共用生产数据库（必须独立 schema）
- ❌ 禁止 E2E 测试结束后不清理 DB（使用 `E2EDatabaseCleanupExtension`）
- ❌ 禁止用 `Thread.sleep()` 等待异步操作（用 `waitForAssertion()` 替代）

---

## Skill 11：接口文档同步

### 适用场景
任何 REST 或 WebSocket 接口开发、修改完成后。**未同步不视为任务完成。**

### 文档位置
`docs/api/FalconX统一接口文档.md`

### 每条接口记录必须包含

```markdown
### {接口名称}

- **所属服务**：falconx-{service}（端口 180xx）
- **路径**：POST /api/v1/{resource}
- **认证**：Bearer JWT（通过 gateway 验证）/ 无
- **请求头**：
  - `X-User-Id`：Long，由 gateway 注入（客户端无需传）
  - `X-Trace-Id`：String，可选，gateway 自动注入或生成
- **请求体**：
  ```json
  { "field": "type, 说明" }
  ```
- **成功响应**：
  ```json
  { "code": "0", "message": "success", "data": {...}, "timestamp": "...", "traceId": "..." }
  ```
- **失败响应**：
  | 错误码 | 说明 |
  |--------|------|
  | 1xxxx | identity 错误 |
  | 3xxxx | trading 错误 |
- **关键日志**：`[INFO] {service}.{action}.completed userId={} result={}`
- **测试结论**：✅ 已通过 / ⏳ 待测试
```

---

## Skill 12：跨服务 contract 变更

### 适用场景
需要修改跨服务共享的事件 Payload 或 API DTO。**这是高风险操作，必须先向用户确认。**

### 变更原则

1. **只新增字段，不删除 / 重命名已有字段**
2. 新字段加 `@JsonIgnoreProperties(ignoreUnknown = true)` 或有默认值（`null`）
3. 若必须破坏性变更：
   - contract 模块新建 `XxxEventPayloadV2`
   - 生产者和消费者**分两个 PR** 上线：先部署消费者（向后兼容），再部署生产者
   - 旧 Payload 至少保留一个发布周期
4. 变更后**必须同时验证生产者和消费者**，不能只测单边
5. 同步更新 `docs/event/Kafka事件规范.md`

### 禁止事项
- ❌ 禁止删除或重命名 contract 模块中的字段（可能导致消费方反序列化失败）
- ❌ 禁止生产者和消费者同时上线破坏性变更（滚动部署窗口内必有失败）
- ❌ 禁止只测生产者不测消费者

---

## Skill 13：前端产品 / 页面设计

### 适用场景
设计 `falconx-frontend` 页面、信息架构、交互流程、响应式布局、动效方向，尚未进入 Figma 节点落地或 React 编码。

### 必读文档

1. `docs/process/全栈Figma协作流程.md`
2. `docs/design/falconx-frontend-react-implementation-spec.md`
3. `docs/design/codex-prompt.md`
4. 若页面依赖后端能力，再读：
   - `docs/api/REST接口规范.md`
   - `docs/api/WebSocket接口规范.md`
   - `docs/api/FalconX统一接口文档.md`

### 操作步骤

1. 先确认页面依赖的后端能力是否已存在；不存在时只能设计禁用态、空态、占位态或明确标注“未接入”。
2. 按用户要求使用 `design-consultation` 定方向，使用 `design-shotgun` 生成多套方向。
3. 输出页面结构、核心用户流程、组件状态、响应式规则、动效规则和数据依赖清单。
4. 若需要进入 Figma，明确需要用户提供或确认的 Figma 文件 / Frame / Node。
5. 若设计会改变 API、WebSocket、状态机、错误码或后端 owner 边界，停止设计并转入对应后端 Skill，先冻结契约。

### 禁止事项

- ❌ 禁止把未实现的后端能力包装成真实可用功能。
- ❌ 禁止在前端设计文档里拍板 API 路径、字段、错误码、数据库字段或 Kafka payload。
- ❌ 禁止绕过 `docs/process/全栈Figma协作流程.md` 直接进入实现。

---

## Skill 14：Figma 设计落地到 React

### 适用场景
用户提供 Figma URL、Figma Node、Figma 截图或要求使用 `figma-implement-design` 把设计实现到 `falconx-frontend`。

### 必读文档与技能

1. `docs/process/全栈Figma协作流程.md`
2. `docs/design/falconx-frontend-react-implementation-spec.md`
3. `figma-implement-design` skill
4. 若需要在 Figma 中读取节点，先按 Figma MCP 规则获取 `get_design_context` 和 `get_screenshot`。

### 操作步骤

1. 从 Figma URL 提取 `fileKey` 和 `nodeId`；若用户使用 Figma Desktop，确认当前选中节点就是目标节点。
2. 调用 Figma MCP 获取目标节点：
   - 必须先 `get_design_context`
   - 再 `get_screenshot`
   - 输出过大时，先 `get_metadata`，再读取必要子节点
3. 下载或引用 Figma 返回的必要资产；若返回 `localhost` 资产地址，直接使用该地址，不得创建占位图替代。
4. 将 Figma 输出翻译为当前项目模式：
   - 组件放入 `falconx-frontend/src/components/` 或 `falconx-frontend/src/features/{domain}/`
   - 样式使用 `src/styles/tokens.css` 与现有 CSS 结构
   - 状态使用现有 React Query / Zustand 模式
   - 图标优先使用 Figma 资产；已有 lucide 图标足够时可复用，禁止新增图标包
5. 实现后必须用浏览器截图对照 Figma 截图，至少覆盖桌面与移动端。
6. 执行 `npm run test`、`npm run lint`、`npm run build`。

### 禁止事项

- ❌ 禁止把 Figma 生成的 Tailwind / 内联样式原样搬进项目形成平行样式系统。
- ❌ 禁止跳过 `get_design_context` 或 `get_screenshot`。
- ❌ 禁止新增未批准的设计系统、UI 框架或图标库。
- ❌ 禁止用 Figma 内容覆盖后端正式 API / WebSocket / 状态机契约。

---

## Skill 15：前端接口 / WebSocket 对接

### 适用场景
在 `falconx-frontend` 中对接 REST、WebSocket、认证刷新、错误态、加载态或后端返回数据。

### 必读文档

1. `docs/process/全栈Figma协作流程.md`
2. `docs/api/REST接口规范.md`
3. `docs/api/WebSocket接口规范.md`
4. `docs/api/FalconX统一接口文档.md`
5. `docs/security/安全规范.md`
6. 相关后端 Skill：REST 用 Skill 1，WebSocket/事件视情况用 Skill 2、3、11、12。

### 真实模式参考

- API 基础封装：`falconx-frontend/src/lib/api.ts`
- 环境配置：`falconx-frontend/src/lib/config.ts`
- 认证状态：`falconx-frontend/src/features/auth/authStore.ts`
- 行情 REST：`falconx-frontend/src/features/market/marketApi.ts`
- 行情 WebSocket：`falconx-frontend/src/features/market/useMarketSocket.ts`

### 操作步骤

1. 先核对接口是否已在统一接口文档中存在；不存在时先回到后端 Skill 冻结契约。
2. 在 `src/features/{domain}/` 内新增或修改 API client、类型、状态管理和组件调用。
3. 认证接口必须遵守 Refresh Token 一次性轮换语义；并发刷新必须单飞。
4. WebSocket token 位于 URL query 时，不得记录完整 URL 或 token。
5. 对 `401 / 403 / token expired / 网络断开 / 重连 / 空数据` 等状态做显式 UI。
6. 补充单元测试或组件测试；全栈链路存在时补充浏览器 QA。

### 禁止事项

- ❌ 禁止浏览器主动发送 `X-Trace-Id`。
- ❌ 禁止前端硬编码可交易 symbol、产品元数据或 owner 数据。
- ❌ 禁止使用本地 mock 替代生产路径并在结论中写成真实对接完成。
- ❌ 禁止在前端日志、错误详情、截图、控制台输出 token。

---

## Skill 16：三端业务功能开发（硬约束）

### 适用场景
一次任务同时涉及后端能力设计 / 实现、客户端页面设计 / 实现、管理端配套（用户/角色/菜单/按钮 RBAC + 业务管理界面）、接口联调和端到端验证。**涉及客户端可见行为的所有业务任务必须按本 Skill 执行三端齐全**。

### 角色路由（强制，V2 三端协作扩展）

按 [`AI工作模式`](docs/process/AI工作模式.md) §4.1，三端业务功能任务必须经过 10 角色路径：

```
R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8 → R1 (commit)
```

- **R4**：业务后端（identity/market/trading-core/wallet）
- **R5**：客户端前端（`falconx-frontend`）
- **R9**：管理端后端（`falconx-console-service`）
- **R10**：管理端前端（`falconx-console-frontend`）

工具执行方式：

- **Claude Code**：R2 / R3 / R6 完成并回收后，可并行派发 R4 / R5 / R9 / R10；每个 Agent 必须有独立读写范围。
- **Codex**：不使用并行或原生 subagent；同一会话内按 R4 → R5 → R9 → R10 顺序执行，每个角色完成 handoff 后再进入下一角色。

详见 [`fullstack-bundle.md`](.agents/templates/fullstack-bundle.md) 三端任务打包模板。

### 必读文档

1. `docs/process/AI工作模式.md`（含 §2 十角色定义、§4.1 任务路由）
2. `docs/process/AI工作模式-Claude适配.md` 或 `docs/process/AI工作模式-Codex适配.md`（按当前工具选择）
3. `docs/process/全栈Figma协作流程.md`
4. `docs/process/完成定义.md`（§4.A 三端硬约束）
5. `docs/process/Karpathy式AI编码行为准则.md`
6. `docs/process/BBook一期完成执行路径.md`（V2 三端阶段路径）
7. 本轮涉及的后端 Skill 文档（Skill 1-12）
8. 本轮涉及的前端 Skill 文档（Skill 13-15、17）
9. 10 角色任务模板（按介入角色读取对应 `role-*.md`，含 R9/R10 管理端模板）

### 完成判定（任意一项未完成均不得标记完成）

- [ ] R2 输出的契约已被用户确认（含客户端 API + 管理端 API + Kafka + DB）
- [ ] R3 输出的客户端 + 管理端双端设计方案已被 R1 接收
- [ ] R6 测试用例先于实现写出（含三端 E2E）
- [ ] R4 业务后端实现 + 单元测试 + 集成测试通过
- [ ] R5 客户端前端实现 + `npm run test` + `npm run lint` + `npm run build` 通过 + 桌面/移动 QA 截图齐全
- [ ] R9 管理端后端实现 + 单元测试 + 集成测试通过
- [ ] R10 管理端前端实现 + 三件套通过 + 桌面 QA 截图齐全（含按钮级 RBAC 验证）
- [ ] 至少一条 E2E 测试覆盖客户端调用 → 后端 → 管理端可见整链
- [ ] R8 完成文档同步：`FalconX 统一接口文档` + 管理端接口规范 + 涉及的专题正式规范
- [ ] R7 最终验证报告完整
- [ ] 形成单一 Git commit（含三端代码 + 文档 + 测试）

**例外**：
- 纯运维 / 内部维护任务（如对账接口、LP 历史 K 线回填、可观测性接入）若无客户端 / 管理端入口，由 R1 在任务启动时显式声明豁免
- 阶段 0 基础设施任务按基础设施例外执行，不强制管理端介入
- 阶段 1 管理端地基任务豁免客户端要求（仅管理端单端）
- R1 不得任意豁免有客户端入口的业务任务的"管理端"要求

### 操作步骤

按 [`fullstack-bundle.md`](.agents/templates/fullstack-bundle.md) §"实施流程"执行各 Phase。

### 禁止事项

- ❌ 禁止前端先行虚构接口倒逼后端契约
- ❌ 禁止后端改了契约但不更新前端类型和 UI 状态
- ❌ **禁止只完成一端或两端就声明三端业务完成（任意一项未完成均不得标记完成）**
- ❌ 禁止 R3-R6/R9/R10 自行决定契约、schema、事件、状态机或设计方向（必须由 R2 主导）
- ❌ 禁止跳过 R3 直接进入前端实施（先设计后开发）
- ❌ 禁止跳过 R6 测试用例骨架直接实施（测试先于实现）
- ❌ 禁止 R7 修改代码（仅报告，由 R4/R5/R9/R10 修复）
- ❌ 禁止 R8 自行决定阶段结论
- ❌ 禁止 R5 写管理端前端代码或 R10 写客户端前端代码（客户端 / 管理端端别越界）
- ❌ 禁止 R4 写管理端后端代码或 R9 写业务后端代码（业务 / 管理端端别越界）

---

## Skill 17：前端 QA / 设计复核

### 适用场景
前端实现完成后做视觉、响应式、交互、接口状态、浏览器错误和设计一致性检查。

### 必读文档

1. `docs/process/全栈Figma协作流程.md`
2. `docs/design/falconx-frontend-react-implementation-spec.md`
3. 若来自 Figma，实现时使用的 Figma 截图与 Node 信息。

### 操作步骤

1. 启动前端 dev server，并确认依赖后端或 QA mock 的边界。
2. 使用 `browse` / Playwright 检查：
   - 首屏是否加载
   - 控制台是否有错误
   - 网络请求是否符合预期
   - 桌面和移动端是否无重叠、无溢出、无不可点击控件
3. 若来自 Figma，逐项对照：
   - 布局、间距、层级
   - 字体、字号、字重
   - 颜色和透明度
   - hover / active / disabled / loading / error 状态
   - 资产是否正确渲染
4. 执行 `npm run test`、`npm run lint`、`npm run build`。
5. 输出截图路径、验证命令、问题列表和修复结论。

### 禁止事项

- ❌ 禁止只看静态截图不测试交互。
- ❌ 禁止只测桌面不测移动端。
- ❌ 禁止浏览器控制台或网络错误未处理就声明完成。
