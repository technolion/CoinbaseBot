package org.netno;

import java.io.Serializable;
import java.time.LocalDateTime;

public class TradeInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final double purchasePrice;
    private final LocalDateTime purchaseDate;

    public TradeInfo(double purchasePrice, LocalDateTime purchaseDate) {
        this.purchasePrice = purchasePrice;
        this.purchaseDate = purchaseDate;
    }

    public double getPurchasePrice() {
        return purchasePrice;
    }

    public LocalDateTime getPurchaseDate() {
        return purchaseDate;
    }
}