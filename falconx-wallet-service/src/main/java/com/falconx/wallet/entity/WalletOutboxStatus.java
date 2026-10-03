package com.falconx.wallet.entity;

/**
 * wallet-service Outbox 状态枚举。
 *
 * <p>该枚举对齐事务与幂等规范中约定的五态状态机，
 * 用于表达钱包低频关键事件在本地发件箱中的发送进度。
 */
public enum WalletOutboxStatus {
    PENDING,
    DISPATCHING,
    SENT,
    FAILED,
    DEAD
}
