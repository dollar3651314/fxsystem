package com.falconx.domain.enums;

/**
 * FalconX 一期用户账户可用状态枚举。
 *
 * <p>该状态只表达账户可用性和风控限制，不表达用户是否已经入金。
 * `PENDING_DEPOSIT` 是历史兼容状态；新注册用户应直接进入 `ACTIVE`，
 * 入金事实由 wallet/trading 资金链路表达。
 */
public enum UserStatus {
    PENDING_DEPOSIT,
    ACTIVE,
    FROZEN,
    BANNED
}
