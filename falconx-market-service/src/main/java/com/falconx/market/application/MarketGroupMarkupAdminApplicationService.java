package com.falconx.market.application;

import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.entity.MarketSymbolGroupMarkup;
import com.falconx.market.error.MarketBusinessException;
import com.falconx.market.repository.MarketSymbolGroupMarkupRepository;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.service.MarketGroupMarkupService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-12-GROUP-MARKUP 管理端编排服务。
 *
 * <p>承载 {@code t_symbol_group_markup} 的 CRUD 与列表查询。
 * 写操作完成后立即调用 {@link MarketGroupMarkupService#refresh()} 让内存快照同步生效，
 * trading-core 通过 30s 定时拉取保持一致。
 */
@Service
public class MarketGroupMarkupAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(MarketGroupMarkupAdminApplicationService.class);
    private static final BigDecimal MAX_ABS = new BigDecimal("1000000");
    private static final int BULK_MAX = 500;

    private final MarketSymbolGroupMarkupRepository repository;
    private final MarketSymbolAdminMapper symbolAdminMapper;
    private final MarketGroupMarkupService groupMarkupService;

    public MarketGroupMarkupAdminApplicationService(
            MarketSymbolGroupMarkupRepository repository,
            MarketSymbolAdminMapper symbolAdminMapper,
            MarketGroupMarkupService groupMarkupService) {
        this.repository = repository;
        this.symbolAdminMapper = symbolAdminMapper;
        this.groupMarkupService = groupMarkupService;
    }

    public ListResult list(String groupCode, String symbolLike, Integer enabled, int page, int size) {
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        int normalizedPage = Math.max(page, 0);
        int offset = normalizedPage * normalizedSize;
        List<MarketGroupMarkupItem> items = repository
                .findByFilters(groupCode, symbolLike, enabled, offset, normalizedSize).stream()
                .map(this::toContract).toList();
        long total = repository.countByFilters(groupCode, symbolLike, enabled);
        return new ListResult(items, total, normalizedPage, normalizedSize);
    }

    public GroupedListResult listGrouped() {
        List<MarketSymbolGroupMarkup> all = repository.findAllEnabled();
        Map<String, List<MarketGroupMarkupItem>> grouped = new LinkedHashMap<>();
        for (MarketSymbolGroupMarkup item : all) {
            grouped.computeIfAbsent(item.groupCode(), k -> new ArrayList<>()).add(toContract(item));
        }
        List<GroupedListItem> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<MarketGroupMarkupItem>> e : grouped.entrySet()) {
            result.add(new GroupedListItem(e.getKey(), e.getValue().size(), e.getValue()));
        }
        return new GroupedListResult(result);
    }

    public List<MarketGroupMarkupItem> findAllEnabled() {
        return groupMarkupService.findAllEnabled().stream().map(this::toContract).toList();
    }

    public List<MarketGroupMarkupItem> findChangedSince(OffsetDateTime since) {
        return groupMarkupService.findChangedSince(since).stream().map(this::toContract).toList();
    }

    public MarketGroupMarkupItem findByPk(String groupCode, String platformSymbol) {
        return repository.findByPk(groupCode, platformSymbol)
                .map(this::toContract)
                .orElseThrow(() -> new MarketBusinessException("90640",
                        "Group markup not found: groupCode=" + groupCode + " symbol=" + platformSymbol));
    }

    @Transactional
    public MarketGroupMarkupItem create(String groupCode, String platformSymbol,
                                        BigDecimal bidExtra, BigDecimal askExtra, int enabled) {
        validate(groupCode, platformSymbol, bidExtra, askExtra);
        if (repository.findByPk(groupCode, platformSymbol).isPresent()) {
            throw new MarketBusinessException("90642",
                    "Group markup duplicate: groupCode=" + groupCode + " symbol=" + platformSymbol);
        }
        repository.upsert(groupCode, platformSymbol, bidExtra, askExtra, enabled);
        log.info("market.group-markup.created groupCode={} platformSymbol={} bidExtra={} askExtra={} enabled={}",
                groupCode, platformSymbol, bidExtra, askExtra, enabled);
        refreshSnapshot();
        return findByPk(groupCode, platformSymbol);
    }

    @Transactional
    public MarketGroupMarkupItem update(String groupCode, String platformSymbol,
                                        BigDecimal bidExtra, BigDecimal askExtra, int enabled) {
        validate(groupCode, platformSymbol, bidExtra, askExtra);
        if (repository.findByPk(groupCode, platformSymbol).isEmpty()) {
            throw new MarketBusinessException("90640",
                    "Group markup not found: groupCode=" + groupCode + " symbol=" + platformSymbol);
        }
        repository.upsert(groupCode, platformSymbol, bidExtra, askExtra, enabled);
        log.info("market.group-markup.updated groupCode={} platformSymbol={} bidExtra={} askExtra={} enabled={}",
                groupCode, platformSymbol, bidExtra, askExtra, enabled);
        refreshSnapshot();
        return findByPk(groupCode, platformSymbol);
    }

    @Transactional
    public List<MarketGroupMarkupItem> bulkUpsert(String groupCode, List<BulkItem> items) {
        if (groupCode == null || groupCode.isBlank()) {
            throw new MarketBusinessException("90641", "groupCode is blank");
        }
        if (items == null || items.isEmpty()) {
            throw new MarketBusinessException("90641", "bulk items is empty");
        }
        if (items.size() > BULK_MAX) {
            throw new MarketBusinessException("90641",
                    "bulk items exceeds limit: " + items.size() + " > " + BULK_MAX);
        }
        for (BulkItem item : items) {
            validate(groupCode, item.platformSymbol(), item.bidExtra(), item.askExtra());
        }
        List<MarketGroupMarkupItem> applied = new ArrayList<>(items.size());
        for (BulkItem item : items) {
            repository.upsert(groupCode, item.platformSymbol(), item.bidExtra(), item.askExtra(), item.enabled());
            applied.add(findByPk(groupCode, item.platformSymbol()));
        }
        log.info("market.group-markup.bulk-upsert groupCode={} count={}", groupCode, items.size());
        refreshSnapshot();
        return applied;
    }

    @Transactional
    public void delete(String groupCode, String platformSymbol) {
        int affected = repository.delete(groupCode, platformSymbol);
        if (affected == 0) {
            throw new MarketBusinessException("90640",
                    "Group markup not found: groupCode=" + groupCode + " symbol=" + platformSymbol);
        }
        log.info("market.group-markup.deleted groupCode={} platformSymbol={}", groupCode, platformSymbol);
        refreshSnapshot();
    }

    private void validate(String groupCode, String platformSymbol, BigDecimal bidExtra, BigDecimal askExtra) {
        if (groupCode == null || groupCode.isBlank() || groupCode.length() > 64) {
            throw new MarketBusinessException("90641",
                    "groupCode invalid: " + groupCode);
        }
        if (platformSymbol == null || platformSymbol.isBlank() || platformSymbol.length() > 32) {
            throw new MarketBusinessException("90641",
                    "platformSymbol invalid: " + platformSymbol);
        }
        if (symbolAdminMapper.selectQuoteMappingByPlatformSymbol(platformSymbol) == null) {
            throw new MarketBusinessException("90613",
                    "platformSymbol not found in mapping: " + platformSymbol);
        }
        if (bidExtra == null || bidExtra.abs().compareTo(MAX_ABS) >= 0) {
            throw new MarketBusinessException("90641", "bidExtra out of range: " + bidExtra);
        }
        if (askExtra == null || askExtra.abs().compareTo(MAX_ABS) >= 0) {
            throw new MarketBusinessException("90641", "askExtra out of range: " + askExtra);
        }
    }

    private void refreshSnapshot() {
        try {
            groupMarkupService.refresh();
        } catch (RuntimeException error) {
            log.warn("market.group-markup.refresh.after-write.failed reason={}", error.toString());
        }
    }

    private MarketGroupMarkupItem toContract(MarketSymbolGroupMarkup item) {
        return new MarketGroupMarkupItem(
                item.groupCode(),
                item.platformSymbol(),
                item.bidExtra(),
                item.askExtra(),
                item.enabled() == 1,
                item.updatedAt() == null ? OffsetDateTime.now() : item.updatedAt()
        );
    }

    public record ListResult(List<MarketGroupMarkupItem> items, long total, int page, int size) {
    }

    public record GroupedListResult(List<GroupedListItem> groups) {
    }

    public record GroupedListItem(String groupCode, int configuredCount, List<MarketGroupMarkupItem> items) {
    }

    public record BulkItem(String platformSymbol, BigDecimal bidExtra, BigDecimal askExtra, int enabled) {
    }
}
