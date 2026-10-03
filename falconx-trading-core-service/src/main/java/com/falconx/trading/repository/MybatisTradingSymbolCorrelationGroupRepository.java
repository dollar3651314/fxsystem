package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingSymbolCorrelationGroup;
import com.falconx.trading.entity.TradingSymbolCorrelationMember;
import com.falconx.trading.repository.mapper.TradingSymbolCorrelationGroupMapper;
import com.falconx.trading.repository.mapper.record.TradingSymbolCorrelationGroupRecord;
import com.falconx.trading.repository.mapper.record.TradingSymbolCorrelationMemberRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisTradingSymbolCorrelationGroupRepository implements TradingSymbolCorrelationGroupRepository {

    private final TradingSymbolCorrelationGroupMapper mapper;

    public MybatisTradingSymbolCorrelationGroupRepository(TradingSymbolCorrelationGroupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<TradingSymbolCorrelationGroup> findAllEnabled() {
        List<TradingSymbolCorrelationGroupRecord> groups = mapper.selectAllEnabled();
        if (groups.isEmpty()) return List.of();
        Map<String, List<TradingSymbolCorrelationMember>> membersByGroup = groupMembersByGroupCode(mapper.selectAllMembers());
        List<TradingSymbolCorrelationGroup> result = new ArrayList<>(groups.size());
        for (TradingSymbolCorrelationGroupRecord g : groups) {
            result.add(toDomain(g, membersByGroup.getOrDefault(g.groupCode(), List.of())));
        }
        return result;
    }

    @Override
    public List<TradingSymbolCorrelationGroup> findGroupsBySymbol(String symbol) {
        List<TradingSymbolCorrelationGroupRecord> groups = mapper.selectGroupsBySymbol(symbol);
        if (groups.isEmpty()) return List.of();
        List<String> codes = new ArrayList<>(groups.size());
        for (TradingSymbolCorrelationGroupRecord g : groups) codes.add(g.groupCode());
        Map<String, List<TradingSymbolCorrelationMember>> membersByGroup = groupMembersByGroupCode(mapper.selectMembersByGroupCodes(codes));
        List<TradingSymbolCorrelationGroup> result = new ArrayList<>(groups.size());
        for (TradingSymbolCorrelationGroupRecord g : groups) {
            result.add(toDomain(g, membersByGroup.getOrDefault(g.groupCode(), List.of())));
        }
        return result;
    }

    private static Map<String, List<TradingSymbolCorrelationMember>> groupMembersByGroupCode(
            List<TradingSymbolCorrelationMemberRecord> records) {
        Map<String, List<TradingSymbolCorrelationMember>> map = new HashMap<>();
        for (TradingSymbolCorrelationMemberRecord r : records) {
            map.computeIfAbsent(r.groupCode(), k -> new ArrayList<>())
                    .add(new TradingSymbolCorrelationMember(r.symbol(), r.weight()));
        }
        return map;
    }

    private static TradingSymbolCorrelationGroup toDomain(TradingSymbolCorrelationGroupRecord r,
                                                            List<TradingSymbolCorrelationMember> members) {
        return new TradingSymbolCorrelationGroup(
                r.groupCode(),
                r.groupName(),
                r.thresholdUsd(),
                r.enabled() != null && r.enabled() == 1,
                members
        );
    }
}
