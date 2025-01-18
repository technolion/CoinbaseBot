package org.netno;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.List;

public class WebServer {

    private final TradingBot tradingBot;

    public WebServer(TradingBot tradingBot) {
        this.tradingBot = tradingBot;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new HeldCoinsHandler());
        server.setExecutor(null); // Use default executor
        server.start();
        System.out.println("Web server started on http://localhost:8080");
    }

    private class HeldCoinsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = generateHtmlResponse();
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();
        }

        private String generateHtmlResponse() {
            StringBuilder html = new StringBuilder();

            html.append("<!DOCTYPE html>");
            html.append("<html>");
            html.append("<head>");
            html.append("<meta http-equiv='refresh' content='10'>"); // Auto-refresh every 10 seconds
            html.append("<title>Held Coins</title>");
            html.append("<style>");
            html.append("table { border-collapse: collapse; width: 80%; margin: auto; }");
            html.append("th, td { border: 1px solid black; padding: 3px; text-align: center; }");
            html.append("th { background-color: #f2f2f2; }");
            html.append(".profit { color: green; }");
            html.append(".loss { color: red; }");
            html.append("</style>");
            html.append("</head>");
            html.append("<body>");
            html.append("<h1 style='text-align:center;'>Held Coins</h1>");
            html.append("<table>");
            html.append("<tr>");
            html.append("<th>Coin</th>");
            html.append("<th>Purchase Date</th>");
            html.append("<th>Days Held</th>");
            html.append("<th>Average Purchase Price</th>");
            html.append("<th>Current Price</th>");
            html.append("<th>Stop Loss at</th>");
            html.append("<th>Win/Loss (%)</th>");
            html.append("<th>Win/Loss (USDC)</th>");
            html.append("<th>Highest Profit Level</th>");
            html.append("<th>Average Down Steps</th>");
            html.append("</tr>");

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            Map<String, TradeInfo> purchaseHistory = tradingBot.getPurchaseHistory();
            purchaseHistory.forEach((coin, tradeInfo) -> {
                double currentPrice;
                try {
                    currentPrice = tradingBot.getMarketDataFetcher().getCurrentPrice(coin + "-USDC");
                } catch (Exception e) {
                    currentPrice = 0.0; // Fallback if fetching fails
                }

                double winLossPercent = ((currentPrice - tradeInfo.getPurchasePrice()) / tradeInfo.getPurchasePrice()) * 100;
                double winLossUSDC = (currentPrice - tradeInfo.getPurchasePrice()) * tradeInfo.getAmount();
                long daysHeld = ChronoUnit.DAYS.between(tradeInfo.getPurchaseDate(), LocalDateTime.now());
                String profitLevel = String.format("%d (%.2f%%)", tradeInfo.getProfitLevelIndex(),
                    tradingBot.getProfitLevels().get(tradeInfo.getProfitLevelIndex()));
                String averageDownStep = String.format("%d (%.2f%%)", tradeInfo.getAverageDownStepIndex(),
                    tradingBot.getAverageDownSteps().get(tradeInfo.getAverageDownStepIndex()));
                
                html.append("<tr>");
                html.append("<td>").append(coin).append("</td>");
                html.append("<td>").append(tradeInfo.getPurchaseDate().format(formatter)).append("</td>");
                html.append("<td>").append(daysHeld).append("</td>");
                html.append("<td>").append(String.format("%.6f", tradeInfo.getPurchasePrice()).replaceAll("\\.?0+$", "")).append("</td>");
                html.append("<td>").append(String.format("%.6f", currentPrice).replaceAll("\\.?0+$", "")).append("</td>");
                html.append("<td>").append(String.format("%.6f", tradeInfo.getTrailingStopLoss()).replaceAll("\\.?0+$", "")).append("</td>");
                html.append("<td class='").append(winLossPercent >= 0 ? "profit" : "loss").append("'>")
                        .append(String.format("%.2f%%", winLossPercent)).append("</td>");
                html.append("<td class='").append(winLossUSDC >= 0 ? "profit" : "loss").append("'>")
                        .append(String.format("%.2f USDC", winLossUSDC)).append("</td>");
                html.append("<td>").append(tradeInfo.getProfitLevelIndex() > 0 ? profitLevel : "None").append("</td>");
                html.append("<td>").append(tradeInfo.getAverageDownStepIndex() > 0 ? averageDownStep : "None").append("</td>");
                html.append("</tr>");
            });

            html.append("</table>");

            // Add Profit Levels Section
            html.append("<h2 style='text-align:center;'>Profit Levels</h2>");
            html.append("<ul style='width: 80%; margin: auto;'>");
            List<Double> profitLevels = tradingBot.getProfitLevels();
            for (int i = 0; i < profitLevels.size(); i++) {
                html.append("<li>Level ").append(i).append(": ").append(profitLevels.get(i)).append("%</li>");
            }
            html.append("</ul>");

            // Add Average Down Steps Section
            html.append("<h2 style='text-align:center;'>Average Down Levels</h2>");
            html.append("<ul style='width: 80%; margin: auto;'>");
            List<Double> averageDownSteps = tradingBot.getAverageDownSteps();
            for (int i = 0; i < averageDownSteps.size(); i++) {
                html.append("<li>Step ").append(i).append(": ").append(averageDownSteps.get(i)).append("%</li>");
            }
            html.append("</ul>");

            html.append("</body>");
            html.append("</html>");

            return html.toString();
        }
    }
}