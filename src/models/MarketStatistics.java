package src.model;

import java.util.*;

/**
 * Market Statistics calculator
 * Tracks market metrics and technical indicators
 */
public class MarketStatistics {
    
    private List<Double> priceHistory;
    private List<Integer> volumeHistory;
    private int maxHistorySize = 100;
    
    // Statistics
    private double high;
    private double low;
    private double open;
    private double close;
    private double vwap; // Volume Weighted Average Price
    private double volatility;
    private int totalVolume;
    private int numberOfTrades;
    
    // Technical indicators
    private double sma20; // 20-period Simple Moving Average
    private double sma50; // 50-period Simple Moving Average
    private double ema12; // 12-period Exponential Moving Average
    private double ema26; // 26-period Exponential Moving Average
    private double macd; // MACD indicator
    private double rsi; // Relative Strength Index
    
    public MarketStatistics() {
        this.priceHistory = new ArrayList<>();
        this.volumeHistory = new ArrayList<>();
        this.high = 0;
        this.low = Double.MAX_VALUE;
        this.open = 0;
        this.close = 0;
        this.totalVolume = 0;
        this.numberOfTrades = 0;
    }
    
    /**
     * Update statistics with new price
     */
    public void updatePrice(double price) {
        priceHistory.add(price);
        if (priceHistory.size() > maxHistorySize) {
            priceHistory.remove(0);
        }
        
        // Update OHLC
        if (open == 0) {
            open = price;
        }
        close = price;
        
        if (price > high) {
            high = price;
        }
        if (price < low) {
            low = price;
        }
        
        // Calculate indicators
        calculateMovingAverages();
        calculateVolatility();
        calculateRSI();
        calculateMACD();
    }
    
    /**
     * Update volume statistics
     */
    public void updateVolume(int volume) {
        volumeHistory.add(volume);
        if (volumeHistory.size() > maxHistorySize) {
            volumeHistory.remove(0);
        }
        
        totalVolume += volume;
        numberOfTrades++;
        
        calculateVWAP();
    }
    
    /**
     * Calculate Simple Moving Averages
     */
    private void calculateMovingAverages() {
        if (priceHistory.size() >= 20) {
            double sum20 = 0;
            for (int i = priceHistory.size() - 20; i < priceHistory.size(); i++) {
                sum20 += priceHistory.get(i);
            }
            sma20 = sum20 / 20;
        }
        
        if (priceHistory.size() >= 50) {
            double sum50 = 0;
            for (int i = priceHistory.size() - 50; i < priceHistory.size(); i++) {
                sum50 += priceHistory.get(i);
            }
            sma50 = sum50 / 50;
        }
    }
    
    /**
     * Calculate volatility (standard deviation)
     */
    private void calculateVolatility() {
        if (priceHistory.size() < 20) {
            volatility = 0.02; // Default 2%
            return;
        }
        
        // Calculate returns
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < priceHistory.size(); i++) {
            double returnVal = (priceHistory.get(i) - priceHistory.get(i-1)) / priceHistory.get(i-1);
            returns.add(returnVal);
        }
        
        // Calculate standard deviation of returns
        double mean = returns.stream().mapToDouble(r -> r).average().orElse(0);
        double variance = returns.stream()
            .mapToDouble(r -> Math.pow(r - mean, 2))
            .average().orElse(0);
        
        volatility = Math.sqrt(variance);
    }
    
    /**
     * Calculate Relative Strength Index
     */
    private void calculateRSI() {
        if (priceHistory.size() < 14) {
            rsi = 50; // Neutral
            return;
        }
        
        double gains = 0;
        double losses = 0;
        
        for (int i = priceHistory.size() - 14; i < priceHistory.size(); i++) {
            if (i > 0) {
                double change = priceHistory.get(i) - priceHistory.get(i - 1);
                if (change > 0) {
                    gains += change;
                } else {
                    losses -= change;
                }
            }
        }
        
        double avgGain = gains / 14;
        double avgLoss = losses / 14;
        
        if (avgLoss == 0) {
            rsi = 100;
        } else {
            double rs = avgGain / avgLoss;
            rsi = 100 - (100 / (1 + rs));
        }
    }
    
    /**
     * Calculate MACD
     */
    private void calculateMACD() {
        if (priceHistory.size() < 26) {
            macd = 0;
            return;
        }
        
        // Simplified EMA calculation
        ema12 = calculateEMA(12);
        ema26 = calculateEMA(26);
        macd = ema12 - ema26;
    }
    
    /**
     * Calculate Exponential Moving Average
     */
    private double calculateEMA(int period) {
        if (priceHistory.size() < period) {
            return close;
        }
        
        double multiplier = 2.0 / (period + 1);
        double ema = priceHistory.get(priceHistory.size() - period);
        
        for (int i = priceHistory.size() - period + 1; i < priceHistory.size(); i++) {
            ema = (priceHistory.get(i) - ema) * multiplier + ema;
        }
        
        return ema;
    }
    
    /**
     * Calculate Volume Weighted Average Price
     */
    private void calculateVWAP() {
        if (volumeHistory.isEmpty()) {
            vwap = close;
            return;
        }
        
        double sumPriceVolume = 0;
        double sumVolume = 0;
        
        int start = Math.max(0, priceHistory.size() - 20);
        for (int i = start; i < priceHistory.size() && i < volumeHistory.size(); i++) {
            sumPriceVolume += priceHistory.get(i) * volumeHistory.get(i);
            sumVolume += volumeHistory.get(i);
        }
        
        if (sumVolume > 0) {
            vwap = sumPriceVolume / sumVolume;
        } else {
            vwap = close;
        }
    }
    
    /**
     * Get market summary
     */
    public String getMarketSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Market Statistics ===\n");
        summary.append(String.format("OHLC: %.2f / %.2f / %.2f / %.2f\n", open, high, low, close));
        summary.append(String.format("Volume: %d trades, %d shares\n", numberOfTrades, totalVolume));
        summary.append(String.format("VWAP: $%.2f\n", vwap));
        summary.append(String.format("Volatility: %.2f%%\n", volatility * 100));
        summary.append(String.format("SMA(20): $%.2f, SMA(50): $%.2f\n", sma20, sma50));
        summary.append(String.format("RSI: %.1f\n", rsi));
        summary.append(String.format("MACD: %.3f\n", macd));
        
        // Market condition
        String condition = "NEUTRAL";
        if (rsi > 70) condition = "OVERBOUGHT";
        else if (rsi < 30) condition = "OVERSOLD";
        else if (close > sma20 && sma20 > sma50) condition = "BULLISH";
        else if (close < sma20 && sma20 < sma50) condition = "BEARISH";
        
        summary.append("Market Condition: ").append(condition).append("\n");
        
        return summary.toString();
    }
    
    // Getters
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getOpen() { return open; }
    public double getClose() { return close; }
    public double getVWAP() { return vwap; }
    public double getVolatility() { return volatility; }
    public int getTotalVolume() { return totalVolume; }
    public int getNumberOfTrades() { return numberOfTrades; }
    public double getSMA20() { return sma20; }
    public double getSMA50() { return sma50; }
    public double getRSI() { return rsi; }
    public double getMACD() { return macd; }
}