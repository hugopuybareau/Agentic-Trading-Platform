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

/**
 * Agent trader agressif utilisant l'approche BDI.
 * Strategie: momentum trading, effet de levier, reactions rapides aux news.
 */
public class AggressiveTraderAgent extends Agent {
    private Portfolio portfolio;
    private AID marketMaker;
    private TradingBeliefs beliefs;
    
    // Configuration strategie agressive
    private static final double MAX_POSITION_SIZE = 0.5; // 50% max par position
    private static final double MOMENTUM_THRESHOLD = 0.02;
    private static final double LEVERAGE_RATIO = 1.5; // 150% max
    private static final double MIN_CASH_RESERVE = 1000.0;

    private double initialCapital;
    private double currentPrice; 
    
    // Statistiques de performance
    private int totalTrades = 0;
    private int consecutiveWins = 0;
    private int consecutiveLosses = 0;
    private double totalProfit = 0.0;
    private List<Double> tradeProfits = new ArrayList<>();
    
    private boolean isTrading = false;
    private long lastTradeTime = 0;
    private double borrowedAmount = 0.0;
    
    // Garde-fous pour eviter le sur-trading
    private long lastTradeRealMs = 0L;
    private boolean hasPendingOrder = false;
    private int tradesThisSession = 0;
    private final int maxTradesPerSimHour = 20; // Limite par heure simulee
    private long lastNewsHandled = 0L;
    private final long newsDebounceMs = simSecondsToRealMs(20);

    // Conversion du temps simule en temps reel
    private static int accel() {
        try { 
            return Integer.parseInt(System.getProperty("trading.acceleration", "1")); 
        } catch (Exception e) { 
            return 1; 
        }
    }
    
    private static long simSecondsToRealMs(int s) { 
        return (s * 1000L) / Math.max(1, accel()); 
    }
    
    private final long minCooldownRealMs = simSecondsToRealMs(45);

    // Verification si un trade peut etre execute
    private boolean canTradeNow() {
        long now = System.currentTimeMillis();
        return !hasPendingOrder
            && (now - lastTradeRealMs >= minCooldownRealMs)
            && (tradesThisSession < maxTradesPerSimHour);
    }

    @Override
    protected void setup() {
        Object[] args = getArguments();
        double initialCapital = 15000.0;
        
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        }
        
        System.out.println("=== AGGRESSIVE TRADER SETUP ===");
        System.out.println("Agent: " + getLocalName());
        System.out.println("Initial Capital: $" + String.format("%.2f", initialCapital));
        System.out.println("Strategy: Aggressive Momentum & Leverage");
        
        portfolio = new Portfolio(getLocalName());
        portfolio.addCash(initialCapital);
        this.initialCapital = initialCapital;
        beliefs = new TradingBeliefs();
        
        // Recherche du MarketMaker et initialisation des comportements
        addBehaviour(new WakerBehaviour(this, 3000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    System.out.println(getLocalName() + " found MarketMaker: " + marketMaker.getLocalName());
                    registerWithMarket();
                    subscribeMarketData();
                    
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new OrderResponseHandler());
                    addBehaviour(new NewsListener());
                    addBehaviour(new MomentumTradingBehaviour(myAgent, adjustInterval(1000)));
                    addBehaviour(new PortfolioStatusBehaviour(myAgent, adjustInterval(5000)));
                    
                    System.out.println(getLocalName() + " Ready for aggressive trading!");
                } else {
                    System.err.println(getLocalName() + " Could not find MarketMaker!");
                }
            }
        });
        
        System.out.println(getLocalName() + " setup complete");
    }

    private void subscribeMarketData() {
        try {
            ACLMessage sub = new ACLMessage(ACLMessage.SUBSCRIBE);
            sub.setProtocol("MARKET-DATA-SUB");
            sub.addReceiver(marketMaker);
            sub.setContent("AAPL");
            send(sub);
            System.out.println(getLocalName() + " -> SUBSCRIBE MARKET-DATA");
        } catch (Exception e) {
            System.err.println(getLocalName() + " subscribe error: " + e.getMessage());
        }
    }
    
    // Reaction agressive aux actualites financieres
    private class NewsListener extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("NEWS");
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String newsContent = msg.getContent();
                beliefs.updateNewsData(newsContent);
                System.out.println(getLocalName() + " News received: " + newsContent);
                reactAggressivelyToNews();
            } else {
                block();
            }
        }

        private void reactAggressivelyToNews() {
            long now = System.currentTimeMillis();
            if (now - lastNewsHandled < newsDebounceMs) return;
            if (!canTradeNow()) return;

            double newsImpact = beliefs.getNewsImpact();

            if (beliefs.hasPositiveNews()) {
                if (getCurrentCash() > MIN_CASH_RESERVE) {
                    int quantity = calculateNewsBasedQuantity() * 2;
                    executeBuyOrder(quantity, beliefs.getAskPrice());
                    lastNewsHandled = now;
                    System.out.println(getLocalName() + " AGGRESSIVE NEWS BUY: " + quantity +
                            " (impact: " + String.format("%.2f", newsImpact) + ")");
                }
            } else if (beliefs.hasNegativeNews()) {
                if (getSharesOwned() > 0) {
                    int quantity = Math.min(getSharesOwned(), Math.max(1, getSharesOwned() / 2));
                    executeSellOrder(quantity, beliefs.getBidPrice());
                    lastNewsHandled = now;
                    System.out.println(getLocalName() + " AGGRESSIVE NEWS SELL: " + quantity +
                            " (impact: " + String.format("%.2f", newsImpact) + ")");
                }
            }
        }
    }
    
    private double getCurrentCash() {
        return portfolio.getCash();
    }
    
    private int getSharesOwned() {
        return portfolio.getShares("AAPL");
    }
    
    private double getPortfolioValue() {
        return portfolio.getTotalValue("AAPL", currentPrice);
    }
    
    private double getInitialCapital() {
        return initialCapital;
    }
        
    private double getPortfolioReturn() {
        double currentValue = getPortfolioValue();
        return ((currentValue - initialCapital) / initialCapital) * 100;
    }
    
    // Execution d'un ordre d'achat
    private void executeBuyOrder(int quantity, double price) {
        if (!canTradeNow()) return;
        double totalCost = quantity * price;
        if (portfolio.canAfford("AAPL", quantity, price)) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("BUY:AAPL:" + quantity + ":" + String.format("%.2f", price));
            send(order);
            hasPendingOrder = true;
            System.out.println(getLocalName() + " BUY ORDER: " + quantity +
                    " @ $" + String.format("%.2f", price) +
                    " (Cost: $" + String.format("%.2f", totalCost) + ")");
        } else {
            System.out.println(getLocalName() + " Insufficient funds: Need $" +
                    String.format("%.2f", totalCost) +
                    ", Have $" + String.format("%.2f", getCurrentCash()));
        }
    }
    
    // Execution d'un ordre de vente
    private void executeSellOrder(int quantity, double price) {
        if (!canTradeNow()) return;
        if (getSharesOwned() >= quantity && quantity > 0) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("SELL:AAPL:" + quantity + ":" + String.format("%.2f", price));
            send(order);
            hasPendingOrder = true;
            double totalRevenue = quantity * price;
            System.out.println(getLocalName() + " SELL ORDER: " + quantity +
                    " @ $" + String.format("%.2f", price) +
                    " (Revenue: $" + String.format("%.2f", totalRevenue) + ")");
        } else {
            System.out.println(getLocalName() + " Insufficient shares: Need " + quantity +
                    ", Have " + getSharesOwned());
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
                            
                            totalTrades++;
                            tradesThisSession++;
                            lastTradeRealMs = System.currentTimeMillis();
                            System.out.println(getLocalName() + " EXECUTED " + action + " " + quantity +
                                    " " + symbol + " @ $" + String.format("%.2f", executionPrice));
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

    // Reception des donnees de marche
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage msg = receive(MessageTemplate.MatchProtocol("MARKET-DATA"));
            boolean handled = false;
            if (msg != null) {
                beliefs.updateMarketData(msg.getContent());
                currentPrice = beliefs.getCurrentPrice();
                handled = true;
            }
            ACLMessage tradeMsg = receive(MessageTemplate.MatchProtocol("TRADE-EXECUTED"));
            if (tradeMsg != null) {
                beliefs.updateTradeInfo(tradeMsg.getContent());
                handled = true;
            }
            if (!handled) block();
        }
    }
    
    // Trading base sur le momentum (strategie agressive)
    private class MomentumTradingBehaviour extends TickerBehaviour {
        public MomentumTradingBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            if (marketMaker == null || !isTrading) return;
            if (!canTradeNow()) return;

            double momentum = beliefs.getMomentum();
            double rsi = beliefs.getRSI();
            currentPrice = beliefs.getCurrentPrice();

            // Signal d'achat: momentum positif et RSI pas en surachat
            if (momentum > MOMENTUM_THRESHOLD && rsi < 70 && getCurrentCash() > MIN_CASH_RESERVE) {
                int quantity = calculateMomentumBuyQuantity();
                if (quantity > 0) {
                    executeBuyOrder(quantity, beliefs.getAskPrice());
                    System.out.println(getLocalName() + " MOMENTUM BUY: " + quantity +
                            " (M: " + String.format("%.4f", momentum) + ")");
                }
            }

            // Signal de vente: momentum negatif ou RSI en surachat
            if (getSharesOwned() > 0 && (momentum < -MOMENTUM_THRESHOLD || rsi > 80)) {
                int quantity = Math.min(getSharesOwned(), calculateMomentumSellQuantity());
                if (quantity > 0) {
                    executeSellOrder(quantity, beliefs.getBidPrice());
                    System.out.println(getLocalName() + " MOMENTUM SELL: " + quantity +
                            " (RSI: " + String.format("%.1f", rsi) + ")");
                }
            }
        }
    }
    
    // Affichage periodique du statut du portfolio
    private class PortfolioStatusBehaviour extends TickerBehaviour {
        public PortfolioStatusBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            System.out.println("\n=== " + getLocalName() + " STATUS ===");
            System.out.println("Portfolio Value: $" + String.format("%.2f", getPortfolioValue()));
            System.out.println("Cash: $" + String.format("%.2f", getCurrentCash()));
            System.out.println("Shares: " + getSharesOwned());
            System.out.println("Return: " + String.format("%.2f", getPortfolioReturn()) + "%");
            System.out.println("Total Trades: " + totalTrades);
            System.out.println("Borrowed: $" + String.format("%.2f", borrowedAmount));
            System.out.println("Net Value: $" + String.format("%.2f", getPortfolioValue() - borrowedAmount));
            System.out.println("Strategy: Aggressive Momentum & Leverage");
            
            if (consecutiveWins > 0) {
                System.out.println("Streak: " + consecutiveWins + " wins");
            } else if (consecutiveLosses > 0) {
                System.out.println("Streak: " + consecutiveLosses + " losses");
            }
        }
    }
    
    // Calcul de la quantite a acheter selon le momentum
    private int calculateMomentumBuyQuantity() {
        double availableCash = getCurrentCash();
        double maxInvestment = availableCash * MAX_POSITION_SIZE;
        
        // Boost pour serie gagnante
        if (consecutiveWins > 2) {
            maxInvestment *= 1.2;
        }
        
        int quantity = (int)(maxInvestment / currentPrice);
        return Math.max(1, Math.min(20, quantity));
    }
    
    private int calculateMomentumSellQuantity() {
        int shares = getSharesOwned();
        return Math.min(shares, Math.max(1, shares / 3));
    }
    
    private int calculateNewsBasedQuantity() {
        double availableCash = getCurrentCash();
        double maxInvestment = availableCash * 0.15;
        
        int quantity = (int)(maxInvestment / beliefs.getAskPrice());
        return Math.max(1, Math.min(10, quantity));
    }
    
    // Ajustement de l'intervalle selon le facteur d'acceleration
    private long adjustInterval(long originalInterval) {
        int acceleration = getAccelerationFactor();
        return Math.max(50, originalInterval / acceleration);
    }
    
    private int getAccelerationFactor() {
        try {
            return Integer.parseInt(System.getProperty("trading.acceleration", "1"));
        } catch (Exception e) {
            return 1;
        }
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
            isTrading = true;
            System.out.println(getLocalName() + " registration request sent");
        } catch (Exception e) {
            System.err.println(getLocalName() + " error registering: " + e.getMessage());
        }
    }
    
    @Override
    protected void takeDown() {
        System.out.println("\n=== " + getLocalName() + " FINAL REPORT ===");
        System.out.println("Initial Capital: $" + String.format("%.2f", getInitialCapital()));
        System.out.println("Final Portfolio: $" + String.format("%.2f", getPortfolioValue()));
        System.out.println("Outstanding Debt: $" + String.format("%.2f", borrowedAmount));
        System.out.println("Net Value: $" + String.format("%.2f", getPortfolioValue() - borrowedAmount));
        System.out.println("Total Return: " + String.format("%.2f", getPortfolioReturn()) + "%");
        System.out.println("Total Trades: " + totalTrades);
        System.out.println("Strategy: Aggressive Momentum & Leverage");
        
        if (totalTrades > 0) {
            double winRate = (consecutiveWins / (double) totalTrades) * 100;
            System.out.println("Estimated Win Rate: " + String.format("%.1f", winRate) + "%");
        }
        
        System.out.println("=====================================");
    }
}