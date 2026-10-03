package com.falconx.wallet.repository.mapper;

import com.falconx.wallet.repository.mapper.record.WalletAddressProvisionDlqRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WalletAddressProvisionDlqMapper {

    int insert(WalletAddressProvisionDlqRecord record);

    /** 重复 event_id 时 attempt++ + 最新错误覆盖；status 保持 PENDING（除非已 RESOLVED）。 */
    int upsertOnDuplicate(@Param("eventId") String eventId,
                          @Param("lastErrorCode") String lastErrorCode,
                          @Param("lastErrorMessage") String lastErrorMessage,
                          @Param("lastAttemptAt") LocalDateTime lastAttemptAt);

    WalletAddressProvisionDlqRecord selectById(@Param("id") Long id);

    WalletAddressProvisionDlqRecord selectByEventId(@Param("eventId") String eventId);

    List<WalletAddressProvisionDlqRecord> selectPaginated(@Param("status") Integer status,
                                                          @Param("userId") Long userId,
                                                          @Param("offset") int offset,
                                                          @Param("limit") int limit);

    long countFiltered(@Param("status") Integer status, @Param("userId") Long userId);

    int markResolved(@Param("id") Long id, @Param("resolvedAt") LocalDateTime resolvedAt);
}
