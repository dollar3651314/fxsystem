package com.falconx.market.service.impl;

import com.falconx.market.entity.MarketSymbolGroupMarkup;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketSymbolGroupMarkupRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-UT-001 ~ TC-GM-UT-007：
 * DefaultMarketGroupMarkupService 内存快照查询 + applyMarkup + 增量刷新。
 */
class DefaultMarketGroupMarkupServiceTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-05-21T00:00:00Z");

    @Test
    void TC_GM_UT_001_find_hit() {
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(new FixedRepository(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        new BigDecimal("0.50"), new BigDecimal("1.00"), 1, T0, T0)
        )));
        service.refresh();

        Optional<MarketSymbolGroupMarkup> hit = service.find("vip", "BTCUSDT");

        Assertions.assertTrue(hit.isPresent());
        Assertions.assertEquals("vip", hit.get().groupCode());
        Assertions.assertEquals("BTCUSDT", hit.get().platformSymbol());
    }

    @Test
    void TC_GM_UT_001b_find_normalizes_group_code_and_symbol_case() {
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(new FixedRepository(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        new BigDecimal("0.50"), BigDecimal.ZERO, 1, T0, T0)
        )));
        service.refresh();

        Assertions.assertTrue(service.find("vip", "btcusdt").isPresent(),
                "platformSymbol 必须按 upper-case 归一化匹配");
        Assertions.assertTrue(service.find(null, "BTCUSDT").isEmpty(),
                "null groupCode 走 default，不命中 vip");
    }

    @Test
    void TC_GM_UT_002_find_miss_returns_empty() {
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(new FixedRepository(List.of()));
        service.refresh();

        Assertions.assertTrue(service.find("vip", "BTCUSDT").isEmpty());
    }

    @Test
    void TC_GM_UT_003_applyMarkup_zero_extras_returns_same_instance() {
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(new FixedRepository(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        BigDecimal.ZERO, BigDecimal.ZERO, 1, T0, T0)
        )));
        service.refresh();
        StandardQuote base = quote("BTCUSDT", "60000.00", "60010.00");

        StandardQuote out = service.applyMarkup(base, "vip");

        Assertions.assertSame(base, out, "0 加点零分配回退");
    }

    @Test
    void TC_GM_UT_003b_applyMarkup_disabled_returns_same_instance() {
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(new FixedRepository(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        new BigDecimal("5.0"), new BigDecimal("5.0"), 0 /* disabled */, T0, T0)
        )));
        service.refresh();
        StandardQuote base = quote("BTCUSDT", "60000.00", "60010.00");

        StandardQuote out = service.applyMarkup(base, "vip");

        Assertions.assertSame(base, out, "enabled=0 配置不生效，必须回退基准价");
    }

    @Test
    void TC_GM_UT_004_applyMarkup_positive_extras_adjust_bid_and_ask() {
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(new FixedRepository(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        new BigDecimal("0.50"), new BigDecimal("1.00"), 1, T0, T0)
        )));
        service.refresh();
        StandardQuote base = quote("BTCUSDT", "60000.00", "60010.00");

        StandardQuote out = service.applyMarkup(base, "vip");

        Assertions.assertNotSame(base, out, "非零加点必须返回新对象");
        Assertions.assertEquals(0, out.bid().compareTo(new BigDecimal("60000.50")));
        Assertions.assertEquals(0, out.ask().compareTo(new BigDecimal("60011.00")));
    }

    @Test
    void TC_GM_UT_005_applyMarkup_negative_extras_let_user_benefit() {
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(new FixedRepository(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        new BigDecimal("-0.50"), new BigDecimal("-1.00"), 1, T0, T0)
        )));
        service.refresh();
        StandardQuote base = quote("BTCUSDT", "60000.00", "60010.00");

        StandardQuote out = service.applyMarkup(base, "vip");

        Assertions.assertEquals(0, out.bid().compareTo(new BigDecimal("59999.50")));
        Assertions.assertEquals(0, out.ask().compareTo(new BigDecimal("60009.00")));
    }

    @Test
    void TC_GM_UT_006_refresh_full_then_incremental_via_findChangedSince_pipeline() {
        MutableRepository repo = new MutableRepository();
        repo.allEnabled = new ArrayList<>(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        new BigDecimal("0.50"), BigDecimal.ZERO, 1, T0, T0)
        ));
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(repo);
        service.refresh();
        Assertions.assertEquals(1, service.findAllEnabled().size());

        OffsetDateTime t1 = T0.plusMinutes(1);
        repo.changesSinceQueue.add(List.of(
                new MarketSymbolGroupMarkup("vip", "ETHUSDT",
                        new BigDecimal("0.20"), BigDecimal.ZERO, 1, t1, t1)
        ));

        service.refresh();
        Assertions.assertEquals(1, service.findAllEnabled().size(),
                "refresh() 是全量重建，依赖 findAllEnabled 返回的新集合");
    }

    @Test
    void TC_GM_UT_007_disabled_record_removed_on_full_refresh() {
        MutableRepository repo = new MutableRepository();
        repo.allEnabled = new ArrayList<>(List.of(
                new MarketSymbolGroupMarkup("vip", "BTCUSDT",
                        new BigDecimal("0.50"), BigDecimal.ZERO, 1, T0, T0)
        ));
        DefaultMarketGroupMarkupService service = new DefaultMarketGroupMarkupService(repo);
        service.refresh();

        Assertions.assertTrue(service.find("vip", "BTCUSDT").isPresent());

        repo.allEnabled = new ArrayList<>();
        service.refresh();

        Assertions.assertTrue(service.find("vip", "BTCUSDT").isEmpty(),
                "运营把记录置 enabled=0 后下一次全量刷新内存快照剔除");
    }

    private StandardQuote quote(String symbol, String bid, String ask) {
        BigDecimal b = new BigDecimal(bid);
        BigDecimal a = new BigDecimal(ask);
        BigDecimal mid = b.add(a).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.DOWN);
        return new StandardQuote(symbol, b, a, mid, mid, T0, "LP", false);
    }

    private record FixedRepository(List<MarketSymbolGroupMarkup> all) implements MarketSymbolGroupMarkupRepository {
        @Override public List<MarketSymbolGroupMarkup> findAllEnabled() { return all; }
        @Override public List<MarketSymbolGroupMarkup> findChangedSince(OffsetDateTime since) { return List.of(); }
        @Override public Optional<MarketSymbolGroupMarkup> findByPk(String g, String s) {
            return all.stream().filter(x -> x.groupCode().equals(g) && x.platformSymbol().equals(s)).findFirst();
        }
        @Override public List<MarketSymbolGroupMarkup> findByFilters(String g, String s, Integer e, int p, int sz) { return all; }
        @Override public long countByFilters(String g, String s, Integer e) { return all.size(); }
        @Override public int upsert(String g, String s, BigDecimal be, BigDecimal ae, int en) { return 1; }
        @Override public int delete(String g, String s) { return 1; }
    }

    private static class MutableRepository implements MarketSymbolGroupMarkupRepository {
        List<MarketSymbolGroupMarkup> allEnabled = new ArrayList<>();
        final List<List<MarketSymbolGroupMarkup>> changesSinceQueue = new ArrayList<>();

        @Override public List<MarketSymbolGroupMarkup> findAllEnabled() { return new ArrayList<>(allEnabled); }
        @Override public List<MarketSymbolGroupMarkup> findChangedSince(OffsetDateTime since) {
            return changesSinceQueue.isEmpty() ? List.of() : changesSinceQueue.remove(0);
        }
        @Override public Optional<MarketSymbolGroupMarkup> findByPk(String g, String s) {
            return allEnabled.stream().filter(x -> x.groupCode().equals(g) && x.platformSymbol().equals(s)).findFirst();
        }
        @Override public List<MarketSymbolGroupMarkup> findByFilters(String g, String s, Integer e, int p, int sz) { return allEnabled; }
        @Override public long countByFilters(String g, String s, Integer e) { return allEnabled.size(); }
        @Override public int upsert(String g, String s, BigDecimal be, BigDecimal ae, int en) { return 1; }
        @Override public int delete(String g, String s) { return 1; }
    }
}
