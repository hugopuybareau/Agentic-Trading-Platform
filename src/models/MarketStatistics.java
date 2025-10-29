package src.models;

import java.util.*;

/**
 * Calculateur de statistiques de marche
 * Suit les metriques de marche et les indicateurs techniques
 */
public class MarketStatistics {
    
    private List<Double> priceHistory;
    private List<Integer> volumeHistory;
    private int maxHistorySize = 100;
    
    // Statistiques
    private double high;
    private double low;
    private double open;
    private double close;
    private double vwap; // Prix Moyen Pondere par le Volume
    private double volatility;
    private int totalVolume;
    private int numberOfTrades;
    
    // Indicateurs techniques
    private double sma20; // Moyenne Mobile Simple 20 periodes
    private double sma50; // Moyenne Mobile Simple 50 periodes
    private double ema12; // Moyenne Mobile Exponentielle 12 periodes
    private double ema26; // Moyenne Mobile Exponentielle 26 periodes
    private double macd; // Indicateur MACD
    private double rsi; // Indice de Force Relative
    
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
     * Met a jour les statistiques avec un nouveau prix
     */
    public void updatePrice(double price) {
        priceHistory.add(price);
        if (priceHistory.size() > maxHistorySize) {
            priceHistory.remove(0);
        }
        
        // Met a jour OHLC (Ouverture, Haut, Bas, Cloture)
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
        
        // Calcule les indicateurs
        calculateMovingAverages();
        calculateVolatility();
        calculateRSI();
        calculateMACD();
    }
    
    /**
     * Met a jour les statistiques de volume
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
     * Calcule les Moyennes Mobiles Simples
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
     * Calcule la volatilite (ecart-type)
     */
    private void calculateVolatility() {
        if (priceHistory.size() < 20) {
            volatility = 0.02; // Valeur par defaut 2%
            return;
        }
        
        // Calcule les rendements
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < priceHistory.size(); i++) {
            double returnVal = (priceHistory.get(i) - priceHistory.get(i-1)) / priceHistory.get(i-1);
            returns.add(returnVal);
        }
        
        // Calcule l'ecart-type des rendements
        double mean = returns.stream().mapToDouble(r -> r).average().orElse(0);
        double variance = returns.stream()
            .mapToDouble(r -> Math.pow(r - mean, 2))
            .average().orElse(0);
        
        volatility = Math.sqrt(variance);
    }
    
    /**
     * Calcule l'Indice de Force Relative (RSI)
     */
    private void calculateRSI() {
        if (priceHistory.size() < 14) {
            rsi = 50; // Neutre
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
     * Calcule le MACD (Moving Average Convergence Divergence)
     */
    private void calculateMACD() {
        if (priceHistory.size() < 26) {
            macd = 0;
            return;
        }
        
        // Calcul simplifie de l'EMA
        ema12 = calculateEMA(12);
        ema26 = calculateEMA(26);
        macd = ema12 - ema26;
    }
    
    /**
     * Calcule la Moyenne Mobile Exponentielle
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
     * Calcule le Prix Moyen Pondere par le Volume (VWAP)
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
     * Obtient un resume du marche
     */
    public String getMarketSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Statistiques du Marche ===\n");
        summary.append(String.format("OHLC: %.2f / %.2f / %.2f / %.2f\n", open, high, low, close));
        summary.append(String.format("Volume: %d transactions, %d actions\n", numberOfTrades, totalVolume));
        summary.append(String.format("VWAP: $%.2f\n", vwap));
        summary.append(String.format("Volatilite: %.2f%%\n", volatility * 100));
        summary.append(String.format("SMA(20): $%.2f, SMA(50): $%.2f\n", sma20, sma50));
        summary.append(String.format("RSI: %.1f\n", rsi));
        summary.append(String.format("MACD: %.3f\n", macd));
        
        // Condition du marche
        String condition = "NEUTRE";
        if (rsi > 70) condition = "SURVENTE";
        else if (rsi < 30) condition = "SURVENTE";
        else if (close > sma20 && sma20 > sma50) condition = "HAUSSIER";
        else if (close < sma20 && sma20 < sma50) condition = "BAISSIER";
        
        summary.append("Condition du Marche: ").append(condition).append("\n");
        
        return summary.toString();
    }
    
    // Accesseurs (getters)
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