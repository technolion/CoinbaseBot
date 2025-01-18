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
    private final int maxHeldCoins;
    private final double useFundsPortionPerTrade;
    private final double trailingStopLossPercent; // Stop-loss percentage
    private final List<Double> profitLevels; // Profit levels for tracking
    private final List<Double> averageDownSteps;

    private Map<String, TradeInfo> purchaseHistory;

    public boolean initialized = false;

    public TradingBot(CoinbaseAdvancedClient client, Config config) {
        this.ordersService = CoinbaseAdvancedServiceFactory.createOrdersService(client);
        this.marketDataFetcher = new MarketDataFetcher(client, config.getPortfolioId());
        double usdcBalance = marketDataFetcher.getUsdcBalance();

        this.coinsToTrade = config.getCoins();
        this.purchaseDropPercent = config.getPurchaseDropPercent();
        this.maxHeldCoins = config.getMaxHeldCoins();
        this.useFundsPortionPerTrade = config.getUseFundsPortionPerTrade();
        this.trailingStopLossPercent = config.getTrailingStopLossPercent();
        this.profitLevels = config.getProfitLevels();
        this.averageDownSteps = config.getAverageDownSteps();

        try {
            this.purchaseHistory = loadAssets();
        } catch (Exception e) {
            return;
        }
        this.logLevel = LogLevel.valueOf(config.getLogLevel().toUpperCase());
        log("INFO", "TradingBot initialized.");
        log("INFO", String.format("Current cash: %s USDC.", usdcBalance));
        initialized = true;
    }

    // for unit tests only
    public TradingBot(OrdersService orderService, MarketDataFetcher marketDataFetcher, Config config,
            Map<String, TradeInfo> purchaseHistory) {
        this.ordersService = orderService;
        this.marketDataFetcher = marketDataFetcher;
        double usdcBalance = marketDataFetcher.getUsdcBalance();

        this.coinsToTrade = config.getCoins();
        this.purchaseDropPercent = config.getPurchaseDropPercent();
        this.maxHeldCoins = config.getMaxHeldCoins();
        this.useFundsPortionPerTrade = config.getUseFundsPortionPerTrade();
        this.trailingStopLossPercent = config.getTrailingStopLossPercent();
        this.profitLevels = config.getProfitLevels();
        this.averageDownSteps = config.getAverageDownSteps();

        this.purchaseHistory = purchaseHistory;
        this.logLevel = LogLevel.valueOf(config.getLogLevel().toUpperCase());
        log("INFO", "TradingBot initialized.");
        log("INFO", String.format("Current cash: %s USDC.", usdcBalance));
    }

    public Map<String, TradeInfo> getPurchaseHistory() {
        return purchaseHistory;
    }
    
    public MarketDataFetcher getMarketDataFetcher() {
        return marketDataFetcher;
    }

    int getNumberOfHeldCoins() {
        return purchaseHistory.size();
    }

    public List<Double> getProfitLevels() {
        return profitLevels;
    }

    public List<Double> getAverageDownSteps() {
        return averageDownSteps;
    }

    /**
     * Calculates the total USDC value of all held coins.
     * The value is based on the held amount multiplied by the average purchase
     * price.
     *
     * @return The total USDC value of all held coins.
     */
    public double getTotalUsdcValueOfHeldCoins() {
        double totalUsdcValue = 0.0;

        for (Map.Entry<String, TradeInfo> entry : purchaseHistory.entrySet()) {
            TradeInfo tradeInfo = entry.getValue();
            double coinValue = tradeInfo.getAmount() * tradeInfo.getPurchasePrice();
            totalUsdcValue += coinValue;
        }
        return totalUsdcValue;
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
                    // Retrieve the current USDC balance before making a decision
                    double usdcBalance = marketDataFetcher.getUsdcBalance();
                    log("DEBUG", String.format("Current cash: %s USDC.", usdcBalance));
                    for (String coin : coinsToTrade) {
                        executeTrade(coin, usdcBalance); // Process each coin
                    }
                } catch (Exception e) {
                    log("ERROR", "Error during trading loop: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, 0, 30000); // Initial delay 0ms, repeat every 30000ms (30 seconds)
    }

    public void executeTrade(String coin, double usdcBalance) throws Exception {
        String tradingPair = coin + "-USDC";

        // Check if the coin is already in the purchase history
        if (purchaseHistory.containsKey(coin)) {
            // Existing coin - handle sell or average down

            double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

            TradeInfo tradeInfo = purchaseHistory.get(coin);

            // Update trailing stop-loss based on price movement
            tradeInfo.updateStopLoss(currentPrice, trailingStopLossPercent);

            // Calculate percentage difference between current price and purchase price
            double priceDifference = ((currentPrice - tradeInfo.purchasePrice) / tradeInfo.purchasePrice) * 100;

            // Display current status of the coin
            log("DEBUG", String.format(
                    "Status for %s: Held Amount: %.6f, Purchase Price: %.6f, Current Price: %.6f, Profit Level: %d (%.2f%%), Highest Price: %.6f, Stop-Loss: %.6f, Difference: %.2f%%, Averaged Down Step: %d",
                    coin, tradeInfo.amount, tradeInfo.purchasePrice, currentPrice, tradeInfo.profitLevelIndex, profitLevels.get(tradeInfo.profitLevelIndex), tradeInfo.highestPrice, tradeInfo.trailingStopLoss, priceDifference, tradeInfo.averageDownStepIndex));

            // 1. Average Down Logic
            boolean canAverageDown = false;
            double nextAverageDownPrice = 0;
            if (tradeInfo.getAverageDownStepIndex() < (averageDownSteps.size() -1) ) {
                canAverageDown = true;
                double nextAverageDownDropPercentage = averageDownSteps.get(tradeInfo.getAverageDownStepIndex() + 1);
                nextAverageDownPrice = tradeInfo.purchasePrice - (tradeInfo.purchasePrice / 100 * nextAverageDownDropPercentage);
            }

            if (canAverageDown && currentPrice <= nextAverageDownPrice) {
                log("INFO", String.format("Averaging down for %s at %.6f.",
                        coin, currentPrice));

                double fundsToSpend = usdcBalance >= (tradeInfo.amount * currentPrice)
                        ? (tradeInfo.amount * currentPrice)
                        : getPurchaseMoney(usdcBalance, useFundsPortionPerTrade);

                buyCoin(coin, tradingPair, fundsToSpend, currentPrice, true);

                // Move to the next step
                tradeInfo.increaseAverageDown();
                saveAssets();
                log("INFO", String.format("Held coin %s is now a average down %d.",
                coin, tradeInfo.averageDownStepIndex));

                return; // Skip further processing
            }

            // 2. Profit Level Handling
            boolean canIncreaseProfitLevel = false;
            double nextProfitLevelPrice = 10000000; // impossible high number
            if (tradeInfo.profitLevelIndex < (profitLevels.size() -1 )) {
                // not yet on maximum profit level
                canIncreaseProfitLevel = true;
                double nextProfitLevelPercentage = profitLevels.get(tradeInfo.profitLevelIndex + 1);
                nextProfitLevelPrice = tradeInfo.purchasePrice + (tradeInfo.purchasePrice / 100 * nextProfitLevelPercentage);
            }

            if (canIncreaseProfitLevel && currentPrice >= nextProfitLevelPrice) {
                // Move to the next profit level
                tradeInfo.increaseProfitLevel();
                saveAssets(); // Save changes

                if (tradeInfo.profitLevelIndex == (profitLevels.size() - 1)) {
                    log("INFO", String.format("Selling %s due to max profit level reached. Current: %.6f", coin,
                            currentPrice));
                    sellCoin(coin, tradingPair, tradeInfo.amount);
                } else {
                    log("INFO", String.format("Reached profit level %d (%.2f%%) for %s. Waiting for next level...",
                            tradeInfo.profitLevelIndex, profitLevels.get(tradeInfo.profitLevelIndex), coin));
                }

                return; // Skip further processing
            }

            // 3. Recovery Sale Handling
            double firstRealProfitLevelPercentage = profitLevels.get(1);
            double firstRealProfitLevelPrice = tradeInfo.purchasePrice + (tradeInfo.purchasePrice / 100 * firstRealProfitLevelPercentage);

            if(tradeInfo.averageDownStepIndex > 1 && currentPrice >= firstRealProfitLevelPrice ) {
                // coin has been previously averaged down twice and now reached first real profit level
                // selling to free funds and lower risk
                log("INFO", String.format(
                    "Selling %s  at %.6f due recovery from averaging down.",
                    coin, currentPrice));
                 sellCoin(coin, tradingPair, tradeInfo.amount);
            }

            // 4. Stop-Loss or Profit Drop Handling
            double previousProfitLevelPrice = 0;
            if (tradeInfo.profitLevelIndex > 0) {
                double previousProfitLevelPercentage = profitLevels.get(tradeInfo.profitLevelIndex - 1);
                previousProfitLevelPrice = tradeInfo.purchasePrice + (tradeInfo.purchasePrice / 100 * previousProfitLevelPercentage);
            }

            if (currentPrice < tradeInfo.trailingStopLoss) {
                //price dropped below stop loss
                log("INFO", String.format("Selling %s due to stop-loss. Current: %.6f, Stop-Loss: %.6f",
                        coin, currentPrice, tradeInfo.trailingStopLoss));
                sellCoin(coin, tradingPair, tradeInfo.amount);
                return;
            } else if (currentPrice < previousProfitLevelPrice && tradeInfo.profitLevelIndex >= 2) {
                // price dropped below previously reached profit level and last reached profit level was at least 2
                log("INFO", String.format(
                        "Selling %s due to profit drop. Current: %.6f, Previous Profit level price (%.6f) undercut",
                        coin, currentPrice, previousProfitLevelPrice));
                sellCoin(coin, tradingPair, tradeInfo.amount);
                return;
            }

            // 5. Hold the coin if no condition is met
            log("DEBUG", String.format("Holding %s. Price above stop-loss and profit levels. Skipping SALE.", coin));

        } else {
            // Initial Purchase Logic - Only buy if not already held
            if (purchaseHistory.size() >= maxHeldCoins) {
                log("DEBUG",
                        String.format("Max held coins limit (%d) reached. Skipping BUY for %s.", maxHeldCoins, coin));
                return; // Skip the BUY operation if limit is reached
            }

            double priceChangePercentage = marketDataFetcher.get24hPriceChangePercentage(tradingPair);
            double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

            log("DEBUG",
                    String.format("Checking BUY condition for %s. Price Change: %.2f%%", coin, priceChangePercentage));
            if (priceChangePercentage <= (purchaseDropPercent * -1)) {
                double fundsToSpend = getPurchaseMoney(usdcBalance, useFundsPortionPerTrade);
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

        // Calculate the number of decimal places based on the precision value
        int decimalPlaces = BigDecimal.valueOf(precision)
                .stripTrailingZeros()
                .scale();

        // Calculate how many coins can be bought for the USD amount
        double amountToBuy = amountToSpend / currentPrice;

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
                                currentPrice,
                                initialStopLoss,
                                0,
                                0,
                                decimalPlaces));
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

    double getPurchaseMoney(double funds, double useFundsPortionPerTrade) {
        double portfolioValue = funds + getTotalUsdcValueOfHeldCoins();
        double purchaseMoney = portfolioValue * useFundsPortionPerTrade;
        if (funds < purchaseMoney) {
            purchaseMoney = funds;
        }
        return purchaseMoney;
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
    private Map<String, TradeInfo> loadAssets() throws Exception {
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
            throw new Exception("Error when trying to load existing asset file!");
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