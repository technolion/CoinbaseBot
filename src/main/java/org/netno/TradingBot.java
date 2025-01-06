package org.netno;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.coinbase.advanced.client.CoinbaseAdvancedClient;
import com.coinbase.advanced.factory.CoinbaseAdvancedServiceFactory;
import com.coinbase.advanced.model.orders.CreateOrderRequest;
import com.coinbase.advanced.model.orders.CreateOrderResponse;
import com.coinbase.advanced.model.orders.MarketIoc;
import com.coinbase.advanced.model.orders.OrderConfiguration;
import com.coinbase.advanced.orders.OrdersService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;
import java.util.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class TradingBot {

    public enum LogLevel {
        TRACE, DEBUG, INFO, ERROR
    }

    private LogLevel logLevel;
    private static final String ASSETS_FILE = "currentAssets.json";
    private static final String LOG_FILE = "trading.log";

    private final OrdersService ordersService;
    private final MarketDataFetcher marketDataFetcher;

    private final List<String> coinsToTrade;
    private final double purchaseDropPercent;
    private final double averageDownDropPercent;
    private final int maxHeldCoins;
    private final double useFundsPortionPerTrade;
    private final double trailingStopLossPercent; // Stop-loss percentage
    private final List<Double> profitLevels; // Profit levels for tracking

    private Map<String, TradeInfo> purchaseHistory;

    public TradingBot(CoinbaseAdvancedClient client, Config config) {
        this.ordersService = CoinbaseAdvancedServiceFactory.createOrdersService(client);
        this.marketDataFetcher = new MarketDataFetcher(client, config.getPortfolioId());
        double usdcBalance = marketDataFetcher.getUsdcBalance();

        this.coinsToTrade = config.getCoins();
        this.purchaseDropPercent = config.getPurchaseDropPercent();
        this.averageDownDropPercent = config.getAverageDownDropPercent();
        this.maxHeldCoins = config.getMaxHeldCoins();
        this.useFundsPortionPerTrade = config.getUseFundsPortionPerTrade();
        this.trailingStopLossPercent = config.getTrailingStopLossPercent();
        this.profitLevels = config.getProfitLevels();

        this.purchaseHistory = loadAssets();
        this.logLevel = LogLevel.valueOf(config.getLogLevel().toUpperCase());
        log("INFO", "TradingBot initialized.");
        log("INFO", String.format("Current cash: %s USDC.", usdcBalance));
    }

    public void startTrading() {
        log("INFO", "Starting trading loop...");

        // Configure the timer to run the trading loop every minute (60,000 ms)
        Timer timer = new Timer(true); // Daemon thread
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    log("DEBUG", "---- CHECKING COINS ----");
                    for (String coin : coinsToTrade) {
                        executeTrade(coin); // Process each coin
                    }
                } catch (Exception e) {
                    log("ERROR", "Error during trading loop: " + e.getMessage());
                }
            }
        }, 0, 60000); // Initial delay 0ms, repeat every 60000ms (1 minute)
    }

    public void executeTrade(String coin) throws Exception {
        String tradingPair = coin + "-USDC";

        // Retrieve the current USDC balance before making a decision
        double usdcBalance = marketDataFetcher.getUsdcBalance();

        double priceChange = marketDataFetcher.get24hPriceChangePercentage(tradingPair);
        double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

        // Check if the coin is already in the purchase history
        if (purchaseHistory.containsKey(coin)) {
            // Existing coin - handle sell or average down
            TradeInfo tradeInfo = purchaseHistory.get(coin);
            double purchasePrice = tradeInfo.getPurchasePrice();
            double heldAmount = tradeInfo.getAmount();

            // Dynamic stop-loss and profit tracking variables
            double highestPrice = tradeInfo.getHighestPrice();
            double trailingStopLoss = tradeInfo.getTrailingStopLoss();
            int profitLevelIndex = tradeInfo.getProfitLevelIndex();
            boolean hasAveragedDown = tradeInfo.hasAveragedDown();

            // Update trailing stop-loss based on price movement
            tradeInfo.updateStopLoss(currentPrice, trailingStopLossPercent);

            // Calculate percentage difference between current price and purchase price
            double priceDifference = ((currentPrice - purchasePrice) / purchasePrice) * 100;

            // Display current status of the coin
            log("DEBUG", String.format(
                    "Status for %s: Held Amount: %.6f, Purchase Price: %.6f, Current Price: %.6f, Highest Price: %.6f, Stop-Loss: %.6f, Difference: %.2f%%, Averaged Down: %b",
                    coin, heldAmount, purchasePrice, currentPrice, highestPrice, trailingStopLoss, priceDifference,
                    tradeInfo.hasAveragedDown()));
            if(profitLevelIndex > 0) {
                log("DEBUG", String.format("YEAH! profit level %s reached!", profitLevelIndex));
            }

            // 1. Average Down Logic (Only Once Per Coin)
            boolean averageDownCondition = !hasAveragedDown &&
                    currentPrice <= purchasePrice * (1 + (averageDownDropPercent / 100.0));
            if (averageDownCondition) {
                log("INFO", String.format("Averaging down for %s at %.6f. Current Stop-Loss: %.6f",
                        coin, currentPrice, trailingStopLoss));

                // Calculate funds to spend for averaging down
                double fundsToSpend = usdcBalance >= (heldAmount * currentPrice)
                        ? (heldAmount * currentPrice)
                        : usdcBalance * useFundsPortionPerTrade;

                buyCoin(coin, tradingPair, fundsToSpend, currentPrice, true);
                return; // Skip further processing
            }

            // 2. Profit Level Handling
            if (profitLevelIndex < profitLevels.size() &&
                    currentPrice >= purchasePrice * (1 + profitLevels.get(profitLevelIndex) / 100.0)) {
                // Move to the next profit level
                tradeInfo.setProfitLevelIndex(profitLevelIndex + 1);
                log("INFO", String.format("Reached profit level %d (%.2f%%) for %s. Waiting for next level...",
                        profitLevelIndex + 1, profitLevels.get(profitLevelIndex), coin));
                saveAssets(); // Save changes
                return; // Skip further processing
            }

            // 3. Stop-Loss or Profit Drop Handling
            double previousProfitLevel = profitLevelIndex > 0
                    ? purchasePrice * (1 + profitLevels.get(profitLevelIndex - 1) / 100.0)
                    : purchasePrice;

            if (currentPrice < trailingStopLoss) {
                log("INFO", String.format("Selling %s due to stop-loss. Current: %.6f, Stop-Loss: %.6f",
                        coin, currentPrice, trailingStopLoss));
                sellCoin(coin, tradingPair, heldAmount);
                return;
            } else if (profitLevelIndex > 0 && currentPrice < previousProfitLevel * 0.995) { // Allow 0.5% drop
                log("INFO", String.format("Selling %s due to profit drop. Current: %.6f, Allowed Drop: %.6f",
                        coin, currentPrice, previousProfitLevel * 0.999));
                sellCoin(coin, tradingPair, heldAmount);
                return;
            }

            // 4. Hold the coin if no condition is met
            log("DEBUG", String.format("Holding %s. Price above stop-loss and profit levels. Skipping SALE.", coin));

        } else {
            // Initial Purchase Logic - Only buy if not already held
            if (purchaseHistory.size() >= maxHeldCoins) {
                log("DEBUG",
                        String.format("Max held coins limit (%d) reached. Skipping BUY for %s.", maxHeldCoins, coin));
                return; // Skip the BUY operation if limit is reached
            }

            log("DEBUG", String.format("Checking BUY condition for %s. Price Change: %.2f%%", coin, priceChange));
            if (priceChange <= purchaseDropPercent) {
                double fundsToSpend = usdcBalance * useFundsPortionPerTrade;
                log("INFO", String.format("Buying %s for %.6f USDC at %.6f per unit.",
                        coin, fundsToSpend, currentPrice));
                buyCoin(coin, tradingPair, fundsToSpend, currentPrice, false); // Initial buy
            } else {
                log("DEBUG", String.format("No significant price drop for %s. Skipping BUY.", coin));
            }
        }
    }

    private void buyCoin(String coin, String tradingPair, double amountToSpend, double currentPrice, boolean update)
            throws Exception {
        // Fetch precision requirement for the trading pair
        double precision = marketDataFetcher.getBasePrecision(tradingPair);

        // Calculate how many coins can be bought for the USD amount
        double amountToBuy = amountToSpend / currentPrice;

        // Calculate the number of decimal places based on the precision value
        int decimalPlaces = BigDecimal.valueOf(precision)
                .stripTrailingZeros()
                .scale();

        // Round the number of coins to the required precision
        String roundedBaseSize = BigDecimal.valueOf(amountToBuy)
                .setScale(decimalPlaces, RoundingMode.HALF_DOWN)
                .toPlainString();

        // Create the OrderConfiguration using baseSize
        OrderConfiguration config = new OrderConfiguration();
        config.setMarketMarketIoc(new MarketIoc.Builder()
                .baseSize(roundedBaseSize) // Use baseSize instead of quoteSize
                .build());

        // Build the order request
        CreateOrderRequest orderRequest = new CreateOrderRequest.Builder()
                .clientOrderId(LocalDateTime.now().toString())
                .productId(tradingPair)
                .side("BUY")
                .orderConfiguration(config)
                .build();

        // Execute the order
        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);

        if (orderResponse.isSuccess()) {
            log("INFO", String.format("Bought %s coins of %s. Order ID: %s", roundedBaseSize, coin,
                    orderResponse.getSuccessResponse().getOrderId()));

            // If this is an update (averaging down)
            if (update) {
                TradeInfo tradeInfo = purchaseHistory.get(coin);
                if (tradeInfo != null) {
                    // Update purchase price and amount
                    tradeInfo.updatePurchase(currentPrice, Double.parseDouble(roundedBaseSize));

                    // Mark as averaged down
                    tradeInfo.setAveragedDown(true);

                    // Update stop-loss based on the new average purchase price
                    double updatedStopLoss = tradeInfo.getPurchasePrice() * (1 - trailingStopLossPercent / 100.0);
                    tradeInfo.setTrailingStopLoss(updatedStopLoss);

                    log("INFO", String.format("Updated stop-loss for %s to %.6f", coin, updatedStopLoss));
                }
            } else { // Initial purchase
                // Calculate the initial stop-loss price
                double initialStopLoss = currentPrice * (1 - trailingStopLossPercent / 100.0);

                // Add new entry to the purchase history
                purchaseHistory.put(coin,
                        new TradeInfo(currentPrice, Double.parseDouble(roundedBaseSize), LocalDateTime.now(),
                                currentPrice, // Highest price starts at the purchase price
                                initialStopLoss, // Initial stop-loss
                                0, // Profit level index
                                false // Averaged down flag
                        ));
                log("INFO", String.format("Initial stop-loss for %s set to %.6f", coin, initialStopLoss));
            }

            // Save updated assets to file
            saveAssets();
        } else {
            log("ERROR", String.format("Buying %s failed!", coin));
            log("ERROR", String.format(orderResponse.getErrorResponse().getError()));
        }
    }

    private void sellCoin(String coin, String tradingPair, double amount) throws Exception {
        // Use the exact amount from purchase history without rounding
        String exactSize = Double.toString(amount);
    
        // Create the OrderConfiguration using baseSize
        OrderConfiguration config = new OrderConfiguration();
        config.setMarketMarketIoc(new MarketIoc.Builder()
                .baseSize(exactSize) // Use exact amount
                .build());
    
        // Build the order request
        CreateOrderRequest orderRequest = new CreateOrderRequest.Builder()
                .clientOrderId(LocalDateTime.now().toString())
                .productId(tradingPair)
                .side("SELL")
                .orderConfiguration(config)
                .build();
    
        // Get the current price for profit/loss calculation
        double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);
    
        // Retrieve the purchase price for profit calculation
        TradeInfo tradeInfo = purchaseHistory.get(coin);
        double purchasePrice = tradeInfo.getPurchasePrice();
    
        // Calculate profit or loss in USDC
        double totalPurchaseValue = tradeInfo.getAmount() * purchasePrice;
        double totalSellValue = amount * currentPrice;
        double profitOrLoss = totalSellValue - totalPurchaseValue;
    
        // Execute the order
        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);
        if (orderResponse.isSuccess()) {
            log("INFO", String.format(
                    "Sold %s coins of %s. Order ID: %s, Profit/Loss: %.2f USDC",
                    exactSize, coin, orderResponse.getSuccessResponse().getOrderId(), profitOrLoss));
    
            // Remove the coin from purchase history
            purchaseHistory.remove(coin);
            saveAssets();
        } else {
            log("ERROR", String.format("Selling %s failed!", coin));
            log("ERROR", String.format(orderResponse.getErrorResponse().getError()));
        }
    }

    // Save purchase history to file
    private void saveAssets() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule()); // Register Java 8 time module
            mapper.writeValue(new File(ASSETS_FILE), purchaseHistory);
        } catch (IOException e) {
            log("ERROR", String.format("Failed to save purchase history: " + e.getMessage()));
        }
    }

    // Load purchase history from file
    private Map<String, TradeInfo> loadAssets() {
        try {
            File file = new File(ASSETS_FILE);
            if (file.exists()) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule()); // Register Java 8 time module
                return mapper.readValue(file, new TypeReference<Map<String, TradeInfo>>() {
                });
            }
        } catch (IOException e) {
            log("ERROR", String.format("Failed to load purchase history: " + e.getMessage()));
        }
        return new HashMap<>();
    }

    private void log(String level, String message) {
        try {
            LogLevel currentLevel = LogLevel.valueOf(level.toUpperCase());

            // Print log to console
            System.out.printf("[%s] [%s] %s%n", LocalDateTime.now(), level, message);

            // Write to file only if log level is met or surpassed
            if (currentLevel.ordinal() >= logLevel.ordinal()) {
                try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE, true))) {
                    out.printf("[%s] [%s] %s%n", LocalDateTime.now(), level, message);
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to write log: " + e.getMessage());
        }
    }
}