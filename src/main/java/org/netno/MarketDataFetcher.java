package org.netno;

import java.util.List;
import java.util.Optional;

import com.coinbase.advanced.client.CoinbaseAdvancedClient;
import com.coinbase.advanced.factory.CoinbaseAdvancedServiceFactory;
import com.coinbase.advanced.model.portfolios.GetPortfolioBreakdownRequest;
import com.coinbase.advanced.model.portfolios.GetPortfolioBreakdownResponse;
import com.coinbase.advanced.model.portfolios.ListPortfoliosRequest;
import com.coinbase.advanced.model.portfolios.ListPortfoliosResponse;
import com.coinbase.advanced.model.products.GetProductRequest;
import com.coinbase.advanced.model.products.GetProductResponse;
import com.coinbase.advanced.portfolios.PortfoliosService;
import com.coinbase.advanced.products.ProductsService;
import com.coinbase.advanced.model.portfolios.Portfolio;
import com.coinbase.advanced.model.portfolios.PortfolioBalances;

public class MarketDataFetcher {
    private final ProductsService productsService;
    private final PortfoliosService portfoliosService;
    private final Portfolio portfolio;

    public MarketDataFetcher(CoinbaseAdvancedClient client, String portfolioId) {
        this.productsService = CoinbaseAdvancedServiceFactory.createProductsService(client);
        this.portfoliosService = CoinbaseAdvancedServiceFactory.createPortfoliosService(client);
        this.portfolio = findPortfolioById(portfolioId);
    }

    // Method to get the current USDC balance
    public double getUsdcBalance() {

        if(portfolio == null) {
            return 0.0;
        }
        
        GetPortfolioBreakdownResponse getPortfolioBreakdownResponse = portfoliosService.getPortfolioBreakdown(new GetPortfolioBreakdownRequest(portfolio.getUuid()));
        PortfolioBalances balances = getPortfolioBreakdownResponse.getBreakdown().getPortfolioBalances();
        try {
            return Double.parseDouble(balances.getTotalCashEquivalentBalance().getValue());
        } catch (Exception e) {
            System.out.println("Could not fetch USDC balance!!");
            return 0;
        }
    }

    // Get 24h price change percentage
    public double get24hPriceChangePercentage(String tradingPair) throws Exception {
        GetProductRequest request = new GetProductRequest.Builder()
                .productId(tradingPair)
                .build();

        GetProductResponse response = productsService.getProduct(request);
        return Double.parseDouble(response.getPricePercentageChange24h());
    }

    // Get current price
    public double getCurrentPrice(String tradingPair) throws Exception {
        GetProductRequest request = new GetProductRequest.Builder()
                .productId(tradingPair)
                .build();

        GetProductResponse response = productsService.getProduct(request);
        return Double.parseDouble(response.getPrice());
    }

    private Portfolio findPortfolioById(String uuid) {
        ListPortfoliosRequest listReq = new ListPortfoliosRequest();
        ListPortfoliosResponse listResponse = portfoliosService.listPortfolios(listReq);

        List<Portfolio> portfolios = listResponse.getPortfolios();
        Optional<Portfolio> result = portfolios.stream()
                .filter(p -> p.getUuid().equalsIgnoreCase(uuid)) // Match by name (case-insensitive)
                .findFirst(); // Get the first match, if available

         // Return the portfolio or null if not found
         return result.orElse(null);
    }

    // Fetch precision for base currency size
    public double getBasePrecision(String tradingPair) throws Exception {
        GetProductRequest request = new GetProductRequest.Builder()
                .productId(tradingPair)
                .build();

        GetProductResponse response = productsService.getProduct(request);
        return Double.parseDouble(response.getBaseIncrement());
    }
}