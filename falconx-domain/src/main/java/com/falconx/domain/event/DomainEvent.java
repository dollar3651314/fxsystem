package com.falconx.domain.event;

import java.time.OffsetDateTime;

/**
 * FalconX 跨服务领域事件最小抽象接口。
 *
 * <p>后续所有需要进入 Outbox 的事件契约都可以围绕该接口扩展，
 * 统一暴露事件标识、事件类型和发生时间，便于基础设施层做泛化处理。
 */
public interface DomainEvent {

    /**
     * @return 事件唯一标识
     */
    String eventId();

    /**
     * @return 事件类型
     */
    String eventType();

    /**
     * @return 事件发生时间
     */
    OffsetDateTime occurredAt();
}
