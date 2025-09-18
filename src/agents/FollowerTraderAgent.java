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

/**
 * Follower Trader Agent - BDI Architecture avec Portfolio
 * Copies successful traders and follows market trends
 * Exhibits herd behavior and social trading
 */
public class FollowerTraderAgent extends Agent {
    
    // BDI Components
    private TradingBeliefs beliefs;
    private FollowerDesires desires;
    private FollowerIntentions intentions;
    
    // 🔧 REFACTORING: Portfolio Management comme ConservativeTrader
    private double initialCapital;
    private Portfolio portfolio;
    private String stockSymbol = "AAPL";
    
    // Social Trading
    private Map<String, TraderProfile> observedTraders;
    private String currentLeader = null;
    private int followDelay = 0; // Pas de délai pour plus de trading
    private Queue<String> recentTrades;
    
    // Herd Behavior Parameters
    private double herdConfidence = 0.5; // 0 = independent, 1 = pure follower
    private int minTradersForHerd = 1; // Minimum traders doing same action
    private int totalFollows = 0;
    
    private AID marketMaker;
    
    @Override
    protected void setup() {
        // Initialize capital
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        } else {
            initialCapital = 8000.0;
        }
        
        // 🔧 REFACTORING: Utiliser Portfolio comme ConservativeTrader
        portfolio = new Portfolio(getLocalName());
        portfolio.addCash(initialCapital);
        
        // Initialize components
        beliefs = new TradingBeliefs();
        desires = new FollowerDesires();
        intentions = new FollowerIntentions();
        observedTraders = new HashMap<>();
        recentTrades = new LinkedList<>();
        
        // Randomize follower personality
        herdConfidence = 0.2 + Math.random() * 0.6; // 30% to 90% follower
        
        System.out.println("Follower Trader " + getLocalName() + 
                         " started with capital: $" + initialCapital +
                         " (Herd confidence: " + String.format("%.0f%%", herdConfidence * 100) + ")");
        
        // Find market maker
        addBehaviour(new WakerBehaviour(this, 3000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    registerWithMarket();
                    
                    // Add follower behaviours
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new OrderResponseHandler()); // 🔧 NOUVEAU
                    addBehaviour(new TradeObservationBehaviour());
                    addBehaviour(new FollowTradingBehaviour(myAgent, adjustInterval(2000)));
                    addBehaviour(new HerdBehaviourAnalysis(myAgent, adjustInterval(5000)));
                    addBehaviour(new LeaderSelectionBehaviour(myAgent, adjustInterval(10000)));
                    
                    System.out.println(getLocalName() + " ready for social trading!");
                }
            }
        });
    }
    
    // 🔧 NOUVEAU: Méthodes Portfolio
    private double getCurrentCash() {
        return portfolio.getCash();
    }
    
    private int getSharesOwned() {
        return portfolio.getShares(stockSymbol);
    }
    
    private double getPortfolioValue() {
        double currentPrice = beliefs.getCurrentPrice() > 0 ? beliefs.getCurrentPrice() : 100.0;
        return portfolio.getTotalValue(stockSymbol, currentPrice);
    }
    
    private double getPortfolioReturn() {
        double currentValue = getPortfolioValue();
        return ((currentValue - initialCapital) / initialCapital) * 100;
    }
    
    private int getAccelerationFactor() {
        return Integer.parseInt(System.getProperty("trading.acceleration", "1"));
    }

    private long adjustInterval(long originalInterval) {
        return Math.max(50, originalInterval / getAccelerationFactor());
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
                System.out.println(getLocalName() + " found MarketMaker: " + marketMaker.getLocalName());
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    private void registerWithMarket() {
        try {
            ACLMessage register = new ACLMessage(ACLMessage.REQUEST);
            register.addReceiver(marketMaker);
            register.setProtocol("PORTFOLIO"); // 🔧 CORRECTION: Même protocole
            register.setContent("REGISTER:" + initialCapital); // 🔧 CORRECTION: Même format
            send(register);
            
            System.out.println(getLocalName() + " registration request sent");
        } catch (Exception e) {
            System.err.println(getLocalName() + " error registering: " + e.getMessage());
        }
    }
    
    // 🔧 NOUVEAU: OrderResponseHandler comme ConservativeTrader
    private class OrderResponseHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchProtocol("TRADING"),
                MessageTemplate.MatchPerformative(ACLMessage.CONFIRM)
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String response = msg.getContent();
                System.out.println(getLocalName() + " Order response: " + response);
                
                try {
                    if (response.startsWith("EXECUTED:")) {
                        String[] parts = response.split(":");
                        
                        if (parts.length >= 5) {
                            String action = parts[1];
                            int quantity = Integer.parseInt(parts[2]);
                            String symbol = parts[3];
                            String priceStr = parts[4].replace(",", ".");
                            double executionPrice = Double.parseDouble(priceStr);
                            
                            // 🔧 CORRECTION: Mettre à jour le portfolio local
                            if ("BUY".equals(action)) {
                                portfolio.buy(symbol, quantity, executionPrice);
                                totalFollows++;
                                System.out.println(getLocalName() + " ✅ LOCAL Portfolio updated: " +
                                                "Bought " + quantity + " @ $" + String.format("%.2f", executionPrice));
                            } else if ("SELL".equals(action)) {
                                portfolio.sell(symbol, quantity, executionPrice);
                                totalFollows++;
                                System.out.println(getLocalName() + " ✅ LOCAL Portfolio updated: " +
                                                "Sold " + quantity + " @ $" + String.format("%.2f", executionPrice));
                            }
                            
                            // Debug portfolio state
                            double currentPrice = beliefs.getCurrentPrice();
                            System.out.println(getLocalName() + " 📊 Portfolio after trade: " +
                                            "Cash=$" + String.format("%.2f", getCurrentCash()) +
                                            ", Shares=" + getSharesOwned() +
                                            ", Value=$" + String.format("%.2f", getPortfolioValue()));
                        }
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " ❌ Error processing order response: " + e.getMessage());
                }
            } else {
                block();
            }
        }
    }

    private boolean shouldFollowTrade(String trader, String action, int quantity, double price) {
        boolean hasEnoughCash = getCurrentCash() >= quantity * price * 0.5; // 50% du trade
        boolean hasShares = getSharesOwned() > 0;
        boolean mediumConfidence = herdConfidence > 0.25; // 25% seuil
        boolean isLeader = trader.equals(currentLeader);
        boolean isNotFollower = !trader.startsWith("Follower"); // Ne pas suivre d'autres followers
        
        System.out.println(getLocalName() + " 🤔 Should follow " + trader + " " + action + "?");
        System.out.println("  Trade value: $" + String.format("%.2f", quantity * price));
        System.out.println("  My cash: $" + String.format("%.2f", getCurrentCash()));
        System.out.println("  Enough cash for 50%: " + hasEnoughCash);
        System.out.println("  Herd confidence: " + String.format("%.0f%%", herdConfidence * 100) + " (need >40%)");
        System.out.println("  Is leader: " + isLeader);
        System.out.println("  Not follower: " + isNotFollower);
        
        if ("BUY".equals(action)) {
            boolean decision = hasEnoughCash && (mediumConfidence || isLeader) && isNotFollower;
            System.out.println("  👉 BUY Decision: " + decision);
            return decision;
        } else if ("SELL".equals(action)) {
            boolean decision = hasShares && quantity <= getSharesOwned() && (mediumConfidence || isLeader) && isNotFollower;
            System.out.println("  👉 SELL Decision: " + decision);
            return decision;
        }
        
        return false;
    }
    
    /**
     * Trader profile for tracking other traders
     */
    private class TraderProfile {
        String name;
        int totalTrades = 0;
        int profitableTrades = 0;
        String lastAction = "";
        double lastPrice = 0;
        long lastTradeTime = 0;
        double estimatedPerformance = 0;
        
        public TraderProfile(String name) {
            this.name = name;
        }
        
        public void recordTrade(String action, double price) {
            totalTrades++;
            lastAction = action;
            lastPrice = price;
            lastTradeTime = System.currentTimeMillis();
            
            // Estimate performance
            if ("SELL".equals(action) && price > beliefs.getMovingAverage20()) {
                profitableTrades++;
            }
            
            if (totalTrades > 0) {
                estimatedPerformance = (double) profitableTrades / totalTrades;
            }
        }
        
        public double getSuccessRate() {
            return estimatedPerformance;
        }
        
        public boolean isRecentlyActive() {
            return (System.currentTimeMillis() - lastTradeTime) < 30000; // Active in last 30 seconds
        }
    }
    
    /**
     * Follower Desires - What the follower wants
     */
    private class FollowerDesires {
        
        public boolean wantsToFollowLeader() {
            if (currentLeader == null) return false;
            
            TraderProfile leader = observedTraders.get(currentLeader);
            return leader != null && 
                   leader.isRecentlyActive() &&
                   leader.getSuccessRate() > 0.3; // 30% seuil
        }
        
        public boolean wantsToJoinHerd() {
            Map<String, Integer> actionCounts = getRecentActionCounts();
            
            for (int count : actionCounts.values()) {
                if (count >= minTradersForHerd) {
                    return Math.random() < herdConfidence;
                }
            }
            
            return false;
        }
        
        public boolean wantsToActIndependently() {
            // Sometimes act on own analysis
            return Math.random() > herdConfidence &&
                   (beliefs.isOversold() || beliefs.isOverbought());
        }
        
        public Map<String, Integer> getRecentActionCounts() {
            Map<String, Integer> counts = new HashMap<>();
            
            for (TraderProfile trader : observedTraders.values()) {
                if (trader.isRecentlyActive()) {
                    counts.put(trader.lastAction, 
                              counts.getOrDefault(trader.lastAction, 0) + 1);
                }
            }
            
            return counts;
        }
    }
    
    /**
     * Follower Intentions - How to execute following strategy
     */
    private class FollowerIntentions {
        
        public String determineActionToFollow() {
            if (currentLeader != null) {
                TraderProfile leader = observedTraders.get(currentLeader);
                if (leader != null) {
                    return leader.lastAction;
                }
            }
            
            // Follow the herd
            Map<String, Integer> actionCounts = desires.getRecentActionCounts();
            String mostPopularAction = null;
            int maxCount = 0;
            
            for (Map.Entry<String, Integer> entry : actionCounts.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    mostPopularAction = entry.getKey();
                }
            }
            
            return mostPopularAction;
        }
        
        public int calculateFollowQuantity(String action) {
            double riskFactor = herdConfidence; // More confident = larger positions
            
            if ("BUY".equals(action)) {
                double maxInvestment = getCurrentCash() * 0.3 * riskFactor; // 30% max
                int quantity = (int)(maxInvestment / beliefs.getAskPrice());
                return Math.max(5, Math.min(15, quantity)); // Entre 5 et 15 shares
            } else if ("SELL".equals(action)) {
                int maxSell = (int)(getSharesOwned() * 0.5 * riskFactor); // 50% max
                return Math.max(5, Math.min(maxSell, getSharesOwned()));
            }
            
            return 0;
        }
    }
    
    /**
     * Market data receiver
     */
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("MARKET-DATA");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                beliefs.updateMarketData(msg.getContent());
            } else {
                block();
            }
        }
    }
    
    /**
     * Observe other traders' actions
     */
    private class TradeObservationBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("TRADE-EXECUTED");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                String content = msg.getContent();
                beliefs.updateTradeInfo(content);
                
                try {
                    // Parse trade: TRADE:ConservativeTrader-1:AAPL:29:100,58:BUY
                    String[] parts = content.split(":");
                    
                    if (parts.length >= 6 && "TRADE".equals(parts[0])) {
                        String traderName = parts[1];
                        String symbol = parts[2];
                        int quantity = Integer.parseInt(parts[3]);
                        String priceStr = parts[4].replace(",", ".");
                        double price = Double.parseDouble(priceStr);
                        String action = parts[5];
                        
                        // Don't follow own trades
                        if (!traderName.equals(getLocalName())) {
                            // Update trader profile
                            TraderProfile profile = observedTraders.getOrDefault(
                                traderName, new TraderProfile(traderName));
                            profile.recordTrade(action, price);
                            observedTraders.put(traderName, profile);
                            
                            // Add to recent trades queue
                            recentTrades.offer(action);
                            if (recentTrades.size() > 20) {
                                recentTrades.poll();
                            }
                            
                            System.out.println(getLocalName() + " observed: TRADE " + 
                                            traderName + " @ $" + String.format("%.2f", price));

                            System.out.println(getLocalName() + " 🚀 FORCED FOLLOW TEST:");
                            System.out.println("  Trader: " + traderName + ", Action: " + action);
                            System.out.println("  My cash: $" + String.format("%.2f", getCurrentCash()));
                            System.out.println("  Trade cost: $" + String.format("%.2f", quantity * price * 0.5)); // 50% du trade

                            // 🔧 CORRECTION: Follow logic avec Portfolio
                            if ("BUY".equals(action) && shouldFollowTrade(traderName, action, quantity, price)) {
                                int followQuantity = Math.max(1, quantity / 2); // 50% du trade
                                double followCost = followQuantity * beliefs.getAskPrice();
                                
                                if (getCurrentCash() >= followCost) {
                                    System.out.println(getLocalName() + " 💸 EXECUTING FOLLOW BUY!");
                                    executeBuyOrder(followQuantity, "Following " + traderName);
                                } else {
                                    System.out.println(getLocalName() + " ❌ Not enough cash to follow");
                                }
                            } else if ("SELL".equals(action) && shouldFollowTrade(traderName, action, quantity, price)) {
                                int followQuantity = Math.min(getSharesOwned(), Math.max(1, quantity / 2));
                                
                                if (followQuantity > 0) {
                                    System.out.println(getLocalName() + " 💰 EXECUTING FOLLOW SELL!");
                                    executeSellOrder(followQuantity, "Following " + traderName);
                                } else {
                                    System.out.println(getLocalName() + " ❌ No shares to follow sell");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " ❌ Error parsing trade: " + content);
                    System.err.println("Error: " + e.getMessage());
                }
            } else {
                block();
            }
        }
    }
    
    // 🔧 NOUVEAU: Méthodes d'exécution des ordres avec Portfolio
    private void executeBuyOrder(int quantity, String reason) {
        if (quantity > 0 && getCurrentCash() >= quantity * beliefs.getAskPrice()) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("BUY:" + stockSymbol + ":" + quantity + ":" + String.format("%.2f", beliefs.getAskPrice()));
            send(order);
            
            System.out.println(getLocalName() + " 🚀 BUY ORDER: " + quantity + 
                             " shares @ $" + String.format("%.2f", beliefs.getAskPrice()) +
                             " (" + reason + ")");
        }
    }
    
    private void executeSellOrder(int quantity, String reason) {
        if (quantity > 0 && getSharesOwned() >= quantity) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("SELL:" + stockSymbol + ":" + quantity + ":" + String.format("%.2f", beliefs.getBidPrice()));
            send(order);
            
            System.out.println(getLocalName() + " 💰 SELL ORDER: " + quantity + 
                             " shares @ $" + String.format("%.2f", beliefs.getBidPrice()) +
                             " (" + reason + ")");
        }
    }
    
    /**
     * Main following behaviour
     */
    private class FollowTradingBehaviour extends TickerBehaviour {
        public FollowTradingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Update follow delay
            if (followDelay > 0) {
                followDelay--;
            }
            
            if (followDelay == 0) {
                if (desires.wantsToFollowLeader()) {
                    followLeaderAction();
                } else if (desires.wantsToJoinHerd()) {
                    followHerdAction();  
                } else if (desires.wantsToActIndependently()) {
                    actIndependently();
                }
            }
        }
        
        private void followLeaderAction() {
            String action = intentions.determineActionToFollow();
            
            if ("BUY".equals(action)) {
                int quantity = intentions.calculateFollowQuantity("BUY");
                executeBuyOrder(quantity, "Following leader: " + currentLeader);
            } else if ("SELL".equals(action) && getSharesOwned() > 0) {
                int quantity = intentions.calculateFollowQuantity("SELL");
                executeSellOrder(quantity, "Following leader: " + currentLeader);
            }
            
            followDelay = 1 + (int)(Math.random() * 2); // Reset delay
        }
        
        private void followHerdAction() {
            String action = intentions.determineActionToFollow();
            
            if ("BUY".equals(action)) {
                int quantity = intentions.calculateFollowQuantity("BUY");
                executeBuyOrder(quantity, "Following the herd");
            } else if ("SELL".equals(action) && getSharesOwned() > 0) {
                int quantity = intentions.calculateFollowQuantity("SELL");
                executeSellOrder(quantity, "Following the herd");
            }
            
            followDelay = 1 + (int)(Math.random() * 2);
        }
        
        private void actIndependently() {
            if (beliefs.isOversold() && getCurrentCash() > 1000) {
                int quantity = (int)(getCurrentCash() * 0.2 / beliefs.getAskPrice());
                executeBuyOrder(quantity, "Independent decision (oversold)");
            } else if (beliefs.isOverbought() && getSharesOwned() > 0) {
                int quantity = getSharesOwned() / 3;
                executeSellOrder(quantity, "Independent decision (overbought)");
            }
        }
    }
    
    /**
     * Analyze herd behavior patterns
     */
    private class HerdBehaviourAnalysis extends TickerBehaviour {
        public HerdBehaviourAnalysis(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Count recent actions
            int buyCount = 0;
            int sellCount = 0;
            
            for (String trade : recentTrades) {
                if ("BUY".equals(trade)) buyCount++;
                else if ("SELL".equals(trade)) sellCount++;
            }
            
            // Adjust herd confidence based on market volatility
            if (beliefs.getVolatility() > 0.05) {
                // High volatility - increase herd behavior
                herdConfidence = Math.min(0.9, herdConfidence + 0.05);
            } else {
                // Low volatility - decrease herd behavior
                herdConfidence = Math.max(0.3, herdConfidence - 0.02);
            }
            
            // Log herd analysis
            System.out.println(getLocalName() + " Herd Analysis: " +
                             "Buy signals: " + buyCount + 
                             ", Sell signals: " + sellCount +
                             ", Confidence: " + String.format("%.0f%%", herdConfidence * 100));
        }
    }
    
    /**
     * Select and update leader to follow
     */
    private class LeaderSelectionBehaviour extends TickerBehaviour {
        public LeaderSelectionBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Find best performing trader
            String bestTrader = null;
            double bestPerformance = 0;
            
            for (Map.Entry<String, TraderProfile> entry : observedTraders.entrySet()) {
                TraderProfile profile = entry.getValue();
                
                if (profile.totalTrades > 3 && // Minimum trades
                    profile.getSuccessRate() > bestPerformance &&
                    profile.isRecentlyActive()) {
                    
                    bestTrader = entry.getKey();
                    bestPerformance = profile.getSuccessRate();
                }
            }
            
            // Update leader if found better one
            if (bestTrader != null && !bestTrader.equals(currentLeader)) {
                String oldLeader = currentLeader;
                currentLeader = bestTrader;
                
                System.out.println(getLocalName() + " changed leader from " + 
                                 oldLeader + " to " + currentLeader +
                                 " (Success rate: " + String.format("%.0f%%", bestPerformance * 100) + ")");
            }
            
            // Log portfolio status
            System.out.println(getLocalName() + " Portfolio: $" + 
                             String.format("%.2f", getPortfolioValue()) +
                             " (Return: " + String.format("%.2f%%", getPortfolioReturn()) +
                             ", Following: " + (currentLeader != null ? currentLeader : "None") + ")");
        }
    }
    
    @Override
    protected void takeDown() {
        double finalValue = getPortfolioValue();
        double totalReturn = getPortfolioReturn();
        
        System.out.println("=== " + getLocalName() + " Final Report ===");
        System.out.println("Initial Capital: $" + initialCapital);
        System.out.println("Final Value: $" + String.format("%.2f", finalValue));
        System.out.println("Total Return: " + String.format("%.2f%%", totalReturn));
        System.out.println("Strategy: Social Trading / Copy Trading");
        System.out.println("Final Leader: " + (currentLeader != null ? currentLeader : "None"));
        System.out.println("Herd Confidence: " + String.format("%.0f%%", herdConfidence * 100));
        System.out.println("Total Follows: " + totalFollows);
    }
}