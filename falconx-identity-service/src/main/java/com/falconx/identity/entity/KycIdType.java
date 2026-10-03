package com.falconx.identity.entity;

public enum KycIdType {
    ID_CARD(1),
    PASSPORT(2),
    DRIVER_LICENSE(3);

    private final int code;

    KycIdType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static KycIdType fromCode(int code) {
        for (KycIdType t : values()) {
            if (t.code == code) return t;
        }
        return ID_CARD;
    }
}
