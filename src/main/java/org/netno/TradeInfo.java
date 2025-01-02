package org.netno;

import java.io.Serializable;
import java.time.LocalDateTime;

public class TradeInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private double purchasePrice;
    private double amount;
    private final LocalDateTime purchaseDate;

    public TradeInfo(double purchasePrice, double amount, LocalDateTime purchaseDate) {
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
        // Calculate new weighted average price
        double totalCost = (purchasePrice * amount) + (additionalPrice * additionalAmount);
        double totalAmount = amount + additionalAmount;

        this.purchasePrice = totalCost / totalAmount;
        this.amount = totalAmount;
    }
}