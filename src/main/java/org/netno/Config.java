package org.netno;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.json.JSONObject;

public class Config {
    private String apiKey;
    private String apiSecret;
    private String portfolioName;
    private List<String> coins;

    // New configurable conditions
    private double purchaseDropPercent;
    private double sellRisePercent;
    private int sellAfterHours;

    // Getters
    public String getApiKey() {
        return apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public String getPortfolioName() {
        return portfolioName;
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

    // Load configuration from JSON file
    public static Config loadConfig(String filePath) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        JSONObject json = new JSONObject(content);
        Config config = new Config();
        config.apiKey = json.getString("apiKey");
        config.apiSecret = json.getString("apiSecret");
        config.portfolioName = json.getString("portfolioName");
        config.coins = json.getJSONArray("coins").toList().stream()
                .map(Object::toString)
                .collect(Collectors.toList());

        // Load new conditions
        config.purchaseDropPercent = json.getDouble("purchaseDropPercent");
        config.sellRisePercent = json.getDouble("sellRisePercent");
        config.sellAfterHours = json.getInt("sellAfterHours");

        return config;
    }
}