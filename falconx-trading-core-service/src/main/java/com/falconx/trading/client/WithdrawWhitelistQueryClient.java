package com.falconx.trading.client;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * STAGE-7-WITHDRAW：trading-core 出金白名单跨服务客户端。
 *
 * <p>历史命名为 "QueryClient"，自 Phase 2 起承担完整 CRUD（add / list / delete）。
 * 名称保留以避免 Phase 1 既有引用大面积改动。
 *
 * <p>抽象接口便于：
 * <ul>
 *   <li>对外：HTTP 实现走 wallet {@code /internal/v1/wallet/withdraw/whitelists*}</li>
 *   <li>测试：mock 实现避免 IT 启动 wallet 容器</li>
 * </ul>
 */
public interface WithdrawWhitelistQueryClient {

    /**
     * 按 ID 查询白名单（供出金提交时确权）。
     *
     * @param whitelistId 白名单主键
     * @return 白名单详情；不存在返回 {@code Optional.empty()}
     */
    Optional<WhitelistView> findById(long whitelistId);

    /**
     * 按用户列出白名单（非 REMOVED）。
     */
    java.util.List<WhitelistView> listByUser(long userId);

    /**
     * 新增白名单（status=PENDING，24h 冷静期后自动 ACTIVE）。
     *
     * @throws com.falconx.trading.error.TradingExternalRpcException 携带下游 wallet code：
     *   20021 LIMIT_EXCEEDED → trading 30051；20022 DUPLICATE → trading 30052
     */
    WhitelistView add(long userId, String network, String address, String label);

    /**
     * 删除白名单（→ REMOVED）。
     *
     * @throws com.falconx.trading.error.TradingExternalRpcException 携带下游 wallet code：
     *   20020 NOT_FOUND → trading 30053
     */
    WhitelistView delete(long userId, long whitelistId);

    /**
     * 白名单视图。{@code status} 来自 wallet 端 {@code WalletWithdrawWhitelistStatus.name()}：
     * {@code PENDING} / {@code ACTIVE} / {@code REMOVED}。
     */
    record WhitelistView(
            long id,
            long userId,
            String network,
            String address,
            String label,
            String status,
            OffsetDateTime activatedAt,
            OffsetDateTime createdAt
    ) {}
}
