package org.netno;

import com.coinbase.advanced.factory.CoinbaseAdvancedServiceFactory;
import com.coinbase.advanced.model.products.GetProductRequest;
import com.coinbase.advanced.model.products.GetProductResponse;
import com.coinbase.advanced.products.ProductsService;

public class MarketDataFetcher {
    private final ProductsService productsService;

    public MarketDataFetcher(CoinbaseBot bot) {
        this.productsService = CoinbaseAdvancedServiceFactory.createProductsService(bot.getClient());
    }

    // Get 24h price change percentage
    public double get24hPriceChange(String tradingPair) throws Exception {
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
}