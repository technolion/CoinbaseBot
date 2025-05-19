package org.netno;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.json.JSONObject;

public class Config {
    String apiKey;
    String apiSecret;
    String portfolioId;
    List<String> coins;
    double purchaseDropPercent;
    int maxHeldCoins;
    double useFundsPortionPerTrade;
    String logLevel;
    String timeZone;
    double trailingStopLossPercent;
    List<Double> profitLevels;
    List<Double> negativeProfitLevels;
    List<Double> averageDownSteps;
    int minimumProfitLevelForRegularSale;
    double marketRecoveryPercent;
    double takerFeePercentage;

    public Config(){};

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
        config.timeZone = json.has("timeZone") ? json.getString("timeZone") : "UTC";
        config.trailingStopLossPercent = json.getDouble("trailingStopLossPercent");
        config.profitLevels = json.getJSONArray("profitLevels").toList().stream()
                .map(obj -> Double.valueOf(obj.toString()))
                .collect(Collectors.toList());
        config.negativeProfitLevels = json.getJSONArray("negativeProfitLevels").toList().stream()
                .map(obj -> Double.valueOf(obj.toString()))
                .collect(Collectors.toList());
        config.averageDownSteps = json.getJSONArray("averageDownSteps").toList().stream()
                .map(val -> Double.parseDouble(val.toString()))
                .collect(Collectors.toList());
        config.minimumProfitLevelForRegularSale = json.getInt("minimumProfitLevelForRegularSale");
        config.marketRecoveryPercent = json.getDouble("marketRecoveryPercent");
        config.takerFeePercentage = json.getDouble("takerFeePercentage");
        return config;
    }

}