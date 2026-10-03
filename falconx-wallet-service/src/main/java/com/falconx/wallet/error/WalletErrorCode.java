package com.falconx.wallet.error;

import com.falconx.common.error.ErrorCode;

/**
 * wallet-service 业务错误码。
 */
public enum WalletErrorCode implements ErrorCode {
    UNSUPPORTED_CHAIN("20004", "Unsupported Chain"),
    WALLET_ADDRESS_ALLOCATION_FAILED("20006", "Wallet Address Allocation Failed"),
    /** STAGE-2-DEPOSIT：按 id 查 deposit 不存在。 */
    ADMIN_DEPOSIT_NOT_FOUND("90850", "Admin Deposit Not Found"),
    /** STAGE-5-WALLET-PROVISION Phase 2：DLQ 条目不存在。 */
    WALLET_ADDRESS_DLQ_NOT_FOUND("90860", "Wallet Address Provision DLQ Not Found"),
    /** STAGE-5-WALLET-PROVISION Phase 2：DLQ 条目已经 RESOLVED，不再允许重试。 */
    WALLET_ADDRESS_DLQ_ALREADY_RESOLVED("90861", "Wallet Address Provision DLQ Already Resolved"),
    /** STAGE-7-WITHDRAW：internal RPC 查白名单不存在。 */
    WITHDRAW_WHITELIST_NOT_FOUND("20020", "Withdraw Whitelist Not Found"),
    /** STAGE-7-WITHDRAW：用户白名单 ACTIVE 数量超 10。 */
    WITHDRAW_WHITELIST_LIMIT_EXCEEDED("20021", "Withdraw Whitelist Limit Exceeded"),
    /** STAGE-7-WITHDRAW：同 user_id + network + address 已存在（PENDING 或 ACTIVE）。 */
    WITHDRAW_WHITELIST_DUPLICATE("20022", "Withdraw Whitelist Duplicate"),
    /** STAGE-7-WITHDRAW Phase 3：链上 sendRawTransaction 失败（网络、HTTP、JSON-RPC error）。 */
    WITHDRAW_BROADCAST_FAILED("20010", "Withdraw Broadcast Failed"),
    /** STAGE-7-WITHDRAW Phase 3：链上 receipt status=0 / reverted。 */
    WITHDRAW_TX_REVERTED("20011", "Withdraw Tx Reverted"),
    /** STAGE-7-WITHDRAW Phase 3：KmsSigner 不可用（key 缺失、HSM 故障、Stub 在 prod 启动）。 */
    WITHDRAW_SIGNER_UNAVAILABLE("20014", "Withdraw Signer Unavailable");

    private final String code;
    private final String message;

    WalletErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
