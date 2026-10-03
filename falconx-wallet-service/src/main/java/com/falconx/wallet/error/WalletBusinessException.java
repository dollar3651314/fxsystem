package com.falconx.wallet.error;

import java.util.Map;

/**
 * wallet-service 业务异常。
 */
public class WalletBusinessException extends RuntimeException {

    private final WalletErrorCode errorCode;
    private final Map<String, Object> context;

    public WalletBusinessException(WalletErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public WalletBusinessException(WalletErrorCode errorCode, Map<String, Object> context) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public WalletErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
