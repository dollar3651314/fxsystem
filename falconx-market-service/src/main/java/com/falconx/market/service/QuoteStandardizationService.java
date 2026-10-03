package com.falconx.market.service;

import com.falconx.market.entity.StandardQuote;
import com.falconx.market.provider.ExternalRawQuote;

/**
 * 报价标准化服务。
 *
 * <p>该服务负责把外部原始报价映射为平台内部统一报价对象，
 * 并在映射过程中计算 `mid`、`mark` 和 `stale` 等字段。
 */
public interface QuoteStandardizationService {

    /**
     * 将外部原始报价转换为平台标准报价对象。
     *
     * @param rawQuote 外部原始报价
     * @return 平台内部统一报价对象
     */
    StandardQuote standardize(ExternalRawQuote rawQuote);

}
