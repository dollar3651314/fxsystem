package com.falconx.market.repository;

import com.falconx.market.entity.MarketTradingHoliday;
import com.falconx.market.repository.mapper.MarketTradingHolidayMapper;
import com.falconx.market.repository.mapper.record.MarketTradingHolidayRecord;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 市场节假日规则仓储的 MyBatis 实现。
 */
@Repository
public class MybatisMarketTradingHolidayRepository implements MarketTradingHolidayRepository {

    private final MarketTradingHolidayMapper marketTradingHolidayMapper;

    public MybatisMarketTradingHolidayRepository(MarketTradingHolidayMapper marketTradingHolidayMapper) {
        this.marketTradingHolidayMapper = marketTradingHolidayMapper;
    }

    @Override
    public List<MarketTradingHoliday> findAll() {
        return marketTradingHolidayMapper.selectAll().stream()
                .map(this::toDomain)
                .toList();
    }

    private MarketTradingHoliday toDomain(MarketTradingHolidayRecord record) {
        return new MarketTradingHoliday(
                record.id(),
                record.marketCode(),
                record.holidayDate(),
                record.holidayType(),
                record.openTime(),
                record.closeTime(),
                record.timezone(),
                record.holidayName(),
                record.countryCode()
        );
    }
}
