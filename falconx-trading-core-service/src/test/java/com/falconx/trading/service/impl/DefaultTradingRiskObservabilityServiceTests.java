package com.falconx.trading.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.calculator.RiskExposureCalculator;
import com.falconx.trading.entity.TradingHedgeLog;
import com.falconx.trading.entity.TradingHedgeLogStatus;
import com.falconx.trading.entity.TradingHedgeTriggerSource;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskConfig;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.entity.TradingSymbolCorrelationGroup;
import com.falconx.trading.entity.TradingSymbolCorrelationMember;
import com.falconx.trading.event.TradingHedgeAlertEvent;
import com.falconx.trading.producer.TradingHedgeAlertEventPublisher;
import com.falconx.trading.repository.TradingHedgeLogRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.repository.TradingRiskExposureRepository;
import com.falconx.trading.repository.TradingRiskMarketConfigRepository;
import com.falconx.trading.repository.TradingSymbolCorrelationGroupRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultTradingRiskObservabilityServiceTests {

    @Test
    void shouldPublishSpringEventWhenExposureFirstBreachesThreshold() {
        TradingRiskExposureRepository exposureRepository = mock(TradingRiskExposureRepository.class);
        TradingRiskConfigRepository configRepository = mock(TradingRiskConfigRepository.class);
        TradingHedgeLogRepository hedgeLogRepository = mock(TradingHedgeLogRepository.class);
        TradingHedgeAlertEventPublisher eventPublisher = mock(TradingHedgeAlertEventPublisher.class);
        DefaultTradingRiskObservabilityService service = new DefaultTradingRiskObservabilityService(
                exposureRepository,
                configRepository,
                hedgeLogRepository,
                new TradingRiskObservabilityDecider(new RiskExposureCalculator()),
                eventPublisher,
                mock(TradingRiskControlActionRepository.class),
                mock(TradingRiskMarketConfigRepository.class),
                mock(com.falconx.trading.repository.TradingSymbolCorrelationGroupRepository.class),
                // 多币种 USD 化：spec 返回 empty → QC 缺失降级 fx=1，既有 USD 断言保持不变
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class),
                mock(com.falconx.trading.service.FxRateService.class)
        );
        TradingQuoteSnapshot quote = quote("BTCUSDT", "9995.00000000");
        TradingRiskExposure exposure = exposure("BTCUSDT", "2.00000000", "0.00000000");
        TradingHedgeLog persistedLog = new TradingHedgeLog(
                99L,
                "BTCUSDT",
                123L,
                TradingHedgeTriggerSource.OPEN_POSITION,
                TradingHedgeLogStatus.ALERT_ONLY,
                new BigDecimal("2.00000000"),
                new BigDecimal("19990.00000000"),
                new BigDecimal("15000.00000000"),
                new BigDecimal("9995.00000000"),
                quote.ts(),
                quote.source(),
                OffsetDateTime.now()
        );

        when(configRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(riskConfig("BTCUSDT", "15000.00000000")));
        when(exposureRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(exposure));
        when(hedgeLogRepository.findLatestBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        when(hedgeLogRepository.save(any())).thenReturn(persistedLog);

        service.applyOpenPosition(
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("2.00000000"),
                quote,
                OffsetDateTime.now(),
                123L
        );

        ArgumentCaptor<TradingHedgeAlertEvent> eventCaptor = ArgumentCaptor.forClass(TradingHedgeAlertEvent.class);
        verify(eventPublisher).publishAfterCommit(eventCaptor.capture());
        TradingHedgeAlertEvent event = eventCaptor.getValue();
        Assertions.assertEquals("BTCUSDT", event.symbol());
        Assertions.assertEquals(new BigDecimal("19990.00000000"), event.netExposureUsd());
        Assertions.assertEquals(new BigDecimal("15000.00000000"), event.hedgeThresholdUsd());
        Assertions.assertEquals(123L, event.positionId());
        Assertions.assertEquals(99L, event.hedgeLogId());
    }

    @Test
    void shouldNotPublishSpringEventWhenExposureRecoversWithinThreshold() {
        TradingRiskExposureRepository exposureRepository = mock(TradingRiskExposureRepository.class);
        TradingRiskConfigRepository configRepository = mock(TradingRiskConfigRepository.class);
        TradingHedgeLogRepository hedgeLogRepository = mock(TradingHedgeLogRepository.class);
        TradingHedgeAlertEventPublisher eventPublisher = mock(TradingHedgeAlertEventPublisher.class);
        DefaultTradingRiskObservabilityService service = new DefaultTradingRiskObservabilityService(
                exposureRepository,
                configRepository,
                hedgeLogRepository,
                new TradingRiskObservabilityDecider(new RiskExposureCalculator()),
                eventPublisher,
                mock(TradingRiskControlActionRepository.class),
                mock(TradingRiskMarketConfigRepository.class),
                mock(com.falconx.trading.repository.TradingSymbolCorrelationGroupRepository.class),
                // 多币种 USD 化：spec 返回 empty → QC 缺失降级 fx=1，既有 USD 断言保持不变
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class),
                mock(com.falconx.trading.service.FxRateService.class)
        );
        TradingQuoteSnapshot quote = quote("BTCUSDT", "6995.00000000");
        TradingRiskExposure exposure = exposure("BTCUSDT", "2.00000000", "0.00000000");

        when(configRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(riskConfig("BTCUSDT", "15000.00000000")));
        when(exposureRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(exposure));
        when(hedgeLogRepository.findLatestBySymbol("BTCUSDT")).thenReturn(Optional.of(latestAlertLog()));
        when(hedgeLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.refreshExposureFromQuote(quote, OffsetDateTime.now());

        verify(eventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void shouldSkipThresholdObservationWhenQuoteRefreshHasNoExposure() {
        TradingRiskExposureRepository exposureRepository = mock(TradingRiskExposureRepository.class);
        TradingRiskConfigRepository configRepository = mock(TradingRiskConfigRepository.class);
        TradingHedgeLogRepository hedgeLogRepository = mock(TradingHedgeLogRepository.class);
        TradingRiskControlActionRepository actionRepository = mock(TradingRiskControlActionRepository.class);
        TradingSymbolCorrelationGroupRepository groupRepository = mock(TradingSymbolCorrelationGroupRepository.class);
        DefaultTradingRiskObservabilityService service = new DefaultTradingRiskObservabilityService(
                exposureRepository,
                configRepository,
                hedgeLogRepository,
                new TradingRiskObservabilityDecider(new RiskExposureCalculator()),
                mock(TradingHedgeAlertEventPublisher.class),
                actionRepository,
                mock(TradingRiskMarketConfigRepository.class),
                groupRepository,
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class),
                mock(com.falconx.trading.service.FxRateService.class)
        );
        when(configRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(riskConfig("BTCUSDT", "100000.00000000")));
        when(exposureRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());

        service.refreshExposureFromQuote(quote("BTCUSDT", "100.00000000"), OffsetDateTime.now());

        verify(exposureRepository).refreshNetExposureUsd(any(), any(), any(), any(), any());
        verify(exposureRepository).findBySymbol("BTCUSDT");
        verify(configRepository, never()).findBySymbol("BTCUSDT");
        verify(hedgeLogRepository, never()).findLatestBySymbol(any());
        verify(actionRepository, never()).activateIfAbsent(any(), any(), any(), any(), any());
        verify(actionRepository, never()).deactivate(any(), any(), any());
        verify(groupRepository, never()).findGroupsBySymbol(any());
    }

    @Test
    void shouldBatchLoadCorrelationGroupExposuresOnQuoteRefresh() {
        TradingRiskExposureRepository exposureRepository = mock(TradingRiskExposureRepository.class);
        TradingRiskConfigRepository configRepository = mock(TradingRiskConfigRepository.class);
        TradingHedgeLogRepository hedgeLogRepository = mock(TradingHedgeLogRepository.class);
        TradingRiskControlActionRepository actionRepository = mock(TradingRiskControlActionRepository.class);
        TradingSymbolCorrelationGroupRepository groupRepository = mock(TradingSymbolCorrelationGroupRepository.class);
        DefaultTradingRiskObservabilityService service = new DefaultTradingRiskObservabilityService(
                exposureRepository,
                configRepository,
                hedgeLogRepository,
                new TradingRiskObservabilityDecider(new RiskExposureCalculator()),
                mock(TradingHedgeAlertEventPublisher.class),
                actionRepository,
                mock(TradingRiskMarketConfigRepository.class),
                groupRepository,
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class),
                mock(com.falconx.trading.service.FxRateService.class)
        );
        TradingQuoteSnapshot quote = quote("BTCUSDT", "100.00000000");
        TradingRiskExposure btcExposure = exposureWithUsd("BTCUSDT", "1.00000000", "0.00000000", "100.00000000");
        TradingRiskExposure ethExposure = exposureWithUsd("ETHUSDT", "2.00000000", "0.00000000", "200.00000000");
        List<String> memberSymbols = List.of("BTCUSDT", "ETHUSDT", "XRPUSDT");

        when(configRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(riskConfig("BTCUSDT", "100000.00000000")));
        when(exposureRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(btcExposure));
        when(hedgeLogRepository.findLatestBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        when(groupRepository.findGroupsBySymbol("BTCUSDT")).thenReturn(List.of(new TradingSymbolCorrelationGroup(
                "CRYPTO_MAJOR_GROUP",
                "Crypto Major",
                new BigDecimal("1000000.00000000"),
                true,
                List.of(
                        new TradingSymbolCorrelationMember("BTCUSDT", BigDecimal.ONE),
                        new TradingSymbolCorrelationMember("ETHUSDT", BigDecimal.ONE),
                        new TradingSymbolCorrelationMember("XRPUSDT", BigDecimal.ONE)
                )
        )));
        when(exposureRepository.findBySymbols(memberSymbols)).thenReturn(List.of(btcExposure, ethExposure));

        service.refreshExposureFromQuote(quote, OffsetDateTime.now());

        verify(exposureRepository).findBySymbols(memberSymbols);
        verify(exposureRepository, never()).findBySymbol("ETHUSDT");
        verify(exposureRepository, never()).findBySymbol("XRPUSDT");
    }

    @Test
    void shouldLoadCorrelationGroupExposuresOnceForOverlappingGroups() {
        TradingRiskExposureRepository exposureRepository = mock(TradingRiskExposureRepository.class);
        TradingRiskConfigRepository configRepository = mock(TradingRiskConfigRepository.class);
        TradingHedgeLogRepository hedgeLogRepository = mock(TradingHedgeLogRepository.class);
        TradingSymbolCorrelationGroupRepository groupRepository = mock(TradingSymbolCorrelationGroupRepository.class);
        DefaultTradingRiskObservabilityService service = new DefaultTradingRiskObservabilityService(
                exposureRepository,
                configRepository,
                hedgeLogRepository,
                new TradingRiskObservabilityDecider(new RiskExposureCalculator()),
                mock(TradingHedgeAlertEventPublisher.class),
                mock(TradingRiskControlActionRepository.class),
                mock(TradingRiskMarketConfigRepository.class),
                groupRepository,
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class),
                mock(com.falconx.trading.service.FxRateService.class)
        );
        TradingQuoteSnapshot quote = quote("BTCUSDT", "100.00000000");
        TradingRiskExposure btcExposure = exposureWithUsd("BTCUSDT", "1.00000000", "0.00000000", "100.00000000");
        TradingRiskExposure ethExposure = exposureWithUsd("ETHUSDT", "2.00000000", "0.00000000", "200.00000000");
        TradingRiskExposure xrpExposure = exposureWithUsd("XRPUSDT", "3.00000000", "0.00000000", "300.00000000");
        List<String> memberSymbols = List.of("BTCUSDT", "ETHUSDT", "XRPUSDT");

        when(configRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(riskConfig("BTCUSDT", "100000.00000000")));
        when(exposureRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(btcExposure));
        when(hedgeLogRepository.findLatestBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        when(groupRepository.findGroupsBySymbol("BTCUSDT")).thenReturn(List.of(
                new TradingSymbolCorrelationGroup(
                        "CRYPTO_MAJOR_GROUP",
                        "Crypto Major",
                        new BigDecimal("1000000.00000000"),
                        true,
                        List.of(
                                new TradingSymbolCorrelationMember("BTCUSDT", BigDecimal.ONE),
                                new TradingSymbolCorrelationMember("ETHUSDT", BigDecimal.ONE)
                        )
                ),
                new TradingSymbolCorrelationGroup(
                        "CRYPTO_ALT_GROUP",
                        "Crypto Alt",
                        new BigDecimal("1000000.00000000"),
                        true,
                        List.of(
                                new TradingSymbolCorrelationMember("BTCUSDT", BigDecimal.ONE),
                                new TradingSymbolCorrelationMember("XRPUSDT", BigDecimal.ONE)
                        )
                )
        ));
        when(exposureRepository.findBySymbols(memberSymbols))
                .thenReturn(List.of(btcExposure, ethExposure, xrpExposure));

        service.refreshExposureFromQuote(quote, OffsetDateTime.now());

        verify(exposureRepository, times(1)).findBySymbols(memberSymbols);
        verify(exposureRepository, never()).findBySymbol("ETHUSDT");
        verify(exposureRepository, never()).findBySymbol("XRPUSDT");
    }

    private TradingQuoteSnapshot quote(String symbol, String markPrice) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TradingQuoteSnapshot(
                symbol,
                new BigDecimal(markPrice),
                new BigDecimal(markPrice),
                new BigDecimal(markPrice),
                now,
                "risk-observability-unit-test",
                false
        );
    }

    private TradingRiskExposure exposure(String symbol, String totalLongQty, String totalShortQty) {
        BigDecimal longQty = new BigDecimal(totalLongQty);
        BigDecimal shortQty = new BigDecimal(totalShortQty);
        return exposureWithUsd(symbol, totalLongQty, totalShortQty, "0");
    }

    private TradingRiskExposure exposureWithUsd(String symbol,
                                                String totalLongQty,
                                                String totalShortQty,
                                                String netExposureUsd) {
        BigDecimal longQty = new BigDecimal(totalLongQty);
        BigDecimal shortQty = new BigDecimal(totalShortQty);
        return new TradingRiskExposure(
                symbol,
                longQty,
                shortQty,
                longQty.subtract(shortQty),
                new BigDecimal(netExposureUsd),
                OffsetDateTime.now()
        );
    }

    private TradingRiskConfig riskConfig(String symbol, String hedgeThresholdUsd) {
        return new TradingRiskConfig(
                1L,
                symbol,
                null,
                new BigDecimal("5.00000000"),
                new BigDecimal("100.00000000"),
                new BigDecimal("0.005000"),
                50,
                new BigDecimal(hedgeThresholdUsd),
                null,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private TradingHedgeLog latestAlertLog() {
        return new TradingHedgeLog(
                1L,
                "BTCUSDT",
                1001L,
                TradingHedgeTriggerSource.OPEN_POSITION,
                TradingHedgeLogStatus.ALERT_ONLY,
                new BigDecimal("2.00000000"),
                new BigDecimal("19990.00000000"),
                new BigDecimal("15000.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "risk-observability-unit-test",
                OffsetDateTime.now()
        );
    }
}
