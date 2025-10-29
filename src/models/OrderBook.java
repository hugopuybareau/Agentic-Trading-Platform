package src.models;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Carnet d'ordres pour la gestion des ordres d'achat et de vente
 * Implemente l'appariement selon la priorite prix-temps
 */
public class OrderBook {
    
    private Queue<Order> buyOrders;
    private Queue<Order> sellOrders;
    private List<Order> executedOrders;
    
    public OrderBook() {
        this.buyOrders = new ConcurrentLinkedQueue<>();
        this.sellOrders = new ConcurrentLinkedQueue<>();
        this.executedOrders = new ArrayList<>();
    }
    
    /**
     * Ajoute un ordre d'achat au carnet
     */
    public synchronized void addBuyOrder(Order order) {
        buyOrders.offer(order);
    }
    
    /**
     * Ajoute un ordre de vente au carnet
     */
    public synchronized void addSellOrder(Order order) {
        sellOrders.offer(order);
    }
    
    /**
     * Apparie les ordres dans le carnet
     * @return Liste des transactions executees
     */
    public synchronized List<Trade> matchOrders() {
        List<Trade> trades = new ArrayList<>();
        
        while (!buyOrders.isEmpty() && !sellOrders.isEmpty()) {
            Order buyOrder = buyOrders.peek();
            Order sellOrder = sellOrders.peek();
            
            // Verifie si les ordres peuvent etre apparies
            if (buyOrder.getPrice() >= sellOrder.getPrice()) {
                int tradeQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
                double tradePrice = (buyOrder.getPrice() + sellOrder.getPrice()) / 2;
                
                // Cree la transaction
                Trade trade = new Trade(
                    buyOrder.getTrader().getLocalName(),
                    "EXECUTED",
                    buyOrder.getSymbol(),
                    tradeQuantity,
                    tradePrice
                );
                trades.add(trade);
                
                // Met a jour les quantites des ordres
                buyOrder.setQuantity(buyOrder.getQuantity() - tradeQuantity);
                sellOrder.setQuantity(sellOrder.getQuantity() - tradeQuantity);
                
                // Retire les ordres completement executes
                if (buyOrder.getQuantity() == 0) {
                    buyOrders.poll();
                    buyOrder.setStatus("EXECUTED");
                    executedOrders.add(buyOrder);
                }
                
                if (sellOrder.getQuantity() == 0) {
                    sellOrders.poll();
                    sellOrder.setStatus("EXECUTED");
                    executedOrders.add(sellOrder);
                }
            } else {
                break; // Plus d'appariements possibles
            }
        }
        
        return trades;
    }
    
    /**
     * Obtient le meilleur prix d'achat (bid)
     */
    public double getBestBid() {
        if (buyOrders.isEmpty()) return 0;
        
        double maxBid = 0;
        for (Order order : buyOrders) {
            if (order.getPrice() > maxBid) {
                maxBid = order.getPrice();
            }
        }
        return maxBid;
    }
    
    /**
     * Obtient le meilleur prix de vente (ask)
     */
    public double getBestAsk() {
        if (sellOrders.isEmpty()) return Double.MAX_VALUE;
        
        double minAsk = Double.MAX_VALUE;
        for (Order order : sellOrders) {
            if (order.getPrice() < minAsk) {
                minAsk = order.getPrice();
            }
        }
        return minAsk;
    }
    
    /**
     * Obtient la profondeur des ordres d'achat
     */
    public int getBuyOrdersCount() {
        return buyOrders.size();
    }
    
    /**
     * Obtient la profondeur des ordres de vente
     */
    public int getSellOrdersCount() {
        return sellOrders.size();
    }
    
    /**
     * Obtient le volume total des ordres d'achat
     */
    public int getTotalBuyVolume() {
        int volume = 0;
        for (Order order : buyOrders) {
            volume += order.getQuantity();
        }
        return volume;
    }
    
    /**
     * Obtient le volume total des ordres de vente
     */
    public int getTotalSellVolume() {
        int volume = 0;
        for (Order order : sellOrders) {
            volume += order.getQuantity();
        }
        return volume;
    }
    
    /**
     * Annule un ordre
     */
    public synchronized boolean cancelOrder(Order order) {
        boolean removed = buyOrders.remove(order) || sellOrders.remove(order);
        if (removed) {
            order.setStatus("CANCELLED");
        }
        return removed;
    }
    
    /**
     * Obtient les informations de profondeur du marche
     */
    public String getMarketDepth() {
        StringBuilder depth = new StringBuilder();
        depth.append("=== Profondeur du Marche ===\n");
        depth.append("Ordres ACHAT: ").append(buyOrders.size()).append("\n");
        depth.append("Ordres VENTE: ").append(sellOrders.size()).append("\n");
        depth.append("Volume Achat: ").append(getTotalBuyVolume()).append("\n");
        depth.append("Volume Vente: ").append(getTotalSellVolume()).append("\n");
        depth.append("Meilleur Bid: $").append(String.format("%.2f", getBestBid())).append("\n");
        depth.append("Meilleur Ask: $").append(String.format("%.2f", getBestAsk())).append("\n");
        return depth.toString();
    }
}