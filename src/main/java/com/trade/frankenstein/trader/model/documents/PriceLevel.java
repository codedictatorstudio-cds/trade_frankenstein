package com.trade.frankenstein.trader.model.documents;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PriceLevel {
    private BigDecimal price;
    private BigDecimal quantity;
    private Integer orderCount;
}
