package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import model.*;
import java.util.*;

/**
 * Follower Trader Agent - BDI Architecture
 * Copies successful traders and follows market trends
 * Exhibits herd behavior and social trading
 */
public class FollowerTraderAgent extends Agent {
    
    // BDI Components
    private TradingBeliefs beliefs;
    private FollowerDesires desires;
    private FollowerIntentions intentions;
    
    // Portfolio Management
    private double initialCapital;
    private double currentCash;
    private int sharesOwned;
    
    // Social Trading
    private Map<String, TraderProfile> observedTraders;
    private String currentLeader = null;
    private int followDelay = 2; // Delay in following trades
    private Queue<String> recentTrades;
    
    // Herd Behavior Parameters
    private double herdConfidence = 0.5; // 0 = independent, 1 = pure follower
    private int minTradersForHerd = 2; // Minimum traders doing same action
    
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
        currentCash = initialCapital;
        sharesOwned = 0;
        
        // Initialize components
        beliefs = new TradingBeliefs();
        desires = new FollowerDesires();
        intentions = new FollowerIntentions();
        observedTraders = new HashMap<>();
        recentTrades = new LinkedList<>();
        
        // Randomize follower personality
        herdConfidence = 0.3 + Math.random() * 0.6; // 30% to 90% follower
        
        System.out.println("Follower Trader " + getLocalName() + 
                         " started with capital: $" + initialCapital +
                         " (Herd confidence: " + String.format("%.0f%%", herdConfidence * 100) + ")");
        
        // Find market maker
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    registerWithMarket();
                    
                    // Add follower behaviours
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new TradeObservationBehaviour());
                    addBehaviour(new FollowTradingBehaviour(myAgent, 2000));
                    addBehaviour(new HerdBehaviourAnalysis(myAgent, 3000));
                    addBehaviour(new LeaderSelectionBehaviour(myAgent, 10000));
                }
            }
        });
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
                System.out.println(getLocalName() + " found MarketMaker");
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    private void registerWithMarket() {
        ACLMessage register = new ACLMessage(ACLMessage.REQUEST);
        register.addReceiver(marketMaker);
        register.setProtocol("PORTFOLIO");
        register.setContent("REGISTER:" + currentCash);
        send(register);
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
                   leader.getSuccessRate() > 0.5;
        }
        
        public boolean wantsToJoinHerd() {
            // Check if many traders are doing the same thing
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
        
        private Map<String, Integer> getRecentActionCounts() {
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
                double maxInvestment = currentCash * 0.3 * riskFactor;
                return Math.max(10, (int)(maxInvestment / beliefs.getAskPrice()));
            } else if ("SELL".equals(action)) {
                return Math.max(10, (int)(sharesOwned * 0.5 * riskFactor));
            }
            
            return 0;
        }
        
        public boolean shouldDelayAction() {
            // Add realistic delay to following
            return followDelay > 0;
        }
    }
    
    /**
     * Market data and trade observation
     */
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            // Market data
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
                
                // Parse trade: TraderName:BUY/SELL:Symbol:Quantity:Price
                String[] parts = content.split(":");
                String traderName = parts[0];
                String action = parts[1];
                double price = Double.parseDouble(parts[4]);
                
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
                    
                    System.out.println(getLocalName() + " observed: " + 
                                     traderName + " " + action + " @ $" + 
                                     String.format("%.2f", price));
                }
            } else {
                block();
            }
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
            
            // Determine action based on desires
            if (desires.wantsToFollowLeader() && !intentions.shouldDelayAction()) {
                followLeaderAction();
            } else if (desires.wantsToJoinHerd() && !intentions.shouldDelayAction()) {
                followHerdAction();
            } else if (desires.wantsToActIndependently()) {
                actIndependently();
            }
        }
        
        private void followLeaderAction() {
            String action = intentions.determineActionToFollow();
            
            if ("BUY".equals(action)) {
                int quantity = intentions.calculateFollowQuantity("BUY");
                if (quantity > 0 && currentCash >= quantity * beliefs.getAskPrice()) {
                    executeBuy(quantity, "Following leader: " + currentLeader);
                }
            } else if ("SELL".equals(action) && sharesOwned > 0) {
                int quantity = intentions.calculateFollowQuantity("SELL");
                quantity = Math.min(quantity, sharesOwned);
                if (quantity > 0) {
                    executeSell(quantity, "Following leader: " + currentLeader);
                }
            }
            
            followDelay = 2 + (int)(Math.random() * 3); // Reset delay
        }
        
        private void followHerdAction() {
            String action = intentions.determineActionToFollow();
            
            if ("BUY".equals(action)) {
                int quantity = intentions.calculateFollowQuantity("BUY");
                if (quantity > 0 && currentCash >= quantity * beliefs.getAskPrice()) {
                    executeBuy(quantity, "Following the herd");
                }
            } else if ("SELL".equals(action) && sharesOwned > 0) {
                int quantity = intentions.calculateFollowQuantity("SELL");
                quantity = Math.min(quantity, sharesOwned);
                if (quantity > 0) {
                    executeSell(quantity, "Following the herd");
                }
            }
            
            followDelay = 1 + (int)(Math.random() * 3);
        }
        
        private void actIndependently() {
            if (beliefs.isOversold() && currentCash > 1000) {
                int quantity = (int)(currentCash * 0.2 / beliefs.getAskPrice());
                if (quantity > 0) {
                    executeBuy(quantity, "Independent decision (oversold)");
                }
            } else if (beliefs.isOverbought() && sharesOwned > 0) {
                int quantity = sharesOwned / 3;
                if (quantity > 0) {
                    executeSell(quantity, "Independent decision (overbought)");
                }
            }
        }
        
        private void executeBuy(int quantity, String reason) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("BUY:AAPL:" + quantity + ":SHARES:MARKET_PRICE");
            send(order);
            
            double price = beliefs.getAskPrice();
            currentCash -= quantity * price;
            sharesOwned += quantity;
            
            System.out.println(getLocalName() + " BUYING " + quantity + 
                             " shares @ $" + String.format("%.2f", price) +
                             " (" + reason + ")");
        }
        
        private void executeSell(int quantity, String reason) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("SELL:AAPL:" + quantity + ":SHARES:MARKET_PRICE");
            send(order);
            
            double price = beliefs.getBidPrice();
            currentCash += quantity * price;
            sharesOwned -= quantity;
            
            System.out.println(getLocalName() + " SELLING " + quantity + 
                             " shares @ $" + String.format("%.2f", price) +
                             " (" + reason + ")");
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
            if (Math.random() < 0.2) { // Log occasionally
                System.out.println(getLocalName() + " Herd Analysis: " +
                                 "Buy signals: " + buyCount + 
                                 ", Sell signals: " + sellCount +
                                 ", Confidence: " + String.format("%.0f%%", herdConfidence * 100));
            }
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
                
                if (profile.totalTrades > 5 && // Minimum trades
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
            double portfolioValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
            double returnPercent = (portfolioValue - initialCapital) / initialCapital;
            
            System.out.println(getLocalName() + " Portfolio: $" + 
                             String.format("%.2f", portfolioValue) +
                             " (Return: " + String.format("%.2f%%", returnPercent * 100) +
                             ", Following: " + (currentLeader != null ? currentLeader : "None") + ")");
        }
    }
    
    @Override
    protected void takeDown() {
        double finalValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
        double totalReturn = (finalValue - initialCapital) / initialCapital;
        
        System.out.println("=== " + getLocalName() + " Final Report ===");
        System.out.println("Initial Capital: $" + initialCapital);
        System.out.println("Final Value: $" + String.format("%.2f", finalValue));
        System.out.println("Total Return: " + String.format("%.2f%%", totalReturn * 100));
        System.out.println("Strategy: Social Trading / Copy Trading");
        System.out.println("Final Leader: " + (currentLeader != null ? currentLeader : "None"));
        System.out.println("Herd Confidence: " + String.format("%.0f%%", herdConfidence * 100));
    }
}