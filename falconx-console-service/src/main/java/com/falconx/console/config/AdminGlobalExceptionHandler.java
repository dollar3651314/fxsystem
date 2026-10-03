package com.falconx.console.config;

import com.falconx.common.api.ApiResponse;
import com.falconx.common.error.CommonErrorCode;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理端全局异常处理器。
 *
 * <p>统一映射：
 *
 * <ul>
 *   <li>{@link AdminBusinessException} → 90001-90008 + 对应 HTTP status</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 + {@link CommonErrorCode#INVALID_REQUEST_PAYLOAD}</li>
 *   <li>其他未捕获异常 → 500 + {@link CommonErrorCode#INTERNAL_ERROR}（不暴露 stack trace）</li>
 * </ul>
 *
 * <p>filter 链路抛出的 {@link AdminBusinessException} 由
 * {@link com.falconx.console.security.AdminAuthenticationFilter} 内部直接写 JSON 响应，
 * 不经过本 handler；本 handler 仅处理 controller 层抛出的异常。
 */
@RestControllerAdvice
public class AdminGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminGlobalExceptionHandler.class);

    @ExceptionHandler(AdminBusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(AdminBusinessException ex) {
        AdminErrorCode errorCode = ex.getErrorCode();
        int httpStatus = switch (errorCode) {
            case ADMIN_TOKEN_EXPIRED, ADMIN_TOKEN_INVALID, ADMIN_AUTH_FAILED -> HttpServletResponse.SC_UNAUTHORIZED;
            case ADMIN_PERMISSION_DENIED, ADMIN_IP_NOT_WHITELISTED, ADMIN_PASSWORD_MUST_CHANGE,
                 ADMIN_USER_DISABLED,
                 ADMIN_USER_SUPER_ADMIN_ROLE_LOCKED, ADMIN_USER_CANNOT_DISABLE_SELF,
                 ADMIN_USER_SUPER_ADMIN_NOT_DELETABLE, ADMIN_USER_CANNOT_DELETE_SELF,
                 ADMIN_ROLE_IS_SYSTEM_NOT_EDITABLE, ADMIN_ROLE_SUPER_ADMIN_PERMISSIONS_LOCKED ->
                    HttpServletResponse.SC_FORBIDDEN;
            case ADMIN_LOGIN_LOCKED -> 423;
            case ADMIN_BALANCE_ADJUST_SINGLE_LIMIT_EXCEEDED, ADMIN_BALANCE_ADJUST_DAILY_LIMIT_EXCEEDED,
                 ADMIN_REASON_TOO_SHORT, ADMIN_BALANCE_INSUFFICIENT_FOR_NEGATIVE_ADJUST,
                 ADMIN_PASSWORD_POLICY_VIOLATION,
                 ADMIN_USER_USERNAME_INVALID_FORMAT, ADMIN_USER_SUPER_ADMIN_ROLE_NOT_ASSIGNABLE,
                 ADMIN_ROLE_PERMISSION_CODE_NOT_FOUND,
                 ADMIN_MENU_PERMISSION_CODE_NOT_FOUND, ADMIN_MENU_SORT_BOUNDARY,
                 ADMIN_SYMBOL_INVALID_LEVERAGE, ADMIN_SYMBOL_INVALID_FEE_RATE,
                 ADMIN_SYMBOL_INVALID_SPREAD, ADMIN_SYMBOL_INVALID_QTY_RANGE,
                 ADMIN_SYMBOL_SWAP_RATE_DATE_INVALID, ADMIN_SYMBOL_SWAP_RATE_OUT_OF_RANGE,
                 ADMIN_SYMBOL_MAPPING_INVALID_PRICE_RULE, ADMIN_SYMBOL_GROUP_VISIBILITY_INVALID,
                 ADMIN_SYMBOL_SOURCE_INVALID, ADMIN_SYMBOL_TRADING_SCHEDULE_INVALID,
                 ADMIN_SYMBOL_HOLIDAY_INVALID,
                 ADMIN_SYMBOL_GROUP_MARKUP_INVALID_RANGE,
                 ADMIN_TRADING_RISK_SWITCH_KEY_INVALID, ADMIN_TRADING_REASON_REQUIRED,
                 ADMIN_RISK_CONFIG_INVALID_LEVERAGE, ADMIN_RISK_CONFIG_INVALID_POSITION_LIMIT,
                 ADMIN_RISK_CONFIG_INVALID_HEDGE_THRESHOLD,
                 ADMIN_RISK_MARKET_CONFIG_INVALID_THRESHOLD, ADMIN_RISK_REASON_REQUIRED,
                 ADMIN_KYC_REJECT_REASON_REQUIRED,
                 ADMIN_WITHDRAW_REJECT_REASON_REQUIRED,
                 ADMIN_WALLET_PROVISION_REASON_REQUIRED,
                 ADMIN_NOTIFICATION_TEMPLATE_CODE_INVALID_FORMAT,
                 ADMIN_NOTIFICATION_TEMPLATE_CHANNEL_INVALID,
                 ADMIN_NOTIFICATION_REASON_REQUIRED,
                 ADMIN_RECONCILIATION_REASON_REQUIRED,
                 ADMIN_TIER_VALIDATION_FAILED,
                 ADMIN_MARGIN_MODE_CONFIG_INVALID, ADMIN_FX_PAUSE_BEHAVIOR_INVALID,
                 ADMIN_RISK_THRESHOLD_INVALID ->
                    HttpServletResponse.SC_BAD_REQUEST;
            case ADMIN_CUSTOMER_NOT_FOUND, ADMIN_USER_NOT_FOUND, ADMIN_ROLE_NOT_FOUND,
                 ADMIN_MENU_NOT_FOUND, ADMIN_SYMBOL_NOT_FOUND,
                 ADMIN_SYMBOL_MAPPING_NOT_FOUND, ADMIN_SYMBOL_MAPPING_SOURCE_NOT_FOUND,
                 ADMIN_SYMBOL_TRADING_SCHEDULE_NOT_FOUND, ADMIN_SYMBOL_HOLIDAY_NOT_FOUND,
                 ADMIN_SYMBOL_GROUP_MARKUP_NOT_FOUND,
                 ADMIN_TRADING_POSITION_NOT_FOUND,
                 ADMIN_RISK_ACTION_NOT_FOUND, ADMIN_RISK_CONFIG_NOT_FOUND,
                 ADMIN_RISK_MARKET_CONFIG_NOT_FOUND,
                 ADMIN_DEPOSIT_NOT_FOUND,
                 ADMIN_WALLET_PROVISION_DLQ_NOT_FOUND,
                 ADMIN_KYC_NOT_FOUND,
                 ADMIN_WITHDRAW_NOT_FOUND,
                 ADMIN_NOTIFICATION_TEMPLATE_NOT_FOUND,
                 ADMIN_NOTIFICATION_USER_NOT_FOUND,
                 ADMIN_NOTIFICATION_NOT_FOUND,
                 ADMIN_AUDIT_LOG_NOT_FOUND,
                 ADMIN_RECONCILIATION_NOT_FOUND,
                 ADMIN_TIER_NOT_FOUND,
                 ADMIN_FX_RATE_NOT_FOUND ->
                    HttpServletResponse.SC_NOT_FOUND;
            case ADMIN_CUSTOMER_ALREADY_FROZEN, ADMIN_CUSTOMER_TERMINAL_STATUS,
                 ADMIN_USER_USERNAME_DUPLICATE, ADMIN_ROLE_CODE_DUPLICATE,
                 ADMIN_ROLE_HAS_MEMBERS_CANNOT_DELETE,
                 ADMIN_MENU_CODE_DUPLICATE, ADMIN_MENU_HAS_CHILDREN_CANNOT_DELETE,
                 ADMIN_SYMBOL_ALREADY_SUSPENDED, ADMIN_SYMBOL_ALREADY_TRADING,
                 ADMIN_SYMBOL_SWAP_RATE_OVERLAP_DATE, ADMIN_SYMBOL_MAPPING_DUPLICATE,
                 ADMIN_SYMBOL_SOURCE_DUPLICATE,
                 ADMIN_SYMBOL_GROUP_MARKUP_DUPLICATE,
                 ADMIN_TRADING_POSITION_ALREADY_CLOSED, ADMIN_TRADING_POSITION_LOCK_TIMEOUT,
                 ADMIN_TRADING_RISK_SWITCH_VALUE_UNCHANGED,
                 ADMIN_RISK_ACTION_ALREADY_ACTIVE, ADMIN_RISK_CONFIG_DUPLICATE_SYMBOL,
                 ADMIN_WALLET_PROVISION_DLQ_ALREADY_RESOLVED,
                 ADMIN_KYC_NOT_PENDING,
                 ADMIN_WITHDRAW_NOT_PENDING, ADMIN_WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED,
                 ADMIN_WITHDRAW_REVIEW_RACE,
                 ADMIN_NOTIFICATION_TEMPLATE_CODE_DUPLICATE,
                 ADMIN_NOTIFICATION_TEMPLATE_IN_USE,
                 ADMIN_RECONCILIATION_ALREADY_RESOLVED,
                 ADMIN_TIER_OVERLAP ->
                    HttpServletResponse.SC_CONFLICT;
            case ADMIN_TRADING_MANUAL_LIQUIDATE_FAILED,
                 ADMIN_WITHDRAW_WALLET_UNREACHABLE,
                 ADMIN_RECONCILIATION_WALLET_UNREACHABLE,
                 ADMIN_RECONCILIATION_TRADING_UNREACHABLE ->
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            case ADMIN_RISK_ACTION_NOT_DEACTIVATABLE ->
                    HttpServletResponse.SC_FORBIDDEN;
        };
        log.warn("admin.exception.business code={} message={}", errorCode.code(), ex.getMessage());
        return ResponseEntity.status(httpStatus).body(buildResponse(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationException(MethodArgumentNotValidException ex) {
        log.warn("admin.exception.validation message={}", ex.getMessage());
        return ResponseEntity.status(HttpServletResponse.SC_BAD_REQUEST).body(buildResponse(
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpectedException(Exception ex) {
        log.error("admin.exception.internal message={}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).body(buildResponse(
                CommonErrorCode.INTERNAL_ERROR.code(),
                CommonErrorCode.INTERNAL_ERROR.message()));
    }

    private ApiResponse<Object> buildResponse(String code, String message) {
        return new ApiResponse<>(
                code,
                message,
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }
}
