package org.netno;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public class TradeInfo {

    private double purchasePrice; // Average purchase price
    private double amount; // Amount of coins held
    private LocalDateTime purchaseDate; // Date of purchase
    private double highestPrice; // Highest price observed
    private double trailingStopLoss; // Current trailing stop-loss price
    private int profitLevelIndex; // Index of the last reached profit level
    private int averageDownStepIndex; // Index of the last reached average down step
    private int decimalPlaces; //number of places after the decimal point for the coin

    // Constructor with parameters for JSON deserialization
    @JsonCreator
    public TradeInfo(
            @JsonProperty("purchasePrice") double purchasePrice,
            @JsonProperty("amount") double amount,
            @JsonProperty("purchaseDate") LocalDateTime purchaseDate,
            @JsonProperty("highestPrice") double highestPrice,
            @JsonProperty("trailingStopLoss") double trailingStopLoss,
            @JsonProperty("profitLevelIndex") int profitLevelIndex,
            @JsonProperty("averageDownStepIndex") Integer averageDownStepIndex,
            @JsonProperty("decimalPlaces") Integer decimalPlaces) {
        this.purchasePrice = purchasePrice;
        this.amount = amount;
        this.purchaseDate = purchaseDate;
        this.highestPrice = highestPrice;
        this.trailingStopLoss = trailingStopLoss;
        this.profitLevelIndex = profitLevelIndex;
        this.averageDownStepIndex = (averageDownStepIndex != null) ? averageDownStepIndex : 0;
        this.decimalPlaces = (decimalPlaces != null) ? decimalPlaces : 99;
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

    public void setTrailingStopLoss(double newStopLoss) {
        this.trailingStopLoss = newStopLoss;
    }

    public int getProfitLevelIndex() {
        return profitLevelIndex;
    }

    public void setProfitLevelIndex(int newIndex) {
        profitLevelIndex = newIndex;
    }

    public int getAverageDownStepIndex() {
        return averageDownStepIndex;
    }

    public void setAverageDownStepIndex(int index) {
        this.averageDownStepIndex = index;
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    public void setDecimalPlaces(int places) {
        this.decimalPlaces = places;
    }

    // Update purchase info for averaging down
    public void updatePurchase(double newPrice, double additionalAmount) {
        double totalValue = (purchasePrice * amount) + (newPrice * additionalAmount);
        amount += additionalAmount;
        String newAveragePrice = BigDecimal.valueOf(totalValue / amount)
                .setScale(decimalPlaces, RoundingMode.HALF_DOWN).toString();
        purchasePrice = Double.parseDouble(newAveragePrice);
    }

    // Update stop-loss based on current price
    public void updateStopLoss(double currentPrice, double trailingStopLossPercent) {
        if (currentPrice > highestPrice) {
            highestPrice = currentPrice; // Update the highest price seen
            trailingStopLoss = highestPrice * (1 - trailingStopLossPercent / 100.0); // Adjust stop-loss
            String newTrailingStopLoss = BigDecimal.valueOf(highestPrice * (1 - trailingStopLossPercent / 100.0))
                    .setScale(decimalPlaces, RoundingMode.HALF_DOWN).toString();
            trailingStopLoss = Double.parseDouble(newTrailingStopLoss);
        }
    }

}
