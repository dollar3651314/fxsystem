package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingNotification;
import java.util.List;
import java.util.Optional;

public interface TradingNotificationRepository {
    void insert(TradingNotification notification);
    Optional<TradingNotification> findById(Long id);
    List<TradingNotification> findByUserId(Long userId, int offset, int limit);
    long countByUserId(Long userId);
    int countUnreadByUserId(Long userId);
    /** 标记单条已读；返回是否更新（已读再次调用返回 false）。 */
    boolean markRead(Long id, Long userId);
    /** 批量标记所有未读为已读，返回更新行数。 */
    int markAllRead(Long userId);
    /** STAGE-6-KYC Phase 4：按 relatedKey + relatedId 查重，消费端幂等用。 */
    boolean existsByRelated(String relatedKey, Long relatedId);
    /** STAGE-8-NOTIFICATION Phase 3：管理端跨用户多条件分页查询。 */
    List<TradingNotification> findByAdminFilters(Long userId, String type, String templateCode,
                                                  Integer levelCode, Integer statusCode,
                                                  java.time.LocalDateTime fromCreatedAt,
                                                  java.time.LocalDateTime toCreatedAt,
                                                  int offset, int limit);
    /** STAGE-8-NOTIFICATION Phase 3：管理端 count，与 findByAdminFilters 过滤口径一致。 */
    long countByAdminFilters(Long userId, String type, String templateCode,
                              Integer levelCode, Integer statusCode,
                              java.time.LocalDateTime fromCreatedAt,
                              java.time.LocalDateTime toCreatedAt);
}
