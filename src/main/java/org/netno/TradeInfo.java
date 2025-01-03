package org.netno;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.LocalDateTime;

public class TradeInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private double purchasePrice;
    private double amount;
    private LocalDateTime purchaseDate;

    @JsonCreator
    public TradeInfo(
            @JsonProperty("purchasePrice") double purchasePrice,
            @JsonProperty("amount") double amount,
            @JsonProperty("purchaseDate") LocalDateTime purchaseDate) {
        this.purchasePrice = purchasePrice;
        this.amount = amount;
        this.purchaseDate = purchaseDate;
    }

    public double getPurchasePrice() {
        return purchasePrice;
    }

    public double getAmount() {
        return amount;
    }

    public LocalDateTime getPurchaseDate() {
        return purchaseDate;
    }

    public void updatePurchase(double additionalPrice, double additionalAmount) {
        double totalCost = (purchasePrice * amount) + (additionalPrice * additionalAmount);
        double totalAmount = amount + additionalAmount;

        this.purchasePrice = totalCost / totalAmount;
        this.amount = totalAmount;
    }
}