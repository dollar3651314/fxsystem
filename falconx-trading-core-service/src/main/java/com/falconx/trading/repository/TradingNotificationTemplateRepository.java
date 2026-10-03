package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingNotificationTemplate;
import java.util.List;
import java.util.Optional;

/**
 * STAGE-8-NOTIFICATION Phase 1：通知模板 Repository。
 */
public interface TradingNotificationTemplateRepository {

    /** insert 新模板；code 重复将抛 DuplicateKeyException（由调用方翻译为 30061）。 */
    void insert(TradingNotificationTemplate template);

    /** code PK 查询。 */
    Optional<TradingNotificationTemplate> findByCode(String code);

    /** 分页查询，按 created_at DESC 排序；statusEnabled=null 不过滤；level=null 不过滤。 */
    List<TradingNotificationTemplate> findPaginated(Boolean enabled, Integer levelCode, int offset, int limit);

    long countFiltered(Boolean enabled, Integer levelCode);

    /** 编辑模板（code 不可改）。返回是否更新（行不存在或 enabled=0 → false 由调用方判定）。 */
    boolean update(TradingNotificationTemplate template);

    /** 软删除（enabled=0）；返回是否更新。 */
    boolean softDelete(String code);
}
