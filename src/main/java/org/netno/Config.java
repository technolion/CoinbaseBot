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
    private int maxHeldCoins;
    private double useFundsPortionPerTrade;
    private String logLevel;
    private double trailingStopLossPercent;
    private List<Double> profitLevels;
    private List<Double> averageDownSteps;

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

    public int getMaxHeldCoins() {
        return maxHeldCoins;
    }

    public double getUseFundsPortionPerTrade() {
        return useFundsPortionPerTrade;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public double getTrailingStopLossPercent() {
        return trailingStopLossPercent;
    }

    public List<Double> getProfitLevels() {
        return profitLevels;
    }

    public List<Double> getAverageDownSteps() {
        return averageDownSteps;
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
        config.maxHeldCoins = json.getInt("maxHeldCoins");
        config.useFundsPortionPerTrade = json.getDouble("useFundsPortionPerTrade");
        config.logLevel = json.getString("logLevel").toUpperCase();
        config.trailingStopLossPercent = json.getDouble("trailingStopLossPercent");
        config.profitLevels = json.getJSONArray("profitLevels").toList().stream()
                .map(obj -> Double.valueOf(obj.toString()))
                .collect(Collectors.toList());
        config.averageDownSteps = json.getJSONArray("averageDownSteps").toList().stream()
                .map(val -> Double.parseDouble(val.toString()))
                .collect(Collectors.toList());
        return config;
    }
}