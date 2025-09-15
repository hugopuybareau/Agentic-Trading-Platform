package model;

import java.util.*;

/**
 * BDI Beliefs component for trading agents
 * Maintains market beliefs and technical indicators
 */
public class TradingBeliefs {
    
    private double currentPrice;
    private double previousPrice;
    private LinkedList<Double> priceHistory;
    private int historySize = 50;
    
    private double bidPrice;
    private double askPrice;
    private double spread;
    
    private double movingAverage20;
    private double movingAverage50;
    private double volatility;
    private int volume;
    
    private String marketSentiment; // BULLISH, BEARISH, NEUTRAL
    private double momentum;
    private double rsi; // Relative Strength Index
    
    private Map<String, TradeInfo> otherTradersActivity;
    private List<NewsEvent> recentNews;
    
    public TradingBeliefs() {
        this.priceHistory = new LinkedList<>();
        this.otherTradersActivity = new HashMap<>();
        this.recentNews = new ArrayList<>();
        this.marketSentiment = "NEUTRAL";
        this.currentPrice = 100.0;
        this.previousPrice = 100.0;
    }
    
    /**
     * Update beliefs with new market data
     */
    public void updateMarketData(String marketData) {
        // Parse: PRICE:AAPL:105.50:BID:104.80:ASK:106.20:VOLUME:1500:VOLATILITY:0.02
        String[] parts = marketData.split(":");
        
        previousPrice = currentPrice;
        currentPrice = Double.parseDouble(parts[2]);
        bidPrice = Double.parseDouble(parts[4]);
        askPrice = Double.parseDouble(parts[6]);
        volume = Integer.parseInt(parts[8]);
        volatility = Double.parseDouble(parts[10]);
        
        spread = (askPrice - bidPrice) / currentPrice;
        
        updatePriceHistory(currentPrice);
        calculateTechnicalIndicators();
        updateMarketSentiment();
    }
    
    /**
     * Update beliefs with trade execution information
     */
    public void updateTradeInfo(String tradeData) {
        // Parse trade execution data
        String[] parts = tradeData.split(":");
        String trader = parts[0];
        String action = parts[1];
        int quantity = Integer.parseInt(parts[3]);
        double price = Double.parseDouble(parts[4]);
        
        TradeInfo info = otherTradersActivity.getOrDefault(trader, new TradeInfo(trader));
        info.addTrade(action, quantity, price);
        otherTradersActivity.put(trader, info);
    }
    
    /**
     * Update beliefs with news events
     */
    public void updateNews(String newsData) {
        // Parse: POSITIVE/NEGATIVE:description:IMPACT:HIGH/MEDIUM/LOW
        String[] parts = newsData.split(":");
        String sentiment = parts[0];
        String description = parts[1];
        String impact = parts[3];
        
        NewsEvent news = new NewsEvent(sentiment, description, impact);
        recentNews.add(news);
        
        // Keep only recent news (last 10)
        if (recentNews.size() > 10) {
            recentNews.remove(0);
        }
        
        // Adjust market sentiment based on news
        adjustSentimentForNews(news);
    }
    
    private void updatePriceHistory(double price) {
        priceHistory.add(price);
        if (priceHistory.size() > historySize) {
            priceHistory.removeFirst();
        }
    }
    
    private void calculateTechnicalIndicators() {
        // Calculate moving averages
        if (priceHistory.size() >= 20) {
            double sum20 = 0;
            List<Double> last20 = priceHistory.subList(
                Math.max(0, priceHistory.size() - 20), 
                priceHistory.size()
            );
            for (double p : last20) {
                sum20 += p;
            }
            movingAverage20 = sum20 / 20;
        }
        
        if (priceHistory.size() >= 50) {
            double sum50 = 0;
            for (double p : priceHistory) {
                sum50 += p;
            }
            movingAverage50 = sum50 / priceHistory.size();
        }
        
        // Calculate momentum
        if (priceHistory.size() >= 10) {
            double oldPrice = priceHistory.get(priceHistory.size() - 10);
            momentum = (currentPrice - oldPrice) / oldPrice;
        }
        
        // Calculate RSI
        calculateRSI();
    }
    
    private void calculateRSI() {
        if (priceHistory.size() < 14) {
            rsi = 50; // Neutral
            return;
        }
        
        double gains = 0;
        double losses = 0;
        
        for (int i = priceHistory.size() - 14; i < priceHistory.size(); i++) {
            double change = priceHistory.get(i) - priceHistory.get(i - 1);
            if (change > 0) {
                gains += change;
            } else {
                losses -= change;
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
    
    private void updateMarketSentiment() {
        // Determine sentiment based on technical indicators
        int bullishSignals = 0;
        int bearishSignals = 0;
        
        // Moving average crossover
        if (movingAverage20 > 0 && movingAverage50 > 0) {
            if (movingAverage20 > movingAverage50) {
                bullishSignals++;
            } else {
                bearishSignals++;
            }
        }
        
        // Price vs MA
        if (currentPrice > movingAverage20) {
            bullishSignals++;
        } else {
            bearishSignals++;
        }
        
        // Momentum
        if (momentum > 0.02) {
            bullishSignals++;
        } else if (momentum < -0.02) {
            bearishSignals++;
        }
        
        // RSI
        if (rsi > 70) {
            bearishSignals++; // Overbought
        } else if (rsi < 30) {
            bullishSignals++; // Oversold
        }
        
        // Determine overall sentiment
        if (bullishSignals > bearishSignals + 1) {
            marketSentiment = "BULLISH";
        } else if (bearishSignals > bullishSignals + 1) {
            marketSentiment = "BEARISH";
        } else {
            marketSentiment = "NEUTRAL";
        }
    }
    
    private void adjustSentimentForNews(NewsEvent news) {
        if ("HIGH".equals(news.getImpact())) {
            if ("POSITIVE".equals(news.getSentiment())) {
                // Strong positive news can override technical indicators
                if (!"BEARISH".equals(marketSentiment)) {
                    marketSentiment = "BULLISH";
                }
            } else if ("NEGATIVE".equals(news.getSentiment())) {
                if (!"BULLISH".equals(marketSentiment)) {
                    marketSentiment = "BEARISH";
                }
            }
        }
    }
    
    // Analysis methods for decision making
    
    public boolean isBullishTrend() {
        return "BULLISH".equals(marketSentiment) && momentum > 0;
    }
    
    public boolean isBearishTrend() {
        return "BEARISH".equals(marketSentiment) && momentum < 0;
    }
    
    public boolean isOverbought() {
        return rsi > 70 && currentPrice > movingAverage20 * 1.05;
    }
    
    public boolean isOversold() {
        return rsi < 30 && currentPrice < movingAverage20 * 0.95;
    }
    
    public boolean hasPositiveNews() {
        for (NewsEvent news : recentNews) {
            if ("POSITIVE".equals(news.getSentiment()) && 
                "HIGH".equals(news.getImpact())) {
                return true;
            }
        }
        return false;
    }
    
    public boolean hasNegativeNews() {
        for (NewsEvent news : recentNews) {
            if ("NEGATIVE".equals(news.getSentiment()) && 
                "HIGH".equals(news.getImpact())) {
                return true;
            }
        }
        return false;
    }
    
    public Map<String, Integer> getTopTraders() {
        Map<String, Integer> traderScores = new HashMap<>();
        
        for (Map.Entry<String, TradeInfo> entry : otherTradersActivity.entrySet()) {
            TradeInfo info = entry.getValue();
            int score = info.getProfitableTrades() - info.getLosingTrades();
            traderScores.put(entry.getKey(), score);
        }
        
        return traderScores;
    }
    
    // Getters
    
    public double getCurrentPrice() { return currentPrice; }
    public double getBidPrice() { return bidPrice; }
    public double getAskPrice() { return askPrice; }
    public double getSpread() { return spread; }
    public double getMovingAverage20() { return movingAverage20; }
    public double getMovingAverage50() { return movingAverage50; }
    public double getVolatility() { return volatility; }
    public double getMomentum() { return momentum; }
    public double getRSI() { return rsi; }
    public String getMarketSentiment() { return marketSentiment; }
    public int getVolume() { return volume; }
    
    public double getPriceChange() {
        return currentPrice - previousPrice;
    }
    
    public double getPriceChangePercent() {
        if (previousPrice == 0) return 0;
        return ((currentPrice - previousPrice) / previousPrice) * 100;
    }
}

/**
 * Helper class to track other traders' activity
 */
class TradeInfo {
    private String traderName;
    private int totalTrades;
    private int buyTrades;
    private int sellTrades;
    private double totalVolume;
    private double lastTradePrice;
    private int profitableTrades;
    private int losingTrades;
    
    public TradeInfo(String traderName) {
        this.traderName = traderName;
    }
    
    public void addTrade(String action, int quantity, double price) {
        totalTrades++;
        totalVolume += quantity * price;
        lastTradePrice = price;
        
        if ("BUY".equals(action)) {
            buyTrades++;
        } else {
            sellTrades++;
        }
    }
    
    public int getProfitableTrades() { return profitableTrades; }
    public int getLosingTrades() { return losingTrades; }
    public int getBuyTrades() { return buyTrades; }
    public int getSellTrades() { return sellTrades; }
}

/**
 * News event representation
 */
class NewsEvent {
    private String sentiment;
    private String description;
    private String impact;
    private long timestamp;
    
    public NewsEvent(String sentiment, String description, String impact) {
        this.sentiment = sentiment;
        this.description = description;
        this.impact = impact;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getSentiment() { return sentiment; }
    public String getDescription() { return description; }
    public String getImpact() { return impact; }
    public long getTimestamp() { return timestamp; }
}