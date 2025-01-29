package org.netno;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;

public class WebServer {

    private final TradingBot tb;

    public WebServer(TradingBot tradingBot) {
        this.tb = tradingBot;
    }

    public void start() throws IOException {
        tb.log("INFO", "Starting Web Server...");
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new HeldCoinsHandler());
        server.setExecutor(null); // Use default executor
        server.start();
        tb.log("INFO", "Web server started on http://localhost:8080");
    }

    private class HeldCoinsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                tb.log("DEBUG", "Received request: " + exchange.getRequestURI());
                String response = generateHtmlResponse();
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                exchange.getResponseBody().write(response.getBytes());
                exchange.getResponseBody().close();
                tb.log("DEBUG", "Response successfully sent to client.");
            } catch (Exception e) {
                tb.log("ERROR", "Error handling request: " + e.getMessage());
                e.printStackTrace();
                String errorResponse = "<html><body><h1>500 Internal Server Error</h1><p>" + e.getMessage()
                        + "</p></body></html>";
                exchange.sendResponseHeaders(500, errorResponse.getBytes().length);
                exchange.getResponseBody().write(errorResponse.getBytes());
                exchange.getResponseBody().close();
            }
        }

        private String generateHtmlResponse() {
            StringBuilder html = new StringBuilder();
            try {
                tb.log("DEBUG", "Generating HTML response...");
                html.append("<!DOCTYPE html>");
                html.append("<html>");
                html.append("<head>");
                html.append("<meta http-equiv='refresh' content='10'>"); // Auto-refresh every 10 seconds
                html.append("<title>CoinbaseBot</title>");
                html.append("<style>");
                html.append("body { font-family: Arial, sans-serif; margin: 0; padding: 0; }");
                html.append("table { border-collapse: collapse; width: 90%; margin: auto; }");
                html.append("th, td { border: 1px solid black; padding: 8px; text-align: center; }");
                html.append("th { background-color: #f2f2f2; }");
                html.append(".profit { color: green; }");
                html.append(".loss { color: red; }");
                html.append(".cash-info { text-align: center; font-size: 18px; margin-top: 20px; }");
                html.append(".datetime { position: absolute; top: 10px; left: 10px; font-size: 12px; color: gray; }");
                html.append(
                        ".collapsible { cursor: pointer; padding: 10px; text-align: left; background-color: #f2f2f2; border: none; outline: none; width: 90%; margin: auto; font-size: 16px; }");
                html.append(
                        ".content { padding: 10px 15px; display: none; background-color: #ffffff; width: 90%; margin: auto; }");
                html.append(".collapsible:after { content: '\\002B'; float: right; }");
                html.append(".active:after { content: '\\2212'; }");
                html.append(
                        "@media screen and (max-width: 600px) { table { font-size: 12px; } th, td { padding: 5px; } }");
                html.append("</style>");
                html.append("<script>");
                html.append("document.addEventListener('DOMContentLoaded', () => {");
                html.append("    const collapsibles = document.querySelectorAll('.collapsible');");
                html.append("    collapsibles.forEach(button => {");
                html.append("        button.addEventListener('click', () => {");
                html.append("            button.classList.toggle('active');");
                html.append("            const content = button.nextElementSibling;");
                html.append("            if (content.style.display === 'block') {");
                html.append("                content.style.display = 'none';");
                html.append("            } else {");
                html.append("                content.style.display = 'block';");
                html.append("            }");
                html.append("        });");
                html.append("    });");
                html.append("});");
                html.append("</script>");
                html.append("</head>");
                html.append("<body>");

                // Current Date and Time
                html.append("<div class='datetime'>")
                        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                        .append("</div>");

                // Held Coins Table
                html.append("<h1 style='text-align:center;'>CoinbaseBot</h1>");
                html.append("<table>");
                html.append("<tr>");
                html.append("<th>Coin</th>");
                html.append("<th>Purchase<br>Date</th>");
                html.append("<th>Days<br>Held</th>");
                html.append("<th>Average<br>Purchase<br>Price</th>");
                html.append("<th>Current<br>Price</th>");
                html.append("<th>Current<br>Value<br>(USDC)</th>");
                html.append("<th>Stop<br>Loss<br>at</th>");
                html.append("<th>Win/Loss<br>(%)</th>");
                html.append("<th>Win/Loss<br>(USDC)</th>");
                html.append("<th>Highest<br>Profit<br>Level</th>");
                html.append("<th>Average<br>Down<br>Steps</th>");
                html.append("</tr>");

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                Map<String, TradeInfo> purchaseHistory = tb.getPurchaseHistory();
                purchaseHistory.forEach((coin, tradeInfo) -> {
                    double currentPrice;
                    try {
                        currentPrice = tb.getMarketDataFetcher().getCurrentPrice(coin + "-USDC");
                    } catch (Exception e) {
                        currentPrice = 0.0; // Fallback if fetching fails
                    }

                    double currentValue = currentPrice * tradeInfo.getAmount();
                    double winLossPercent = ((currentPrice - tradeInfo.getPurchasePrice())
                            / tradeInfo.getPurchasePrice()) * 100;
                    double winLossUSDC = currentValue - (tradeInfo.getAmount() * tradeInfo.getPurchasePrice());
                    long daysHeld = ChronoUnit.DAYS.between(tradeInfo.getPurchaseDate(), LocalDateTime.now());
                    String profitLevel = String.format("%d (%.2f%%)", tradeInfo.getProfitLevelIndex(),
                            tb.config.profitLevels.get(tradeInfo.getProfitLevelIndex()));
                    String averageDownStep = String.format("%d (%.2f%%)", tradeInfo.getAverageDownStepIndex(),
                            tb.config.averageDownSteps.get(tradeInfo.getAverageDownStepIndex()));

                    html.append("<tr>");
                    html.append("<td>").append(coin).append("</td>");
                    html.append("<td>").append(tradeInfo.getPurchaseDate().format(formatter)).append("</td>");
                    html.append("<td>").append(daysHeld).append("</td>");
                    html.append("<td>")
                            .append(String.format("%.6f", tradeInfo.getPurchasePrice()).replaceAll("\\.?0+$", ""))
                            .append("</td>");
                    html.append("<td>").append(String.format("%.6f", currentPrice).replaceAll("\\.?0+$", ""))
                            .append("</td>");
                    html.append("<td>").append(String.format("%.2f", currentValue)).append("</td>");
                    html.append("<td>")
                            .append(String.format("%.6f", tradeInfo.getTrailingStopLoss()).replaceAll("\\.?0+$", ""))
                            .append("</td>");
                    html.append("<td class='").append(winLossPercent >= 0 ? "profit" : "loss").append("'>")
                            .append(String.format("%.2f%%", winLossPercent)).append("</td>");
                    html.append("<td class='").append(winLossUSDC >= 0 ? "profit" : "loss").append("'>")
                            .append(String.format("%.2f $", winLossUSDC)).append("</td>");
                    html.append("<td>").append(tradeInfo.getProfitLevelIndex() > 0 ? profitLevel : "None")
                            .append("</td>");
                    html.append("<td>").append(tradeInfo.getAverageDownStepIndex() > 0 ? averageDownStep : "None")
                            .append("</td>");
                    html.append("</tr>");
                });

                html.append("</table>");

                // Display Current USDC Cash
                html.append("<div class='cash-info'>");
                html.append("Current USDC Cash: ").append(String.format("%.2f USDC", tb.usdcBalance));
                html.append("</div>");

                // Collapsible Profit Levels Section
                html.append("<button class='collapsible'>Profit Levels</button>");
                html.append("<div class='content'>");
                List<Double> profitLevels = tb.config.profitLevels;
                for (int i = 0; i < profitLevels.size(); i++) {
                    html.append("<p>Level ").append(i).append(": ").append(profitLevels.get(i)).append("%</p>");
                }
                html.append("</div>");

                // Collapsible Average Down Steps Section
                html.append("<button class='collapsible'>Average Down Levels</button>");
                html.append("<div class='content'>");
                List<Double> averageDownSteps = tb.config.averageDownSteps;
                for (int i = 0; i < averageDownSteps.size(); i++) {
                    html.append("<p>Step ").append(i).append(": ").append(averageDownSteps.get(i)).append("%</p>");
                }
                html.append("</div>");

                html.append("</body>");
                html.append("</html>");
            } catch (Exception e) {
                tb.log("ERROR", "Error generating HTML response: " + e.getMessage());
                e.printStackTrace();
                return "<html><body><h1>Error Generating Response</h1><p>" + e.getMessage() + "</p></body></html>";
            }
            return html.toString();
        }
    }
}