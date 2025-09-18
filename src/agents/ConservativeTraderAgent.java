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

/**
 * Conservative Trader Agent - BDI Architecture
 * Low risk strategy focusing on long-term value investing
 * Target: 5-10% annual returns with minimal risk
 */
public class ConservativeTraderAgent extends Agent {
    
    // BDI Components
    private TradingBeliefs beliefs;
    private ConservativeDesires desires;
    private TradingIntentions intentions;
    
    // Portfolio Management
    private double initialCapital;
    private Portfolio portfolio; // Utiliser l'objet Portfolio
    
    // Risk Management
    private final double MAX_POSITION_SIZE = 0.3; // Max 30% of capital in one trade
    private final double STOP_LOSS = 0.05; // 5% stop loss
    private final double TAKE_PROFIT = 0.10; // 10% profit target
    
    // Trading Parameters
    private int tradingCooldown = 0;
    private final int MIN_TRADE_INTERVAL = 5; // Minimum ticks between trades
    private String stockSymbol = "AAPL";
    
    private AID marketMaker;
    
    @Override
    protected void setup() {
        // Initialize capital
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        } else {
            initialCapital = 10000.0;
        }
        
        // Initialize portfolio et BDI components
        portfolio = new Portfolio(getLocalName());
        portfolio.addCash(initialCapital);
        beliefs = new TradingBeliefs();
        desires = new ConservativeDesires();
        intentions = new TradingIntentions();
        
        System.out.println("Conservative Trader " + getLocalName() + 
                         " started with capital: $" + initialCapital);
        
        // Find market maker
        addBehaviour(new WakerBehaviour(this, 3000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                // Register with market
                if (marketMaker != null) {
                    registerWithMarket();
                    
                    // Add trading behaviours
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new ConservativeTradingBehaviour(myAgent, adjustInterval(3000)));
                    addBehaviour(new RiskManagementBehaviour(myAgent, adjustInterval(5000)));
                    addBehaviour(new OrderResponseHandler());
                } else {
                    System.err.println(getLocalName() + ": MarketMaker not found!");
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
                System.out.println(getLocalName() + " found MarketMaker: " + marketMaker.getLocalName());
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    private void registerWithMarket() {
        ACLMessage register = new ACLMessage(ACLMessage.REQUEST);
        register.addReceiver(marketMaker);
        register.setProtocol("PORTFOLIO");
        register.setContent("REGISTER:" + initialCapital);
        send(register);
        System.out.println(getLocalName() + " registration request sent");
    }
    
    /**
     * Conservative Desires - Investment goals
     */
    private class ConservativeDesires {
        
        public boolean wantsToBuy() {
            // Conditions simplifiées pour plus de trading
            if (beliefs.getCurrentPrice() <= 0 || portfolio.getCash() < beliefs.getCurrentPrice() * 10) {
                return false;
            }
            
            // Acheter si:
            // 1. On n'a pas d'actions OU
            // 2. RSI < 50 (oversold/neutral) OU
            // 3. Prix en baisse et sentiment positif
            boolean noShares = portfolio.getShares(stockSymbol) == 0;
            boolean oversold = beliefs.getRSI() < 50;
            boolean goodValue = beliefs.getCurrentPrice() < beliefs.getMovingAverage20();
            
            return noShares || oversold || goodValue;
        }
        
        public boolean wantsToSell() {
            int currentShares = portfolio.getShares(stockSymbol);
            if (currentShares == 0) return false;
            
            // Vendre si RSI > 70 (overbought) ou pour prendre profit
            boolean overbought = beliefs.getRSI() > 70;
            boolean takingProfit = Math.random() < 0.1; // 10% chance de prendre profit
            
            System.out.println(getLocalName() + " Sell evaluation:");
            System.out.println("  Shares owned: " + currentShares);
            System.out.println("  Overbought: " + overbought + " (RSI: " + String.format("%.1f", beliefs.getRSI()) + ")");
            
            return overbought || takingProfit;
        }
        
        public boolean wantsToHold() {
            return !wantsToBuy() && !wantsToSell();
        }
    }
    
    /**
     * Trading Intentions - Concrete action plans
     */
    private class TradingIntentions {
        
        public int calculateBuyQuantity() {
            double maxInvestment = portfolio.getCash() * MAX_POSITION_SIZE;
            double currentPrice = beliefs.getCurrentPrice();
            
            if (currentPrice <= 0) return 0;
            
            int quantity = (int)(maxInvestment / currentPrice);
            
            // Minimum 5 shares pour que ce soit significatif
            if (quantity < 5) {
                quantity = Math.min(5, (int)(portfolio.getCash() / currentPrice));
            }
            
            return quantity;
        }
        
        public int calculateSellQuantity() {
            int currentShares = portfolio.getShares(stockSymbol);
            // Vendre 25% des positions
            return Math.max(1, currentShares / 4);
        }
        
        public boolean shouldExecuteTrade() {
            return tradingCooldown == 0 && beliefs.getCurrentPrice() > 0;
        }
    }
    
    /**
     * Behaviour for receiving and processing market data
     */
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                String content = msg.getContent();
                
                if (content.startsWith("PRICE:")) {
                    beliefs.updateMarketData(content);
                    
                    // Debug occasionnel
                    if (Math.random() < 0.05) { // 5% chance
                        System.out.println(getLocalName() + " received market data: Price=$" + 
                                         String.format("%.2f", beliefs.getCurrentPrice()));
                    }
                } else if (content.startsWith("TRADE:")) {
                    beliefs.updateTradeInfo(content);
                }
            } else {
                block();
            }
        }
    }
    
    /**
     * Gestionnaire des réponses aux ordres
     */
    private class OrderResponseHandler extends CyclicBehaviour {
        @Override
        public void action() {
            // Écouter les CONFIRMATIONS du MarketMaker
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchProtocol("TRADING"),
                MessageTemplate.MatchPerformative(ACLMessage.CONFIRM)
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String response = msg.getContent();
                System.out.println(getLocalName() + " Order response: " + response);
                
                try {
                    // Parse: EXECUTED:BUY:29:AAPL:100.51652480224706
                    if (response.startsWith("EXECUTED:")) {
                        String[] parts = response.split(":");
                        
                        if (parts.length >= 5) {
                            String action = parts[1];        // BUY/SELL
                            int quantity = Integer.parseInt(parts[2]);  // 29
                            String symbol = parts[3];        // AAPL
                            String priceStr = parts[4].replace(",", ".");
                            double executionPrice = Double.parseDouble(priceStr);
                            
                            // 🔧 CORRECTION CRITIQUE: Mettre à jour le portfolio local
                            if ("BUY".equals(action)) {
                                portfolio.buy(symbol, quantity, executionPrice);
                                System.out.println(getLocalName() + " ✅ LOCAL Portfolio updated: " +
                                                "Bought " + quantity + " @ $" + String.format("%.2f", executionPrice));
                            } else if ("SELL".equals(action)) {
                                portfolio.sell(symbol, quantity, executionPrice);
                                System.out.println(getLocalName() + " ✅ LOCAL Portfolio updated: " +
                                                "Sold " + quantity + " @ $" + String.format("%.2f", executionPrice));
                            }
                            
                            // Debug portfolio state
                            double currentPrice = beliefs.getCurrentPrice();
                            System.out.println(getLocalName() + " 📊 Portfolio after trade: " +
                                            "Cash=$" + String.format("%.2f", portfolio.getCash()) +
                                            ", Shares=" + portfolio.getShares(symbol) +
                                            ", Value=$" + String.format("%.2f", portfolio.getTotalValue(symbol, currentPrice)));
                        }
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " ❌ Error processing order response: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                block();
            }
        }
    }
    
    /**
     * Main trading behaviour - Conservative strategy
     */
    private class ConservativeTradingBehaviour extends TickerBehaviour {
        public ConservativeTradingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Update cooldown
            if (tradingCooldown > 0) {
                tradingCooldown--;
            }
            
            if (beliefs.getCurrentPrice() <= 0) {
                System.out.println(getLocalName() + ": Waiting for market data...");
                return;
            }
            
            // Check desires and execute intentions
            if (desires.wantsToBuy() && intentions.shouldExecuteTrade()) {
                executeBuyOrder();
            } else if (desires.wantsToSell() && intentions.shouldExecuteTrade()) {
                executeSellOrder();
            } else if (desires.wantsToHold()) {
                // Log holding decision occasionnellement
                if (Math.random() < 0.1) { // 10% chance
                    System.out.println(getLocalName() + " holding position. " +
                                     "Price: $" + String.format("%.2f", beliefs.getCurrentPrice()) +
                                     ", Sentiment: " + beliefs.getMarketSentiment());
                }
            }
        }
        
        private void executeBuyOrder() {
            int quantity = intentions.calculateBuyQuantity();
            if (quantity > 0 && portfolio.getCash() >= quantity * beliefs.getCurrentPrice()) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("BUY:" + stockSymbol + ":" + quantity + ":" + beliefs.getCurrentPrice());
                send(order);
                
                tradingCooldown = MIN_TRADE_INTERVAL;
                
                System.out.println(getLocalName() + " SENT BUY ORDER: " + quantity + 
                                 " shares @ $" + String.format("%.2f", beliefs.getCurrentPrice()) +
                                 " (Total: $" + String.format("%.2f", quantity * beliefs.getCurrentPrice()) + ")");
            } else {
                System.out.println(getLocalName() + " Cannot buy: insufficient funds or invalid quantity");
            }
        }
        
        private void executeSellOrder() {
            int quantity = intentions.calculateSellQuantity();
            int currentShares = portfolio.getShares(stockSymbol);
            quantity = Math.min(quantity, currentShares);
            
            if (quantity > 0) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("SELL:" + stockSymbol + ":" + quantity + ":" + beliefs.getCurrentPrice());
                send(order);
                
                tradingCooldown = MIN_TRADE_INTERVAL;
                
                System.out.println(getLocalName() + " SENT SELL ORDER: " + quantity + 
                                 " shares @ $" + String.format("%.2f", beliefs.getCurrentPrice()) +
                                 " (Total: $" + String.format("%.2f", quantity * beliefs.getCurrentPrice()) + ")");
            }
        }
    }
    
    /**
     * Risk management behaviour
     */
    private class RiskManagementBehaviour extends TickerBehaviour {
        public RiskManagementBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Calculate current portfolio value
            double currentPrice = beliefs.getCurrentPrice();
            if (currentPrice <= 0) return;
            
            double portfolioValue = portfolio.getTotalValue(stockSymbol, currentPrice);
            double totalReturn = (portfolioValue - initialCapital) / initialCapital;
            
            // Log performance
            System.out.println(getLocalName() + " Portfolio Status: " +
                             "Value: $" + String.format("%.2f", portfolioValue) +
                             ", Return: " + String.format("%.2f%%", totalReturn * 100) +
                             ", Shares: " + portfolio.getShares(stockSymbol) +
                             ", Cash: $" + String.format("%.2f", portfolio.getCash()));
        }
    }
    
    @Override
    protected void takeDown() {
        double currentPrice = beliefs.getCurrentPrice() > 0 ? beliefs.getCurrentPrice() : 100.0;
        double finalValue = portfolio.getTotalValue(stockSymbol, currentPrice);
        double totalReturn = (finalValue - initialCapital) / initialCapital;
        
        System.out.println("=== " + getLocalName() + " Final Report ===");
        System.out.println("Initial Capital: $" + initialCapital);
        System.out.println("Final Value: $" + String.format("%.2f", finalValue));
        System.out.println("Total Return: " + String.format("%.2f%%", totalReturn * 100));
        System.out.println("Strategy: Conservative Value Investing");
    }
}