package com.falconx.canary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * STAGE-11-OBS-RECON §11.2 canary 配置。
 *
 * <p>配置 6 服务 base URL + admin 登录凭证 + recon 阈值 + 超时。
 */
@ConfigurationProperties(prefix = "canary")
public class CanaryProperties {

    private BaseUrl baseUrl = new BaseUrl();
    private Admin admin = new Admin();
    private User user = new User();
    private Recon recon = new Recon();
    private Timeout timeout = new Timeout();

    public BaseUrl getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(BaseUrl baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Recon getRecon() {
        return recon;
    }

    public void setRecon(Recon recon) {
        this.recon = recon;
    }

    public Timeout getTimeout() {
        return timeout;
    }

    public void setTimeout(Timeout timeout) {
        this.timeout = timeout;
    }

    public static class BaseUrl {
        private String gateway;
        private String identity;
        private String market;
        private String tradingCore;
        private String wallet;
        private String console;

        public String getGateway() { return gateway; }
        public void setGateway(String gateway) { this.gateway = gateway; }
        public String getIdentity() { return identity; }
        public void setIdentity(String identity) { this.identity = identity; }
        public String getMarket() { return market; }
        public void setMarket(String market) { this.market = market; }
        public String getTradingCore() { return tradingCore; }
        public void setTradingCore(String tradingCore) { this.tradingCore = tradingCore; }
        public String getWallet() { return wallet; }
        public void setWallet(String wallet) { this.wallet = wallet; }
        public String getConsole() { return console; }
        public void setConsole(String console) { this.console = console; }
    }

    public static class Admin {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class User {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Recon {
        private int maxUnmatched = 100;

        public int getMaxUnmatched() { return maxUnmatched; }
        public void setMaxUnmatched(int maxUnmatched) { this.maxUnmatched = maxUnmatched; }
    }

    public static class Timeout {
        private int defaultSeconds = 30;
        private int depositListenSeconds = 60;
        private int withdrawSeconds = 120;

        public int getDefaultSeconds() { return defaultSeconds; }
        public void setDefaultSeconds(int defaultSeconds) { this.defaultSeconds = defaultSeconds; }
        public int getDepositListenSeconds() { return depositListenSeconds; }
        public void setDepositListenSeconds(int depositListenSeconds) { this.depositListenSeconds = depositListenSeconds; }
        public int getWithdrawSeconds() { return withdrawSeconds; }
        public void setWithdrawSeconds(int withdrawSeconds) { this.withdrawSeconds = withdrawSeconds; }
    }
}
