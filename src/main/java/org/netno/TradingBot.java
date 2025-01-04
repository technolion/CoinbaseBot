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
import java.time.temporal.ChronoUnit;
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
    private final double sellRisePercent;
    private final int sellAfterHours;
    private final double averageDownDropPercent;
    private final int maxHeldCoins;
    private final double useFundsPortionPerTrade;

    private Map<String, TradeInfo> purchaseHistory;

    public TradingBot(CoinbaseAdvancedClient client, Config config) {
        this.ordersService = CoinbaseAdvancedServiceFactory.createOrdersService(client);
        this.marketDataFetcher = new MarketDataFetcher(client, config.getPortfolioId());
        double usdcBalance = marketDataFetcher.getUsdcBalance();

        this.coinsToTrade = config.getCoins();
        this.purchaseDropPercent = config.getPurchaseDropPercent();
        this.sellRisePercent = config.getSellRisePercent();
        this.sellAfterHours = config.getSellAfterHours();
        this.averageDownDropPercent = config.getAverageDownDropPercent();
        this.maxHeldCoins = config.getMaxHeldCoins();
        this.useFundsPortionPerTrade = config.getUseFundsPortionPerTrade();

        this.purchaseHistory = loadAssets();
        this.logLevel = LogLevel.valueOf(config.getLogLevel().toUpperCase());
        log("INFO", "TradingBot initialized.");
        log("INFO", String.format("Current cash: %s USDC.%n", usdcBalance));
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

        double priceChange = marketDataFetcher.get24hPriceChange(tradingPair);
        double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

        // Check if the coin is already in the purchase history
        if (purchaseHistory.containsKey(coin)) {
            // Existing coin - handle sell or average down
            TradeInfo tradeInfo = purchaseHistory.get(coin);
            LocalDateTime purchaseDate = tradeInfo.getPurchaseDate();
            double purchasePrice = tradeInfo.getPurchasePrice();
            double heldAmount = tradeInfo.getAmount();

            // Calculate percentage difference between current price and purchase price
            double priceDifference = ((currentPrice - purchasePrice) / purchasePrice) * 100;

            // Display current status of the coin
            log("DEBUG", String.format(
                    "Status for %s: Held Amount: %.6f, Purchase Price: %.2f, Current Price: %.2f, Difference: %.2f%%%n",
                    coin, heldAmount, purchasePrice, currentPrice, priceDifference));

            // Check for selling conditions
            boolean profitCondition = currentPrice >= purchasePrice * (1 + (sellRisePercent / 100.0));
            boolean timeCondition = ChronoUnit.HOURS.between(purchaseDate, LocalDateTime.now()) > sellAfterHours;
            boolean averageDownCondition = currentPrice <= purchasePrice * (1 + (averageDownDropPercent / 100.0));

            // Sell if profit or time condition is met
            if (profitCondition || timeCondition) {
                log("INFO", String.format("Selling %s due to %s%n",
                        coin, profitCondition ? "profit condition" : "time condition"));
                sellCoin(coin, tradingPair, heldAmount); // Sell existing coin
                return;
            } else {
                log("DEBUG", String.format("No significant price increase for %s. Skipping SALE.%n", coin));
            }

            // Average Down Logic
            if (averageDownCondition) {
                log("INFO", String.format("Averaging down for %s. Price drop detected!%n", coin));
                double fundsToSpend = usdcBalance >= (heldAmount * currentPrice) ? (heldAmount * currentPrice)
                        : usdcBalance * useFundsPortionPerTrade;
                log("INFO", String.format("Buying %s for %.2f USDC at %.2f per unit.%n",
                        coin, fundsToSpend, currentPrice));
                buyCoin(coin, tradingPair, fundsToSpend, currentPrice, true); // Average down
                return;
            } else {
                log("DEBUG", String.format("No price drop for averaging down %s. Skipping.%n", coin));
            }
        } else {
            // Not existing. Check the limit for new purchases
            if (purchaseHistory.size() >= maxHeldCoins) {
                log("DEBUG",
                        String.format("Max held coins limit (%d) reached. Skipping BUY for %s.%n", maxHeldCoins, coin));
                return; // Skip the BUY operation if limit is reached
            }

            // Initial Purchase Logic
            log("DEBUG", String.format("Checking BUY condition for %s. Price Change: %.2f%%%n", coin, priceChange));
            if (priceChange <= purchaseDropPercent) {
                double fundsToSpend = usdcBalance * useFundsPortionPerTrade;
                log("INFO", String.format("Buying %s for %.2f USDC at %.2f per unit.%n",
                        coin, fundsToSpend, currentPrice));
                buyCoin(coin, tradingPair, fundsToSpend, currentPrice, false); // Initial buy
            } else {
                log("DEBUG", String.format("No significant price drop for %s. Skipping BUY.%n", coin));
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
            log("INFO", String.format("Bought %s coins of %s. Order ID: %s%n", roundedBaseSize, coin,
                    orderResponse.getSuccessResponse().getOrderId()));
            if (update) {
                TradeInfo ti = purchaseHistory.get(coin);
                if (ti != null) {
                    ti.updatePurchase(currentPrice, Double.parseDouble(roundedBaseSize));
                }
            } else {
                purchaseHistory.put(coin,
                        new TradeInfo(currentPrice, Double.parseDouble(roundedBaseSize), LocalDateTime.now()));
            }
            saveAssets();
        } else {
            log("ERROR", String.format("Buying %s failed!%n", coin));
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

        // Execute the order
        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);
        if (orderResponse.isSuccess()) {
            log("INFO", String.format("Sold %s coins of %s. Order ID: %s%n", exactSize, coin,
                    orderResponse.getSuccessResponse().getOrderId()));
            purchaseHistory.remove(coin);
            saveAssets();
        } else {
            log("ERROR", String.format("Selling %s failed!%n", coin));
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