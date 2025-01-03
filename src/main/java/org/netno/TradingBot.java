package org.netno;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.coinbase.advanced.factory.CoinbaseAdvancedServiceFactory;
import com.coinbase.advanced.model.orders.CreateOrderRequest;
import com.coinbase.advanced.model.orders.CreateOrderResponse;
import com.coinbase.advanced.model.orders.MarketIoc;
import com.coinbase.advanced.model.orders.OrderConfiguration;
import com.coinbase.advanced.orders.OrdersService;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class TradingBot {
    private static final String HISTORY_FILE = "purchaseHistory.json";

    private final OrdersService ordersService;
    private final MarketDataFetcher marketDataFetcher;

    private final double purchaseDropPercent;
    private final double sellRisePercent;
    private final int sellAfterHours;
    private final double averageDownDropPercent;

    private Map<String, TradeInfo> purchaseHistory;

    public TradingBot(CoinbaseBot bot, Config config) {
        this.ordersService = CoinbaseAdvancedServiceFactory.createOrdersService(bot.getClient());
        this.marketDataFetcher = new MarketDataFetcher(bot, config.getPortfolioId());
        double usdcBalance = marketDataFetcher.getUsdcBalance();
        System.out.printf("Current cash: %s USDC.%n", usdcBalance);

        this.purchaseDropPercent = config.getPurchaseDropPercent();
        this.sellRisePercent = config.getSellRisePercent();
        this.sellAfterHours = config.getSellAfterHours();
        this.averageDownDropPercent = config.getAverageDownDropPercent();

        this.purchaseHistory = loadPurchaseHistory();
    }

    public void executeTrade(String coin) throws Exception {
        String tradingPair = coin + "-USDC";

        // Retrieve the current USDC balance before making a decision
        double usdcBalance = marketDataFetcher.getUsdcBalance();

        double priceChange = marketDataFetcher.get24hPriceChange(tradingPair);
        double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

        if (purchaseHistory.containsKey(coin)) {
            TradeInfo tradeInfo = purchaseHistory.get(coin);
            LocalDateTime purchaseDate = tradeInfo.getPurchaseDate();
            double purchasePrice = tradeInfo.getPurchasePrice();
            double heldAmount = tradeInfo.getAmount();

            boolean profitCondition = currentPrice >= purchasePrice * (1 + (sellRisePercent / 100.0));
            boolean timeCondition = ChronoUnit.HOURS.between(purchaseDate, LocalDateTime.now()) > sellAfterHours;
            boolean averageDownCondition = currentPrice <= purchasePrice * (1 + (averageDownDropPercent / 100.0));

            if (profitCondition || timeCondition) {
                sellCoin(coin, tradingPair, heldAmount);
                return;
            } else {
                System.out.printf("No significant price increase for %s. Skipping.%n", coin);
            }

            // Average Down Logic
            if (averageDownCondition) {
                double fundsToSpend = usdcBalance >= (heldAmount * currentPrice) ? (heldAmount * currentPrice)
                        : usdcBalance * 0.5;
                double additionalAmount = fundsToSpend / currentPrice;
                buyCoin(coin, tradingPair, additionalAmount, currentPrice);

                // Update average purchase price
                tradeInfo.updatePurchase(currentPrice, additionalAmount);
                savePurchaseHistory(); // Persist changes
                return;
            }
        } else {
            if (priceChange <= purchaseDropPercent) {
                double fundsToSpend = usdcBalance * 0.2;
                double amountToBuy = fundsToSpend / currentPrice;
                buyCoin(coin, tradingPair, amountToBuy, currentPrice);
            } else {
                System.out.printf("No significant price drop for %s. Skipping.%n", coin);
            }
        }
    }

    private void buyCoin(String coin, String tradingPair, double amount, double currentPrice) throws Exception {
        // Calculate the quote size based on the amount and price
        double quoteSize = amount * currentPrice;

        // Round the quote size to 2 decimal places
        String roundedQuoteSize = BigDecimal.valueOf(quoteSize)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();

        OrderConfiguration config = new OrderConfiguration();
        config.setMarketMarketIoc(new MarketIoc.Builder()
                .quoteSize(roundedQuoteSize) // Use rounded value here
                .build());

        CreateOrderRequest orderRequest = new CreateOrderRequest.Builder()
                .clientOrderId(LocalDateTime.now().toString())
                .productId(tradingPair)
                .side("BUY")
                .orderConfiguration(config)
                .build();

        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);
        if (orderResponse.isSuccess()) {
            System.out.printf("Bought %s: %s%n", coin, orderResponse.getOrderId());
            purchaseHistory.put(coin, new TradeInfo(currentPrice, amount, LocalDateTime.now()));
            savePurchaseHistory();
        } else {
            System.out.printf("Buying %s failed!%n", coin);
            System.out.println(orderResponse.getErrorResponse().getError());
        }
    }

    private void sellCoin(String coin, String tradingPair, double amount) throws Exception {
        OrderConfiguration config = new OrderConfiguration();
        config.setMarketMarketIoc(new MarketIoc.Builder()
                .baseSize(String.valueOf(amount))
                .build());

        CreateOrderRequest orderRequest = new CreateOrderRequest.Builder()
                .clientOrderId(LocalDateTime.now().toString())
                .productId(tradingPair)
                .side("SELL")
                .orderConfiguration(config)
                .build();

        CreateOrderResponse orderResponse = ordersService.createOrder(orderRequest);
        if (orderResponse.isSuccess()) {
            System.out.printf("Sold %s: %s%n", coin, orderResponse.getOrderId());
            purchaseHistory.remove(coin);
            savePurchaseHistory();
        } else {
            System.out.printf("Selling %s failed!%n", coin);
            System.out.println(orderResponse.getErrorResponse().getError());
        }
    }

    // Save purchase history to file
    private void savePurchaseHistory() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule()); // Register Java 8 time module
            mapper.writeValue(new File(HISTORY_FILE), purchaseHistory);
        } catch (IOException e) {
            System.out.println("Failed to save purchase history: " + e.getMessage());
        }
    }

    // Load purchase history from file
    private Map<String, TradeInfo> loadPurchaseHistory() {
        try {
            File file = new File(HISTORY_FILE);
            if (file.exists()) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule()); // Register Java 8 time module
                return mapper.readValue(file, new TypeReference<Map<String, TradeInfo>>() {
                });
            }
        } catch (IOException e) {
            System.out.println("Failed to load purchase history: " + e.getMessage());
        }
        return new HashMap<>();
    }
}