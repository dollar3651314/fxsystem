package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingNotificationTemplateRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TradingNotificationTemplateMapper {

    int insert(TradingNotificationTemplateRecord record);

    TradingNotificationTemplateRecord selectByCode(@Param("code") String code);

    List<TradingNotificationTemplateRecord> selectPaginated(@Param("enabled") Integer enabled,
                                                             @Param("levelCode") Integer levelCode,
                                                             @Param("offset") int offset,
                                                             @Param("limit") int limit);

    long countFiltered(@Param("enabled") Integer enabled,
                       @Param("levelCode") Integer levelCode);

    int update(TradingNotificationTemplateRecord record);

    int softDelete(@Param("code") String code,
                   @Param("updatedAt") LocalDateTime updatedAt);
}
