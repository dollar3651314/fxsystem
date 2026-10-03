package com.falconx.market.error;

/**
 * 市场域业务异常。
 *
 * <p>当前仅用于北向最新报价查询接口表达品种不存在或无可用报价等业务失败场景。
 */
public class MarketBusinessException extends RuntimeException {

    private final String code;

    public MarketBusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
