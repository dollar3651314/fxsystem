package com.falconx.trading.application;

import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-7-WITHDRAW Phase 1：客户端出金列表 / 详情查询应用服务。
 *
 * <p>详细契约见 docs/api/REST接口规范.md §9.2.3 / §9.2.4：
 * <ul>
 *   <li>列表：created_at DESC, id DESC；可选 status 过滤；分页 page / pageSize</li>
 *   <li>详情：30047 NOT_FOUND（不存在或不属于当前用户）</li>
 * </ul>
 */
@Service
public class WithdrawQueryApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawQueryApplicationService.class);

    private final TradingWithdrawOrderRepository withdrawOrderRepository;

    public WithdrawQueryApplicationService(TradingWithdrawOrderRepository withdrawOrderRepository) {
        this.withdrawOrderRepository = withdrawOrderRepository;
    }

    @Transactional(readOnly = true)
    public List<TradingWithdrawOrder> list(long userId, String statusName, int page, int pageSize) {
        Integer statusCode = parseStatusCode(statusName);
        int offset = (page - 1) * pageSize;
        return withdrawOrderRepository.findByUserId(userId, statusCode, offset, pageSize);
    }

    @Transactional(readOnly = true)
    public long count(long userId, String statusName) {
        Integer statusCode = parseStatusCode(statusName);
        return withdrawOrderRepository.countByUserId(userId, statusCode);
    }

    @Transactional(readOnly = true)
    public TradingWithdrawOrder getOwnedByUserOrThrow(long userId, long withdrawId) {
        Optional<TradingWithdrawOrder> found = withdrawOrderRepository.findById(withdrawId);
        if (found.isEmpty() || found.get().userId() != userId) {
            log.info("trading.withdraw.detail.not-found userId={} withdrawId={} exists={}",
                    userId, withdrawId, found.isPresent());
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NOT_FOUND,
                    Map.of("withdrawId", withdrawId));
        }
        return found.get();
    }

    private static Integer parseStatusCode(String statusName) {
        if (statusName == null || statusName.isBlank()) {
            return null;
        }
        try {
            return TradingWithdrawOrderStatus.valueOf(statusName).code();
        } catch (IllegalArgumentException ex) {
            // 未知 status 值视为无匹配；返回 -1 让 SQL 命中空集
            return -1;
        }
    }
}
