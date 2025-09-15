package model;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Order Book for managing buy and sell orders
 * Implements price-time priority matching
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
     * Add a buy order to the book
     */
    public synchronized void addBuyOrder(Order order) {
        buyOrders.offer(order);
    }
    
    /**
     * Add a sell order to the book
     */
    public synchronized void addSellOrder(Order order) {
        sellOrders.offer(order);
    }
    
    /**
     * Match orders in the book
     * @return List of executed trades
     */
    public synchronized List<Trade> matchOrders() {
        List<Trade> trades = new ArrayList<>();
        
        while (!buyOrders.isEmpty() && !sellOrders.isEmpty()) {
            Order buyOrder = buyOrders.peek();
            Order sellOrder = sellOrders.peek();
            
            // Check if orders can be matched
            if (buyOrder.getPrice() >= sellOrder.getPrice()) {
                int tradeQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
                double tradePrice = (buyOrder.getPrice() + sellOrder.getPrice()) / 2;
                
                // Create trade
                Trade trade = new Trade(
                    buyOrder.getTrader().getLocalName(),
                    "EXECUTED",
                    buyOrder.getSymbol(),
                    tradeQuantity,
                    tradePrice
                );
                trades.add(trade);
                
                // Update order quantities
                buyOrder.setQuantity(buyOrder.getQuantity() - tradeQuantity);
                sellOrder.setQuantity(sellOrder.getQuantity() - tradeQuantity);
                
                // Remove fully executed orders
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
                break; // No more matches possible
            }
        }
        
        return trades;
    }
    
    /**
     * Get the best bid price
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
     * Get the best ask price
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
     * Get depth of buy orders
     */
    public int getBuyOrdersCount() {
        return buyOrders.size();
    }
    
    /**
     * Get depth of sell orders
     */
    public int getSellOrdersCount() {
        return sellOrders.size();
    }
    
    /**
     * Get total buy volume
     */
    public int getTotalBuyVolume() {
        int volume = 0;
        for (Order order : buyOrders) {
            volume += order.getQuantity();
        }
        return volume;
    }
    
    /**
     * Get total sell volume
     */
    public int getTotalSellVolume() {
        int volume = 0;
        for (Order order : sellOrders) {
            volume += order.getQuantity();
        }
        return volume;
    }
    
    /**
     * Cancel an order
     */
    public synchronized boolean cancelOrder(Order order) {
        boolean removed = buyOrders.remove(order) || sellOrders.remove(order);
        if (removed) {
            order.setStatus("CANCELLED");
        }
        return removed;
    }
    
    /**
     * Get market depth information
     */
    public String getMarketDepth() {
        StringBuilder depth = new StringBuilder();
        depth.append("=== Market Depth ===\n");
        depth.append("BUY Orders: ").append(buyOrders.size()).append("\n");
        depth.append("SELL Orders: ").append(sellOrders.size()).append("\n");
        depth.append("Buy Volume: ").append(getTotalBuyVolume()).append("\n");
        depth.append("Sell Volume: ").append(getTotalSellVolume()).append("\n");
        depth.append("Best Bid: $").append(String.format("%.2f", getBestBid())).append("\n");
        depth.append("Best Ask: $").append(String.format("%.2f", getBestAsk())).append("\n");
        return depth.toString();
    }
}