package org.netno;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public class TradeInfo {

    private double purchasePrice;           // Average purchase price
    private double amount;                  // Amount of coins held
    private LocalDateTime purchaseDate;     // Date of purchase
    private double highestPrice;            // Highest price observed
    private double trailingStopLoss;        // Current trailing stop-loss price
    private int profitLevelIndex;           // Index of the last reached profit level
    private boolean averagedDown;           // Whether the coin was averaged down

    // Constructor with parameters for JSON deserialization
    @JsonCreator
    public TradeInfo(
            @JsonProperty("purchasePrice") double purchasePrice,
            @JsonProperty("amount") double amount,
            @JsonProperty("purchaseDate") LocalDateTime purchaseDate,
            @JsonProperty("highestPrice") double highestPrice,
            @JsonProperty("trailingStopLoss") double trailingStopLoss,
            @JsonProperty("profitLevelIndex") int profitLevelIndex,
            @JsonProperty("averagedDown") boolean averagedDown) {
        this.purchasePrice = purchasePrice;
        this.amount = amount;
        this.purchaseDate = purchaseDate;
        this.highestPrice = highestPrice;
        this.trailingStopLoss = trailingStopLoss;
        this.profitLevelIndex = profitLevelIndex;
        this.averagedDown = averagedDown;
    }

    // Constructor for new purchases
    public TradeInfo(double purchasePrice, double amount, LocalDateTime purchaseDate,
                     double highestPrice, double trailingStopLoss) {
        this.purchasePrice = purchasePrice;
        this.amount = amount;
        this.purchaseDate = purchaseDate;
        this.highestPrice = highestPrice;
        this.trailingStopLoss = trailingStopLoss;
        this.profitLevelIndex = 0;  // Start with the first profit level
        this.averagedDown = false; // Default to not averaged down
    }

    // Getter methods
    public double getPurchasePrice() {
        return purchasePrice;
    }

    public double getAmount() {
        return amount;
    }

    public LocalDateTime getPurchaseDate() {
        return purchaseDate;
    }

    public double getHighestPrice() {
        return highestPrice;
    }

    public double getTrailingStopLoss() {
        return trailingStopLoss;
    }

    public int getProfitLevelIndex() {
        return profitLevelIndex;
    }

    public boolean hasAveragedDown() {
        return averagedDown;
    }

    // Update purchase info for averaging down
    public void updatePurchase(double newPrice, double additionalAmount) {
        double totalValue = (purchasePrice * amount) + (newPrice * additionalAmount);
        amount += additionalAmount;
        purchasePrice = totalValue / amount;
    }

    // Update stop-loss based on current price
    public void updateStopLoss(double currentPrice, double trailingStopLossPercent) {
        if (currentPrice > highestPrice) {
            highestPrice = currentPrice; // Update the highest price seen
            trailingStopLoss = highestPrice * (1 - trailingStopLossPercent / 100.0); // Adjust stop-loss
        }
    }

    // Increment profit level index
    public void setProfitLevelIndex(int newIndex) {
        profitLevelIndex = newIndex;
    }

    // Set averaged down flag
    public void setAveragedDown(boolean averagedDown) {
        this.averagedDown = averagedDown;
    }

    // Set a new trailing stop-loss manually
    public void setTrailingStopLoss(double newStopLoss) {
        this.trailingStopLoss = newStopLoss;
    }
}