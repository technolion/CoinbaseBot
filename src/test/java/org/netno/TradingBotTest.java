package org.netno;

import com.coinbase.advanced.model.orders.CreateOrderResponse;
import com.coinbase.advanced.model.orders.SuccessResponse;
import com.coinbase.advanced.orders.OrdersService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TradingBotTest {

    private TradingBot bot;
    private MarketDataFetcher marketDataFetcherMock;
    private Map<String, TradeInfo> purchaseHistoryMock;

    @BeforeEach
    void setUp() throws Exception {
        // Mock dependencies
        marketDataFetcherMock = mock(MarketDataFetcher.class);

        Config testConfig = new Config();
        testConfig.coins = List.of("TEST");
        testConfig.purchaseDropPercent = 5.0;
        testConfig.maxHeldCoins = 5;
        testConfig.useFundsPortionPerTrade = 0.2;
        testConfig.profitLevels = List.of(0.0, 2.0, 5.0, 10.0);
        testConfig.averageDownSteps = List.of(0.0, 2.0, 4.0, 6.0);
        testConfig.minimumProfitLevelForRegularSale = 2;
        testConfig.takerFeePercentage = 0;
        testConfig.negativeProfitLevels = List.of(1.0, 2.0, 3.0, 4.0, 5.0); // -1% to -5%
        testConfig.timeZone = "Europe/Berlin";

        // Ensure log level is properly set
        testConfig.logLevel = "DEBUG";

        // Mock MarketDataFetcher
        when(marketDataFetcherMock.getUsdcBalance()).thenReturn(1000.0);
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.50);
        when(marketDataFetcherMock.get24hPriceChangePercentage("TEST-USDC")).thenReturn(-6.0);
        when(marketDataFetcherMock.getBasePrecision("TEST-USDC")).thenReturn(0.001);

        // Mock Purchase History
        purchaseHistoryMock = new ConcurrentHashMap<>();

        // Mock OrdersService
        OrdersService ordersServiceMock = mock(OrdersService.class);
        SuccessResponse successResponse = new SuccessResponse.Builder()
                .orderId("test-order-id")
                .build();
        CreateOrderResponse orderResponse = new CreateOrderResponse.Builder()
                .orderId("test-order-id")
                .success(true)
                .successResponse(successResponse)
                .build();
        when(ordersServiceMock.createOrder(any())).thenReturn(orderResponse);

        // Initialize TradingBot with mocks
        bot = new TradingBot(ordersServiceMock, marketDataFetcherMock, testConfig, purchaseHistoryMock);
    }

    @Test
    void testInitialBuyConditionMet() throws Exception {
        // Simulate initial buy condition
        bot.evaluateInitialPurchase();

        // Check if the purchase is recorded in history
        assertTrue(purchaseHistoryMock.containsKey("TEST"));
        TradeInfo tradeInfo = purchaseHistoryMock.get("TEST");
        assertEquals(0.50, tradeInfo.getPurchasePrice());
    }

    @Test
    void testBuySkippedDueToNoDrop() throws Exception {
        // Change price change to -4% (doesn't meet 5% drop condition)
        when(marketDataFetcherMock.get24hPriceChangePercentage("TEST-USDC")).thenReturn(-4.0);

        bot.evaluateInitialPurchase();

        // Verify no purchase is recorded
        assertFalse(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testVerifyBuyEachCoinOnlyOnce() throws Exception {
        // Simulate initial buy condition
        bot.evaluateInitialPurchase();
        // Check if the purchase is recorded in history
        assertTrue(purchaseHistoryMock.containsKey("TEST"));
        TradeInfo tradeInfo = purchaseHistoryMock.get("TEST");
        double amount = tradeInfo.getAmount();
        bot.evaluateInitialPurchase();
        tradeInfo = purchaseHistoryMock.get("TEST");
        double newamount = tradeInfo.getAmount();
        assertEquals(amount, newamount);
    }

    @Test
    void testAverageDownNotYetAveragedDown() throws Exception {
        // Add an existing purchase
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.50, 0, 0, 0, 3));

        // Simulate price drop to average down
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.489);

        bot.executeTrade();

        // Verify the average down step is updated
        TradeInfo tradeInfo = purchaseHistoryMock.get("TEST");
        assertEquals(1, tradeInfo.getAverageDownStepIndex()); // Step index incremented
        assertEquals(tradeInfo.getPurchasePrice(), 0.4945);
    }

    @Test
    void testAverageDownSecondTime() throws Exception {
        // Add an existing purchase
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.50, 0, 0, 1, 3));

        // Simulate price drop to average down
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.479);

        bot.executeTrade();

        // Verify the average down step is updated
        TradeInfo tradeInfo = purchaseHistoryMock.get("TEST");
        assertEquals(2, tradeInfo.getAverageDownStepIndex()); // Step index incremented
        assertEquals(tradeInfo.getPurchasePrice(), 0.4895);
    }

    @Test
    void testProfitLevelReached() throws Exception {
        // Add a purchase with profit levels
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.51, 0, 0, 0, 3));

        // Simulate price increase to hit profit level
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.51);

        bot.executeTrade();

        // Verify profit level index is updated
        TradeInfo tradeInfo = purchaseHistoryMock.get("TEST");
        assertEquals(1, tradeInfo.getProfitLevelIndex());
    }

    @Test
    void testHighestProfitLevelReached() throws Exception {
        // Add a purchase with profit levels
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.509, 0, 2, 0, 3));

        // Simulate price increase to hit maximum profit level
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.55);

        bot.executeTrade();

        // Verify the coin was sold (removed from history)
        assertFalse(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testProfitDropSale() throws Exception {
        // Add a purchase reaching profit levels
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.61, 0, 3, 0, 3));

        // Simulate price drop below previously reached profit level
        // we were already at level 3 (10%) but are now below level 2 (5%)
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.5245);

        bot.executeTrade();

        // Verify the coin was sold (removed from history)
        assertFalse(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testProfitDropBelowFirstLevel() throws Exception {
        // Add a purchase reaching profit levels
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.61, 0, 1, 0, 3));

        // Simulate price drop below previously reached profit level
        // we were already at level 1 (2%) but are now below
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.5095);

        bot.executeTrade();

        // Verify the coin was not sold
        assertTrue(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testProfitDropBelowPurchase() throws Exception {
        // Add a purchase reaching profit levels
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.501, 0, 0, 0, 3));

        // Simulate price drops below purchase price but above stop loss
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.499);

        bot.executeTrade();

        // Verify the coin was not sold
        assertTrue(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void holdingOnHighestAverageDownStep() throws Exception {
        // Add a purchase reaching profit levels
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.501, 0, 0, 0, 3));

        // Simulate price drops below purchase price but above stop loss
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.499);

        bot.executeTrade();

        // Verify the coin was not sold
        assertTrue(purchaseHistoryMock.containsKey("TEST"));
    }


    @Test
    void testGetNumberOfHeldCoins() throws Exception {
        // Add a purchase reaching profit levels
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.501, 0, 0, 0, 3));
        // Add a purchase reaching profit levels
        purchaseHistoryMock.put("TEST2", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.501, 0, 0, 0, 3));
        int heldCoins = bot.getNumberOfHeldCoins();
        assertEquals(2, heldCoins);
    }

    @Test
    void testGetPurchaseMoney() throws Exception {
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now(), 0.501, 0, 0, 0, 3));
        purchaseHistoryMock.put("TEST2", new TradeInfo(
                    0.80, 80, LocalDateTime.now(), 0.501, 0, 0, 0, 3));

        when(marketDataFetcherMock.getUsdcBalance()).thenReturn(700.0);

        double money = bot.getBudgetForNextPurchase(700.0, 0.5);
        assertEquals(407, money);
    }

    @Test
    void testLogWithDifferentLevels() throws Exception {
        bot.log("TRACE", "This is a TRACE log.");
        bot.log("DEBUG", "This is a DEBUG log.");
        bot.log("INFO", "This is an INFO log.");
        bot.log("ERROR", "This is an ERROR log.");
        // No assertions needed, just ensure no exceptions are thrown
    }


    @Test
    void testTimeBasedSelling_ThreeWeeksMinus1Percent() throws Exception {
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now().minusWeeks(3).minusDays(1), 0.50, 0, 0, 3, 3));

        // Price dropped exactly -1%
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.495);

        bot.executeTrade();

        // Verify the coin was sold
        assertFalse(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testTimeBasedSelling_FiveWeeksMinus2Percent() throws Exception {
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now().minusWeeks(5).minusDays(1), 0.50, 0, 0, 3, 3));

        // Price dropped exactly -2%
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.49);

        bot.executeTrade();

        // Verify the coin was sold
        assertFalse(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testTimeBasedSelling_FiveWeeksMinus5Percent() throws Exception {
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now().minusWeeks(5).minusDays(1), 0.50, 0, 0, 3, 3));

        // Price dropped exactly -5%
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.475);

        bot.executeTrade();

        // Verify the coin was sold
        assertFalse(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testTimeBasedSelling_NotTriggeredEarly() throws Exception {
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now().minusWeeks(3).minusDays(1), 0.50, 0, 0, 3, 3));

        // Price has dropped 3.5% and we have the coin since 3 weeks. Should not sell
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.4825);

        bot.executeTrade();

        // Verify the coin was not sold
        assertTrue(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testTimeBasedSelling_ExactlyOnBoundary() throws Exception {
        purchaseHistoryMock.put("TEST", new TradeInfo(
                0.50, 100, LocalDateTime.now().minusWeeks(4).minusDays(1), 0.50, 0, 0, 3, 3));

        // Price dropped exactly -4%
        when(marketDataFetcherMock.getCurrentPrice("TEST-USDC")).thenReturn(0.48);

        bot.executeTrade();

        // Verify the coin was sold
        assertFalse(purchaseHistoryMock.containsKey("TEST"));
    }

    @Test
    void testStopTrading() throws Exception {
        bot.startTrading();
        bot.stopTrading();
        assertTrue(bot.scheduler.isShutdown());
    }


    @Test
    void testLoadAssetsFileNotExist() throws Exception {
        File file = new File("currentAssets.json");
        if (file.exists()) {
            file.delete();
        }
        Map<String, TradeInfo> assets = bot.loadAssets();
        assertTrue(assets.isEmpty());
    }
}