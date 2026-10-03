package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.service.QuoteStandardizationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * 默认报价标准化实现。
 *
 * <p>当前实现遵循市场数据契约中的标准字段要求：
 * 1. `mid = (bid + ask) / 2`
 * 2. `mark` 当前阶段仍作为兼容字段与 `mid` 对齐；交易侧逐仓估值与强平不得直接依赖该字段
 * 3. `stale` 按配置的最大可接受年龄计算
 *
 * <p>后续若需要加入点差调整、标记价算法或来源差异修正，应继续放在该服务内处理。
 */
@Service
public class DefaultQuoteStandardizationService implements QuoteStandardizationService {

    private final MarketServiceProperties properties;

    public DefaultQuoteStandardizationService(MarketServiceProperties properties) {
        this.properties = properties;
    }

    /**
     * 标准化外部原始报价。
     *
     * @param rawQuote 外部原始报价
     * @return 平台标准报价对象
     */
    @Override
    public StandardQuote standardize(ExternalRawQuote rawQuote) {
        BigDecimal mid = rawQuote.bid()
                .add(rawQuote.ask())
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.DOWN);
        StandardQuote baseQuote = new StandardQuote(
                rawQuote.ticker(),
                rawQuote.bid(),
                rawQuote.ask(),
                mid,
                mid,
                rawQuote.ts(),
                rawQuote.source(),
                false,
                MarketQuoteQualityStatus.FRESH,
                null
        );
        if (rawQuote.bid().signum() <= 0 || rawQuote.ask().signum() <= 0) {
            return baseQuote.withQuality(MarketQuoteQualityStatus.ABNORMAL, "NON_POSITIVE_PRICE");
        }
        if (rawQuote.bid().compareTo(rawQuote.ask()) >= 0) {
            return baseQuote.withQuality(MarketQuoteQualityStatus.ABNORMAL, "BID_ASK_CROSSED");
        }
        BigDecimal maxSpreadRate = properties.getQuoteQuality().getMaxSpreadRate();
        if (maxSpreadRate != null && maxSpreadRate.signum() > 0 && mid.signum() > 0) {
            BigDecimal spreadRate = rawQuote.ask().subtract(rawQuote.bid()).divide(mid, 8, RoundingMode.DOWN);
            if (spreadRate.compareTo(maxSpreadRate) > 0) {
                return baseQuote.withQuality(MarketQuoteQualityStatus.ABNORMAL, "SPREAD_TOO_LARGE");
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        boolean stale = Duration.between(rawQuote.ts(), now)
                .abs()
                .compareTo(properties.getStale().getMaxAge()) > 0;
        return baseQuote.withQuality(
                stale ? MarketQuoteQualityStatus.STALE : MarketQuoteQualityStatus.FRESH,
                stale ? "QUOTE_TIME_DRIFT_EXCEEDED" : null
        );
    }
}
