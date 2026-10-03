package com.falconx.market;

import com.falconx.market.application.MarketSymbolAdminApplicationService;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.repository.mapper.record.MarketTradingHolidayRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionRecord;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * market-service 最小上下文测试。
 *
 * <p>该测试用于确保市场服务在接入真实 MySQL、Redis 和 ClickHouse 后，
 * 仍然可以稳定完成基础装配。
 */
@ActiveProfiles("stage5")
@ContextConfiguration(initializers = MarketTestDatabaseInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                MarketServiceApplication.class,
                MarketMybatisTestSupportConfiguration.class
        },
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_market_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx"
        }
)
class MarketServiceApplicationTests {

    @Autowired
    private MarketSymbolAdminMapper marketSymbolAdminMapper;

    @Autowired
    private MarketSymbolAdminApplicationService marketSymbolAdminApplicationService;

    @Test
    void contextLoads() {
    }

    @Test
    void shouldMapAdminTradingSessionsWithPrimitiveConstructorTypes() {
        List<MarketTradingSessionRecord> sessions = marketSymbolAdminMapper.selectTradingSessions("BTCUSD");

        Assertions.assertEquals(7, sessions.size());
        Assertions.assertTrue(sessions.stream().allMatch(MarketTradingSessionRecord::enabled));
    }

    @Test
    void shouldRefreshTradingScheduleAfterMarketHolidayUpsert() {
        LocalDate holidayDate = LocalDate.now().plusYears(10);

        MarketTradingHolidayRecord holiday = Assertions.assertDoesNotThrow(() ->
                marketSymbolAdminApplicationService.upsertMarketHoliday(
                        null,
                        "US_STOCK",
                        holidayDate,
                        1,
                        null,
                        null,
                        "America/New_York",
                        "Mapper Regression Holiday",
                        "US"
                ));

        Assertions.assertEquals("US_STOCK", holiday.marketCode());
        Assertions.assertEquals(holidayDate, holiday.holidayDate());
        Assertions.assertEquals(1, holiday.holidayType());
    }
}
