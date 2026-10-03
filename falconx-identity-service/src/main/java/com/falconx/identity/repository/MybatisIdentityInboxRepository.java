package com.falconx.identity.repository;

import tools.jackson.databind.ObjectMapper;
import com.falconx.identity.repository.mapper.IdentityInboxMapper;
import com.falconx.identity.repository.mapper.record.IdentityInboxRecord;
import com.falconx.infrastructure.id.IdGenerator;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Repository;

/**
 * identity inbox Repository 的 MyBatis 实现。
 *
 * <p>该实现遵守“Repository 负责组织参数和调用 Mapper，SQL 只写在 XML”规则，
 * 用于承接低频关键事件的消费幂等保护。
 */
@Repository
public class MybatisIdentityInboxRepository implements IdentityInboxRepository {

    private final IdentityInboxMapper identityInboxMapper;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public MybatisIdentityInboxRepository(IdentityInboxMapper identityInboxMapper,
                                          IdGenerator idGenerator,
                                          ObjectMapper objectMapper) {
        this.identityInboxMapper = identityInboxMapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean existsProcessed(String eventId) {
        Integer count = identityInboxMapper.countProcessedByEventId(eventId);
        return count != null && count > 0;
    }

    @Override
    public int deleteProcessedBefore(OffsetDateTime cutoff, int limit) {
        return identityInboxMapper.deleteProcessedBefore(IdentityMybatisSupport.toLocalDateTime(cutoff), limit);
    }

    @Override
    public void saveProcessed(String eventId, String eventType, String source, Object payload, OffsetDateTime consumedAt) {
        IdentityInboxRecord record = new IdentityInboxRecord(
                idGenerator.nextId(),
                eventId,
                eventType,
                source,
                IdentityMybatisSupport.toJson(payload, objectMapper),
                1,
                IdentityMybatisSupport.toLocalDateTime(consumedAt),
                IdentityMybatisSupport.toLocalDateTime(consumedAt),
                IdentityMybatisSupport.toLocalDateTime(consumedAt)
        );
        identityInboxMapper.insertProcessed(record);
    }
}
