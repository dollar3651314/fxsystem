package com.falconx.market.service.impl;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.market.repository.RedisMarketSymbolSpecRepository;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.repository.mapper.record.MarketSymbolAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import com.falconx.market.service.MarketSymbolSpecWarmupService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SymbolSpec 共享快照预热服务默认实现。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B：
 * 启动时全量刷新 + mapping CRUD afterCommit 单独刷新。
 *
 * <p>装配规则：precision 与 6 交易字段全部取自 mapping；source 仅用于确认上游 LP 元数据存在。
 */
@Service
public class DefaultMarketSymbolSpecWarmupService implements MarketSymbolSpecWarmupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketSymbolSpecWarmupService.class);

    private final MarketSymbolAdminMapper symbolMapper;
    private final RedisMarketSymbolSpecRepository specRepository;

    public DefaultMarketSymbolSpecWarmupService(MarketSymbolAdminMapper symbolMapper,
                                                RedisMarketSymbolSpecRepository specRepository) {
        this.symbolMapper = symbolMapper;
        this.specRepository = specRepository;
    }

    @Override
    public void refreshAll() {
        // 用现有 list 分页接口拿全量 mapping（按 platform_symbol 升序）
        int pageSize = 200;
        int offset = 0;
        int totalRefreshed = 0;
        // 预加载 source 元数据到 Map，避免 N+1 查询
        Map<String, MarketSymbolAdminRecord> sourcesBySymbol = new HashMap<>();
        while (true) {
            List<MarketSymbolQuoteMappingAdminRecord> page = symbolMapper.selectQuoteMappingsForList(
                    null, null, null, null, null, offset, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (MarketSymbolQuoteMappingAdminRecord mapping : page) {
                MarketSymbolAdminRecord source = sourcesBySymbol.computeIfAbsent(
                        mapping.sourceLpCode() + "|" + mapping.sourceSymbol(),
                        ignored -> symbolMapper.selectSymbolByLpCodeAndSymbol(mapping.sourceLpCode(), mapping.sourceSymbol())
                );
                SymbolSpec spec = specRepository.toSpec(mapping, source);
                specRepository.save(spec);
                totalRefreshed++;
            }
            if (page.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        log.info("market.symbol-spec.warmup.completed mappings={}", totalRefreshed);
    }

    @Override
    public void refresh(String platformSymbol) {
        MarketSymbolQuoteMappingAdminRecord mapping = symbolMapper.selectQuoteMappingByPlatformSymbol(platformSymbol);
        if (mapping == null) {
            specRepository.delete(platformSymbol);
            log.info("market.symbol-spec.refresh.deleted platformSymbol={} reason=mapping-missing", platformSymbol);
            return;
        }
        MarketSymbolAdminRecord source = symbolMapper.selectSymbolByLpCodeAndSymbol(
                mapping.sourceLpCode(), mapping.sourceSymbol());
        SymbolSpec spec = specRepository.toSpec(mapping, source);
        specRepository.save(spec);
        log.info("market.symbol-spec.refresh.completed platformSymbol={} maxLeverage={} takerFeeRate={}",
                platformSymbol, spec.maxLeverage(), spec.takerFeeRate());
    }
}
