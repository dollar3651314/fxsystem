package com.falconx.common.error;

/**
 * FalconX 统一错误码接口。
 *
 * <p>后续不同服务的错误码枚举都应实现该接口，
 * 以便在网关、统一异常处理和接口文档生成时采用同一读取方式。
 */
public interface ErrorCode {

    /**
     * @return 业务错误码
     */
    String code();

    /**
     * @return 面向接口调用方的简要错误说明
     */
    String message();
}
