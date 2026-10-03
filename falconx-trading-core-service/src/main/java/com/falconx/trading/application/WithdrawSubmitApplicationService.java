package com.falconx.trading.application;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.client.WithdrawDepositAddressQueryClient;
import com.falconx.trading.client.WithdrawKycLevelQueryClient;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.contract.event.TradingWithdrawRequestedEventPayload;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.entity.TradingWithdrawNetwork;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.trading.service.TradingAccountService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-7-WITHDRAW Phase 1：提交出金应用服务。
 *
 * <p>编排顺序：
 * <ol>
 *   <li>请求体合法性（amount / network / address 格式）</li>
 *   <li>幂等查询（同 userId + idempotencyKey 在 24h 内返回原 withdrawId）</li>
 *   <li>KYC 前置（identity kyc-status RPC）</li>
 *   <li>白名单确权（wallet whitelist RPC：归属 / address / network / 24h cooling）</li>
 *   <li>单笔 / 单日累计限额</li>
 *   <li>账户加锁、available 校验、freeze + ledger</li>
 *   <li>写 t_withdraw_order status=COOLING + cooling_until = now + 2h</li>
 * </ol>
 *
 * <p>失败路径全部抛 {@link TradingBusinessException}（30040-30055）；外部 RPC 异常（identity/wallet）
 * 直接向上传播（由 GlobalExceptionHandler 映射为 5xx）。
 */
@Service
public class WithdrawSubmitApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawSubmitApplicationService.class);

    /** ERC20 地址：0x + 40 hex（不强制 EIP-55 大小写，wallet 端再校验）。 */
    private static final Pattern ERC20_ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    /** TRC20 地址：T + 33 个 base58 字符（基本字符集校验，wallet 端再做 base58check 校验）。 */
    private static final Pattern TRC20_ADDRESS_PATTERN = Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");

    private static final String FIXED_CURRENCY = "USDT";

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final TradingAccountService accountService;
    private final WithdrawKycLevelQueryClient kycLevelQueryClient;
    private final WithdrawWhitelistQueryClient whitelistQueryClient;
    private final WithdrawDepositAddressQueryClient depositAddressQueryClient;
    private final TradingOutboxRepository outboxRepository;
    private final TradingCoreServiceProperties properties;
    private final IdGenerator idGenerator;

    public WithdrawSubmitApplicationService(TradingWithdrawOrderRepository withdrawOrderRepository,
                                             TradingAccountService accountService,
                                             WithdrawKycLevelQueryClient kycLevelQueryClient,
                                             WithdrawWhitelistQueryClient whitelistQueryClient,
                                             WithdrawDepositAddressQueryClient depositAddressQueryClient,
                                             TradingOutboxRepository outboxRepository,
                                             TradingCoreServiceProperties properties,
                                             IdGenerator idGenerator) {
        this.withdrawOrderRepository = withdrawOrderRepository;
        this.accountService = accountService;
        this.kycLevelQueryClient = kycLevelQueryClient;
        this.whitelistQueryClient = whitelistQueryClient;
        this.depositAddressQueryClient = depositAddressQueryClient;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public TradingWithdrawOrder submit(SubmitWithdrawCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_AMOUNT_INVALID,
                    Map.of("reason", "idempotencyKey-required"));
        }
        // 1. 幂等查询：同 userId + idempotencyKey 已存在 → 返回原单
        Optional<TradingWithdrawOrder> existing = withdrawOrderRepository
                .findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            log.info("trading.withdraw.submit.idempotent userId={} idempotencyKey={} withdrawId={}",
                    command.userId(), command.idempotencyKey(), existing.get().id());
            return existing.get();
        }

        // 2. 金额合法性（≤ 8 位小数；正数）
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_AMOUNT_INVALID);
        }
        BigDecimal truncated = amount.setScale(8, RoundingMode.DOWN);
        if (truncated.compareTo(amount) != 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_AMOUNT_INVALID,
                    Map.of("reason", "precision-exceeds-8"));
        }
        BigDecimal scaledAmount = truncated;
        if (scaledAmount.compareTo(properties.getWithdraw().getMinAmount()) < 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_AMOUNT_INVALID,
                    Map.of("amount", scaledAmount, "min", properties.getWithdraw().getMinAmount()));
        }
        if (scaledAmount.compareTo(properties.getWithdraw().getSingleLimitUsd()) > 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_AMOUNT_EXCEEDS_SINGLE_LIMIT,
                    Map.of("amount", scaledAmount, "limit", properties.getWithdraw().getSingleLimitUsd()));
        }

        // 3. 币种：一期固定 USDT
        String currency = command.currency() == null || command.currency().isBlank()
                ? FIXED_CURRENCY : command.currency();
        if (!FIXED_CURRENCY.equals(currency)) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_AMOUNT_INVALID,
                    Map.of("currency", currency, "supported", FIXED_CURRENCY));
        }

        // 4. 网络
        TradingWithdrawNetwork network = TradingWithdrawNetwork.fromValue(command.network());
        if (network == null) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NETWORK_UNSUPPORTED,
                    Map.of("network", command.network()));
        }

        // 5. 地址格式
        String targetAddress = command.targetAddress() == null ? "" : command.targetAddress().trim();
        if (!matchesAddressPattern(network, targetAddress)) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_ADDRESS_INVALID,
                    Map.of("network", network.name()));
        }

        // 6. KYC 前置（identity RPC）
        int kycLevel = kycLevelQueryClient.queryKycLevel(command.userId());
        if (kycLevel < 1) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_KYC_REQUIRED,
                    Map.of("kycLevel", kycLevel));
        }

        // 6.5 STAGE-6-KYC trigger 2：陌生地址校验
        // 「目标地址 ∉ 用户历史 CONFIRMED 入金 from_address 集合」→ 拒绝
        // KYC=1 已过，但仍要求出金地址必须是用户先前作为入金来源出现过的地址，
        // 防止「KYC 通过后改向陌生地址提现」绕过 KYC 主体一致性的弱风控。
        // 实现说明：与白名单 (step 7) 是独立两层校验：白名单只要求用户登记过该地址；
        // 陌生地址校验要求历史链上证据该用户确实从此地址入过金。两者均失败时按本步先抛。
        String walletChain = network.toWalletChain();
        java.util.List<String> historyFromAddresses =
                depositAddressQueryClient.listConfirmedFromAddresses(command.userId(), walletChain);
        if (!containsIgnoreCase(historyFromAddresses, targetAddress)) {
            log.warn("trading.withdraw.unfamiliar-address userId={} network={} addressShort={} historySize={}",
                    command.userId(), network, shortenAddress(targetAddress), historyFromAddresses.size());
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_UNFAMILIAR_ADDRESS,
                    Map.of("network", network.name(), "historySize", historyFromAddresses.size()));
        }

        // 7. 白名单确权（wallet RPC）
        WithdrawWhitelistQueryClient.WhitelistView whitelist = whitelistQueryClient.findById(command.whitelistId())
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.WITHDRAW_ADDRESS_NOT_WHITELISTED,
                        Map.of("whitelistId", command.whitelistId())));
        if (whitelist.userId() != command.userId()) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_ADDRESS_NOT_WHITELISTED,
                    Map.of("reason", "userId-mismatch"));
        }
        if (!network.name().equals(whitelist.network())) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_ADDRESS_NOT_WHITELISTED,
                    Map.of("reason", "network-mismatch"));
        }
        if (!targetAddress.equalsIgnoreCase(whitelist.address())) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_ADDRESS_NOT_WHITELISTED,
                    Map.of("reason", "address-mismatch"));
        }
        if (!"ACTIVE".equals(whitelist.status())) {
            // PENDING → 24h 冷静期未过；REMOVED → 已删除
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_WHITELIST_COOLING_NOT_PASSED,
                    Map.of("whitelistStatus", whitelist.status()));
        }

        // 8. 单日累计上限（UTC 日 + COOLING/PENDING/APPROVED/APPROVED_DELAYED/PROCESSING/COMPLETED 计入）
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime dayStart = now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime dayEnd = dayStart.plusDays(1);
        BigDecimal dailyActive = withdrawOrderRepository.sumActiveAmountForUserOnDay(
                command.userId(), dayStart, dayEnd);
        BigDecimal projectedDaily = dailyActive.add(scaledAmount);
        if (projectedDaily.compareTo(properties.getWithdraw().getDailyLimitUsd()) >= 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_AMOUNT_EXCEEDS_DAILY_LIMIT,
                    Map.of("dailyActive", dailyActive, "amount", scaledAmount,
                            "limit", properties.getWithdraw().getDailyLimitUsd()));
        }

        // 9. 账户加锁 + available 校验
        TradingAccount account = accountService.getOrCreateAccountForUpdate(command.userId(), currency);
        BigDecimal available = account.available();
        if (available.compareTo(scaledAmount) < 0) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_BALANCE_INSUFFICIENT,
                    Map.of("available", available, "amount", scaledAmount));
        }

        // 10. 写出金单（先生成 id 用于 ledger referenceNo）
        long withdrawId = idGenerator.nextId();
        OffsetDateTime coolingUntil = now.plus(properties.getWithdraw().getCoolingDuration());
        TradingWithdrawOrder order = new TradingWithdrawOrder(
                withdrawId,
                command.userId(),
                scaledAmount,
                currency,
                network,
                targetAddress,
                command.whitelistId(),
                TradingWithdrawOrderStatus.COOLING,
                coolingUntil,
                null, null, null, null, null, null, null, 0, null, null,
                command.idempotencyKey(),
                dailyActive,
                now,
                now
        );
        try {
            withdrawOrderRepository.insert(order);
        } catch (DuplicateKeyException ex) {
            // 24h TTL 之外重用同 idempotencyKey（客户端 bug），UNIQUE(user_id, idempotency_key) 命中。
            // 转成业务异常给前端明确错误码，避免回 500 让用户以为后端坏了。
            log.warn("trading.withdraw.submit.idempotency-key-reused userId={} idempotencyKey={}",
                    command.userId(), command.idempotencyKey());
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_IDEMPOTENCY_KEY_EXPIRED,
                    Map.of("idempotencyKey", command.idempotencyKey()));
        }

        // 11. 冻结余额 + 写 ledger（biz_type=WITHDRAW_FREEZE）
        accountService.freezeForWithdraw(
                command.userId(),
                currency,
                scaledAmount,
                "withdraw-freeze:" + command.idempotencyKey(),
                "withdraw:" + withdrawId,
                now
        );

        // 12. Outbox 发布 trading.withdraw.requested（admin 待办 + 审计）
        outboxRepository.save(new TradingOutboxMessage(
                null,
                "withdraw-requested:" + withdrawId,
                "trading.withdraw.requested",
                String.valueOf(command.userId()),
                new TradingWithdrawRequestedEventPayload(
                        withdrawId,
                        command.userId(),
                        scaledAmount,
                        currency,
                        network.name(),
                        targetAddress,
                        command.whitelistId(),
                        coolingUntil,
                        now
                ),
                TradingOutboxStatus.PENDING,
                now,
                null,
                0,
                now,
                null
        ));

        log.info("trading.withdraw.submit.completed userId={} withdrawId={} amount={} network={} kycLevel={} dailyActive={}",
                command.userId(), withdrawId, scaledAmount, network, kycLevel, dailyActive);
        return order;
    }

    private static boolean matchesAddressPattern(TradingWithdrawNetwork network, String address) {
        if (address == null || address.isEmpty()) return false;
        return switch (network) {
            case ERC20 -> ERC20_ADDRESS_PATTERN.matcher(address).matches();
            case TRC20 -> TRC20_ADDRESS_PATTERN.matcher(address).matches();
        };
    }

    /** ERC20 大小写不敏感（EIP-55 校验和）、TRC20 base58 严格区分 → 统一按 equalsIgnoreCase 容错。 */
    private static boolean containsIgnoreCase(java.util.List<String> addresses, String target) {
        if (addresses == null || addresses.isEmpty()) return false;
        for (String a : addresses) {
            if (a != null && a.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    /** 日志脱敏：长地址只保留前 8 + 后 6。 */
    private static String shortenAddress(String address) {
        if (address == null || address.length() <= 14) return address;
        return address.substring(0, 8) + "…" + address.substring(address.length() - 6);
    }
}
