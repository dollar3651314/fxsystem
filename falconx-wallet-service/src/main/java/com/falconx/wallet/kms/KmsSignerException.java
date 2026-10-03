package com.falconx.wallet.kms;

/**
 * STAGE-7-WITHDRAW Phase 3：KmsSigner 签名失败异常。
 *
 * <p>典型场景：key 未配置 / PEM 解析失败 / KMS / HSM 不可达 / 签名算法错误。
 * 由 broadcast 应用服务捕获后转写 {@code WalletErrorCode.WITHDRAW_SIGNER_UNAVAILABLE (20014)}
 * 并把整笔出金标记为 FAILED。
 */
public class KmsSignerException extends RuntimeException {

    public KmsSignerException(String message) {
        super(message);
    }

    public KmsSignerException(String message, Throwable cause) {
        super(message, cause);
    }
}
