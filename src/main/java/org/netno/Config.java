package org.netno;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.json.JSONObject;

public class Config {
    String apiKey;                              //our Coinbase API key
    String apiSecret;                           //our Coinbase API secret
    String portfolioId;                         //our Coinbase POrtfolio ID
    List<String> coins;                         //List of Coins to trade
    double purchaseDropPercent;                 //minimum percentage a coin price has to drop compared to previous day in order to buy it
    int maxHeldCoins;                           //maximum number of coins to hold
    double useFundsPortionPerTrade;             //Percentage of our fund to use for an initial purchase
    String logLevel;                            //the log level
    String timeZone;                            //the timezone for displaying dates on th elog and the web interface
    List<Double> negativeProfitLevels;          //list of performance percentages to reach ofter holding a coin for a multiple of a week
    List<Double> averageDownSteps;              //list of performance percentages on which the bot buys the same amount of an existing coin to lower average purchase price
    double minimumProfitPercentage;             //minimum performance percentage to reach in order to sell
    double stopLossSalePercentage;              //percentage down from highest price to sell    
    double takerFeePercentage;                  //percentage of take fee to visualize realistic net performance

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
        config.negativeProfitLevels = json.getJSONArray("negativeProfitLevels").toList().stream()
                .map(obj -> Double.valueOf(obj.toString()))
                .collect(Collectors.toList());
        config.averageDownSteps = json.getJSONArray("averageDownSteps").toList().stream()
                .map(val -> Double.parseDouble(val.toString()))
                .collect(Collectors.toList());
        config.minimumProfitPercentage = json.getDouble("minimumProfitPercentage");
        config.stopLossSalePercentage = json.getDouble("stopLossSalePercentage");
        config.takerFeePercentage = json.getDouble("takerFeePercentage");
        return config;
    }

}