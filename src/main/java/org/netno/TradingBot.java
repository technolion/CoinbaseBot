package org.netno;

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
import java.io.StringWriter;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1); // Single-threaded executor

    public TradingBot(CoinbaseAdvancedClient client, Config config) {
        this.ordersService = CoinbaseAdvancedServiceFactory.createOrdersService(client);
        this.marketDataFetcher = new MarketDataFetcher(client, config.portfolioId);
        this.config = config;
        try {
            this.currentAssets = loadAssets();
        } catch (Exception e) {
            return;
        }
        this.logLevel = LogLevel.valueOf(config.logLevel.toUpperCase());
        getUsdcBalance();
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
        getUsdcBalance();
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

        scheduler.scheduleAtFixedRate(() -> {
            synchronized (this) { // Ensure only one task modifies state at a time
                try {
                    evaluateInitialPurchase();
                } catch (Exception e) {
                    log("ERROR", "Error in evaluateInitialPurchase: " + e.getMessage());
                }
            }
        }, 0, 15, TimeUnit.SECONDS); // Initial delay 0, repeat every 15 seconds

        // Schedule executeTrade every 10 seconds
        scheduler.scheduleAtFixedRate(() -> {
            synchronized (this) { // Ensure only one task modifies state at a time
                try {
                    executeTrade();
                } catch (Exception e) {
                    log("ERROR", "Error in executeTrade: " + e.getMessage());
                    log("ERROR", "Exception: " + e.toString());
                    Writer buffer = new StringWriter();
                    PrintWriter pw = new PrintWriter(buffer);
                    e.printStackTrace(pw);
                    log("ERROR", "Stacktrace: " + buffer.toString());
                }
            }
        }, 7, 15, TimeUnit.SECONDS); // Initial delay 7 seconds, repeat every 15 seconds
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
        log("DEBUG", "---- FETCHING CURRENT USDC BALANCE ----");
        // get the current amount of trade currency (USDC)
        getUsdcBalance();

        log("DEBUG", "---- EVALUATING INITIAL PURCHASE ----");

        if (currentAssets.size() >= config.maxHeldCoins) {
            log("DEBUG", String.format("Max held coins limit (%d) reached. Skipping initial purchase evaluation.",
                    config.maxHeldCoins));
            return;
        }

        log("DEBUG", String.format("Current cash: %.6f USDC.", usdcBalance));

        CoinDropInfo bestCoinToBuy = null;

        for (String coin : config.coins) {
            if (currentAssets.containsKey(coin)) {
                continue; // Skip already held coins
            }

            try {
                String tradingPair = coin + "-" + QUOTECURRENCY;
                double priceChangePercentage = marketDataFetcher.get24hPriceChangePercentage(tradingPair);
                double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

                log("DEBUG", String.format("Checking BUY condition for %s. Price Change: %.2f%%", coin,
                        priceChangePercentage));

                // Keep track of the coin with the strongest decline
                if (priceChangePercentage <= (config.purchaseDropPercent * -1)) {
                    if (bestCoinToBuy == null || priceChangePercentage < bestCoinToBuy.priceChangePercentage) {
                        bestCoinToBuy = new CoinDropInfo(coin, tradingPair, currentPrice, priceChangePercentage);
                    }
                }

            } catch (Exception e) {
                log("ERROR", "Error during initial purchase evaluation for coin " + coin + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // If a suitable coin is found, proceed with purchase
        if (bestCoinToBuy != null) {
            double fundsToSpend = getBudgetForNextPurchase(usdcBalance, config.useFundsPortionPerTrade);
            log("INFO", String.format("Buying %s with strongest decline (%.2f%%) for %.6f USDC at %.6f per unit.",
                    bestCoinToBuy.coin, bestCoinToBuy.priceChangePercentage, fundsToSpend, bestCoinToBuy.currentPrice));
            try {
                buyCoin(bestCoinToBuy.coin, bestCoinToBuy.tradingPair, fundsToSpend, bestCoinToBuy.currentPrice, false);
            } catch (Exception e) {
                log("ERROR", "Error while attempting to buy coin " + bestCoinToBuy.coin + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            log("DEBUG", "No coin met the purchase condition.");
        }
    }

    public void executeTrade() {
        log("DEBUG", "---- EXECUTING ON HELD COINS ----");

        List<String> coinsToSell = new ArrayList<>();

        currentAssets.forEach((coin, tradeInfo) -> {
            try {
                String tradingPair = coin + "-" + QUOTECURRENCY;
                double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

                if(currentPrice > tradeInfo.highestPrice) {
                    tradeInfo.highestPrice = currentPrice;
                    saveAssets();
                }

                // Calculate percentage difference between current price and purchase price
                double performancePercentage = ((currentPrice - tradeInfo.purchasePrice) / tradeInfo.purchasePrice) * 100;

                // Calculate number of full weeks the coin has been held
                long weeksHeld = tradeInfo.getWeeks();

                // Display current status of the coin
                log("DEBUG", String.format(
                        "%s: Amount: %.6f, Purchase $: %.6f, Current $: %.6f, Highest $: %.6f, Performance: %.2f%%, A/D Step: %d, Weeks Held: %d",
                        coin, tradeInfo.amount, tradeInfo.purchasePrice, currentPrice,
                        tradeInfo.highestPrice, performancePercentage, tradeInfo.averageDownStepIndex, weeksHeld));

                // 🔹 Step 1: Average Down Logic
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
                    log("INFO", String.format("Averaging down for %s at %.6f.", coin, currentPrice));

                    // if we have enough funds, try buy the same amount again, otherwise use a portion of the remaining funds
                    double fundsToSpend = usdcBalance >= (tradeInfo.amount * currentPrice)
                            ? (tradeInfo.amount * currentPrice)
                            : getBudgetForNextPurchase(usdcBalance, config.useFundsPortionPerTrade);

                    boolean success = buyCoin(coin, tradingPair, fundsToSpend, currentPrice, true);

                    if (success) {
                        tradeInfo.increaseAverageDown();
                        //reset highest price to current price
                        tradeInfo.highestPrice = currentPrice;
                        saveAssets();
                        log("INFO", String.format("Held coin %s is now at average down step %d.", coin,
                                tradeInfo.averageDownStepIndex));
                    }

                    return; // Skip further processing
                }

                // 🔹 Step 2: Time-Based Selling for Negative Profit Levels
                if (performancePercentage < 0 && weeksHeld >= 3) {
                    int thresholdIndex = (int) Math.min(weeksHeld, config.negativeProfitLevels.size());

                    if (round(performancePercentage, 1) >= round(-config.negativeProfitLevels.get(thresholdIndex - 1), 1)) {
                        log("INFO", String.format(
                                "Selling %s after %d weeks below purchase price (Current: %.6f, Threshold: %.2f%%)",
                                coin, weeksHeld, currentPrice, config.negativeProfitLevels.get(thresholdIndex - 1)));

                        coinsToSell.add(coin);
                        return; // Skip further processing
                    }
                }

                // 🔹 Step 3: Profit Drop Selling
                if(currentPrice > tradeInfo.purchasePrice && performancePercentage >= config.minimumProfitPercentage) {
                    //we have enough profit
                    double stopLossPrice = tradeInfo.highestPrice - (tradeInfo.highestPrice / 100 * config.stopLossSalePercentage);
                    if(currentPrice <= stopLossPrice) {
                        //we dropped at or below stopLoss price, let's sell
                        log("INFO", String.format(
                            "Selling %s due to profit drop. Current: %.6f, Highest: (%.6f).",
                            coin, currentPrice, tradeInfo.highestPrice));
                    coinsToSell.add(coin);

                    return; // Skip further processing
                    }
                }

                // 🔹 Step 4: Hold the coin if no condition is met
                log("DEBUG",
                        String.format("Holding %s. Price above stop-loss and profit levels. Skipping SALE.", coin));

            } catch (Exception e) {
                log("ERROR", "Error during executing trade on coin " + coin + ": " + e.getMessage());
                e.printStackTrace();
            }
        });

        for (String coin : coinsToSell) {
            try {
                sellCoin(coin);
            } catch (Exception e) {
                log("ERROR", "Error while selling " + coin + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
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
                    tradeInfo.updatePurchase(currentPrice, Double.parseDouble(roundedBaseSize), config.takerFeePercentage);
                }
            } else { // Initial purchase
                // Calculate the initial stop-loss price
                

                //calculate the purchase fee
                double purchaseFee = currentPrice * Double.parseDouble(roundedBaseSize) * config.takerFeePercentage / 100.0;
                // round the purchase fee to 2 digits after the comma
                String roundedPurchaseFee = BigDecimal.valueOf(purchaseFee)
                        .setScale(2, RoundingMode.HALF_DOWN)
                        .toPlainString();


                // Add new entry to the purchase history
                currentAssets.put(coin,
                        new TradeInfo(
                                currentPrice,
                                Double.parseDouble(roundedBaseSize), 
                                java.time.ZonedDateTime.now(ZoneId.of(config.timeZone)).toLocalDateTime(),
                                currentPrice,
                                Double.parseDouble(roundedPurchaseFee),
                                0,
                                decimalPlaces
                            ));
            }

            // Save updated assets to file
            saveAssets();
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
        if (tradeInfo == null) {
            log("ERROR", String.format("Selling %s failed because it's not in the assets.", coin));
            return false;
        }

        // Use the exact amount from purchase history without rounding
        String exactSize = Double.toString(tradeInfo.amount);

        // Create the OrderConfiguration using baseSize
        OrderConfiguration orderConfig = new OrderConfiguration();
        orderConfig.setMarketMarketIoc(new MarketIoc.Builder()
                .baseSize(exactSize) // Use exact amount
                .build());

        // Build the order request
        CreateOrderRequest orderRequest = new CreateOrderRequest.Builder()
                .clientOrderId(LocalDateTime.now().toString())
                .productId(tradingPair)
                .side("SELL")
                .orderConfiguration(orderConfig)
                .build();

        // Get the current price for profit/loss calculation
        double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);


        // Execute the order
        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);
        if (orderResponse.isSuccess()) {
            log("INFO", String.format(
                    "Sold %s coins of %s. Order ID: %s, Profit/Loss: %.2f USDC",
                    exactSize, coin, orderResponse.getSuccessResponse().getOrderId(), tradeInfo.getWinLossIncludingFees(currentPrice, config.takerFeePercentage )));

            // Remove the coin from purchase history
            currentAssets.remove(coin);
            saveAssets();
            return true;
        } else {
            log("ERROR", String.format("Selling %s failed!", coin));
            log("ERROR", String.format(orderResponse.getErrorResponse().getError()));
            return false;
        }
    }

    // calculates how much USDC we can spend on the next initial purchase
    double getBudgetForNextPurchase(double funds, double useFundsPortionPerTrade) {
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
            mapper.registerModule(new JavaTimeModule());

            // Use the wrapper class to save both assets and stop-loss marker
            AssetDataWrapper dataWrapper = new AssetDataWrapper(currentAssets);

            mapper.writeValue(new File(ASSETS_FILE), dataWrapper);
        } catch (IOException e) {
            log("ERROR", "Failed to save purchase history: " + e.getMessage());
        }
    }

    // Load purchase history from file
    Map<String, TradeInfo> loadAssets() throws Exception {
        try {
            File file = new File(ASSETS_FILE);
            if (file.exists()) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());

                // Deserialize into the wrapper class
                AssetDataWrapper dataWrapper = mapper.readValue(file, AssetDataWrapper.class);
                return dataWrapper.getCurrentAssets();
            }
        } catch (IOException e) {
            log("ERROR", "Failed to load purchase history: " + e.getMessage());
            throw new Exception("Error when trying to load existing asset file!");
        }
        return new HashMap<>();
    }

    public void getUsdcBalance() {
        double newAmount = marketDataFetcher.getUsdcBalance();
        if (newAmount != usdcBalance) {
            log("INFO", String.format("Current cash: %s USDC.", usdcBalance));
        }
        usdcBalance = newAmount;
    }

    void log(String level, String message) {
        try {
            ZoneId zoneId = ZoneId.of(config.timeZone);
            LogLevel currentLevel = LogLevel.valueOf(level.toUpperCase());
            String timestamp = ZonedDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
            // Print log to console
            System.out.printf("[%s] [%s] %s%n", timestamp, level, message);

            // Write to file only if log level is met or surpassed
            if (currentLevel.ordinal() >= logLevel.ordinal()) {
                try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE, true))) {
                    out.printf("[%s] [%s] %s%n", timestamp, level, message);
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to write log: " + e.getMessage());
        }
    }

    // Helper class to store information about a coin's decline
    private static class CoinDropInfo {
        String coin;
        String tradingPair;
        double currentPrice;
        double priceChangePercentage;

        CoinDropInfo(String coin, String tradingPair, double currentPrice, double priceChangePercentage) {
            this.coin = coin;
            this.tradingPair = tradingPair;
            this.currentPrice = currentPrice;
            this.priceChangePercentage = priceChangePercentage;
        }
    }

    private static double round(double value, int precision) {
        int scale = (int) Math.pow(10, precision);
        return (double) Math.round(value * scale) / scale;
    }
}
