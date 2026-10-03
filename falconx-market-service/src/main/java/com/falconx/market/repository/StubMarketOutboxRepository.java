package com.falconx.market.repository;

import com.falconx.market.entity.MarketOutboxMessage;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("stub")
public class StubMarketOutboxRepository implements MarketOutboxRepository {

    @Override
    public MarketOutboxMessage save(MarketOutboxMessage message) {
        return message;
    }

    @Override
    public List<MarketOutboxMessage> claimDispatchableBatch(OffsetDateTime now, int limit) {
        return Collections.emptyList();
    }

    @Override
    public void markSent(String outboxId, OffsetDateTime sentAt) {
    }

    @Override
    public void markFailed(String outboxId, OffsetDateTime nextRetryAt, String lastError, int maxRetryCount) {
    }

    @Override
    public Optional<MarketOutboxMessage> findByOutboxId(String outboxId) {
        return Optional.empty();
    }

    @Override
    public int deleteSentBefore(OffsetDateTime cutoff, int limit) {
        return 0;
    }
}
