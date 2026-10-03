# FalconX 模块与目录映射

## 1. Maven 依赖方向

```text
falconx-common
    ^
    |
falconx-domain
    ^
    |
falconx-infrastructure

falconx-identity-contract  -> falconx-common, falconx-domain
falconx-market-contract    -> falconx-common, falconx-domain
falconx-trading-contract   -> falconx-common, falconx-domain
falconx-wallet-contract    -> falconx-common, falconx-domain

falconx-gateway
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-identity-contract
  -> falconx-market-contract
  -> falconx-trading-contract
  -> falconx-wallet-contract

falconx-identity-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-identity-contract
  -> falconx-trading-contract

falconx-market-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-market-contract

falconx-trading-core-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-trading-contract

falconx-wallet-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-wallet-contract
```

## 2. 共享模块包名

```text
falconx-common/src/main/java/com/falconx/common
falconx-domain/src/main/java/com/falconx/domain
falconx-infrastructure/src/main/java/com/falconx/infrastructure
falconx-identity-contract/src/main/java/com/falconx/identity
falconx-market-contract/src/main/java/com/falconx/market
falconx-trading-contract/src/main/java/com/falconx/trading
falconx-wallet-contract/src/main/java/com/falconx/wallet
```

## 3. 服务根包

```text
falconx-gateway/src/main/java/com/falconx/gateway
falconx-identity-service/src/main/java/com/falconx/identity
falconx-market-service/src/main/java/com/falconx/market
falconx-trading-core-service/src/main/java/com/falconx/trading
falconx-wallet-service/src/main/java/com/falconx/wallet
```

## 4. 典型服务内目录

### `gateway`

```text
config/
controller/
error/
filter/
security/
websocket/
```

### `identity-service`

```text
application/
command/
config/
consumer/
controller/
entity/
error/
repository/
repository/mapper/
service/
service/impl/
service/model/
```

### `market-service`

```text
analytics/
analytics/mapper/
application/
cache/
config/
controller/
dto/
entity/
error/
producer/
provider/
repository/
repository/mapper/
service/
service/impl/
websocket/
```

### `trading-core-service`

```text
application/
calculator/
command/
config/
consumer/
controller/
dto/
engine/
entity/
error/
event/
producer/
repository/
repository/mapper/
service/
service/impl/
service/model/
support/
```

### `wallet-service`

```text
application/
client/
config/
entity/
listener/
producer/
repository/
repository/mapper/
service/
service/impl/
```

## 5. 配置骨架

至少保留下列配置事实：

- `gateway` 端口 `18080`
- `identity` 端口 `18081`
- `market` 端口 `18082`
- `trading-core` 端口 `18083`
- `wallet` 端口 `18084`
- 各服务独立 `application.yml`
- Kafka topic 名称与当前仓库一致
- 生产者与消费者 group id 独立配置

## 6. 实现骨架建议

### 根工程

- `pom.xml` 使用 `spring-boot-starter-parent:4.0.5`
- `packaging` 为 `pom`
- `groupId` 为 `com.falconx`
- `artifactId` 为 `falconx-parent`

### 每个服务

- 独立 Spring Boot 启动类
- 独立 `application.yml`
- 独立 Flyway schema 管理
- 只访问自己的 owner 库

### 合同模块

- 只放 DTO / payload / client interface / 稳定枚举
- 不放实体、Mapper、Repository、Service 实现

## 7. 复刻时常见误差

- 把 `service -> service` 直接依赖写出来
- 把业务实体放进 `domain` 或 `infrastructure`
- 只搭 5 个服务，漏掉共享模块和 contract 模块
- 把 wallet 当前 stub 能力误写成正式链地址分配
- 把用户侧实时推送 WebSocket 当成当前已冻结能力
