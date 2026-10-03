package com.falconx.market.application;

import com.falconx.market.provider.MarketQuoteProvider;
import com.falconx.market.service.MarketQuoteMappingService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 市场数据入口启动器。
 *
 * <p>该启动器在 market-service 启动完成后挂起外部行情 Provider，
 * 让 Stage 6A 形成明确的运行时入口：服务启动 -> Provider 连接外部源 -> 报价进入应用层。
 *
 * <p>当前实现从 market owner 查询全部启用的 symbol 报价映射。
 * 这里解析出的源 symbol 列表用于 LP 订阅和本地过滤，平台 symbol 由报价映射服务转换出来。
 */
@Component
public class MarketFeedBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketFeedBootstrapRunner.class);

    private final MarketQuoteProvider marketQuoteProvider;
    private final MarketDataIngestionApplicationService marketDataIngestionApplicationService;
    private final MarketQuoteMappingService marketQuoteMappingService;

    public MarketFeedBootstrapRunner(MarketQuoteProvider marketQuoteProvider,
                                     MarketDataIngestionApplicationService marketDataIngestionApplicationService,
                                     MarketQuoteMappingService marketQuoteMappingService) {
        this.marketQuoteProvider = marketQuoteProvider;
        this.marketDataIngestionApplicationService = marketDataIngestionApplicationService;
        this.marketQuoteMappingService = marketQuoteMappingService;
    }

    /**
     * 服务启动后启动报价 Provider。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("market.feed.bootstrap.start");
        List<String> quoteSymbols = resolveQuoteSymbols();
        log.info("market.feed.bootstrap.symbols resolvedCount={} sampleSymbols={}",
                quoteSymbols.size(),
                sampleSymbols(quoteSymbols));
        marketQuoteProvider.start(quoteSymbols, marketDataIngestionApplicationService::ingest);
        log.info("market.feed.bootstrap.ready");
    }

    /**
     * 定时热刷新外部行情源本地白名单。
     *
     * <p>数据库里报价映射或 LP 源 symbol 启用状态变化后，只要定时把最新源 symbol 列表推送给 provider 即可。
     * 这里如果数据库临时不可用，必须保留 provider 当前白名单，避免一次失败把运行中的行情链路整体清空。
     */
    @Scheduled(
            cron = "${falconx.market.lp.symbol-whitelist-refresh-cron:0 */1 * * * *}",
            zone = "${falconx.market.lp.symbol-whitelist-refresh-zone:UTC}"
    )
    public void refreshQuoteSymbolWhitelist() {
        try {
            List<String> quoteSymbols = resolveQuoteSymbols();
            marketQuoteProvider.refreshSymbols(quoteSymbols);
            log.info("market.feed.bootstrap.symbols.refreshed resolvedCount={} sampleSymbols={}",
                    quoteSymbols.size(),
                    sampleSymbols(quoteSymbols));
        } catch (RuntimeException error) {
            log.error("market.feed.bootstrap.symbols.refresh.failed reason={}", error.toString(), error);
        }
    }

    /**
     * 解析外部行情源订阅和本地过滤用的 symbol 列表。
     *
     * <p>这里必须只依赖 market owner 中已启用的报价映射和可用 LP 源 symbol，确保真正订阅外部源的范围与平台配置保持一致。
     * 运行时不允许回退到静态 symbol 列表，否则数据库与应用过滤条件会分叉。
     *
     * @return 应订阅的内部标准 symbol 列表
     */
    private List<String> resolveQuoteSymbols() {
        marketQuoteMappingService.refreshMappings();
        List<String> sourceSymbols = marketQuoteMappingService.sourceSymbols();
        if (sourceSymbols.isEmpty()) {
            log.warn("market.feed.bootstrap.symbols.empty reason=no-enabled-symbols");
        }
        return sourceSymbols;
    }

    private static List<String> sampleSymbols(List<String> symbols) {
        return symbols.stream().limit(10).toList();
    }
}
