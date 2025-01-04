package org.netno;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.json.JSONObject;

public class Config {
    private String apiKey;
    private String apiSecret;
    private String portfolioId;
    private List<String> coins;
    private double purchaseDropPercent;
    private double sellRisePercent;
    private int sellAfterHours;
    private double averageDownDropPercent;
    private int maxHeldCoins;
    private double useFundsPortionPerTrade;
    private String logLevel;

    // Getters
    public String getApiKey() {
        return apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public String getPortfolioId() {
        return portfolioId;
    }

    public List<String> getCoins() {
        return coins;
    }

    public double getPurchaseDropPercent() {
        return purchaseDropPercent;
    }

    public double getSellRisePercent() {
        return sellRisePercent;
    }

    public int getSellAfterHours() {
        return sellAfterHours;
    }

    public double getAverageDownDropPercent() {
        return averageDownDropPercent;
    }

    public int getMaxHeldCoins() {
        return maxHeldCoins;
    }

    public double getUseFundsPortionPerTrade() {
        return useFundsPortionPerTrade;
    }

    public String getLogLevel() {
        return logLevel;
    }

    // Load configuration from JSON file
    public static Config loadConfig(String filePath) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        JSONObject json = new JSONObject(content);
        Config config = new Config();
        config.apiKey = json.getString("apiKey");
        config.apiSecret = json.getString("apiSecret");
        config.portfolioId = json.getString("portfolioId");
        config.coins = json.getJSONArray("coins").toList().stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        config.purchaseDropPercent = json.getDouble("purchaseDropPercent");
        config.sellRisePercent = json.getDouble("sellRisePercent");
        config.sellAfterHours = json.getInt("sellAfterHours");
        config.averageDownDropPercent = json.getDouble("averageDownDropPercent");
        config.maxHeldCoins = json.getInt("maxHeldCoins");
        config.useFundsPortionPerTrade = json.getDouble("useFundsPortionPerTrade");
        config.logLevel = json.getString("logLevel").toUpperCase();
        return config;
    }
}