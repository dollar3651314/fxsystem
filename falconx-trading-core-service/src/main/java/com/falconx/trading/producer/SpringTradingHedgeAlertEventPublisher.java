package com.falconx.trading.producer;

import com.falconx.trading.event.TradingHedgeAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Spring Event 版对冲告警桩发布器。
 */
@Component
public class SpringTradingHedgeAlertEventPublisher implements TradingHedgeAlertEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpringTradingHedgeAlertEventPublisher.class);

    private final ApplicationEventPublisher applicationEventPublisher;

    public SpringTradingHedgeAlertEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publishAfterCommit(TradingHedgeAlertEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishSafely(event);
                }
            });
            return;
        }
        publishSafely(event);
    }

    private void publishSafely(TradingHedgeAlertEvent event) {
        try {
            applicationEventPublisher.publishEvent(event);
            log.info("trading.risk.hedge.event.published symbol={} positionId={} triggerSource={} netExposureUsd={} hedgeThresholdUsd={} hedgeLogId={}",
                    event.symbol(),
                    event.positionId(),
                    event.triggerSource(),
                    event.netExposureUsd(),
                    event.hedgeThresholdUsd(),
                    event.hedgeLogId());
        } catch (RuntimeException exception) {
            log.error("trading.risk.hedge.event.publish.failed symbol={} positionId={} triggerSource={} netExposureUsd={} hedgeThresholdUsd={} hedgeLogId={}",
                    event.symbol(),
                    event.positionId(),
                    event.triggerSource(),
                    event.netExposureUsd(),
                    event.hedgeThresholdUsd(),
                    event.hedgeLogId(),
                    exception);
        }
    }
}
