package org.netno;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class TradeInfo {

    double purchasePrice; // Average purchase price
    double amount; // Amount of coins held
    LocalDateTime purchaseDate; // Date of purchase
    double highestPrice; // Highest price observed
    double purchaseFee; // Highest price observed
    int averageDownStepIndex; // Index of the last reached average down step
    int decimalPlaces; // number of places after the decimal point for the coin

    // Constructor with parameters for JSON deserialization
    @JsonCreator
    public TradeInfo(
            @JsonProperty("purchasePrice") double purchasePrice,
            @JsonProperty("amount") double amount,
            @JsonProperty("purchaseDate") LocalDateTime purchaseDate,
            @JsonProperty("highestPrice") double highestPrice,
            @JsonProperty("purchaseFee") double purchaseFee,
            @JsonProperty("averageDownStepIndex") int averageDownStepIndex,
            @JsonProperty("decimalPlaces") int decimalPlaces) {
        this.purchasePrice = purchasePrice;
        this.amount = amount;
        this.purchaseDate = purchaseDate;
        this.highestPrice = highestPrice;
        this.purchaseFee = purchaseFee;
        this.averageDownStepIndex = averageDownStepIndex;
        this.decimalPlaces = decimalPlaces;
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

    public double getPurchaseFee() {
        return purchaseFee;
    }

    public void setPurchaseFee(double newPurchaseFee) {
        this.purchaseFee = newPurchaseFee;
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

    @JsonIgnore
    public long getWeeks() {
        return ChronoUnit.WEEKS.between(this.purchaseDate, LocalDateTime.now());
    }

    @JsonIgnore
    public double getInvest() {
        return amount * purchasePrice;
    }

    @JsonIgnore
    public double getWinLossIncludingFees(double currentPrice, double takerFeePercentage) {
        double currentValue = currentPrice * amount;
        double winLoss =  currentValue
                - (amount * purchasePrice)  //what we initially paid
                - purchaseFee               // purchase fee when buying
                - calculateTakerFee(currentValue, takerFeePercentage);  // purchase fee when selling
        return winLoss;
    }

    // Update purchase info for averaging down
    public void updatePurchase(double newPrice, double additionalAmount, double takerFeePercentage) {
        double totalValue = (purchasePrice * amount) + (newPrice * additionalAmount);
        amount += additionalAmount;
        String newAveragePrice = BigDecimal.valueOf(totalValue / amount)
                .setScale(6, RoundingMode.HALF_DOWN).toString();
        purchasePrice = Double.parseDouble(newAveragePrice);
        //reset highest price
        highestPrice = purchasePrice;

        purchaseFee += calculateTakerFee(newPrice * additionalAmount, takerFeePercentage);
        averageDownStepIndex++;
    }

    private double calculateTakerFee(double value, double takerFeePercentage) {
        // calculate the purchase fee
        double fee = value * takerFeePercentage / 100.0;
        // round the purchase fee to 2 digits after the comma
        String roundedPurchaseFee = BigDecimal.valueOf(fee)
                .setScale(2, RoundingMode.HALF_DOWN)
                .toPlainString();

        return (Double.parseDouble(roundedPurchaseFee));
    }

}
