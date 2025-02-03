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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class TradingBot {

    public enum LogLevel {
        TRACE, DEBUG, INFO, ERROR
    }

    private LogLevel logLevel;
    private static final String ASSETS_FILE = "currentAssets.json";
    private static final String LOG_FILE = "trading.log";

    static final String QUOTECURRENCY = "USDC";

    private final OrdersService ordersService;
    private final MarketDataFetcher marketDataFetcher;
    private Map<String, TradeInfo> currentAssets = new ConcurrentHashMap<>();
    double usdcBalance;
    public Config config;
    public boolean initialized = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1); // Single-threaded executor

    public TradingBot(CoinbaseAdvancedClient client, Config config) {
        this.ordersService = CoinbaseAdvancedServiceFactory.createOrdersService(client);
        this.marketDataFetcher = new MarketDataFetcher(client, config.portfolioId);
        this.config = config;
        usdcBalance = marketDataFetcher.getUsdcBalance();
        try {
            this.currentAssets = loadAssets();
        } catch (Exception e) {
            return;
        }
        this.logLevel = LogLevel.valueOf(config.logLevel.toUpperCase());
        log("INFO", "TradingBot initialized.");
        log("INFO", String.format("Current cash: %s USDC.", usdcBalance));
        initialized = true;
    }

    // for unit tests only
    public TradingBot(OrdersService orderService, MarketDataFetcher marketDataFetcher, Config config,
            Map<String, TradeInfo> purchaseHistory) {

        this.ordersService = orderService;
        this.marketDataFetcher = marketDataFetcher;
        this.config = config;
        usdcBalance = marketDataFetcher.getUsdcBalance();
        this.currentAssets = purchaseHistory;
        this.logLevel = LogLevel.valueOf(config.logLevel.toUpperCase());
        log("INFO", "TradingBot initialized.");
        log("INFO", String.format("Current cash: %s USDC.", usdcBalance));
    }

    public Map<String, TradeInfo> getCurrentAssets() {
        return currentAssets;
    }

    public MarketDataFetcher getMarketDataFetcher() {
        return marketDataFetcher;
    }

    int getNumberOfHeldCoins() {
        return currentAssets.size();
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

        for (Map.Entry<String, TradeInfo> entry : currentAssets.entrySet()) {
            TradeInfo tradeInfo = entry.getValue();
            double coinValue = tradeInfo.getAmount() * tradeInfo.getPurchasePrice();
            totalUsdcValue += coinValue;
        }
        return totalUsdcValue;
    }

    public void startTrading() {
        log("INFO", "Starting trading loop...");

        // Schedule evaluateInitialPurchase every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            synchronized (this) { // Ensure only one task modifies state at a time
                try {
                    evaluateInitialPurchase();
                } catch (Exception e) {
                    log("ERROR", "Error in evaluateInitialPurchase: " + e.getMessage());
                }
            }
        }, 0, 30, TimeUnit.SECONDS); // Initial delay 0, repeat every 30 seconds

        // Schedule executeTrade every 30 seconds (offset by 15 seconds)
        scheduler.scheduleAtFixedRate(() -> {
            synchronized (this) { // Ensure only one task modifies state at a time
                try {
                    executeTrade();
                } catch (Exception e) {
                    log("ERROR", "Error in executeTrade: " + e.getMessage());
                }
            }
        }, 15, 30, TimeUnit.SECONDS); // Initial delay 15 seconds, repeat every 30 seconds
    }

    // Shutdown method to gracefully terminate the executor service
    public void stopTrading() {
        log("INFO", "Stopping trading loop...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow(); // Force shutdown if tasks are not completed in 10 seconds
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    public void evaluateInitialPurchase() {
        log("DEBUG", "---- EVALUATING INITIAL PURCHASE ----");
        if (currentAssets.size() >= config.maxHeldCoins) {
            log("DEBUG",
                    String.format("Max held coins limit (%d) reached. Skipping initial purchase evaluation.",
                            config.maxHeldCoins));
            return;
        }
        log("DEBUG", String.format("Current cash: %s USDC.", usdcBalance));
        for (String coin : config.coins) {
            if(currentAssets.containsKey(coin)) {
                //already bought, continue
                continue;
            }
            try {
                String tradingPair = coin + "-" + QUOTECURRENCY;
                double priceChangePercentage = marketDataFetcher.get24hPriceChangePercentage(tradingPair);
                double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

                log("DEBUG",
                        String.format("Checking BUY condition for %s. Price Change: %.2f%%", coin,
                                priceChangePercentage));
                if (priceChangePercentage <= (config.purchaseDropPercent * -1)) {
                    double fundsToSpend = getPurchaseMoney(usdcBalance, config.useFundsPortionPerTrade);
                    log("INFO", String.format("Buying %s for %.6f USDC at %.6f per unit.",
                            coin, fundsToSpend, currentPrice));
                    if (buyCoin(coin, tradingPair, fundsToSpend, currentPrice, false)) {
                        //let's only buy one coin at a time and break here out of the for loop
                        break;
                    }       
                } else {
                    log("DEBUG", String.format("No significant price drop for %s. Skipping BUY.", coin));
                }
            } catch (Exception e) {
                log("ERROR", "Error during initial purchase evalutation on coin " + coin + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void executeTrade() {
        log("DEBUG", "---- EXECUTING ON HELD COINS ----");
        currentAssets.forEach((coin, tradeInfo) -> {
            try {
                String tradingPair = coin + "-" + QUOTECURRENCY;
                double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

                // Update trailing stop-loss based on price movement
                tradeInfo.updateStopLoss(currentPrice, config.trailingStopLossPercent);

                // Calculate percentage difference between current price and purchase price
                double priceDifference = ((currentPrice - tradeInfo.purchasePrice) / tradeInfo.purchasePrice) * 100;

                // Display current status of the coin
                log("DEBUG", String.format(
                        "%s: Amount: %.6f, Purchase $: %.6f, Current $: %.6f, Profit Lv: %d (%.2f%%), Highest $: %.6f, Stop-Loss: %.6f, Performance: %.2f%%, A/D Step: %d",
                        coin, tradeInfo.amount, tradeInfo.purchasePrice, currentPrice, tradeInfo.profitLevelIndex,
                        config.profitLevels.get(tradeInfo.profitLevelIndex), tradeInfo.highestPrice,
                        tradeInfo.trailingStopLoss, priceDifference, tradeInfo.averageDownStepIndex));

                // 1. Average Down Logic
                boolean canAverageDown = false;
                double nextAverageDownPrice = 0;
                if (tradeInfo.getAverageDownStepIndex() < (config.averageDownSteps.size() - 1)) {
                    canAverageDown = true;
                    double nextAverageDownDropPercentage = config.averageDownSteps
                            .get(tradeInfo.getAverageDownStepIndex() + 1);
                    nextAverageDownPrice = tradeInfo.purchasePrice
                            - (tradeInfo.purchasePrice / 100 * nextAverageDownDropPercentage);
                }

                if (canAverageDown && currentPrice <= nextAverageDownPrice) {
                    log("INFO", String.format("Averaging down for %s at %.6f.",
                            coin, currentPrice));

                    double fundsToSpend = usdcBalance >= (tradeInfo.amount * currentPrice)
                            ? (tradeInfo.amount * currentPrice)
                            : getPurchaseMoney(usdcBalance, config.useFundsPortionPerTrade);

                    boolean success = buyCoin(coin, tradingPair, fundsToSpend, currentPrice, true);

                    if (success) {
                        // Move to the next step
                        tradeInfo.increaseAverageDown();
                        saveAssets();
                        log("INFO", String.format("Held coin %s is now a average down %d.",
                                coin, tradeInfo.averageDownStepIndex));
                    }

                    return; // Skip further processing
                }

                // 2. Recovery Sale Handling
                double recoveryProfitLevelPercentage = config.profitLevels.get(config.profitLevelForRecoverySale);
                double recoveryProfitLevelPrice = tradeInfo.purchasePrice
                        + (tradeInfo.purchasePrice / 100 * recoveryProfitLevelPercentage);

                if (tradeInfo.averageDownStepIndex == (config.averageDownSteps.size() - 1)
                        && currentPrice >= recoveryProfitLevelPrice) {
                    // coin has been previously averaged down twice and now reached first real
                    // profit level
                    // selling to free funds and lower risk
                    log("INFO", String.format(
                            "Selling %s  at %.6f due recovery from averaging down.",
                            coin, currentPrice));
                    sellCoin(coin);

                    return; // Skip further processing
                }

                // 3. Profit Level Handling
                boolean canIncreaseProfitLevel = false;
                double nextProfitLevelPrice = 10000000; // impossible high number
                if (tradeInfo.profitLevelIndex < (config.profitLevels.size() - 1)) {
                    // not yet on maximum profit level
                    canIncreaseProfitLevel = true;
                    double nextProfitLevelPercentage = config.profitLevels.get(tradeInfo.profitLevelIndex + 1);
                    nextProfitLevelPrice = tradeInfo.purchasePrice
                            + (tradeInfo.purchasePrice / 100 * nextProfitLevelPercentage);
                }

                if (canIncreaseProfitLevel && currentPrice >= nextProfitLevelPrice) {
                    // Move to the next profit level
                    tradeInfo.increaseProfitLevel();
                    saveAssets(); // Save changes

                    if (tradeInfo.profitLevelIndex == (config.profitLevels.size() - 1)) {
                        log("INFO", String.format("Selling %s due to max profit level reached. Current: %.6f", coin,
                                currentPrice));
                        sellCoin(coin);
                    } else {
                        log("INFO", String.format("Reached profit level %d (%.2f%%) for %s. Waiting for next level...",
                                tradeInfo.profitLevelIndex, config.profitLevels.get(tradeInfo.profitLevelIndex), coin));
                    }

                    return; // Skip further processing
                }

                // 4. Stop-Loss or Profit Drop Handling
                double previousProfitLevelPrice = 0; // impossible low
                if (tradeInfo.profitLevelIndex > config.minimumProfitLevelForRegularSale) {
                    // the profit level is higher than the minimum sale level
                    double previousProfitLevelPercentage = config.profitLevels.get(tradeInfo.profitLevelIndex - 1);
                    previousProfitLevelPrice = tradeInfo.purchasePrice
                            + (tradeInfo.purchasePrice / 100 * previousProfitLevelPercentage);
                }

                if (currentPrice < tradeInfo.trailingStopLoss) {
                    // price dropped below stop loss
                    log("INFO", String.format("Selling %s due to stop-loss. Current: %.6f, Stop-Loss: %.6f",
                            coin, currentPrice, tradeInfo.trailingStopLoss));
                    sellCoin(coin);

                    return; // Skip further processing
                } else if (currentPrice < previousProfitLevelPrice) {
                    // price dropped below previously reached profit level and last reached profit
                    // level was at least 2
                    log("INFO", String.format(
                            "Selling %s due to profit drop. Current: %.6f, Previous Profit level price (%.6f) undercut",
                            coin, currentPrice, previousProfitLevelPrice));
                    sellCoin(coin);

                    return; // Skip further processing
                }

                // 5. Hold the coin if no condition is met
                log("DEBUG",
                        String.format("Holding %s. Price above stop-loss and profit levels. Skipping SALE.", coin));

            } catch (Exception e) {
                log("ERROR", "Error during executing trade on coin " + coin + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private boolean buyCoin(String coin, String tradingPair, double amountToSpend, double currentPrice, boolean update)
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
        OrderConfiguration orderConfig = new OrderConfiguration();
        orderConfig.setMarketMarketIoc(new MarketIoc.Builder()
                .baseSize(roundedBaseSize) // Use baseSize instead of quoteSize
                .build());

        // Build the order request
        CreateOrderRequest orderRequest = new CreateOrderRequest.Builder()
                .clientOrderId(LocalDateTime.now().toString())
                .productId(tradingPair)
                .side("BUY")
                .orderConfiguration(orderConfig)
                .build();

        // Execute the order
        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);

        if (orderResponse.isSuccess()) {
            log("INFO", String.format("Bought %s coins of %s. Order ID: %s", roundedBaseSize, coin,
                    orderResponse.getSuccessResponse().getOrderId()));

            // If this is an update (averaging down)
            if (update) {
                TradeInfo tradeInfo = currentAssets.get(coin);
                if (tradeInfo != null) {
                    // Update purchase price and amount
                    tradeInfo.updatePurchase(currentPrice, Double.parseDouble(roundedBaseSize));

                    // Update stop-loss based on the new average purchase price
                    double updatedStopLoss = tradeInfo.getPurchasePrice()
                            * (1 - config.trailingStopLossPercent / 100.0);
                    tradeInfo.setTrailingStopLoss(updatedStopLoss);

                    log("INFO", String.format("Updated stop-loss for %s to %.6f", coin, updatedStopLoss));
                }
            } else { // Initial purchase
                // Calculate the initial stop-loss price
                double initialStopLoss = currentPrice * (1 - config.trailingStopLossPercent / 100.0);

                // Add new entry to the purchase history
                currentAssets.put(coin,
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
            usdcBalance = marketDataFetcher.getUsdcBalance();
            log("DEBUG", String.format("Current cash: %s USDC.", usdcBalance));
            return true;
        } else {
            log("ERROR", String.format("Buying %s failed!", coin));
            log("ERROR", String.format(orderResponse.getErrorResponse().getError()));
            return false;
        }
    }

    boolean sellCoin(String coin) throws Exception {
        String tradingPair = coin + "-" + QUOTECURRENCY;

        TradeInfo tradeInfo = currentAssets.get(coin);
        if(tradeInfo == null) {
            log("ERROR", String.format("Selling %s failed because it's not in the assets.", coin));
            return false;
        }

        double amount = tradeInfo.amount;
        double purchasePrice = tradeInfo.getPurchasePrice();

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

        // Calculate profit or loss in USDC
        double totalPurchaseValue = amount * purchasePrice;
        double totalSellValue = amount * currentPrice;
        double profitOrLoss = totalSellValue - totalPurchaseValue;

        // Execute the order
        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);
        if (orderResponse.isSuccess()) {
            log("INFO", String.format(
                    "Sold %s coins of %s. Order ID: %s, Profit/Loss: %.2f USDC",
                    exactSize, coin, orderResponse.getSuccessResponse().getOrderId(), profitOrLoss));

            // Remove the coin from purchase history
            currentAssets.remove(coin);
            saveAssets();
            usdcBalance = marketDataFetcher.getUsdcBalance();
            return true;
        } else {
            log("ERROR", String.format("Selling %s failed!", coin));
            log("ERROR", String.format(orderResponse.getErrorResponse().getError()));
            return false;
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
    void saveAssets() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule()); // Register Java 8 time module
            mapper.writeValue(new File(ASSETS_FILE), currentAssets);
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

    void log(String level, String message) {
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