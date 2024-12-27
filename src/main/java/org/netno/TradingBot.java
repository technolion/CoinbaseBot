package org.netno;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

public class TradingBot {
    private static final String HISTORY_FILE = "purchaseHistory.json";

    private final OrdersService ordersService;
    private final MarketDataFetcher marketDataFetcher;
    private final double usdcBalance;

    private final double purchaseDropPercent;
    private final double sellRisePercent;
    private final int sellAfterHours;

    private Map<String, TradeInfo> purchaseHistory;

    public TradingBot(CoinbaseBot bot, double usdcBalance, Config config) {
        this.ordersService = CoinbaseAdvancedServiceFactory.createOrdersService(bot.getClient());
        this.marketDataFetcher = new MarketDataFetcher(bot);
        this.usdcBalance = usdcBalance;

        this.purchaseDropPercent = config.getPurchaseDropPercent();
        this.sellRisePercent = config.getSellRisePercent();
        this.sellAfterHours = config.getSellAfterHours();

        // Load purchase history from file
        this.purchaseHistory = loadPurchaseHistory();
    }

    public void executeTrade(String coin) throws Exception {
        String tradingPair = coin + "-USDC";

        double priceChange = marketDataFetcher.get24hPriceChange(tradingPair);
        double currentPrice = marketDataFetcher.getCurrentPrice(tradingPair);

        // Check for selling conditions
        if (purchaseHistory.containsKey(coin)) {
            TradeInfo tradeInfo = purchaseHistory.get(coin);
            LocalDateTime purchaseDate = tradeInfo.getPurchaseDate();
            double purchasePrice = tradeInfo.getPurchasePrice();

            boolean profitCondition = currentPrice >= purchasePrice * (1 + (sellRisePercent / 100.0));
            boolean timeCondition = ChronoUnit.HOURS.between(purchaseDate, LocalDateTime.now()) > sellAfterHours;

            if (profitCondition || timeCondition) {
                sellCoin(coin, tradingPair, currentPrice);
                return; // Skip buying if we just sold
            }
        }

        // Check for buying condition
        if (priceChange <= purchaseDropPercent) {
            buyCoin(coin, tradingPair, currentPrice);
        } else {
            System.out.printf("No significant price drop for %s. Skipping.%n", coin);
        }
    }

    private void buyCoin(String coin, String tradingPair, double currentPrice) throws Exception {
        double amountToSpend = usdcBalance * 0.2;

        OrderConfiguration config = new OrderConfiguration();
        config.setMarketMarketIoc(new MarketIoc.Builder()
                .quoteSize(amountToSpend + "")
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

            // Record purchase in memory and persist it to disk
            purchaseHistory.put(coin, new TradeInfo(currentPrice, LocalDateTime.now()));
            savePurchaseHistory();
        } else {
            System.out.printf("Buying %s failed!%n", coin);
            System.out.println(orderResponse.getErrorResponse().getError());
        }
    }

    private void sellCoin(String coin, String tradingPair, double currentPrice) throws Exception {
        double amountToSell = 1.0;

        OrderConfiguration config = new OrderConfiguration();
        config.setMarketMarketIoc(new MarketIoc.Builder()
                .baseSize(amountToSell + "")
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

            // Remove from purchase history and save changes
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
                return mapper.readValue(file, new TypeReference<Map<String, TradeInfo>>() {});
            }
        } catch (IOException e) {
            System.out.println("Failed to load purchase history: " + e.getMessage());
        }
        return new HashMap<>();
    }
}