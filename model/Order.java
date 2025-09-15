package model;

import jade.core.AID;

/**
 * Order representation for the trading system
 */
public class Order {
    
    private AID trader;
    private String type; // BUY or SELL
    private String symbol;
    private int quantity;
    private double price;
    private long timestamp;
    private String status; // PENDING, EXECUTED, CANCELLED
    
    public Order(AID trader, String type, String symbol, int quantity, double price) {
        this.trader = trader;
        this.type = type;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.timestamp = System.currentTimeMillis();
        this.status = "PENDING";
    }
    
    // Getters
    public AID getTrader() { return trader; }
    public String getType() { return type; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public long getTimestamp() { return timestamp; }
    public String getStatus() { return status; }
    
    // Setters
    public void setStatus(String status) { this.status = status; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    
    @Override
    public String toString() {
        return String.format("%s:%s:%s:%d:%.2f:%s", 
            trader.getLocalName(), type, symbol, quantity, price, status);
    }
}