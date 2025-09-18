package src.model;

import java.util.*;

/**
 * Portfolio management for traders
 * Tracks cash, shares, and performance metrics
 */
public class Portfolio {
    
    private String ownerName;
    private double cash;
    private Map<String, Integer> shares; // Symbol -> Quantity
    private List<Transaction> transactionHistory;
    private double totalCommissions;
    private double realizedPnL;
    private double unrealizedPnL;
    
    public Portfolio(String ownerName) {
        this.ownerName = ownerName;
        this.cash = 0;
        this.shares = new HashMap<>();
        this.transactionHistory = new ArrayList<>();
        this.totalCommissions = 0;
        this.realizedPnL = 0;
        this.unrealizedPnL = 0;
    }
    
    /**
     * Add cash to portfolio
     */
    public void addCash(double amount) {
        this.cash += amount;
    }
    
    /**
     * Remove cash from portfolio
     */
    public boolean removeCash(double amount) {
        if (cash >= amount) {
            cash -= amount;
            return true;
        }
        return false;
    }

    /**
     * Calcule la valeur totale du portfolio (cash + actions)
     */
    public double getTotalValue(String symbol, double currentPrice) {
        double stockValue = getShares(symbol) * currentPrice;
        return cash + stockValue;
    }
    
    /**
     * Add shares to portfolio
     */
    public void addShares(String symbol, int quantity) {
        shares.put(symbol, shares.getOrDefault(symbol, 0) + quantity);
    }
    
    /**
     * Remove shares from portfolio
     */
    public boolean removeShares(String symbol, int quantity) {
        int currentShares = shares.getOrDefault(symbol, 0);
        if (currentShares >= quantity) {
            shares.put(symbol, currentShares - quantity);
            if (shares.get(symbol) == 0) {
                shares.remove(symbol);
            }
            return true;
        }
        return false;
    }
    
    /**
     * Get number of shares for a symbol
     */
    public int getShares(String symbol) {
        return shares.getOrDefault(symbol, 0);
    }
    
    /**
     * Record a transaction
     */
    public void recordTransaction(Transaction transaction) {
        transactionHistory.add(transaction);
        
        // Update P&L
        if (transaction.getType().equals("SELL")) {
            // Calculate realized P&L (simplified - assumes FIFO)
            realizedPnL += transaction.getPnL();
        }
        
        // Add commission
        totalCommissions += transaction.getCommission();
    }
    
    /**
     * Calculate portfolio value
     */
    public double calculateValue(double currentPrice) {
        double stockValue = 0;
        for (Map.Entry<String, Integer> entry : shares.entrySet()) {
            stockValue += entry.getValue() * currentPrice;
        }
        return cash + stockValue;
    }
    
    /**
     * Calculate unrealized P&L
     */
    public double calculateUnrealizedPnL(String symbol, double currentPrice, double avgCost) {
        int quantity = shares.getOrDefault(symbol, 0);
        return quantity * (currentPrice - avgCost);
    }
    
    /**
     * Get portfolio summary
     */
    public String getSummary(double currentPrice) {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Portfolio: ").append(ownerName).append(" ===\n");
        summary.append("Cash: $").append(String.format("%.2f", cash)).append("\n");
        
        for (Map.Entry<String, Integer> entry : shares.entrySet()) {
            summary.append(entry.getKey()).append(": ")
                   .append(entry.getValue()).append(" shares @ $")
                   .append(String.format("%.2f", currentPrice)).append("\n");
        }
        
        summary.append("Total Value: $")
               .append(String.format("%.2f", calculateValue(currentPrice))).append("\n");
        summary.append("Realized P&L: $")
               .append(String.format("%.2f", realizedPnL)).append("\n");
        summary.append("Total Commissions: $")
               .append(String.format("%.2f", totalCommissions)).append("\n");
        
        return summary.toString();
    }
    // Ajoutez ces méthodes à la classe Portfolio :

    /**
     * Buy shares - deduct cash and add shares
     */
    public boolean buy(String symbol, int quantity, double price) {
        double totalCost = quantity * price;
        double commission = quantity * 0.01; // $0.01 per share
        double totalWithCommission = totalCost + commission;
        
        if (cash >= totalWithCommission) {
            cash -= totalWithCommission;
            addShares(symbol, quantity);
            
            // Record transaction
            Transaction transaction = new Transaction("BUY", symbol, quantity, price);
            recordTransaction(transaction);
            
            return true;
        }
        return false;
    }

    /**
     * Sell shares - add cash and remove shares
     */
    public boolean sell(String symbol, int quantity, double price) {
        if (removeShares(symbol, quantity)) {
            double totalReceived = quantity * price;
            double commission = quantity * 0.01; // $0.01 per share
            double netReceived = totalReceived - commission;
            
            cash += netReceived;
            
            // Record transaction
            Transaction transaction = new Transaction("SELL", symbol, quantity, price);
            recordTransaction(transaction);
            
            return true;
        }
        return false;
    }

    /**
     * Check if we can afford to buy
     */
    public boolean canAfford(String symbol, int quantity, double price) {
        double totalCost = quantity * price;
        double commission = quantity * 0.01;
        return cash >= (totalCost + commission);
    }

    /**
     * Get available cash for trading
     */
    public double getAvailableCash() {
        return cash;
    }
    
    // Getters
    public String getOwnerName() { return ownerName; }
    public double getCash() { return cash; }
    public Map<String, Integer> getAllShares() { return new HashMap<>(shares); }
    public List<Transaction> getTransactionHistory() { return new ArrayList<>(transactionHistory); }
    public double getRealizedPnL() { return realizedPnL; }
    public double getTotalCommissions() { return totalCommissions; }
}

/**
 * Transaction record
 */
class Transaction {
    private String type; // BUY or SELL
    private String symbol;
    private int quantity;
    private double price;
    private double commission;
    private double pnl;
    private long timestamp;
    
    public Transaction(String type, String symbol, int quantity, double price) {
        this.type = type;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.commission = quantity * 0.01; // $0.01 per share commission
        this.pnl = 0;
        this.timestamp = System.currentTimeMillis();
    }
    
    // Getters
    public String getType() { return type; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public double getCommission() { return commission; }
    public double getPnL() { return pnl; }
    public long getTimestamp() { return timestamp; }
    
    // Setters
    public void setPnL(double pnl) { this.pnl = pnl; }
}