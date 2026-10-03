package com.falconx.wallet.repository;

import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
import java.util.List;
import java.util.Optional;

public interface WalletAddressProvisionDlqRepository {
    /** insert OR (event_id 已存在 PENDING 行时) attempt_count++ + 错误覆盖。 */
    void recordFailure(WalletAddressProvisionDlqEntry entry);

    Optional<WalletAddressProvisionDlqEntry> findById(long id);

    Optional<WalletAddressProvisionDlqEntry> findByEventId(String eventId);

    List<WalletAddressProvisionDlqEntry> findPaginated(Integer statusCode, Long userId, int offset, int limit);

    long countFiltered(Integer statusCode, Long userId);

    boolean markResolved(long id);
}
