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
 * Agent trader suiveur utilisant l'architecture BDI.
 * Strategie: copie les traders performants et suit les tendances du marche.
 * Exhibe un comportement gregaire et de trading social.
 */
public class FollowerTraderAgent extends Agent {
    
    // Composants BDI
    private TradingBeliefs beliefs;
    private FollowerDesires desires;
    private FollowerIntentions intentions;
    
    // Gestion du portfolio
    private double initialCapital;
    private Portfolio portfolio;
    private String stockSymbol = "AAPL";
    
    // Trading social
    private Map<String, TraderProfile> observedTraders;
    private String currentLeader = null;
    private int followDelay = 0;
    private Queue<String> recentTrades;
    
    // Parametres de comportement gregaire
    private double herdConfidence = 0.5; // 0 = independant, 1 = suiveur pur
    private int minTradersForHerd = 1;
    private int totalFollows = 0;
    
    private AID marketMaker;

    // Garde-fous contre le sur-trading
    private long lastTradeRealMs = 0L;
    private boolean hasPendingOrder = false;
    private int tradesThisSession = 0;
    private final int maxTradesPerSimHour = 80;
    private long lastNewsHandled = 0L;
    private final long newsDebounceMs = simSecondsToRealMs(30);

    private static int accel() {
        try { return Integer.parseInt(System.getProperty("trading.acceleration", "1")); }
        catch (Exception e) { return 1; }
    }
    
    private static long simSecondsToRealMs(int s){ 
        return (s * 1000L) / Math.max(1, accel()); 
    }
    
    private final long minCooldownRealMs = simSecondsToRealMs(90);

    private boolean canTradeNow() {
        long now = System.currentTimeMillis();
        return !hasPendingOrder
            && (now - lastTradeRealMs >= minCooldownRealMs)
            && (tradesThisSession < maxTradesPerSimHour);
    }
    
    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        } else {
            initialCapital = 8000.0;
        }
        
        // Initialisation du portfolio
        portfolio = new Portfolio(getLocalName());
        portfolio.addCash(initialCapital);
        
        // Initialisation des composants
        beliefs = new TradingBeliefs();
        desires = new FollowerDesires();
        intentions = new FollowerIntentions();
        observedTraders = new HashMap<>();
        recentTrades = new LinkedList<>();
        
        // Personnalite aleatoire du suiveur
        herdConfidence = 0.2 + Math.random() * 0.6; // 20% a 80% suiveur
        
        System.out.println("Follower Trader " + getLocalName() + 
                         " started with capital: $" + initialCapital +
                         " (Herd confidence: " + String.format("%.0f%%", herdConfidence * 100) + ")");
        
        // Recherche du MarketMaker et initialisation des comportements
        addBehaviour(new WakerBehaviour(this, 3000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                subscribeMarketData();
                
                if (marketMaker != null) {
                    registerWithMarket();
                    
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new OrderResponseHandler());
                    addBehaviour(new NewsListener());
                    addBehaviour(new TradeObservationBehaviour());
                    addBehaviour(new FollowTradingBehaviour(myAgent, adjustInterval(2000)));
                    addBehaviour(new HerdBehaviourAnalysis(myAgent, adjustInterval(5000)));
                    addBehaviour(new LeaderSelectionBehaviour(myAgent, adjustInterval(10000)));
                    
                    System.out.println(getLocalName() + " ready for social trading!");
                }
            }
        });
    }

    private void subscribeMarketData() {
        try {
            ACLMessage sub = new ACLMessage(ACLMessage.SUBSCRIBE);
            sub.setProtocol("MARKET-DATA-SUB");
            sub.addReceiver(marketMaker);
            sub.setContent(stockSymbol);
            send(sub);
            System.out.println(getLocalName() + " -> SUBSCRIBE MARKET-DATA for " + stockSymbol);
        } catch (Exception e) {
            System.err.println(getLocalName() + " subscribe error: " + e.getMessage());
        }
    }

    // Reaction aux actualites en tant que suiveur
    private class NewsListener extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("NEWS");
            ACLMessage msg = receive(mt);

            if (msg != null) {
                String newsContent = msg.getContent();
                beliefs.updateNewsData(newsContent);
                System.out.println(getLocalName() + " News received: " + newsContent);
                reactAsFollowerToNews();
            } else {
                block();
            }
        }
        
        private void reactAsFollowerToNews() {
            long now = System.currentTimeMillis();
            if (now - lastNewsHandled < newsDebounceMs) return;
            if (!canTradeNow()) return;

            double newsImpact = beliefs.getNewsImpact();

            if (beliefs.hasPositiveNews()) {
                if (getCurrentCash() > 1000 && herdConfidence > 0.4) {
                    int quantity = Math.max(1, (int)(getCurrentCash() * 0.2 / beliefs.getAskPrice()));
                    executeBuyOrder(quantity, "Following positive news sentiment");
                    lastNewsHandled = now;
                    System.out.println(getLocalName() + " NEWS FOLLOW BUY: " + quantity +
                                       " (Herd: " + String.format("%.0f%%", herdConfidence * 100) + ")");
                }
            } else if (beliefs.hasNegativeNews()) {
                if (getSharesOwned() > 0 && herdConfidence > 0.5) {
                    int quantity = Math.min(getSharesOwned(), Math.max(1, getSharesOwned() / 3));
                    executeSellOrder(quantity, "Panic selling on negative news");
                    lastNewsHandled = now;
                    System.out.println(getLocalName() + " NEWS PANIC SELL: " + quantity);
                }
            }

            // Augmentation du comportement gregaire en cas de forte actualite
            if (Math.abs(newsImpact) > 0.5) {
                herdConfidence = Math.min(0.9, herdConfidence + 0.1);
                System.out.println(getLocalName() + " Increased herd behavior: " +
                                   String.format("%.0f%%", herdConfidence * 100));
            }
        }
    }

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
            register.setProtocol("PORTFOLIO");
            register.setContent("REGISTER:" + initialCapital);
            send(register);
            
            System.out.println(getLocalName() + " registration request sent");
        } catch (Exception e) {
            System.err.println(getLocalName() + " error registering: " + e.getMessage());
        }
    }
    
    // Gestion des confirmations d'ordres
    private class OrderResponseHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("TRADING");
            ACLMessage msg = receive(mt);

            if (msg != null) {
                String response = msg.getContent();
                
                try {
                    if (msg.getPerformative() == ACLMessage.CONFIRM && response.startsWith("EXECUTED:")) {
                        String[] parts = response.split(":");
                        if (parts.length >= 5) {
                            String action = parts[1];
                            int quantity = Integer.parseInt(parts[2]);
                            String symbol = parts[3];
                            double executionPrice = Double.parseDouble(parts[4].replace(",", "."));

                            if ("BUY".equals(action)) {
                                portfolio.buy(symbol, quantity, executionPrice);
                            } else if ("SELL".equals(action)) {
                                portfolio.sell(symbol, quantity, executionPrice);
                            }

                            tradesThisSession++;
                            lastTradeRealMs = System.currentTimeMillis();

                            System.out.println(getLocalName() + " EXECUTED " + action + " " + quantity +
                                               " " + symbol + " @ $" + String.format("%.2f", executionPrice) +
                                               " | Cash=$" + String.format("%.2f", getCurrentCash()) +
                                               " | Shares=" + getSharesOwned());
                        }
                    } else if (msg.getPerformative() == ACLMessage.REFUSE ||
                               msg.getPerformative() == ACLMessage.FAILURE) {
                        System.out.println(getLocalName() + " Order rejected: " + response);
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " Error processing order response: " + e.getMessage());
                } finally {
                    hasPendingOrder = false;
                }
            } else {
                block();
            }
        }
    }

    // Evaluation si un trade doit etre suivi
    private boolean shouldFollowTrade(String trader, String action, int quantity, double price) {
        boolean hasEnoughCash = getCurrentCash() >= quantity * price * 0.5;
        boolean hasShares = getSharesOwned() > 0;
        boolean mediumConfidence = herdConfidence > 0.25;
        boolean isLeader = trader.equals(currentLeader);
        boolean isNotFollower = !trader.startsWith("Follower");
        
        if ("BUY".equals(action)) {
            return hasEnoughCash && (mediumConfidence || isLeader) && isNotFollower;
        } else if ("SELL".equals(action)) {
            return hasShares && quantity <= getSharesOwned() && (mediumConfidence || isLeader) && isNotFollower;
        }
        
        return false;
    }
    
    /**
     * Profil d'un trader observe pour le suivi.
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
            
            // Estimation de la performance
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
            return (System.currentTimeMillis() - lastTradeTime) < 30000;
        }
    }
    
    /**
     * Desirs du suiveur - Ce que le suiveur veut faire.
     */
    private class FollowerDesires {
        
        public boolean wantsToFollowLeader() {
            if (currentLeader == null) return false;
            
            TraderProfile leader = observedTraders.get(currentLeader);
            return leader != null && 
                   leader.isRecentlyActive() &&
                   leader.getSuccessRate() > 0.3;
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
     * Intentions du suiveur - Comment executer la strategie de suivi.
     */
    private class FollowerIntentions {
        
        public String determineActionToFollow() {
            if (currentLeader != null) {
                TraderProfile leader = observedTraders.get(currentLeader);
                if (leader != null) {
                    return leader.lastAction;
                }
            }
            
            // Suivre le groupe
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
            double riskFactor = herdConfidence;
            
            if ("BUY".equals(action)) {
                double maxInvestment = getCurrentCash() * 0.3 * riskFactor;
                int quantity = (int)(maxInvestment / beliefs.getAskPrice());
                return Math.max(5, Math.min(15, quantity));
            } else if ("SELL".equals(action)) {
                int maxSell = (int)(getSharesOwned() * 0.5 * riskFactor);
                return Math.max(5, Math.min(maxSell, getSharesOwned()));
            }
            
            return 0;
        }
    }
    
    // Reception des donnees de marche
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
    
    // Observation des actions des autres traders
    private class TradeObservationBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("TRADE-EXECUTED");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                String content = msg.getContent();
                beliefs.updateTradeInfo(content);
                
                try {
                    String[] parts = content.split(":");
                    if (parts.length >= 6 && "TRADE".equals(parts[0])) {
                        String traderName = parts[1];
                        String symbol = parts[2];
                        int quantity = Integer.parseInt(parts[3]);
                        double price = Double.parseDouble(parts[4].replace(",", "."));
                        String action = parts[5];

                        if (!traderName.equals(getLocalName())) {
                            TraderProfile profile = observedTraders.getOrDefault(traderName, new TraderProfile(traderName));
                            profile.recordTrade(action, price);
                            observedTraders.put(traderName, profile);

                            recentTrades.offer(action);
                            if (recentTrades.size() > 20) recentTrades.poll();

                            if (!canTradeNow()) return;

                            if ("BUY".equals(action) && shouldFollowTrade(traderName, action, quantity, price)) {
                                int followQuantity = Math.max(1, quantity / 2);
                                double followCost = followQuantity * beliefs.getAskPrice();
                                if (getCurrentCash() >= followCost) {
                                    executeBuyOrder(followQuantity, "Following " + traderName);
                                }
                            } else if ("SELL".equals(action) && shouldFollowTrade(traderName, action, quantity, price)) {
                                int followQuantity = Math.min(getSharesOwned(), Math.max(1, quantity / 2));
                                if (followQuantity > 0) {
                                    executeSellOrder(followQuantity, "Following " + traderName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " Error parsing trade: " + content);
                }
            } else {
                block();
            }
        }
    }
    
    // Execution des ordres avec gestion du portfolio
    private void executeBuyOrder(int quantity, String reason) {
        if (!canTradeNow()) return;
        if (quantity > 0 && getCurrentCash() >= quantity * beliefs.getAskPrice()) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("BUY:" + stockSymbol + ":" + quantity + ":" + String.format("%.2f", beliefs.getAskPrice()));
            send(order);
            hasPendingOrder = true;
            System.out.println(getLocalName() + " BUY ORDER: " + quantity +
                               " @ $" + String.format("%.2f", beliefs.getAskPrice()) +
                               " (" + reason + ")");
        }
    }
    
    private void executeSellOrder(int quantity, String reason) {
        if (!canTradeNow()) return;
        if (quantity > 0 && getSharesOwned() >= quantity) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("SELL:" + stockSymbol + ":" + quantity + ":" + String.format("%.2f", beliefs.getBidPrice()));
            send(order);
            hasPendingOrder = true;
            System.out.println(getLocalName() + " SELL ORDER: " + quantity +
                               " @ $" + String.format("%.2f", beliefs.getBidPrice()) +
                               " (" + reason + ")");
        }
    }
    
    // Comportement principal de suivi
    private class FollowTradingBehaviour extends TickerBehaviour {
        public FollowTradingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            if (!canTradeNow()) return;

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
            if (!canTradeNow()) return;

            if ("BUY".equals(action)) {
                int quantity = intentions.calculateFollowQuantity("BUY");
                executeBuyOrder(quantity, "Following leader: " + currentLeader);
            } else if ("SELL".equals(action) && getSharesOwned() > 0) {
                int quantity = intentions.calculateFollowQuantity("SELL");
                executeSellOrder(quantity, "Following leader: " + currentLeader);
            }
            followDelay = 1 + (int)(Math.random() * 2);
        }
        
        private void followHerdAction() {
            String action = intentions.determineActionToFollow();
            if (!canTradeNow()) return;

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
            if (!canTradeNow()) return;

            if (beliefs.isOversold() && getCurrentCash() > 1000) {
                int quantity = (int)(getCurrentCash() * 0.2 / beliefs.getAskPrice());
                executeBuyOrder(quantity, "Independent decision (oversold)");
            } else if (beliefs.isOverbought() && getSharesOwned() > 0) {
                int quantity = Math.max(1, getSharesOwned() / 3);
                executeSellOrder(quantity, "Independent decision (overbought)");
            }
        }
    }
    
    // Analyse des patterns de comportement gregaire
    private class HerdBehaviourAnalysis extends TickerBehaviour {
        public HerdBehaviourAnalysis(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            int buyCount = 0;
            int sellCount = 0;
            
            for (String trade : recentTrades) {
                if ("BUY".equals(trade)) buyCount++;
                else if ("SELL".equals(trade)) sellCount++;
            }
            
            // Ajustement de la confiance gregaire selon la volatilite
            if (beliefs.getVolatility() > 0.05) {
                herdConfidence = Math.min(0.9, herdConfidence + 0.05);
            } else {
                herdConfidence = Math.max(0.3, herdConfidence - 0.02);
            }
            
            System.out.println(getLocalName() + " Herd Analysis: " +
                             "Buy signals: " + buyCount + 
                             ", Sell signals: " + sellCount +
                             ", Confidence: " + String.format("%.0f%%", herdConfidence * 100));
        }
    }
    
    // Selection et mise a jour du leader a suivre
    private class LeaderSelectionBehaviour extends TickerBehaviour {
        public LeaderSelectionBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            String bestTrader = null;
            double bestPerformance = 0;
            
            for (Map.Entry<String, TraderProfile> entry : observedTraders.entrySet()) {
                TraderProfile profile = entry.getValue();
                
                if (profile.totalTrades > 3 &&
                    profile.getSuccessRate() > bestPerformance &&
                    profile.isRecentlyActive()) {
                    
                    bestTrader = entry.getKey();
                    bestPerformance = profile.getSuccessRate();
                }
            }
            
            // Mise a jour du leader si un meilleur est trouve
            if (bestTrader != null && !bestTrader.equals(currentLeader)) {
                String oldLeader = currentLeader;
                currentLeader = bestTrader;
                
                System.out.println(getLocalName() + " changed leader from " + 
                                 oldLeader + " to " + currentLeader +
                                 " (Success rate: " + String.format("%.0f%%", bestPerformance * 100) + ")");
            }
            
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