package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-2-REALTIME-DATA Phase 2：管理端净敞口推送 payload。
 *
 * <p>由 QuoteDrivenEngine 在每条 tick 后写完 exposure snapshot 之后触发，
 * 按 symbol 200ms 节流（{@link AdminExposurePushThrottler}）。
 */
public record AdminExposureUpdatePayload(
        String symbol,
        /** STAGE-14E2 Task 1：计价币代码（QC，来源 SymbolSpec），过渡期可能为 null；对齐 E1 双币口径。 */
        String quoteCurrency,
        BigDecimal totalLongQty,
        BigDecimal totalShortQty,
        BigDecimal netExposure,
        BigDecimal netExposureUsd,
        OffsetDateTime quoteTs
) {}
