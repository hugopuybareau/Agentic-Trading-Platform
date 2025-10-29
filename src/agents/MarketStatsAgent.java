package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import src.models.*;
import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;

/**
 * Agent de statistiques de marche.
 * Surveille l'activite du marche et genere des rapports analytiques.
 */
public class MarketStatsAgent extends Agent {
    
    private MarketStatistics marketStats;
    private Map<String, TraderStatistics> traderStats;
    private List<String> marketEvents;
    private AID marketMaker;
    
    // Rapport et journalisation
    private PrintWriter logWriter;
    private SimpleDateFormat dateFormat;
    
    // Metriques de marche
    private double startPrice;
    private double currentPrice;
    private double sessionHigh;
    private double sessionLow;
    private int totalTrades;
    private double totalVolume;

    private String currentMarketType = "UNKNOWN";
    private double priceRange = 0.0;
    private int consecutiveUps = 0;
    private int consecutiveDowns = 0;
    private double lastAnalyzedPrice = 0.0;

    // Analyse du type de marche selon les criteres academiques
    private void analyzeMarketType() {
        if (startPrice == 0 || currentPrice == 0) return;
        
        // Calcul des metriques de base
        double totalReturn = ((currentPrice - startPrice) / startPrice) * 100;
        double volatility = marketStats.getVolatility() * 100;
        priceRange = ((sessionHigh - sessionLow) / startPrice) * 100;
        
        // Analyse de la tendance
        if (lastAnalyzedPrice > 0) {
            if (currentPrice > lastAnalyzedPrice) {
                consecutiveUps++;
                consecutiveDowns = 0;
            } else if (currentPrice < lastAnalyzedPrice) {
                consecutiveDowns++;
                consecutiveUps = 0;
            }
        }
        lastAnalyzedPrice = currentPrice;
        
        // Classification du marche avec criteres sensibles
        if (Math.abs(totalReturn) < 0.5 && volatility < 2 && priceRange < 1) {
            currentMarketType = "SIDEWAYS MARKET";
            
        } else if (totalReturn > 0.2 && consecutiveUps >= 2) {
            if (totalReturn > 2) {
                currentMarketType = "STRONG BULL MARKET";
            } else {
                currentMarketType = "BULL MARKET";
            }
            
        } else if (totalReturn < -0.2 && consecutiveDowns >= 2) {
            if (totalReturn < -2) {
                currentMarketType = "STRONG BEAR MARKET";
            } else {
                currentMarketType = "BEAR MARKET";
            }
            
        } else if (volatility > 4 || priceRange > 3) {
            currentMarketType = "VOLATILE MARKET";
            
        } else if (Math.abs(totalReturn) > 5) {
            if (totalReturn > 0) {
                currentMarketType = "BUBBLE FORMING";
            } else {
                currentMarketType = "MARKET CRASH";
            }
            
        } else if (volatility > 3 && Math.abs(totalReturn) > 1) {
            currentMarketType = "NEWS-DRIVEN";
            
        } else if (totalReturn > 0.1) {
            currentMarketType = "STEADY BULL";
            
        } else if (totalReturn < -0.1) {
            currentMarketType = "STEADY BEAR";
            
        } else {
            currentMarketType = "STABLE MARKET";
        }
        
        System.out.println(String.format("Market Analysis: Return=%.3f%%, Vol=%.2f%%, Range=%.2f%%, Ups=%d, Downs=%d -> %s",
                                        totalReturn, volatility, priceRange, consecutiveUps, consecutiveDowns, currentMarketType));
    }

    private String getMarketTypeDetails() {
        double totalReturn = startPrice > 0 ? ((currentPrice - startPrice) / startPrice) * 100 : 0;
        double volatility = marketStats.getVolatility() * 100;
        
        return String.format("Return: %+.2f%%, Volatility: %.2f%%, Range: %.2f%%", 
                            totalReturn, volatility, priceRange);
    }
        
    @Override
    protected void setup() {
        marketStats = new MarketStatistics();
        traderStats = new HashMap<>();
        marketEvents = new ArrayList<>();
        dateFormat = new SimpleDateFormat("HH:mm:ss");
        
        // Creation du dossier de sessions
        File sessionsDir = new File("sessions");
        if (!sessionsDir.exists()) {
            sessionsDir.mkdirs();
            System.out.println("Created sessions directory");
        }

        // Initialisation de la journalisation
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String logFileName = "sessions/trading_session_" + timestamp + ".log";
            int accelerationFactor = getAccelerationFactor();
            
            logWriter = new PrintWriter(new FileWriter(logFileName));
            
            // En-tete du rapport
            logWriter.println("=== AUTONOMOUS TRADING SYSTEM LOG ===");
            logWriter.println("Platform: JADE Multi-Agent Trading Platform");
            logWriter.println("Session ID: " + timestamp);
            logWriter.println("Start time: " + new Date());
            logWriter.println("Time acceleration: " + accelerationFactor + "x");
            logWriter.println("Expected real duration: " + (60 / accelerationFactor) + " seconds");
            logWriter.println("Simulated time: 1 hour of market activity");
            logWriter.println("=====================================");
            logWriter.println("AGENTS INITIALIZED:");
            logWriter.println("- MarketMaker: AAPL @ $100.00");
            logWriter.println("- ConservativeTrader: $10,000 capital");
            logWriter.println("- AggressiveTrader: $15,000 capital");
            logWriter.println("- FollowerTrader-1: $8,000 capital");
            logWriter.println("- FollowerTrader-2: $8,000 capital");
            logWriter.println("- NewsProvider: Active");
            logWriter.println("- MarketStats: Monitoring");
            logWriter.println("=====================================\n");
            logWriter.flush();
            
            System.out.println("MarketStats logging to: " + logFileName);
            
        } catch (IOException e) {
            System.err.println("Could not create log file: " + e.getMessage());
        }
        
        System.out.println("Market Statistics Agent started");
        
        // Recherche du MarketMaker
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    // Ajout des comportements de surveillance
                    addBehaviour(new MarketDataMonitor());
                    addBehaviour(new TradeMonitor());
                    addBehaviour(new NewsMonitor());
                    addBehaviour(new PeriodicReportBehaviour(myAgent, adjustInterval(10000)));
                    addBehaviour(new MarketAlertBehaviour(myAgent, adjustInterval(5000)));
                }
            }
        });
    }

    private int getAccelerationFactor() {
        return Integer.parseInt(System.getProperty("trading.acceleration", "1"));
    }

    private long adjustInterval(long originalInterval) {
        return originalInterval / getAccelerationFactor();
    }
    
    private void findMarketMaker() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("market-maker");
            template.addServices(sd);
            
            DFAgentDescription[] result = DFService.search(this, template);
            if (result.length > 0) {
                marketMaker = result[0].getName();
                System.out.println("MarketStats found MarketMaker");
                registerForTradeNotifications();
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    // Enregistrement pour recevoir les notifications de trades
    private void registerForTradeNotifications() {
        ACLMessage register = new ACLMessage(ACLMessage.REQUEST);
        register.addReceiver(marketMaker);
        register.setProtocol("PORTFOLIO");
        register.setContent("REGISTER:" + 0.0);
        send(register);
        
        System.out.println("MarketStats registered for trade notifications");
    }
    
    /**
     * Surveillance des mises a jour des donnees de marche.
     */
    private class MarketDataMonitor extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("MARKET-DATA");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                processMarketData(msg.getContent());
            } else {
                block();
            }
        }
        
        private void processMarketData(String data) {
            try {
                String[] parts = data.split(":");
                
                if (parts.length >= 11 && "PRICE".equals(parts[0])) {
                    String priceStr = parts[2].replace(",", ".");
                    String volumeStr = parts[8];
                    String volatilityStr = parts[10].replace(",", ".");
                    
                    currentPrice = Double.parseDouble(priceStr);
                    int volume = Integer.parseInt(volumeStr);
                    double volatility = Double.parseDouble(volatilityStr);
                    
                    // Initialisation du prix d'ouverture
                    if (startPrice == 0) {
                        startPrice = currentPrice;
                        sessionHigh = currentPrice;
                        sessionLow = currentPrice;
                        System.out.println("MarketStats: Opening price set to $" + String.format("%.2f", startPrice));
                    }
                    
                    // Mise a jour des extremes de session
                    if (currentPrice > sessionHigh) {
                        sessionHigh = currentPrice;
                        logEvent("New session HIGH: $" + String.format("%.2f", sessionHigh));
                    }
                    if (currentPrice < sessionLow) {
                        sessionLow = currentPrice;
                        logEvent("New session LOW: $" + String.format("%.2f", sessionLow));
                    }
                    
                    // Mise a jour des statistiques
                    marketStats.updatePrice(currentPrice);
                    marketStats.updateVolume(volume);
                    analyzeMarketType();
                }
            } catch (Exception e) {
                System.err.println("MarketStats error processing: " + data + " - " + e.getMessage());
            }
        }
    }
    
    /**
     * Surveillance des executions de trades.
     */
    private class TradeMonitor extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("TRADE-EXECUTED");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                processTrade(msg.getContent());
            } else {
                block();
            }
        }
        
        private void processTrade(String tradeData) {
            try {
                String[] parts = tradeData.split(":");
                
                if (parts.length >= 6 && "TRADE".equals(parts[0])) {
                    String trader = parts[1];
                    String symbol = parts[2];
                    int quantity = Integer.parseInt(parts[3]);
                    
                    String priceStr = parts[4].replace(",", ".");
                    double price = Double.parseDouble(priceStr);
                    
                    String action = parts[5];
                    
                    // Mise a jour des statistiques des traders
                    TraderStatistics stats = traderStats.getOrDefault(trader, new TraderStatistics(trader));
                    stats.recordTrade(action, quantity, price);
                    traderStats.put(trader, stats);
                    
                    totalTrades++;
                    totalVolume += quantity * price;
                    
                    // Journalisation des trades importants
                    if (quantity * price > 5000) {
                        logEvent(String.format("LARGE TRADE: %s %s %d shares @ $%.2f (Value: $%.2f)",
                            trader, action, quantity, price, quantity * price));
                    }
                }
            } catch (Exception e) {
                System.err.println("MarketStats error parsing trade: " + tradeData + " - " + e.getMessage());
            }
        }
    }
    
    /**
     * Surveillance des evenements d'actualites.
     */
    private class NewsMonitor extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("NEWS");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                processNews(msg.getContent());
            } else {
                block();
            }
        }
        
        private void processNews(String newsData) {
            String[] parts = newsData.split(":");
            String sentiment = parts[0];
            String description = parts[1];
            String impact = parts[3];
            
            String newsEvent = String.format("NEWS [%s/%s]: %s", sentiment, impact, description);
            marketEvents.add(newsEvent);
            logEvent(newsEvent);
        }
    }
    
    /**
     * Generation de rapports periodiques du marche.
     */
    private class PeriodicReportBehaviour extends TickerBehaviour {
        public PeriodicReportBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            generateMarketReport();
        }
        
        private void generateMarketReport() {
            System.out.println("\n" + "=".repeat(70));
            System.out.println("         MARKET REPORT - " + dateFormat.format(new Date()));
            System.out.println("=".repeat(70));
            
            // Type de marche en premiere position
            System.out.println(String.format("MARKET TYPE: %s", currentMarketType));
            System.out.println(String.format("   Details: %s", getMarketTypeDetails()));
            System.out.println();
            
            // Informations sur les prix
            double changePercent = startPrice > 0 ? ((currentPrice - startPrice) / startPrice) * 100 : 0;
            System.out.println(String.format("Current Price: $%.2f (%+.2f%% from open)",
                currentPrice, changePercent));
            System.out.println(String.format("Session Range: $%.2f - $%.2f (%.2f%% range)",
                sessionLow, sessionHigh, priceRange));
            
            // Dynamique du marche
            System.out.println(String.format("Market Trend: %d ups, %d downs", 
                            consecutiveUps, consecutiveDowns));
            
            // Volume et trades
            System.out.println(String.format("Total Trades: %d", totalTrades));
            System.out.println(String.format("Total Volume: $%.2f", totalVolume));
            
            // Statistiques de marche
            System.out.println(marketStats.getMarketSummary());
            
            // Meilleurs traders
            System.out.println("\n--- TOP TRADERS ---");
            traderStats.values().stream()
                .sorted((a, b) -> Double.compare(b.getTotalProfit(), a.getTotalProfit()))
                .limit(3)
                .forEach(stats -> {
                    System.out.println(String.format("%s: Trades=%d, P&L=$%.2f, Win Rate=%.1f%%",
                        stats.getName(),
                        stats.getTotalTrades(),
                        stats.getTotalProfit(),
                        stats.getWinRate() * 100));
                });
            
            // Evenements recents
            if (!marketEvents.isEmpty()) {
                System.out.println("\n--- RECENT EVENTS ---");
                int start = Math.max(0, marketEvents.size() - 3);
                for (int i = start; i < marketEvents.size(); i++) {
                    System.out.println("- " + marketEvents.get(i));
                }
            }
            
            System.out.println("=".repeat(70) + "\n");
            
            // Journalisation du rapport
            if (logWriter != null) {
                logWriter.println("\n[REPORT] " + dateFormat.format(new Date()));
                logWriter.println(String.format("Market Type: %s (%s)", currentMarketType, getMarketTypeDetails()));
                logWriter.println(String.format("Price: $%.2f (%+.2f%%)", currentPrice, changePercent));
                logWriter.println(String.format("Trades: %d, Volume: $%.2f", totalTrades, totalVolume));
                logWriter.flush();
            }
        }
    }
    
    /**
     * Surveillance des alertes de marche.
     */
    private class MarketAlertBehaviour extends TickerBehaviour {
        private boolean crashAlerted = false;
        private boolean bubbleAlerted = false;
        
        public MarketAlertBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Detection de crash (>10% de baisse depuis le plus haut)
            if (!crashAlerted && sessionHigh > 0) {
                double dropPercent = ((sessionHigh - currentPrice) / sessionHigh) * 100;
                if (dropPercent > 10) {
                    String alert = "CRASH ALERT: Market down " + 
                                 String.format("%.1f%%", dropPercent) + " from session high!";
                    System.out.println("\n" + alert);
                    logEvent(alert);
                    crashAlerted = true;
                }
            }
            
            // Detection de bulle (>20% de hausse depuis l'ouverture)
            if (!bubbleAlerted && startPrice > 0) {
                double risePercent = ((currentPrice - startPrice) / startPrice) * 100;
                if (risePercent > 20) {
                    String alert = "BUBBLE ALERT: Market up " + 
                                 String.format("%.1f%%", risePercent) + " from open!";
                    System.out.println("\n" + alert);
                    logEvent(alert);
                    bubbleAlerted = true;
                }
            }
            
            // Detection de forte volatilite
            if (marketStats.getVolatility() > 0.05) {
                System.out.println("HIGH VOLATILITY: " + 
                                 String.format("%.1f%%", marketStats.getVolatility() * 100));
            }
        }
    }
    
    /**
     * Journalisation des evenements de marche.
     */
    private void logEvent(String event) {
        String timestampedEvent = "[" + dateFormat.format(new Date()) + "] " + event;
        
        if (logWriter != null) {
            logWriter.println(timestampedEvent);
            logWriter.flush();
        }
    }
    
    /**
     * Statistiques de suivi des traders.
     */
    private class TraderStatistics {
        private String name;
        private int totalTrades;
        private int winningTrades;
        private int losingTrades;
        private double totalProfit;
        private double lastPrice;
        private String lastAction;
        
        public TraderStatistics(String name) {
            this.name = name;
        }
        
        public void recordTrade(String action, int quantity, double price) {
            totalTrades++;
            
            // Calcul simple du profit/perte
            if ("SELL".equals(action) && "BUY".equals(lastAction)) {
                double profit = (price - lastPrice) * quantity;
                totalProfit += profit;
                
                if (profit > 0) {
                    winningTrades++;
                } else {
                    losingTrades++;
                }
            }
            
            lastAction = action;
            lastPrice = price;
        }
        
        public String getName() { return name; }
        public int getTotalTrades() { return totalTrades; }
        public double getTotalProfit() { return totalProfit; }
        
        public double getWinRate() {
            if (winningTrades + losingTrades == 0) return 0;
            return (double) winningTrades / (winningTrades + losingTrades);
        }
    }
    
    @Override
    protected void takeDown() {
        // Analyse finale du marche
        analyzeMarketType();
        
        // Generation du rapport final
        System.out.println("\n" + "=".repeat(70));
        System.out.println("         FINAL SESSION REPORT");
        System.out.println("=".repeat(70));
        
        // Type de marche final
        System.out.println(String.format("FINAL MARKET TYPE: %s", currentMarketType));
        System.out.println(String.format("   Market Characteristics: %s", getMarketTypeDetails()));
        System.out.println();
        
        double finalReturn = startPrice > 0 ? ((currentPrice - startPrice) / startPrice) * 100 : 0;
        System.out.println(String.format("Opening Price: $%.2f", startPrice));
        System.out.println(String.format("Closing Price: $%.2f", currentPrice));
        System.out.println(String.format("Session Return: %+.2f%%", finalReturn));
        System.out.println(String.format("Session High: $%.2f", sessionHigh));
        System.out.println(String.format("Session Low: $%.2f", sessionLow));
        System.out.println(String.format("Price Range: %.2f%% of opening", priceRange));
        System.out.println(String.format("Market Trend: %d consecutive ups, %d consecutive downs", 
                        consecutiveUps, consecutiveDowns));
        System.out.println(String.format("Total Trades: %d", totalTrades));
        System.out.println(String.format("Total Volume: $%.2f", totalVolume));
        
        // Classification academique du marche
        System.out.println("\n--- MARKET CLASSIFICATION ---");
        if (Math.abs(finalReturn) < 2) {
            System.out.println("Efficient Market: Prices remained stable");
        } else if (finalReturn > 10) {
            System.out.println("Strong Bull Market: Significant positive momentum");
        } else if (finalReturn < -10) {
            System.out.println("Bear Market: Significant negative pressure");
        } else if (marketStats.getVolatility() > 0.05) {
            System.out.println("High Volatility Market: Unstable price action");
        } else {
            System.out.println("Trending Market: Directional price movement");
        }
        
        // Performance des traders
        if (!traderStats.isEmpty()) {
            System.out.println("\n--- TRADER PERFORMANCE ---");
            TraderStatistics winner = traderStats.values().stream()
                .max(Comparator.comparing(TraderStatistics::getTotalProfit))
                .orElse(null);
            
            TraderStatistics loser = traderStats.values().stream()
                .min(Comparator.comparing(TraderStatistics::getTotalProfit))
                .orElse(null);
            
            if (winner != null) {
                System.out.println(String.format("Best Performer: %s (P&L: $%.2f)",
                    winner.getName(), winner.getTotalProfit()));
            }
            
            if (loser != null) {
                System.out.println(String.format("Worst Performer: %s (P&L: $%.2f)",
                    loser.getName(), loser.getTotalProfit()));
            }
            
            // Analyse d'adaptation des agents
            System.out.println("\n--- AGENT ADAPTATION ANALYSIS ---");
            System.out.println("Market Type: " + currentMarketType);
            
            traderStats.values().forEach(stats -> {
                String adaptation = analyzeAgentAdaptation(stats, currentMarketType);
                System.out.println(String.format("- %s: %s", stats.getName(), adaptation));
            });
        }
        
        System.out.println("=".repeat(70));
        
        // Journalisation finale
        if (logWriter != null) {
            logWriter.println("\n" + "=".repeat(50));
            logWriter.println("         FINAL SESSION ANALYSIS");
            logWriter.println("=".repeat(50));
            logWriter.println("End time: " + new Date());
            logWriter.println("Final Market Type: " + currentMarketType);
            logWriter.println("Market Details: " + getMarketTypeDetails());
            logWriter.println(String.format("Price Performance: $%.2f -> $%.2f (%+.2f%%)", 
                            startPrice, currentPrice, finalReturn));
            logWriter.println(String.format("Trading Activity: %d trades, $%.2f volume", 
                            totalTrades, totalVolume));
            logWriter.println("=".repeat(50));
            logWriter.close();
        }
        
        System.out.println("MarketStats agent shutting down");
    }

    // Analyse de l'adaptation des agents au type de marche
    private String analyzeAgentAdaptation(TraderStatistics stats, String marketType) {
        double winRate = stats.getWinRate() * 100;
        
        switch (marketType) {
            case "BULL MARKET":
                if (stats.getTotalTrades() > 10 && winRate > 60) {
                    return "Well adapted to bull market conditions";
                } else if (stats.getTotalTrades() < 5) {
                    return "Too conservative for bull market";
                } else {
                    return "Struggling to capitalize on uptrend";
                }
                
            case "BEAR MARKET":
                if (stats.getTotalProfit() > 0) {
                    return "Successfully preserved capital in bear market";
                } else {
                    return "Failed to adapt to bearish conditions";
                }
                
            case "VOLATILE MARKET":
                if (stats.getTotalTrades() > 15) {
                    return "High trading frequency in volatile conditions";
                } else {
                    return "Conservative approach to volatility";
                }
                
            default:
                return String.format("Standard performance (%.1f%% win rate)", winRate);
        }
    }
}