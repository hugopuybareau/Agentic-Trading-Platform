package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import src.model.*;
import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;

/**
 * Market Statistics Agent
 * Monitors market activity and generates reports
 */
public class MarketStatsAgent extends Agent {
    
    private MarketStatistics marketStats;
    private Map<String, TraderStatistics> traderStats;
    private List<String> marketEvents;
    private AID marketMaker;
    
    // Reporting
    private PrintWriter logWriter;
    private SimpleDateFormat dateFormat;
    
    // Market metrics
    private double startPrice;
    private double currentPrice;
    private double sessionHigh;
    private double sessionLow;
    private int totalTrades;
    private double totalVolume;
    
    @Override
    protected void setup() {
        marketStats = new MarketStatistics();
        traderStats = new HashMap<>();
        marketEvents = new ArrayList<>();
        dateFormat = new SimpleDateFormat("HH:mm:ss");
        
        // ✅ CRÉER le dossier sessions
        File sessionsDir = new File("sessions");
        if (!sessionsDir.exists()) {
            sessionsDir.mkdirs();
            System.out.println("📁 Created sessions directory");
        }

        // Initialize logging
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String logFileName = "sessions/trading_session_" + timestamp + ".log";
            int accelerationFactor = getAccelerationFactor();
            
            logWriter = new PrintWriter(new FileWriter(logFileName));
            
            // ✅ HEADER COMPLET
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
            
            System.out.println("📝 MarketStats logging to: " + logFileName);
            
        } catch (IOException e) {
            System.err.println("❌ Could not create log file: " + e.getMessage());
        }
        
        System.out.println("Market Statistics Agent started");
        
        // Find market maker
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    // Add monitoring behaviours
                    addBehaviour(new MarketDataMonitor());
                    addBehaviour(new TradeMonitor());
                    addBehaviour(new NewsMonitor());
                    addBehaviour(new PeriodicReportBehaviour(myAgent, adjustInterval(10000))); // Report every 10 seconds
                    addBehaviour(new MarketAlertBehaviour(myAgent, adjustInterval(5000))); // Check alerts every 5 seconds
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
                
                // 🔧 CORRECTION: S'enregistrer pour recevoir les trades
                registerForTradeNotifications();
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    // 🔧 AJOUT: Nouvelle méthode pour s'enregistrer
    private void registerForTradeNotifications() {
        ACLMessage register = new ACLMessage(ACLMessage.REQUEST);
        register.addReceiver(marketMaker);
        register.setProtocol("PORTFOLIO");
        register.setContent("REGISTER:" + 0.0); // MarketStats n'a pas de capital
        send(register);
        
        System.out.println("MarketStats registered for trade notifications");
    }
    
    /**
     * Monitor market data updates
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
                // Parse: PRICE:AAPL:100,50:BID:99,75:ASK:101,25:VOLUME:150:VOLATILITY:0,0250
                String[] parts = data.split(":");
                
                if (parts.length >= 11 && "PRICE".equals(parts[0])) {
                    // CORRECTION: Remplacer virgules par points
                    String priceStr = parts[2].replace(",", ".");
                    String volumeStr = parts[8];
                    String volatilityStr = parts[10].replace(",", ".");
                    
                    currentPrice = Double.parseDouble(priceStr);
                    int volume = Integer.parseInt(volumeStr);
                    double volatility = Double.parseDouble(volatilityStr);
                    
                    // Initialize start price
                    if (startPrice == 0) {
                        startPrice = currentPrice;
                        sessionHigh = currentPrice;
                        sessionLow = currentPrice;
                        System.out.println("MarketStats: Opening price set to $" + String.format("%.2f", startPrice));
                    }
                    
                    // Update session high/low
                    if (currentPrice > sessionHigh) {
                        sessionHigh = currentPrice;
                        logEvent("New session HIGH: $" + String.format("%.2f", sessionHigh));
                    }
                    if (currentPrice < sessionLow) {
                        sessionLow = currentPrice;
                        logEvent("New session LOW: $" + String.format("%.2f", sessionLow));
                    }
                    
                    // Update statistics
                    marketStats.updatePrice(currentPrice);
                    marketStats.updateVolume(volume);
                }
            } catch (Exception e) {
                System.err.println("MarketStats error processing: " + data + " - " + e.getMessage());
            }
        }
    }
    
    /**
     * Monitor trade executions
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
            // Parse: TRADE:ConservativeTrader-1:AAPL:29:100,58:BUY
            String[] parts = tradeData.split(":");
            
            if (parts.length >= 6 && "TRADE".equals(parts[0])) {
                String trader = parts[1];
                String symbol = parts[2];
                int quantity = Integer.parseInt(parts[3]);
                
                // CORRECTION: Conversion virgule→point
                String priceStr = parts[4].replace(",", ".");
                double price = Double.parseDouble(priceStr);
                
                String action = parts[5];
                
                // Update trader statistics
                TraderStatistics stats = traderStats.getOrDefault(trader, new TraderStatistics(trader));
                stats.recordTrade(action, quantity, price);
                traderStats.put(trader, stats);
                
                totalTrades++;
                totalVolume += quantity * price;
                
                // Log significant trades
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
     * Monitor news events
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
            // Parse: POSITIVE/NEGATIVE:description:IMPACT:HIGH/MEDIUM/LOW
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
     * Generate periodic market reports
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
            System.out.println("\n" + "=".repeat(60));
            System.out.println("         MARKET REPORT - " + dateFormat.format(new Date()));
            System.out.println("=".repeat(60));
            
            // Price information
            double changePercent = ((currentPrice - startPrice) / startPrice) * 100;
            System.out.println(String.format("Current Price: $%.2f (%.2f%% from open)",
                currentPrice, changePercent));
            System.out.println(String.format("Session Range: $%.2f - $%.2f",
                sessionLow, sessionHigh));
            
            // Volume and trades
            System.out.println(String.format("Total Trades: %d", totalTrades));
            System.out.println(String.format("Total Volume: $%.2f", totalVolume));
            
            // Market statistics
            System.out.println(marketStats.getMarketSummary());
            
            // Top traders
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
            
            // Recent events
            if (!marketEvents.isEmpty()) {
                System.out.println("\n--- RECENT EVENTS ---");
                int start = Math.max(0, marketEvents.size() - 3);
                for (int i = start; i < marketEvents.size(); i++) {
                    System.out.println("• " + marketEvents.get(i));
                }
            }
            
            System.out.println("=".repeat(60) + "\n");
            
            // Write to log file
            if (logWriter != null) {
                logWriter.println("\n[REPORT] " + dateFormat.format(new Date()));
                logWriter.println(String.format("Price: $%.2f (%.2f%%)", currentPrice, changePercent));
                logWriter.println(String.format("Trades: %d, Volume: $%.2f", totalTrades, totalVolume));
                logWriter.flush();
            }
        }
    }
    
    /**
     * Monitor for market alerts
     */
    private class MarketAlertBehaviour extends TickerBehaviour {
        private boolean crashAlerted = false;
        private boolean bubbleAlerted = false;
        
        public MarketAlertBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Check for crash (>10% drop from high)
            if (!crashAlerted && sessionHigh > 0) {
                double dropPercent = ((sessionHigh - currentPrice) / sessionHigh) * 100;
                if (dropPercent > 10) {
                    String alert = "⚠️ CRASH ALERT: Market down " + 
                                 String.format("%.1f%%", dropPercent) + " from session high!";
                    System.out.println("\n" + alert);
                    logEvent(alert);
                    crashAlerted = true;
                }
            }
            
            // Check for bubble (>20% rise from start)
            if (!bubbleAlerted && startPrice > 0) {
                double risePercent = ((currentPrice - startPrice) / startPrice) * 100;
                if (risePercent > 20) {
                    String alert = "🚀 BUBBLE ALERT: Market up " + 
                                 String.format("%.1f%%", risePercent) + " from open!";
                    System.out.println("\n" + alert);
                    logEvent(alert);
                    bubbleAlerted = true;
                }
            }
            
            // Check for high volatility
            if (marketStats.getVolatility() > 0.05) {
                System.out.println("⚡ HIGH VOLATILITY: " + 
                                 String.format("%.1f%%", marketStats.getVolatility() * 100));
            }
        }
    }
    
    /**
     * Log market events
     */
    private void logEvent(String event) {
        String timestampedEvent = "[" + dateFormat.format(new Date()) + "] " + event;
        
        if (logWriter != null) {
            logWriter.println(timestampedEvent);
            logWriter.flush();
        }
    }
    
    /**
     * Trader statistics tracking
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
            
            // Simple P&L calculation
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
        // Generate final report
        System.out.println("\n" + "=".repeat(60));
        System.out.println("         FINAL SESSION REPORT");
        System.out.println("=".repeat(60));
        
        double finalReturn = ((currentPrice - startPrice) / startPrice) * 100;
        System.out.println(String.format("Opening Price: $%.2f", startPrice));
        System.out.println(String.format("Closing Price: $%.2f", currentPrice));
        System.out.println(String.format("Session Return: %.2f%%", finalReturn));
        System.out.println(String.format("Session High: $%.2f", sessionHigh));
        System.out.println(String.format("Session Low: $%.2f", sessionLow));
        System.out.println(String.format("Total Trades: %d", totalTrades));
        System.out.println(String.format("Total Volume: $%.2f", totalVolume));
        
        // Winner and loser
        if (!traderStats.isEmpty()) {
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
        }
        
        System.out.println("=".repeat(60));
        
        // Close log file
        if (logWriter != null) {
            logWriter.println("\n=== SESSION ENDED ===");
            logWriter.println("End time: " + new Date());
            logWriter.close();
        }
        
        System.out.println("MarketStats agent shutting down");
    }
}