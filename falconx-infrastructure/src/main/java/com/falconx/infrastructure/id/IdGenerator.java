package com.falconx.infrastructure.id;

/**
 * 分布式 ID 生成器抽象。
 *
 * <p>Stage 5 开始各服务会把 owner 数据真正写入 MySQL 和 ClickHouse，
 * 因此不能继续依赖内存自增主键。该接口统一冻结“服务内生成业务主键”的入口，
 * 避免每个服务各自实现一套不同的主键规则。
 */
public interface IdGenerator {

    /**
     * 生成下一条 64 位主键。
     *
     * @return 全局唯一且时间有序的 ID
     */
    long nextId();
}
