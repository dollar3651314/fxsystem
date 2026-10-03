package com.falconx.wallet.support;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.tron.trident.utils.Base58Check;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.MnemonicUtils;
import org.web3j.utils.Numeric;

/**
 * 本地离线 HD 钱包派生工具（仅供测试 / 自助使用，不是生产代码）。
 *
 * <p>一份 BIP39 助记词，批量派生 index 区间内的多个子地址，ERC20 与 TRC20
 * 同步生成，派生路径与 {@link WalletTestXpubSupport} 保持一致：
 * <ul>
 *   <li>ERC20 / Ethereum : m/44'/60'/0'/0/{index}</li>
 *   <li>TRC20 / Tron     : m/44'/195'/0'/0/{index}</li>
 * </ul>
 *
 * <p>派生范围有两种指定方式：
 * <pre>
 *   方式一（改代码）：直接修改类顶部的 START_INDEX / END_INDEX，运行即可。
 *   方式二（命令行覆盖，可选，均为非负整数）：
 *     （无参）        → 使用代码里的 START_INDEX ~ END_INDEX
 *     {@code <N>}      → 派生 index 0 ~ N（共 N+1 个子地址）
 *     {@code <a> <b>}  → 派生 index a ~ b
 * </pre>
 *
 * <p>复用已有助记词：设置环境变量 {@code WALLET_MNEMONIC="word1 word2 ..."}，
 * 工具会基于它派生（用环境变量而非命令行参数，避免助记词落入 shell 历史）。
 * 未设置时每次运行生成一份全新助记词。
 *
 * <p>安全提示：助记词与私钥即资产控制权，打印结果请离线妥善保存，
 * 切勿提交到 Git、发送到任何在线服务或日志系统。
 */
public final class WalletKeyGenerationTool {

    // ==================== 在这里修改派生范围（无需命令行参数）====================
    /** 起始 index（含）。直接改这里即可。 */
    private static final int START_INDEX = 0;
    /** 结束 index（含）。例如想要 index 0~9 共 10 个地址，把它改成 9。 */
    private static final int END_INDEX = 9;
    // ==========================================================================

    private static final int HARDENED = Bip32ECKeyPair.HARDENED_BIT;

    /**
     * BIP39 熵长度（字节），决定生成的助记词个数。改这个值即可换词数，
     * 只能取下列之一（其他值 MnemonicUtils 会抛异常）：
     * <pre>
     *   16 字节(128 bit) → 12 词  ← 当前，最常见
     *   20 字节(160 bit) → 15 词
     *   24 字节(192 bit) → 18 词
     *   28 字节(224 bit) → 21 词
     *   32 字节(256 bit) → 24 词  ← 硬件钱包常用，安全冗余更高
     * </pre>
     */
    private static final int ENTROPY_BYTES = 16;

    /** BIP44 coin type：Ethereum=60，Tron=195。 */
    private static final int COIN_TYPE_ETH = 60;
    private static final int COIN_TYPE_TRON = 195;

    /** Tron 主网地址前缀字节 0x41。 */
    private static final byte TRON_ADDRESS_PREFIX = 0x41;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WalletKeyGenerationTool() {
    }

    public static void main(String[] args) {
        IndexRange range;
        try {
            range = IndexRange.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("参数错误：" + ex.getMessage());
            System.err.println("用法：[<endIndex>] 或 [<startIndex> <endIndex>]，例如 `9` 表示派生 index 0~9");
            return;
        }

        MnemonicSource source = resolveMnemonic();
        byte[] seed = MnemonicUtils.generateSeed(source.mnemonic(), "");
        Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(seed);

        printHeader(source, range);
        for (int index = range.start(); index <= range.end(); index++) {
            printChild(master, index);
        }
        System.out.println("============================================================");
    }

    private static void printHeader(MnemonicSource source, IndexRange range) {
        System.out.println("============================================================");
        System.out.println(" FalconX HD 钱包派生   仅供测试 / 离线保存");
        System.out.println("============================================================");
        System.out.println("助记词 (BIP39, 12 words)  [来源: " + source.origin() + "]:");
        System.out.println("  " + source.mnemonic());
        System.out.println("派生范围: index " + range.start() + " ~ " + range.end()
                + "（共 " + range.size() + " 个子地址，ERC20 与 TRC20 同一助记词）");
        System.out.println();
    }

    private static void printChild(Bip32ECKeyPair master, int index) {
        Bip32ECKeyPair ethKey = deriveAddressKey(master, COIN_TYPE_ETH, index);
        Bip32ECKeyPair tronKey = deriveAddressKey(master, COIN_TYPE_TRON, index);

        System.out.println("------------------------------------------------------------");
        System.out.println("[index " + index + "]");
        System.out.println("  ERC20  path : m/44'/60'/0'/0/" + index);
        System.out.println("         地址 : " + Keys.toChecksumAddress(Keys.getAddress(ethKey)));
        System.out.println("         私钥 : " + privateKeyHex(ethKey, true));
        System.out.println("  TRC20  path : m/44'/195'/0'/0/" + index);
        System.out.println("         地址 : " + tronAddress(tronKey));
        System.out.println("         私钥 : " + privateKeyHex(tronKey, false) + "   (TronLink/钱包导入用)");
        System.out.println();
    }

    /** 沿 BIP44 路径 m/44'/coin'/0'/0/index 派生到地址级密钥对。 */
    private static Bip32ECKeyPair deriveAddressKey(Bip32ECKeyPair master, int coinType, int addressIndex) {
        return Bip32ECKeyPair.deriveKeyPair(master, new int[]{
                44 | HARDENED,
                coinType | HARDENED,
                0 | HARDENED,
                0,
                addressIndex
        });
    }

    /** Tron 地址：以太坊式 20 字节地址前面拼 0x41，再做 Base58Check。 */
    private static String tronAddress(Bip32ECKeyPair key) {
        byte[] publicKey = Numeric.toBytesPadded(key.getPublicKey(), 64);
        byte[] address20 = Arrays.copyOfRange(Hash.sha3(publicKey), 12, 32);
        byte[] tronAddress = new byte[21];
        tronAddress[0] = TRON_ADDRESS_PREFIX;
        System.arraycopy(address20, 0, tronAddress, 1, address20.length);
        return Base58Check.bytesToBase58(tronAddress);
    }

    private static String privateKeyHex(Bip32ECKeyPair key, boolean withPrefix) {
        BigInteger priv = key.getPrivateKey();
        return withPrefix
                ? Numeric.toHexStringWithPrefixZeroPadded(priv, 64)
                : Numeric.toHexStringNoPrefixZeroPadded(priv, 64);
    }

    /** 优先复用环境变量 WALLET_MNEMONIC，否则新生成 128bit 熵的 12 词助记词。 */
    private static MnemonicSource resolveMnemonic() {
        String env = System.getenv("WALLET_MNEMONIC");
        if (env != null && !env.isBlank()) {
            String normalized = env.trim().replaceAll("\\s+", " ");
            if (!MnemonicUtils.validateMnemonic(normalized)) {
                throw new IllegalStateException("环境变量 WALLET_MNEMONIC 不是合法的 BIP39 助记词");
            }
            return new MnemonicSource(normalized, "环境变量 WALLET_MNEMONIC");
        }
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return new MnemonicSource(MnemonicUtils.generateMnemonic(entropy), "新生成");
    }

    private record MnemonicSource(String mnemonic, String origin) {
    }

    /** 派生 index 区间 [start, end]，闭区间，非负且 start <= end。 */
    private record IndexRange(int start, int end) {

        private int size() {
            return end - start + 1;
        }

        private static IndexRange parse(String[] args) {
            List<Integer> nums = new ArrayList<>();
            if (args != null) {
                for (String arg : args) {
                    if (arg == null || arg.isBlank()) {
                        continue;
                    }
                    try {
                        nums.add(Integer.parseInt(arg.trim()));
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("无法解析为整数: " + arg);
                    }
                }
            }
            int start;
            int end;
            switch (nums.size()) {
                case 0 -> {
                    // 未传命令行参数：使用类顶部的 START_INDEX / END_INDEX
                    start = START_INDEX;
                    end = END_INDEX;
                }
                case 1 -> {
                    start = 0;
                    end = nums.get(0);
                }
                case 2 -> {
                    start = nums.get(0);
                    end = nums.get(1);
                }
                default -> throw new IllegalArgumentException("最多接受两个整数参数");
            }
            if (start < 0 || end < 0) {
                throw new IllegalArgumentException("index 不能为负数");
            }
            if (start > end) {
                throw new IllegalArgumentException("startIndex(" + start + ") 不能大于 endIndex(" + end + ")");
            }
            return new IndexRange(start, end);
        }
    }
}
