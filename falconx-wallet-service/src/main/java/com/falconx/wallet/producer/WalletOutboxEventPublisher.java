package com.falconx.wallet.producer;

import com.falconx.wallet.entity.WalletOutboxMessage;

/**
 * wallet-service Outbox 发件抽象。
 *
 * <p>该接口只供本地 Outbox 调度器使用，
 * 用于把已经认领的发件箱消息真正发送到 Kafka。
 */
public interface WalletOutboxEventPublisher {

    /**
     * 发送一条已认领的发件箱消息。
     *
     * @param message 发件箱消息
     */
    void publish(WalletOutboxMessage message);
}
