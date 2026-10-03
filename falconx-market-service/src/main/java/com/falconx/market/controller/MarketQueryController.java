package com.falconx.market.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.dto.MarketKlineItemResponse;
import com.falconx.market.dto.MarketKlinesResponse;
import com.falconx.market.dto.MarketQuoteHistoryResponse;
import com.falconx.market.dto.MarketQuoteResponse;
import com.falconx.market.dto.MarketSymbolsResponse;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.service.MarketKlineQueryService;
import com.falconx.market.service.MarketQuoteQueryService;
import com.falconx.market.service.MarketSymbolQueryService;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 市场查询控制器。
 *
 * <p>该控制器负责暴露 Stage 4 最小北向查询能力：
 * 通过 gateway 转发后，外部调用方可以查询某个品种的最新报价快照。
 */
@Validated
@RestController
@RequestMapping("/api/v1/market")
public class MarketQueryController {

    private static final Logger log = LoggerFactory.getLogger(MarketQueryController.class);

    private final MarketQuoteQueryService marketQuoteQueryService;
    private final MarketSymbolQueryService marketSymbolQueryService;
    private final MarketKlineQueryService marketKlineQueryService;

    public MarketQueryController(MarketQuoteQueryService marketQuoteQueryService,
                                 MarketSymbolQueryService marketSymbolQueryService,
                                 MarketKlineQueryService marketKlineQueryService) {
        this.marketQuoteQueryService = marketQuoteQueryService;
        this.marketSymbolQueryService = marketSymbolQueryService;
        this.marketKlineQueryService = marketKlineQueryService;
    }

    /**
     * 查询首页展示用品种列表。
     *
     * @return 品种列表
     */
    @GetMapping("/symbols")
    public ApiResponse<MarketSymbolsResponse> listSymbols(
            @RequestHeader(value = "X-User-Group-Code", required = false) String groupCode) {
        log.info("market.http.symbols.received groupCode={}", normalizeGroupCode(groupCode));
        MarketSymbolsResponse response = marketSymbolQueryService.listTradingSymbols(groupCode);
        return new ApiResponse<>(
                "0",
                "success",
                response,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    /**
     * 查询最新报价。
     *
     * @param symbol 品种代码
     * @return 最新报价快照
     */
    @GetMapping("/quotes/{symbol}")
    public ApiResponse<MarketQuoteResponse> getLatestQuote(
            @PathVariable @NotBlank String symbol,
            @RequestHeader(value = "X-User-Group-Code", required = false) String groupCode) {
        log.info("market.http.quote.received symbol={} groupCode={}", symbol, normalizeGroupCode(groupCode));
        MarketQuoteQueryService.QuoteSnapshot snapshot = marketQuoteQueryService.getLatestQuote(symbol, groupCode);
        return new ApiResponse<>(
                "0",
                "success",
                toQuoteResponse(snapshot),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    /**
     * 查询历史报价 Tick。
     *
     * @param symbol 品种代码
     * @param limit 返回数量
     * @return 历史报价 Tick
     */
    @GetMapping("/quotes/{symbol}/history")
    public ApiResponse<MarketQuoteHistoryResponse> getQuoteHistory(
            @PathVariable @NotBlank String symbol,
            @RequestParam(defaultValue = "600") Integer limit,
            @RequestHeader(value = "X-User-Group-Code", required = false) String groupCode) {
        log.info("market.http.quote_history.received symbol={} limit={} groupCode={}",
                symbol,
                limit,
                normalizeGroupCode(groupCode));
        List<MarketQuoteResponse> quotes = marketQuoteQueryService.getRecentQuotes(symbol, limit, groupCode)
                .stream()
                .map(this::toQuoteResponse)
                .toList();
        return new ApiResponse<>(
                "0",
                "success",
                new MarketQuoteHistoryResponse(quotes),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    /**
     * 查询历史 K 线。
     *
     * @param symbol 品种代码
     * @param interval K 线周期
     * @param limit 返回数量
     * @return 历史 K 线
     */
    @GetMapping("/klines/{symbol}")
    public ApiResponse<MarketKlinesResponse> getKlines(@PathVariable @NotBlank String symbol,
                                                       @RequestParam(defaultValue = "1m") String interval,
                                                       @RequestParam(defaultValue = "200") Integer limit,
                                                       @RequestHeader(value = "X-User-Group-Code", required = false) String groupCode) {
        log.info("market.http.klines.received symbol={} interval={} limit={} groupCode={}",
                symbol,
                interval,
                limit,
                normalizeGroupCode(groupCode));
        List<MarketKlineItemResponse> klines = marketKlineQueryService.getRecentKlines(symbol, interval, limit, groupCode)
                .stream()
                .map(this::toKlineResponse)
                .toList();
        return new ApiResponse<>(
                "0",
                "success",
                new MarketKlinesResponse(klines),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    private String normalizeGroupCode(String groupCode) {
        return groupCode == null || groupCode.isBlank() ? "default" : groupCode.trim();
    }

    private MarketKlineItemResponse toKlineResponse(KlineSnapshot snapshot) {
        return new MarketKlineItemResponse(
                snapshot.symbol(),
                snapshot.interval(),
                snapshot.open(),
                snapshot.high(),
                snapshot.low(),
                snapshot.close(),
                snapshot.volume(),
                snapshot.openTime(),
                snapshot.closeTime(),
                snapshot.isFinal()
        );
    }

    private MarketQuoteResponse toQuoteResponse(MarketQuoteQueryService.QuoteSnapshot snapshot) {
        StandardQuote effective = snapshot.effective();
        StandardQuote base = snapshot.base();
        return new MarketQuoteResponse(
                effective.symbol(),
                effective.bid(),
                effective.ask(),
                effective.mid(),
                effective.mark(),
                // STAGE-12-GROUP-MARKUP: 同时透传基准 bid/ask/mid，公允标记价、
                // markup 对照与诊断展示可用 base*；K 线图不从 REST quote/base* 合成。
                // effective === base 时（无 markup）base 字段值与上面相同。
                base.bid(),
                base.ask(),
                base.mid(),
                snapshot.hasMarkup(),
                effective.ts(),
                effective.source(),
                effective.stale(),
                effective.qualityStatus() == null ? null : effective.qualityStatus().name(),
                effective.qualityReason()
        );
    }
}
