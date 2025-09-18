package src.model;

import java.util.*;

/**
 * Trade execution record
 */
public class Trade {
    
    private String traderName;
    private String action;
    private String symbol;
    private int quantity;
    private double price;
    private long timestamp;
    
    public Trade(String traderName, String action, String symbol, int quantity, double price) {
        this.traderName = traderName;
        this.action = action;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.timestamp = System.currentTimeMillis();
    }
    
    @Override
    public String toString() {
        return String.format("%s:%s:%s:%d:%.2f", 
            traderName, action, symbol, quantity, price);
    }
    
    // Getters
    public String getTraderName() { return traderName; }
    public String getAction() { return action; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public long getTimestamp() { return timestamp; }
}