package com.falconx.market.service;

import com.falconx.market.entity.MarketFeaturedSymbol;
import com.falconx.market.repository.MarketFeaturedSymbolRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 跑马灯热门产品配置领域服务。
 *
 * <p>读：客户端取启用项有序 symbol 列表；管理端取全部（含禁用）。
 * 写：全量替换（管理端保存整列表，顺序即 sort_order）。
 * symbol 合法性由管理端 UI 保证（可选项来自 market /symbols owner 数据），
 * 与 group-markup 一致本层不二次校验。
 */
@Service
public class MarketFeaturedSymbolService {

    private static final Logger log = LoggerFactory.getLogger(MarketFeaturedSymbolService.class);

    private final MarketFeaturedSymbolRepository repository;

    public MarketFeaturedSymbolService(MarketFeaturedSymbolRepository repository) {
        this.repository = repository;
    }

    /** 管理端：全部配置（含禁用），按 sort_order 升序。 */
    public List<MarketFeaturedSymbol> listAll() {
        return repository.findAllOrdered();
    }

    /** 客户端：启用项有序 symbol 列表。 */
    public List<String> listEnabledSymbols() {
        return repository.findAllOrdered().stream()
                .filter(it -> it.enabled() == 1)
                .map(MarketFeaturedSymbol::platformSymbol)
                .toList();
    }

    /**
     * 全量替换。入参顺序即展示序（sortOrder = 下标）。
     *
     * @param items 每项 platformSymbol + enabled（顺序敏感）
     */
    public void replaceAll(List<FeaturedInput> items) {
        OffsetDateTime now = OffsetDateTime.now();
        List<MarketFeaturedSymbol> rows = java.util.stream.IntStream.range(0, items.size())
                .mapToObj(i -> new MarketFeaturedSymbol(
                        items.get(i).platformSymbol().trim(),
                        i,
                        items.get(i).enabled() ? 1 : 0,
                        now,
                        now))
                .filter(it -> !it.platformSymbol().isEmpty())
                .toList();
        repository.replaceAll(rows);
        log.info("market.featured.replaceAll count={}", rows.size());
    }

    /** 全量替换入参项。 */
    public record FeaturedInput(String platformSymbol, boolean enabled) {
    }
}
