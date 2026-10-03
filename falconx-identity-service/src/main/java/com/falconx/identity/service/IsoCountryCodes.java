package com.falconx.identity.service;

import java.util.Set;

/**
 * ISO 3166-1 alpha-3 国家代码字典（STAGE-1B-USER-PROFILE）。
 *
 * <p>静态硬编码常见交易对手国家集合，避免引入额外字典依赖。后续如需 i18n 显示名 / 地区分组，
 * 可改成 Redis 字典或独立 t_country_dict 表。
 *
 * <p>仅做 alpha-3 三字母 code 合法性校验；具体业务规则（如允许/禁止国家列表）由 BBook 风控阶段
 * 9 实现，本字典不参与策略判断。
 */
public final class IsoCountryCodes {

    /** 通过 java.util.Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3) 在 JDK 21 下生成。 */
    public static final Set<String> ALPHA3 = Set.copyOf(java.util.Arrays.asList(
            "ABW","AFG","AGO","AIA","ALA","ALB","AND","ARE","ARG","ARM","ASM","ATA","ATF","ATG","AUS","AUT","AZE",
            "BDI","BEL","BEN","BES","BFA","BGD","BGR","BHR","BHS","BIH","BLM","BLR","BLZ","BMU","BOL","BRA","BRB",
            "BRN","BTN","BVT","BWA","CAF","CAN","CCK","CHE","CHL","CHN","CIV","CMR","COD","COG","COK","COL","COM",
            "CPV","CRI","CUB","CUW","CXR","CYM","CYP","CZE","DEU","DJI","DMA","DNK","DOM","DZA","ECU","EGY","ERI",
            "ESH","ESP","EST","ETH","FIN","FJI","FLK","FRA","FRO","FSM","GAB","GBR","GEO","GGY","GHA","GIB","GIN",
            "GLP","GMB","GNB","GNQ","GRC","GRD","GRL","GTM","GUF","GUM","GUY","HKG","HMD","HND","HRV","HTI","HUN",
            "IDN","IMN","IND","IOT","IRL","IRN","IRQ","ISL","ISR","ITA","JAM","JEY","JOR","JPN","KAZ","KEN","KGZ",
            "KHM","KIR","KNA","KOR","KWT","LAO","LBN","LBR","LBY","LCA","LIE","LKA","LSO","LTU","LUX","LVA","MAC",
            "MAF","MAR","MCO","MDA","MDG","MDV","MEX","MHL","MKD","MLI","MLT","MMR","MNE","MNG","MNP","MOZ","MRT",
            "MSR","MTQ","MUS","MWI","MYS","MYT","NAM","NCL","NER","NFK","NGA","NIC","NIU","NLD","NOR","NPL","NRU",
            "NZL","OMN","PAK","PAN","PCN","PER","PHL","PLW","PNG","POL","PRI","PRK","PRT","PRY","PSE","PYF","QAT",
            "REU","ROU","RUS","RWA","SAU","SDN","SEN","SGP","SGS","SHN","SJM","SLB","SLE","SLV","SMR","SOM","SPM",
            "SRB","SSD","STP","SUR","SVK","SVN","SWE","SWZ","SXM","SYC","SYR","TCA","TCD","TGO","THA","TJK","TKL",
            "TKM","TLS","TON","TTO","TUN","TUR","TUV","TWN","TZA","UGA","UKR","UMI","URY","USA","UZB","VAT","VCT",
            "VEN","VGB","VIR","VNM","VUT","WLF","WSM","YEM","ZAF","ZMB","ZWE"
    ));

    private IsoCountryCodes() {
    }

    public static boolean isValid(String alpha3) {
        return alpha3 != null && ALPHA3.contains(alpha3);
    }
}
