package com.falconx.wallet.entity;

public enum WalletAddressProvisionDlqStatus {
    PENDING(0),
    RESOLVED(1);

    private final int code;

    WalletAddressProvisionDlqStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static WalletAddressProvisionDlqStatus fromCode(int code) {
        for (WalletAddressProvisionDlqStatus s : values()) {
            if (s.code == code) return s;
        }
        return PENDING;
    }
}
