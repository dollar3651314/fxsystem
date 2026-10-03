package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.repository.mapper.SymbolLeverageTierMapper;
import com.falconx.trading.repository.mapper.record.SymbolLeverageTierRecord;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link SymbolLeverageTierRepository} 的 MyBatis 实现（读 + 写）。
 *
 * <p>读写路径均通过 owner（trading）真实 Mapper + XML 完成，符合 AGENTS §3.2.4。
 * STAGE-14C2 Task 3 扩展写路径（save/update/softDelete）+ 分页（page/count）+ findById；
 * id 在本层用 {@link IdGenerator} 雪花生成（与其它写 Repository 一致口径）。
 */
@Repository
public class MybatisSymbolLeverageTierRepository implements SymbolLeverageTierRepository {

    /** group 未单独配置档位时的回退组。 */
    private static final String DEFAULT_GROUP_CODE = "default";

    private final SymbolLeverageTierMapper symbolLeverageTierMapper;
    private final IdGenerator idGenerator;

    public MybatisSymbolLeverageTierRepository(SymbolLeverageTierMapper symbolLeverageTierMapper,
                                               IdGenerator idGenerator) {
        this.symbolLeverageTierMapper = symbolLeverageTierMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public List<SymbolLeverageTier> findTiers(String symbol, String groupCode) {
        List<SymbolLeverageTierRecord> records = symbolLeverageTierMapper.selectBySymbolAndGroup(symbol, groupCode);
        // group 未单独配置档位 → 回退 default 组（groupCode 本就是 default 时不重复查询）
        if (records.isEmpty() && !DEFAULT_GROUP_CODE.equals(groupCode)) {
            records = symbolLeverageTierMapper.selectBySymbolAndGroup(symbol, DEFAULT_GROUP_CODE);
        }
        return records.stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<SymbolLeverageTier> findById(long id) {
        return Optional.ofNullable(symbolLeverageTierMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public SymbolLeverageTier save(SymbolLeverageTier tier) {
        long id = tier.id() == null ? idGenerator.nextId() : tier.id();
        SymbolLeverageTier persisted = new SymbolLeverageTier(
                id, tier.symbol(), tier.groupCode(), tier.tierNo(),
                tier.notionalLower(), tier.notionalUpper(), tier.maxLeverage(), tier.mmRate(), tier.enabled());
        symbolLeverageTierMapper.insert(toRecord(persisted));
        return persisted;
    }

    @Override
    public int update(SymbolLeverageTier tier) {
        return symbolLeverageTierMapper.updateById(toRecord(tier));
    }

    @Override
    public int softDelete(long id) {
        return symbolLeverageTierMapper.softDeleteById(id, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public List<SymbolLeverageTier> page(String symbol, String groupCode, int offset, int limit) {
        return symbolLeverageTierMapper.selectPage(symbol, groupCode, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(String symbol, String groupCode) {
        return symbolLeverageTierMapper.countBy(symbol, groupCode);
    }

    private SymbolLeverageTierRecord toRecord(SymbolLeverageTier tier) {
        return new SymbolLeverageTierRecord(
                tier.id(),
                tier.symbol(),
                tier.groupCode(),
                tier.tierNo(),
                tier.notionalLower(),
                tier.notionalUpper(),
                tier.maxLeverage(),
                tier.mmRate(),
                Boolean.TRUE.equals(tier.enabled()) ? 1 : 0
        );
    }

    private SymbolLeverageTier toDomain(SymbolLeverageTierRecord record) {
        return new SymbolLeverageTier(
                record.id(),
                record.symbol(),
                record.groupCode(),
                record.tierNo(),
                record.notionalLower(),
                record.notionalUpper(),
                record.maxLeverage(),
                record.mmRate(),
                record.enabled() != null && record.enabled() == 1
        );
    }
}
