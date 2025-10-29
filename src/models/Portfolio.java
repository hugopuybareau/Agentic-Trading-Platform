package src.models;

import java.util.*;

/**
 * Gestion de portfolio pour les traders
 * Suit le cash, les actions et les metriques de performance
 */
public class Portfolio {
    
    private String ownerName;
    private double cash;
    private Map<String, Integer> shares; // Symbole -> Quantite
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
     * Ajoute du cash au portfolio
     */
    public void addCash(double amount) {
        this.cash += amount;
    }
    
    /**
     * Retire du cash du portfolio
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
     * Ajoute des actions au portfolio
     */
    public void addShares(String symbol, int quantity) {
        shares.put(symbol, shares.getOrDefault(symbol, 0) + quantity);
    }
    
    /**
     * Retire des actions du portfolio
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
     * Obtient le nombre d'actions pour un symbole
     */
    public int getShares(String symbol) {
        return shares.getOrDefault(symbol, 0);
    }
    
    /**
     * Enregistre une transaction
     */
    public void recordTransaction(Transaction transaction) {
        transactionHistory.add(transaction);
        
        // Met a jour le P&L
        if (transaction.getType().equals("SELL")) {
            // Calcule le P&L realise (simplifie - suppose FIFO)
            realizedPnL += transaction.getPnL();
        }
        
        // Ajoute la commission
        totalCommissions += transaction.getCommission();
    }
    
    /**
     * Calcule la valeur du portfolio
     */
    public double calculateValue(double currentPrice) {
        double stockValue = 0;
        for (Map.Entry<String, Integer> entry : shares.entrySet()) {
            stockValue += entry.getValue() * currentPrice;
        }
        return cash + stockValue;
    }
    
    /**
     * Calcule le P&L non realise
     */
    public double calculateUnrealizedPnL(String symbol, double currentPrice, double avgCost) {
        int quantity = shares.getOrDefault(symbol, 0);
        return quantity * (currentPrice - avgCost);
    }
    
    /**
     * Obtient un resume du portfolio
     */
    public String getSummary(double currentPrice) {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Portfolio: ").append(ownerName).append(" ===\n");
        summary.append("Cash: $").append(String.format("%.2f", cash)).append("\n");
        
        for (Map.Entry<String, Integer> entry : shares.entrySet()) {
            summary.append(entry.getKey()).append(": ")
                   .append(entry.getValue()).append(" actions @ $")
                   .append(String.format("%.2f", currentPrice)).append("\n");
        }
        
        summary.append("Valeur totale: $")
               .append(String.format("%.2f", calculateValue(currentPrice))).append("\n");
        summary.append("P&L realise: $")
               .append(String.format("%.2f", realizedPnL)).append("\n");
        summary.append("Commissions totales: $")
               .append(String.format("%.2f", totalCommissions)).append("\n");
        
        return summary.toString();
    }

    /**
     * Acheter des actions - deduit le cash et ajoute les actions
     */
    public boolean buy(String symbol, int quantity, double price) {
        double totalCost = quantity * price;
        double commission = quantity * 0.01; // 0,01$ par action
        double totalWithCommission = totalCost + commission;
        
        if (cash >= totalWithCommission) {
            cash -= totalWithCommission;
            addShares(symbol, quantity);
            
            // Enregistre la transaction
            Transaction transaction = new Transaction("BUY", symbol, quantity, price);
            recordTransaction(transaction);
            
            return true;
        }
        return false;
    }

    /**
     * Vendre des actions - ajoute le cash et retire les actions
     */
    public boolean sell(String symbol, int quantity, double price) {
        if (removeShares(symbol, quantity)) {
            double totalReceived = quantity * price;
            double commission = quantity * 0.01; // 0,01$ par action
            double netReceived = totalReceived - commission;
            
            cash += netReceived;
            
            // Enregistre la transaction
            Transaction transaction = new Transaction("SELL", symbol, quantity, price);
            recordTransaction(transaction);
            
            return true;
        }
        return false;
    }

    /**
     * Verifie si on peut se permettre d'acheter
     */
    public boolean canAfford(String symbol, int quantity, double price) {
        double totalCost = quantity * price;
        double commission = quantity * 0.01;
        return cash >= (totalCost + commission);
    }

    /**
     * Obtient le cash disponible pour le trading
     */
    public double getAvailableCash() {
        return cash;
    }
    
    // Accesseurs (getters)
    public String getOwnerName() { return ownerName; }
    public double getCash() { return cash; }
    public Map<String, Integer> getAllShares() { return new HashMap<>(shares); }
    public List<Transaction> getTransactionHistory() { return new ArrayList<>(transactionHistory); }
    public double getRealizedPnL() { return realizedPnL; }
    public double getTotalCommissions() { return totalCommissions; }
}

/**
 * Enregistrement de transaction
 */
class Transaction {
    private String type; // BUY ou SELL
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
        this.commission = quantity * 0.01; // Commission de 0,01$ par action
        this.pnl = 0;
        this.timestamp = System.currentTimeMillis();
    }
    
    // Accesseurs (getters)
    public String getType() { return type; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public double getCommission() { return commission; }
    public double getPnL() { return pnl; }
    public long getTimestamp() { return timestamp; }
    
    // Mutateurs (setters)
    public void setPnL(double pnl) { this.pnl = pnl; }
}