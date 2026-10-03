package com.falconx.market.scheduler;

import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateService;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FxRateStaleDetectorTests {

    @Test
    void emits_warning_when_fx_rate_stale() {
        FxRateService service = mock(FxRateService.class);
        when(service.snapshotAll()).thenReturn(List.of(
            new FxRateSnapshotPayload("EUR","USD",new BigDecimal("1.08"),
                System.currentTimeMillis() - 60_000, "GODSA","EURUSD")
        ));
        when(service.isStale("EUR","USD")).thenReturn(true);

        AtomicReference<String> warned = new AtomicReference<>();
        FxRateStaleDetector detector = new FxRateStaleDetector(service,
            (base, quote, ageSec) -> warned.set(base + "/" + quote));
        detector.scan();

        assertThat(warned.get()).isEqualTo("EUR/USD");
    }
}
