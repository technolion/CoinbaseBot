package org.netno;

import static spark.Spark.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class WebServer {

    private final TradingBot tradingBot;

    public WebServer(TradingBot tradingBot) {
        this.tradingBot = tradingBot;
    }

    public void start() {
        port(8080); // Set the port for the web server

        // Log unhandled exceptions
        exception(Exception.class, (e, req, res) -> {
            e.printStackTrace();
            res.status(500);
            res.body("Internal Server Error: " + e.getMessage());
        });

        get("/", (req, res) -> {
            res.type("text/html");
            try {
                return generateHeldCoinsHtml();
            } catch (Exception e) {
                e.printStackTrace(); // Log the exception
                return "<h1>Internal Server Error</h1><p>" + e.getMessage() + "</p>";
            }
        });
    }

    private String generateHeldCoinsHtml() {
        StringBuilder html = new StringBuilder();

        // HTML header with auto-reload and styles
        html.append("<html><head><title>Held Coins</title>");
        html.append("<meta http-equiv='refresh' content='10'>"); // Reload every 10 seconds
        html.append("<style>");
        html.append("table { border-collapse: collapse; width: 100%; }");
        html.append("th, td { border: 1px solid black; padding: 3px; text-align: left; }");
        html.append("th { background-color: #f2f2f2; }");
        html.append("</style>");
        html.append("</head><body>");

        // Table heading
        html.append("<h1>Currently Held Coins</h1>");
        html.append("<table>");
        html.append("<tr>");
        html.append("<th>Coin</th>");
        html.append("<th>Purchase Date</th>");
        html.append("<th>Days Held</th>");
        html.append("<th>Average Purchase Price</th>");
        html.append("<th>Current Price</th>");
        html.append("<th>Win/Loss (%)</th>");
        html.append("<th>Win/Loss (USDC)</th>");
        html.append("<th>Profit Level</th>");
        html.append("<th>Average Down Steps</th>");
        html.append("</tr>");

        // Table rows with data
        try {
            Map<String, TradeInfo> purchaseHistory = tradingBot.getPurchaseHistory();
            MarketDataFetcher marketDataFetcher = tradingBot.getMarketDataFetcher();

            if (purchaseHistory == null || marketDataFetcher == null) {
                throw new IllegalStateException("Purchase history or market data fetcher is not initialized");
            }

            for (Map.Entry<String, TradeInfo> entry : purchaseHistory.entrySet()) {
                String coin = entry.getKey();
                TradeInfo tradeInfo = entry.getValue();

                // Format purchase date
                LocalDateTime purchaseDate = tradeInfo.getPurchaseDate();
                String formattedPurchaseDate = purchaseDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                long daysHeld = ChronoUnit.DAYS.between(purchaseDate, LocalDateTime.now());
                double purchasePrice = tradeInfo.getPurchasePrice();
                double heldAmount = tradeInfo.getAmount();

                double currentPrice;
                try {
                    currentPrice = marketDataFetcher.getCurrentPrice(coin + "-USDC");
                } catch (Exception e) {
                    currentPrice = 0.0;
                    e.printStackTrace();
                }

                double totalPurchaseValue = heldAmount * purchasePrice;
                double totalCurrentValue = heldAmount * currentPrice;
                double winLoss = ((currentPrice - purchasePrice) / purchasePrice) * 100.0;
                double winLossUSDC = totalCurrentValue - totalPurchaseValue;

                String profitLevel = tradeInfo.getProfitLevelIndex() > 0
                        ? String.format("Level %d", tradeInfo.getProfitLevelIndex())
                        : "N/A";

                String row = String.format(
                        "<tr>" +
                                "<td>%s</td>" +
                                "<td>%s</td>" +
                                "<td>%d</td>" +
                                "<td>%.6f</td>" +
                                "<td>%.6f</td>" +
                                "<td style='color:%s'>%.2f%%</td>" +
                                "<td style='color:%s'>%.2f USDC</td>" +
                                "<td>%s</td>" +
                                "<td>%d</td>" +
                                "</tr>",
                        coin, // Coin name
                        formattedPurchaseDate, // Purchase date (formatted)
                        daysHeld, // Days held
                        purchasePrice, // Average purchase price
                        currentPrice, // Current price
                        (winLoss >= 0 ? "green" : "red"), winLoss, // Win/Loss percentage
                        (winLossUSDC >= 0 ? "green" : "red"), winLossUSDC, // Win/Loss in USDC
                        profitLevel, // Profit level reached
                        tradeInfo.getAverageDownStepIndex() // Average down steps
                );

                html.append(row);
            }
        } catch (Exception e) {
            e.printStackTrace();
            html.append("<tr><td colspan='9'>Error generating table: ").append(e.getMessage()).append("</td></tr>");
        }

        html.append("</table>");
        html.append("</body></html>");
        return html.toString();
    }
}