package com.falconx.identity.entity;

public enum KycDocumentType {
    ID_FRONT(1),
    ID_BACK(2),
    HOLDING_SELFIE(3);

    private final int code;

    KycDocumentType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static KycDocumentType fromCode(int code) {
        for (KycDocumentType t : values()) {
            if (t.code == code) return t;
        }
        return ID_FRONT;
    }
}
