package com.falconx.identity.error;

import com.falconx.common.error.ErrorCode;

/**
 * identity-service 业务错误码。
 *
 * <p>当前错误码集合与 REST 接口规范中的 `1xxxx` 保持一致，
 * 覆盖注册、登录、刷新、登出和用户状态限制场景。
 */
public enum IdentityErrorCode implements ErrorCode {
    UNAUTHORIZED("10001", "Unauthorized"),
    USER_BANNED("10002", "User Banned"),
    LOGIN_RATE_LIMITED("10003", "Login Rate Limited"),
    REGISTER_RATE_LIMITED("10004", "Register Rate Limited"),
    INVALID_CREDENTIALS("10005", "Invalid Credentials"),
    REFRESH_TOKEN_INVALID("10006", "Refresh Token Invalid"),
    USER_FROZEN("10007", "User Frozen"),
    USER_ALREADY_EXISTS("10008", "User Already Exists"),
    EMAIL_FORMAT_INVALID("10009", "Email Format Invalid"),
    PASSWORD_TOO_WEAK("10010", "Password Too Weak"),
    /**
     * @deprecated 注册后用户即为可用状态，`PENDING_DEPOSIT` 不再作为登录拦截原因。
     */
    @Deprecated(since = "IDENTITY-STATE-SEMANTICS-01")
    USER_NOT_ACTIVATED("10011", "User Not Activated"),
    /** STAGE-2-CUSTOMER 内部 RPC freeze/unfreeze 用：用户不存在。 */
    USER_NOT_FOUND("10012", "Identity User Not Found"),
    /** STAGE-2-CUSTOMER：客户已 FROZEN，不可重复冻结。 */
    USER_ALREADY_FROZEN("10013", "Identity User Already Frozen"),
    /** STAGE-2-CUSTOMER：客户处于终态 BANNED，不可冻结/解冻。 */
    USER_TERMINAL_STATUS("10014", "Identity User In Terminal Status"),
    /** STAGE-2-CUSTOMER：客户当前不是 FROZEN，不可解冻。 */
    USER_NOT_FROZEN("10015", "Identity User Not Frozen"),

    // STAGE-1B-USER-PROFILE 用户基础资料段（10020-10029）

    /** 注册时未满 18 岁（CFD 监管要求）。 */
    USER_AGE_BELOW_MINIMUM("10020", "User Age Below Minimum"),
    /** 用户基础资料未找到。 */
    USER_PROFILE_NOT_FOUND("10021", "User Profile Not Found"),
    /** 已通过 KYC，5 强制字段已锁定，必须重新 KYC 才能修改。 */
    USER_PROFILE_VERIFIED_LOCKED("10022", "User Profile Verified Locked"),
    /** 国籍 / 居住国 code 不在 ISO 3166-1 alpha-3 字典中。 */
    USER_PROFILE_COUNTRY_INVALID("10023", "User Profile Country Code Invalid"),
    /** 出生日期非法（未来 / 非合法 ISO 日期）。 */
    USER_PROFILE_BIRTH_DATE_INVALID("10024", "User Profile Birth Date Invalid"),
    /** 姓名字段非法（空 / 超长 / 含数字或特殊符号）。 */
    USER_PROFILE_NAME_INVALID("10025", "User Profile Name Invalid"),

    // STAGE-6-KYC 段 (10040-10049)
    KYC_ID_NUMBER_INVALID("10040", "KYC ID Number Invalid"),
    KYC_DOCUMENTS_INCOMPLETE("10041", "KYC Documents Incomplete"),
    KYC_PENDING_EXISTS("10042", "KYC Pending Submission Already Exists"),
    KYC_ALREADY_APPROVED("10043", "KYC Already Approved"),
    KYC_SUBMISSION_NOT_FOUND("10044", "KYC Submission Not Found"),
    KYC_NOT_PENDING("10045", "KYC Submission Not Pending"),
    KYC_REJECT_REASON_REQUIRED("10046", "KYC Reject Reason Required");

    private final String code;
    private final String message;

    IdentityErrorCode(String code, String message) {
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
