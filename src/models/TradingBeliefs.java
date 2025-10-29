package src.models;

import java.util.*;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Composant BDI Beliefs pour les agents de trading
 * Maintient les croyances sur le marche et les indicateurs techniques
 */
public class TradingBeliefs {
    
    // Donnees de marche
    private String symbol = "AAPL";
    private double currentPrice = 0.0;
    private double previousPrice = 0.0;
    private double bidPrice = 0.0;
    private double askPrice = 0.0;
    private double spread = 0.0;
    private int volume = 0;
    private double volatility = 0.02;
    
    // Historique des prix
    private LinkedList<Double> priceHistory;
    private LinkedList<Integer> volumeHistory;
    private long lastUpdateTime;
    
    // Indicateurs techniques
    private double movingAverage20 = 0.0;
    private double movingAverage50 = 0.0;
    private double rsi = 50.0; // Indice de Force Relative
    private double momentum = 0.0;
    private double ema12 = 0.0; // Moyenne Mobile Exponentielle 12
    private double ema26 = 0.0; // Moyenne Mobile Exponentielle 26
    private double macd = 0.0; // Indicateur MACD
    
    // Sentiment du marche
    private String marketSentiment = "NEUTRAL";
    private double sentimentScore = 0.0;
    
    // Activite des autres traders
    private Map<String, TradeInfo> otherTradersActivity;
    
    // Impact des actualites
    private List<NewsEvent> recentNews;
    private double newsImpact = 0.0;
    
    public TradingBeliefs() {
        this.priceHistory = new LinkedList<>();
        this.volumeHistory = new LinkedList<>();
        this.otherTradersActivity = new HashMap<>();
        this.recentNews = new ArrayList<>();
        this.lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Met a jour les croyances avec les donnees de marche
     */
    public void updateMarketData(String marketData) {
        try {
            // Format recu: PRICE:AAPL:100,02:BID:99,47:ASK:100,57:VOLUME:0:VOLATILITY:0,0200
            String[] parts = marketData.split(":");
            
            if (parts.length < 6 || !parts[0].equals("PRICE")) {
                System.err.println("Format de donnees de marche invalide: " + marketData);
                return;
            }
            
            // Parse les donnees dans l'ordre correct
            this.symbol = parts[1];
            
            String priceStr = parts[2].replace(",", ".");
            this.previousPrice = this.currentPrice;
            this.currentPrice = Double.parseDouble(priceStr);
            
            // Parse BID (parts[3] = "BID", parts[4] = valeur)
            if (parts.length >= 5 && "BID".equals(parts[3])) {
                String bidStr = parts[4].replace(",", ".");
                this.bidPrice = Double.parseDouble(bidStr);
            }
            
            // Parse ASK (parts[5] = "ASK", parts[6] = valeur)
            if (parts.length >= 7 && "ASK".equals(parts[5])) {
                String askStr = parts[6].replace(",", ".");
                this.askPrice = Double.parseDouble(askStr);
                this.spread = askPrice - bidPrice;
            }
            
            // Parse VOLUME (parts[7] = "VOLUME", parts[8] = valeur)
            if (parts.length >= 9 && "VOLUME".equals(parts[7])) {
                this.volume = Integer.parseInt(parts[8]);
                volumeHistory.add(volume);
                if (volumeHistory.size() > 100) {
                    volumeHistory.removeFirst();
                }
            }
            
            // Parse VOLATILITY (parts[9] = "VOLATILITY", parts[10] = valeur)
            if (parts.length >= 11 && "VOLATILITY".equals(parts[9])) {
                String volatilityStr = parts[10].replace(",", ".");
                this.volatility = Double.parseDouble(volatilityStr);
            }
            
            // Ajoute a l'historique des prix
            priceHistory.add(currentPrice);
            if (priceHistory.size() > 100) {
                priceHistory.removeFirst();
            }
            
            // Met a jour les indicateurs
            calculateTechnicalIndicators();
            updateMarketSentiment();
            
            // Met a jour l'horodatage
            lastUpdateTime = System.currentTimeMillis();
            
            // Debug occasionnel
            if (Math.random() < 0.01) {
                System.out.println("Donnees de marche analysees: Prix=$" + String.format("%.2f", currentPrice) + 
                                ", Bid=$" + String.format("%.2f", bidPrice) + 
                                ", Ask=$" + String.format("%.2f", askPrice) + 
                                ", Volume=" + volume);
            }
            
        } catch (NumberFormatException e) {
            System.err.println("Erreur de parsing des nombres: " + marketData);
            System.err.println("NumberFormatException: " + e.getMessage());
            String[] parts = marketData.split(":");
            System.err.println("Parts: " + Arrays.toString(parts));
        } catch (Exception e) {
            System.err.println("Erreur inattendue dans updateMarketData: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Met a jour les croyances avec les informations d'execution de trades
     */
    public void updateTradeInfo(String tradeData) {
        try {
            // Format: TRADE:trader:symbol:quantity:price:action
            String[] parts = tradeData.split(":");
            
            if (parts.length < 6) {
                System.err.println("Format de trade invalide: " + tradeData);
                return;
            }
            
            String trader = parts[1];
            String symbol = parts[2];
            int quantity = Integer.parseInt(parts[3]);
            String priceStr = parts[4].replace(",", ".");
            double price = Double.parseDouble(priceStr);
            String action = parts[5];
            
            // Met a jour l'activite des traders
            TradeInfo info = otherTradersActivity.getOrDefault(trader, new TradeInfo(trader));
            info.addTrade(action, quantity, price);
            otherTradersActivity.put(trader, info);
            
            // Met a jour le sentiment base sur l'activite de trading
            updateTradingSentiment(action, quantity);
            
        } catch (NumberFormatException e) {
            System.err.println("Erreur de parsing des donnees de trade: " + tradeData);
        } catch (Exception e) {
            System.err.println("Erreur dans updateTradeInfo: " + e.getMessage());
        }
    }
    
    /**
     * Met a jour les croyances avec les evenements d'actualites
     */
    public void updateNewsData(String newsData) {
        try {
            // Format recu du NewsProvider: "NEUTRAL:Market analysts maintain hold ratings:IMPACT:LOW"
            String[] parts = newsData.split(":");
            
            if (parts.length >= 4) {
                String sentiment = parts[0];
                String title = parts[1];
                String impactLevel = parts[3];
                
                // Conversion du niveau d'impact en valeur numerique
                double impact = 0.0;
                switch (impactLevel.toUpperCase()) {
                    case "LOW":
                        impact = 0.3;
                        break;
                    case "MEDIUM":
                        impact = 0.5;
                        break;
                    case "HIGH":
                        impact = 0.8;
                        break;
                    default:
                        // Si c'est deja un nombre, essayer de le parser
                        try {
                            String impactStr = impactLevel.replace(",", ".");
                            impact = Double.parseDouble(impactStr);
                        } catch (NumberFormatException e) {
                            impact = 0.5; // Valeur par defaut
                        }
                        break;
                }
                
                NewsEvent event = new NewsEvent(title, sentiment, impact);
                recentNews.add(event);
                
                // Garde seulement les 10 dernieres actualites
                if (recentNews.size() > 10) {
                    recentNews.remove(0);
                }
                
                // Met a jour l'impact des actualites
                calculateNewsImpact();
                
                System.out.println("Actualite traitee: " + sentiment + " impact=" + impactLevel + 
                                " (valeur=" + impact + ") - " + title);
            }
        } catch (Exception e) {
            System.err.println("Erreur de mise a jour des actualites: " + e.getMessage());
            System.err.println("Donnees recues: " + newsData);
        }
    }
    
    /**
     * Calcule les indicateurs techniques
     */
    private void calculateTechnicalIndicators() {
        if (priceHistory.size() < 2) return;
        
        calculateMovingAverages();
        calculateRSI();
        calculateMomentum();
        calculateMACD();
    }
    
    /**
     * Calcule les moyennes mobiles
     */
    private void calculateMovingAverages() {
        int size = priceHistory.size();
        
        // MA20
        if (size >= 20) {
            double sum = 0.0;
            for (int i = size - 20; i < size; i++) {
                sum += priceHistory.get(i);
            }
            movingAverage20 = sum / 20.0;
        } else if (size > 0) {
            double sum = priceHistory.stream().mapToDouble(Double::doubleValue).sum();
            movingAverage20 = sum / size;
        }
        
        // MA50
        if (size >= 50) {
            double sum = 0.0;
            for (int i = size - 50; i < size; i++) {
                sum += priceHistory.get(i);
            }
            movingAverage50 = sum / 50.0;
        } else if (size > 0) {
            double sum = priceHistory.stream().mapToDouble(Double::doubleValue).sum();
            movingAverage50 = sum / size;
        }
    }
    
    /**
     * Calcule l'Indice de Force Relative (RSI)
     */
    private void calculateRSI() {
        int period = Math.min(14, priceHistory.size() - 1);
        if (period < 2) {
            rsi = 50.0; // RSI neutre
            return;
        }
        
        double gainSum = 0.0;
        double lossSum = 0.0;
        
        for (int i = priceHistory.size() - period; i < priceHistory.size(); i++) {
            if (i > 0) {
                double change = priceHistory.get(i) - priceHistory.get(i - 1);
                if (change > 0) {
                    gainSum += change;
                } else {
                    lossSum += Math.abs(change);
                }
            }
        }
        
        if (lossSum == 0) {
            rsi = 100.0;
        } else {
            double rs = (gainSum / period) / (lossSum / period);
            rsi = 100.0 - (100.0 / (1.0 + rs));
        }
    }
    
    /**
     * Calcule le momentum des prix
     */
    private void calculateMomentum() {
        int period = Math.min(10, priceHistory.size());
        if (period < 2) {
            momentum = 0.0;
            return;
        }
        
        double oldPrice = priceHistory.get(priceHistory.size() - period);
        momentum = ((currentPrice - oldPrice) / oldPrice) * 100.0;
    }
    
    /**
     * Calcule l'indicateur MACD
     */
    private void calculateMACD() {
        if (priceHistory.size() < 26) {
            macd = 0.0;
            return;
        }
        
        // Calcul MACD simplifie
        if (ema12 == 0.0) ema12 = currentPrice;
        if (ema26 == 0.0) ema26 = currentPrice;
        
        // Calcul EMA
        double alpha12 = 2.0 / (12.0 + 1.0);
        double alpha26 = 2.0 / (26.0 + 1.0);
        
        ema12 = (currentPrice * alpha12) + (ema12 * (1.0 - alpha12));
        ema26 = (currentPrice * alpha26) + (ema26 * (1.0 - alpha26));
        
        macd = ema12 - ema26;
    }
    
    /**
     * Met a jour le sentiment du marche base sur divers facteurs
     */
    private void updateMarketSentiment() {
        double sentiment = 0.0;
        
        // Influence du momentum
        if (momentum > 2.0) sentiment += 0.3;
        else if (momentum < -2.0) sentiment -= 0.3;
        
        // Influence du RSI
        if (rsi > 70) sentiment += 0.2; // Surachat - possible retournement
        else if (rsi < 30) sentiment -= 0.2; // Survente - possible reprise
        
        // Influence des moyennes mobiles
        if (currentPrice > movingAverage20) sentiment += 0.2;
        else sentiment -= 0.2;
        
        if (movingAverage20 > movingAverage50) sentiment += 0.1;
        else sentiment -= 0.1;
        
        // Influence du volume
        if (volume > getAverageVolume() * 1.5) sentiment += 0.1;
        
        // Influence des actualites
        sentiment += newsImpact * 0.3;
        
        // Influence de l'activite de trading
        sentiment += getTradingActivitySentiment() * 0.2;
        
        // Met a jour le score et la classification du sentiment
        sentimentScore = Math.max(-1.0, Math.min(1.0, sentiment));
        
        if (sentimentScore > 0.3) {
            marketSentiment = "BULLISH";
        } else if (sentimentScore < -0.3) {
            marketSentiment = "BEARISH";
        } else {
            marketSentiment = "NEUTRAL";
        }
    }
    
    /**
     * Met a jour le sentiment base sur les actions des autres traders
     */
    private void updateTradingSentiment(String action, int quantity) {
        double weight = Math.log(quantity + 1) * 0.1;
        
        if ("BUY".equals(action)) {
            sentimentScore += weight;
        } else if ("SELL".equals(action)) {
            sentimentScore -= weight;
        }
        
        sentimentScore = Math.max(-1.0, Math.min(1.0, sentimentScore));
    }
    
    /**
     * Calcule l'impact des actualites avec decroissance temporelle
     */
    private void calculateNewsImpact() {
        if (recentNews.isEmpty()) {
            newsImpact = 0.0;
            return;
        }
        
        double totalImpact = 0.0;
        long currentTime = System.currentTimeMillis();
        
        for (NewsEvent event : recentNews) {
            // Decroissance de l'impact dans le temps
            long age = currentTime - event.getTimestamp();
            double decayFactor = Math.exp(-age / (1000.0 * 60.0 * 30.0)); // Demi-vie de 30 minutes
            
            double impact = event.getImpact() * decayFactor;
            if ("POSITIVE".equals(event.getSentiment())) {
                totalImpact += impact;
            } else if ("NEGATIVE".equals(event.getSentiment())) {
                totalImpact -= impact;
            }
        }
        
        newsImpact = Math.max(-1.0, Math.min(1.0, totalImpact));
    }
    
    /**
     * Calcule le volume moyen
     */
    private double getAverageVolume() {
        if (volumeHistory.isEmpty()) return 1000.0;
        return volumeHistory.stream().mapToInt(Integer::intValue).average().orElse(1000.0);
    }
    
    /**
     * Obtient le sentiment base sur l'activite de trading
     */
    private double getTradingActivitySentiment() {
        if (otherTradersActivity.isEmpty()) return 0.0;
        
        double buyActivity = 0.0;
        double sellActivity = 0.0;
        
        for (TradeInfo info : otherTradersActivity.values()) {
            buyActivity += info.getBuyVolume();
            sellActivity += info.getSellVolume();
        }
        
        double totalActivity = buyActivity + sellActivity;
        if (totalActivity == 0) return 0.0;
        
        return (buyActivity - sellActivity) / totalActivity;
    }
    
    // Methodes d'analyse du marche
    public boolean isOversold() {
        return rsi < 30;
    }
    
    public boolean isOverbought() {
        return rsi > 70;
    }
    
    public boolean hasPositiveTrend() {
        return momentum > 1.0 && currentPrice > movingAverage20;
    }
    
    public boolean hasNegativeTrend() {
        return momentum < -1.0 && currentPrice < movingAverage20;
    }
    
    public boolean hasNegativeNews() {
        return newsImpact < -0.3;
    }
    
    public boolean hasPositiveNews() {
        return newsImpact > 0.3;
    }
    
    public boolean isVolatile() {
        return volatility > 0.05; // Seuil de volatilite a 5%
    }
    
    public double getPriceChangePercent() {
        if (previousPrice <= 0) return 0.0;
        return ((currentPrice - previousPrice) / previousPrice) * 100.0;
    }
    
    /**
     * Methodes d'analyse supplementaires pour AggressiveTrader
     */
    public boolean isBullishTrend() {
        return momentum > 2.0 && currentPrice > movingAverage20 && 
               movingAverage20 > movingAverage50 && sentimentScore > 0.2;
    }

    public boolean isBearishTrend() {
        return momentum < -2.0 && currentPrice < movingAverage20 && 
               movingAverage20 < movingAverage50 && sentimentScore < -0.2;
    }

    public boolean hasStrongMomentum() {
        return Math.abs(momentum) > 3.0;
    }

    public boolean hasWeakMomentum() {
        return Math.abs(momentum) < 1.0;
    }

    public boolean isInUptrend() {
        return currentPrice > movingAverage20 && movingAverage20 > movingAverage50;
    }

    public boolean isInDowntrend() {
        return currentPrice < movingAverage20 && movingAverage20 < movingAverage50;
    }

    public boolean hasHighVolume() {
        double avgVolume = getAverageVolume();
        return volume > avgVolume * 1.5;
    }

    public boolean hasLowVolume() {
        double avgVolume = getAverageVolume();
        return volume < avgVolume * 0.5;
    }

    public boolean isPriceNearSupport() {
        // Detection de support simplifiee
        if (priceHistory.size() < 20) return false;
        
        double minPrice = priceHistory.stream()
            .skip(Math.max(0, priceHistory.size() - 20))
            .mapToDouble(Double::doubleValue)
            .min()
            .orElse(currentPrice);
        
        return currentPrice <= minPrice * 1.02; // Dans les 2% du plus bas recent
    }

    public boolean isPriceNearResistance() {
        // Detection de resistance simplifiee
        if (priceHistory.size() < 20) return false;
        
        double maxPrice = priceHistory.stream()
            .skip(Math.max(0, priceHistory.size() - 20))
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(currentPrice);
        
        return currentPrice >= maxPrice * 0.98; // Dans les 2% du plus haut recent
    }

    public double getTrendStrength() {
        // Combinaison du momentum et de l'alignement des moyennes mobiles
        double maAlignment = 0.0;
        if (movingAverage20 > movingAverage50) maAlignment = 0.5;
        else if (movingAverage20 < movingAverage50) maAlignment = -0.5;
        
        double momentumStrength = Math.max(-0.5, Math.min(0.5, momentum / 10.0));
        
        return maAlignment + momentumStrength;
    }

    public boolean isMarketConditionsFavorable() {
        return !isVolatile() && hasHighVolume() && Math.abs(sentimentScore) < 0.8;
    }

    public boolean isMarketConditionsRisky() {
        return isVolatile() || Math.abs(sentimentScore) > 0.8 || hasNegativeNews();
    }

    /**
     * Methodes pour l'analyse de convergence/divergence
     */
    public boolean hasBullishDivergence() {
        if (priceHistory.size() < 10) return false;
        
        // Prix fait des plus bas descendants mais RSI fait des plus bas ascendants
        double recentLow = priceHistory.stream()
            .skip(Math.max(0, priceHistory.size() - 5))
            .mapToDouble(Double::doubleValue)
            .min()
            .orElse(currentPrice);
        
        double olderLow = priceHistory.stream()
            .skip(Math.max(0, priceHistory.size() - 10))
            .limit(5)
            .mapToDouble(Double::doubleValue)
            .min()
            .orElse(currentPrice);
        
        return recentLow < olderLow && rsi > 35; // Divergence simplifiee
    }

    public boolean hasBearishDivergence() {
        if (priceHistory.size() < 10) return false;
        
        // Prix fait des plus hauts ascendants mais RSI fait des plus hauts descendants
        double recentHigh = priceHistory.stream()
            .skip(Math.max(0, priceHistory.size() - 5))
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(currentPrice);
        
        double olderHigh = priceHistory.stream()
            .skip(Math.max(0, priceHistory.size() - 10))
            .limit(5)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(currentPrice);
        
        return recentHigh > olderHigh && rsi < 65; // Divergence simplifiee
    }
    
    // Accesseurs (getters)
    public String getSymbol() { return symbol; }
    public double getCurrentPrice() { return currentPrice; }
    public double getPreviousPrice() { return previousPrice; }
    public double getBidPrice() { return bidPrice; }
    public double getAskPrice() { return askPrice; }
    public double getSpread() { return spread; }
    public int getVolume() { return volume; }
    public double getVolatility() { return volatility; }
    public double getMovingAverage20() { return movingAverage20; }
    public double getMovingAverage50() { return movingAverage50; }
    public double getRSI() { return rsi; }
    public double getMomentum() { return momentum; }
    public double getMACD() { return macd; }
    public String getMarketSentiment() { return marketSentiment; }
    public double getSentimentScore() { return sentimentScore; }
    public double getNewsImpact() { return newsImpact; }
    public LinkedList<Double> getPriceHistory() { return new LinkedList<>(priceHistory); }
    public Map<String, TradeInfo> getOtherTradersActivity() { return new HashMap<>(otherTradersActivity); }
    public long getLastUpdateTime() { return lastUpdateTime; }
    
    /**
     * Methode de debogage
     */
    public void printDebugInfo() {
        System.out.println("=== TradingBeliefs Debug ===");
        System.out.println("Symbole: " + symbol);
        System.out.println("Prix actuel: $" + String.format("%.2f", currentPrice));
        System.out.println("Prix precedent: $" + String.format("%.2f", previousPrice));
        System.out.println("Bid/Ask: $" + String.format("%.2f", bidPrice) + "/$" + String.format("%.2f", askPrice));
        System.out.println("Volume: " + volume);
        System.out.println("Volatilite: " + String.format("%.4f", volatility));
        System.out.println("MA20/MA50: " + String.format("%.2f", movingAverage20) + "/" + String.format("%.2f", movingAverage50));
        System.out.println("RSI: " + String.format("%.1f", rsi));
        System.out.println("Momentum: " + String.format("%.2f%%", momentum));
        System.out.println("MACD: " + String.format("%.4f", macd));
        System.out.println("Sentiment du marche: " + marketSentiment + " (" + String.format("%.2f", sentimentScore) + ")");
        System.out.println("Impact actualites: " + String.format("%.2f", newsImpact));
        System.out.println("Taille historique prix: " + priceHistory.size());
        System.out.println("Traders actifs: " + otherTradersActivity.size());
        System.out.println("============================");
    }
}

/**
 * Classe auxiliaire pour suivre l'activite des autres traders
 */
class TradeInfo {
    private String traderName;
    private List<String> recentTrades;
    private double totalBuyVolume;
    private double totalSellVolume;
    private double averageBuyPrice;
    private double averageSellPrice;
    private long lastTradeTime;
    
    public TradeInfo(String traderName) {
        this.traderName = traderName;
        this.recentTrades = new ArrayList<>();
        this.totalBuyVolume = 0.0;
        this.totalSellVolume = 0.0;
        this.lastTradeTime = System.currentTimeMillis();
    }
    
    public void addTrade(String action, int quantity, double price) {
        String trade = action + ":" + quantity + "@" + String.format("%.2f", price);
        recentTrades.add(trade);
        
        if ("BUY".equals(action)) {
            double oldTotal = totalBuyVolume * averageBuyPrice;
            totalBuyVolume += quantity;
            averageBuyPrice = (oldTotal + quantity * price) / totalBuyVolume;
        } else if ("SELL".equals(action)) {
            double oldTotal = totalSellVolume * averageSellPrice;
            totalSellVolume += quantity;
            averageSellPrice = (oldTotal + quantity * price) / totalSellVolume;
        }
        
        // Garde seulement les 10 derniers trades
        if (recentTrades.size() > 10) {
            recentTrades.remove(0);
        }
        
        lastTradeTime = System.currentTimeMillis();
    }
    
    // Accesseurs (getters)
    public String getTraderName() { return traderName; }
    public double getBuyVolume() { return totalBuyVolume; }
    public double getSellVolume() { return totalSellVolume; }
    public double getAverageBuyPrice() { return averageBuyPrice; }
    public double getAverageSellPrice() { return averageSellPrice; }
    public List<String> getRecentTrades() { return new ArrayList<>(recentTrades); }
    public long getLastTradeTime() { return lastTradeTime; }
}

/**
 * Classe auxiliaire pour les evenements d'actualites
 */
class NewsEvent {
    private String title;
    private String sentiment; // POSITIVE, NEGATIVE, NEUTRAL
    private double impact; // -1.0 a 1.0
    private long timestamp;
    
    public NewsEvent(String title, String sentiment, double impact) {
        this.title = title;
        this.sentiment = sentiment;
        this.impact = impact;
        this.timestamp = System.currentTimeMillis();
    }
    
    // Accesseurs (getters)
    public String getTitle() { return title; }
    public String getSentiment() { return sentiment; }
    public double getImpact() { return impact; }
    public long getTimestamp() { return timestamp; }
}