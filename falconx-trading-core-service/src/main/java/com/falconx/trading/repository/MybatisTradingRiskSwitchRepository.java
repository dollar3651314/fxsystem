package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.repository.mapper.TradingRiskSwitchMapper;
import com.falconx.trading.repository.mapper.record.TradingRiskSwitchRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisTradingRiskSwitchRepository implements TradingRiskSwitchRepository {

    private final TradingRiskSwitchMapper mapper;

    public MybatisTradingRiskSwitchRepository(TradingRiskSwitchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TradingRiskSwitch> findByKey(String switchKey) {
        return Optional.ofNullable(toDomain(mapper.selectByKey(switchKey)));
    }

    @Override
    public List<TradingRiskSwitch> findAll() {
        return mapper.selectAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void upsert(TradingRiskSwitch riskSwitch) {
        mapper.upsert(new TradingRiskSwitchRecord(
                riskSwitch.switchKey(),
                riskSwitch.enabled() ? 1 : 0,
                riskSwitch.reason(),
                riskSwitch.updatedBy(),
                TradingMybatisSupport.toLocalDateTime(riskSwitch.createdAt()),
                TradingMybatisSupport.toLocalDateTime(riskSwitch.updatedAt())
        ));
    }

    private TradingRiskSwitch toDomain(TradingRiskSwitchRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingRiskSwitch(
                record.switchKey(),
                record.enabled() != null && record.enabled() == 1,
                record.reason(),
                record.updatedBy(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(record.updatedAt())
        );
    }
}
