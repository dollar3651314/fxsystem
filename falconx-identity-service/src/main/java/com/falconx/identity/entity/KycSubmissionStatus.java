package com.falconx.identity.entity;

public enum KycSubmissionStatus {
    PENDING(0),
    APPROVED(1),
    REJECTED(2);

    private final int code;

    KycSubmissionStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static KycSubmissionStatus fromCode(int code) {
        for (KycSubmissionStatus s : values()) {
            if (s.code == code) return s;
        }
        return PENDING;
    }
}
